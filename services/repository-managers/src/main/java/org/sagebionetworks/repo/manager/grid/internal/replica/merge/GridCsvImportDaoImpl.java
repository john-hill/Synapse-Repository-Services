package org.sagebionetworks.repo.manager.grid.internal.replica.merge;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.StringJoiner;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.json.JSONArray;
import org.sagebionetworks.grid.db.GridTransaction;
import org.sagebionetworks.repo.model.table.ColumnConstants;
import org.sagebionetworks.table.cluster.ColumnTypeInfo;
import org.sagebionetworks.table.cluster.MySqlColumnType;
import org.sagebionetworks.util.PaginationIterator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class GridCsvImportDaoImpl implements GridCsvImportDao {

	private static final String COL_EXTRA = "EXTRA";

	private static String getUpsertKeyColumnName(int index) {
		return "C" + index;
	}

	private static final int BATCH_SIZE = 1000;

	private JdbcTemplate jdbcTemplate;

	public GridCsvImportDaoImpl(JdbcTemplate gridDatabaseJdbcTemplate) {
		this.jdbcTemplate = gridDatabaseJdbcTemplate;
	}

	@Override
	@GridTransaction(readOnly = false)
	public void streamToCsvTempTable(DataStream dataStream, ColumnMapping[] columnMapping) {
		streamToTempTable(TEMP_TABLE_CSV_DATA, dataStream, columnMapping);
	}

	@Override
	@GridTransaction(readOnly = false)
	public void streamToGridTempTable(DataStream dataStream, ColumnMapping[] columnMapping) {
		streamToTempTable(TEMP_TABLE_GRID_DATA, dataStream, columnMapping);
	}

	@Override
	@GridTransaction(readOnly = true)
	public PaginationIterator<Object[]> getCsvTempTableIterator() {
		return getTempTableIterator(TEMP_TABLE_CSV_DATA);
	}

	@Override
	@GridTransaction(readOnly = true)
	public PaginationIterator<Object[]> getGridTempTableIterator() {
		return getTempTableIterator(TEMP_TABLE_GRID_DATA);
	}

	@Override
	@GridTransaction(readOnly = true)
	public PaginationIterator<JoinedRow> getJoinedTempTableIterator(ColumnMapping[] columnMapping) {
		List<ColumnMapping> csvUpsertColumns = Arrays.stream(columnMapping).filter(ColumnMapping::isUpsertColumn).collect(Collectors.toList());

		StringJoiner joinConditions = new StringJoiner(" AND ");

		IntStream.range(0, csvUpsertColumns.size()).mapToObj(index -> getUpsertKeyColumnName(index)).map(columnName -> "C." + columnName + " = G." + columnName)
			.forEach(joinConditions::add);

		String sql = String.format("SELECT C.*, G." + COL_EXTRA + " FROM " + TEMP_TABLE_CSV_DATA + " C LEFT JOIN " + TEMP_TABLE_GRID_DATA + " G ON (%s) LIMIT ? OFFSET ?",
			joinConditions.toString());

		return new PaginationIterator<>((limit, offset) -> jdbcTemplate.query(sql.toString(), (rs, rowNum) -> {
			Object[] csvData = new Object[columnMapping.length];

			// Add the upsert columns first
			for (int i = 0; i < csvUpsertColumns.size(); i++) {
				csvData[i] = rs.getObject(i + 1);
			}

			// Unpack the remaining CSV columns from the extra column
			JSONArray csvExtraArray = new JSONArray(rs.getString(csvUpsertColumns.size() + 1));

			for (int i = 0; i < csvExtraArray.length(); i++) {
				csvData[i + csvUpsertColumns.size()] = csvExtraArray.get(i);
			}

			Object[] gridData = null;

			// The grid data can be null if there is no match
			String gridExtraStr = rs.getString(csvUpsertColumns.size() + 2);

			if (gridExtraStr != null) {
				JSONArray gridExtraArray = new JSONArray(gridExtraStr);
				gridData = new Object[gridExtraArray.length()];
				for (int i = 0; i < gridExtraArray.length(); i++) {
					gridData[i] = gridExtraArray.get(i);
				}
			}

			return new JoinedRow(csvData, gridData);
		}, limit, offset), BATCH_SIZE);
	}

	void streamToTempTable(String tableName, DataStream dataStream, ColumnMapping[] columnMapping) {
		List<ColumnMapping> upsertKey = Arrays.stream(columnMapping).filter(ColumnMapping::isUpsertColumn).collect(Collectors.toList());

		createTemporaryTable(tableName, upsertKey);

		List<Object[]> batch = new ArrayList<>(BATCH_SIZE);

		while (dataStream.hasNext()) {
			batch.add(mapRow(dataStream.next(), upsertKey.size()));
			if (batch.size() >= BATCH_SIZE) {
				flushBatch(tableName, upsertKey, batch);
			}
		}
		
		flushBatch(tableName, upsertKey, batch);
	}

	Object[] mapRow(Object[] row, int upsertKeyLength) {
		Object[] mappedRow = new Object[upsertKeyLength + 1];

		int i = 0;

		// We normalize the upsert key columns first
		for (; i < upsertKeyLength; i++) {
			mappedRow[i] = row[i];
		}

		// Pack the remaining columns into a single extra JSON array column
		JSONArray extraData = new JSONArray();

		for (; i < row.length; i++) {
			extraData.put(row[i]);
		}

		mappedRow[mappedRow.length - 1] = extraData.toString();

		return mappedRow;
	}

	PaginationIterator<Object[]> getTempTableIterator(String tableName) {
		return new PaginationIterator<>((limit, offset) -> {
			String sql = "SELECT * FROM " + tableName + " LIMIT ? OFFSET ?";
			return jdbcTemplate.query(sql, (rs, rowNum) -> {
				Object[] row = new Object[rs.getMetaData().getColumnCount()];
				for (int i = 0; i < rs.getMetaData().getColumnCount(); i++) {
					row[i] = rs.getObject(i + 1);
				}
				return row;
			}, limit, offset);
		}, BATCH_SIZE);
	}

	void flushBatch(String tableName, List<ColumnMapping> upsertKey, List<Object[]> batch) {
		if (batch.isEmpty()) {
			return;
		}

		StringJoiner columnNames = new StringJoiner(",");
		StringJoiner valuePlaceholders = new StringJoiner(",");

		for (int i = 0; i < upsertKey.size(); i++) {
			columnNames.add(getUpsertKeyColumnName(i));
			valuePlaceholders.add("?");
		}

		columnNames.add(COL_EXTRA);
		valuePlaceholders.add("?");

		String sql = "INSERT INTO " + tableName + " (" + columnNames.toString() + ") VALUES (" + valuePlaceholders.toString() + ")";

		jdbcTemplate.batchUpdate(sql, batch);

		batch.clear();
	}

	void createTemporaryTable(String tableName, List<ColumnMapping> upsertKey) {
		StringJoiner columnDefinitions = new StringJoiner(",");
		StringJoiner upsertKeyColumns = new StringJoiner("`,`", "`", "`");

		int index = 0;

		for (ColumnMapping mapping : upsertKey) {

			String columnName = getUpsertKeyColumnName(index);

			StringBuilder columnDefinition = new StringBuilder();

			columnDefinition.append("`");
			columnDefinition.append(columnName);
			columnDefinition.append("` ");

			MySqlColumnType sqlType;

			sqlType = ColumnTypeInfo.getInfoForType(mapping.getType()).getMySqlType();

			columnDefinition.append(sqlType.name());

			if (sqlType.hasSize()) {
				columnDefinition.append("(");
				columnDefinition.append(ColumnConstants.MAX_MYSQL_VARCHAR_INDEX_LENGTH);
				columnDefinition.append(")");
			}

			columnDefinition.append(" NOT NULL");

			columnDefinitions.add(columnDefinition.toString());

			upsertKeyColumns.add(columnName);
			index++;
		}

		// Add extra TEXT column to hold any additional data
		columnDefinitions.add("`EXTRA` " + MySqlColumnType.TEXT.name() + " NOT NULL");

		// Add an index on the upsert key columns
		columnDefinitions.add("INDEX upsertKeyIndex(" + upsertKeyColumns.toString() + ")");

		String sql = "CREATE TEMPORARY TABLE " + tableName + " (" + columnDefinitions.toString() + ")";

		jdbcTemplate.update(sql);
	}

}
