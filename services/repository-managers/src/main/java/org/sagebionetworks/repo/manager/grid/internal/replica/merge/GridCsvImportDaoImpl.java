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
import org.sagebionetworks.util.ValidateArgument;
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
	public void streamToCsvTempTable(DataStream dataStream) {
		streamToTempTable(TEMP_TABLE_CSV_DATA, dataStream);
	}
	
	@Override
	@GridTransaction(readOnly = false)
	public void streamToGridTempTable(DataStream dataStream) {
		streamToTempTable(TEMP_TABLE_GRID_DATA, dataStream);
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
	public PaginationIterator<JoinedRow> getJoinedTempTableIterator(ColumnMapping[] csvColumnMapping, ColumnMapping[] gridColumnMapping) {
		List<ColumnMapping> csvUpsertColumns = Arrays.stream(csvColumnMapping)
			.filter(ColumnMapping::isUpsertColumn)
			.collect(Collectors.toList());
		
		List<ColumnMapping> gridUpsertColumns = Arrays.stream(gridColumnMapping)
			.filter(ColumnMapping::isUpsertColumn)
			.collect(Collectors.toList());
		
		ValidateArgument.requirement(csvUpsertColumns.size() == gridUpsertColumns.size(), "The CSV and the Grid upsert key must have the same number of columns.");
		
		StringJoiner joinConditions = new StringJoiner(" AND ");
		
		IntStream.range(0, csvUpsertColumns.size())
			.mapToObj(index -> getUpsertKeyColumnName(index))
			.map(columnName -> "C." + columnName + " = G." + columnName)
			.forEach(joinConditions::add);
		
		String sql = String.format("SELECT C.*, G." + COL_EXTRA + " FROM " 
			+ TEMP_TABLE_CSV_DATA + " C LEFT JOIN " + TEMP_TABLE_GRID_DATA 
			+ " G ON (%s) LIMIT ? OFFSET ?", joinConditions.toString());
		
		return new PaginationIterator<>((limit, offset) -> 
			jdbcTemplate.query(sql.toString(), (rs, rowNum) -> {
				Object[] upsertKeyValues = new Object[csvUpsertColumns.size()];
				
				int i;
				for (i=1; i <= csvUpsertColumns.size(); i++) {
					upsertKeyValues[i - 1] = rs.getObject(i);
				}				

				String csvData = rs.getString(i++);
				String gridData = rs.getString(i++);
				
				return new JoinedRow(upsertKeyValues, csvData, gridData);
			}, limit, offset)
		, BATCH_SIZE);
	}
	
	void streamToTempTable(String tableName, DataStream dataStream) {
		ColumnMapping[] columnMapping = dataStream.getColumnMapping();
		int upsertKeyLength = (int) Arrays.stream(columnMapping).filter(ColumnMapping::isUpsertColumn).count();
		createTemporaryTable(tableName, columnMapping);
		
		List<Object[]> batch = new ArrayList<>(BATCH_SIZE);
		
		while(dataStream.hasNext()) {
			batch.add(mapRow(dataStream.next(), upsertKeyLength, columnMapping));
			if (batch.size() >= BATCH_SIZE) {
				flushBatch(tableName, columnMapping, batch);
			}
		}
		flushBatch(tableName, columnMapping, batch);
	}
	
	Object[] mapRow(Object[] row, int upsertKeyLength, ColumnMapping[] columnMapping) {
		Object[] mappedRow = new Object[upsertKeyLength + 1];
		
		int i = 0;
		
		for (; i < upsertKeyLength; i++) {
			mappedRow[i] = row[i];			
		}
		
		JSONArray extraData = new JSONArray();
		
		for (; i < columnMapping.length; i++) {
			// Pack the remaining columns into the extra data JSON array
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
	
	void flushBatch(String tableName, ColumnMapping[] columnMapping, List<Object[]> batch) {
		if (batch.isEmpty()) {
			return;
		}
		
		StringJoiner columnNames = new StringJoiner(",");
		StringJoiner valuePlaceholders = new StringJoiner(",");
		
		for (int i = 0; i < columnMapping.length; i++) {
			if (!columnMapping[i].isUpsertColumn()) {
				break;
			}
			columnNames.add(getUpsertKeyColumnName(i));
			valuePlaceholders.add("?");
		}
		
		columnNames.add(COL_EXTRA);
		valuePlaceholders.add("?");
		
		String sql = "INSERT INTO " + tableName + " (" + columnNames.toString() + ") VALUES (" + valuePlaceholders.toString() + ")";
		
		jdbcTemplate.batchUpdate(sql, batch);
		
		batch.clear();
	}
	
	void createTemporaryTable(String tableName, ColumnMapping[] columnMapping) {
		StringJoiner columnDefinitions = new StringJoiner(",");
		StringJoiner upsertKeyColumns = new StringJoiner("`,`", "`", "`");
		
		int index = 0;
		
		for (ColumnMapping mapping : columnMapping) {
			
			// We only normalize the upsert key columns that are indexes and used for the join
			if (!mapping.isUpsertColumn()) {
				break;
			}
			
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
