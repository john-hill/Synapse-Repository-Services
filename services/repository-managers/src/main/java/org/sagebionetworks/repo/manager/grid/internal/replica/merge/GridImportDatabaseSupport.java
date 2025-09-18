package org.sagebionetworks.repo.manager.grid.internal.replica.merge;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import java.util.stream.Collectors;

import org.json.JSONArray;
import org.json.JSONObject;
import org.sagebionetworks.grid.db.GridTransaction;
import org.sagebionetworks.repo.manager.grid.internal.replica.model.Column;
import org.sagebionetworks.repo.manager.grid.internal.replica.model.GridHeader;
import org.sagebionetworks.repo.manager.grid.internal.replica.model.RowView;
import org.sagebionetworks.repo.manager.grid.row.translator.ColumnTypeToConType;
import org.sagebionetworks.repo.manager.grid.row.translator.Translator;
import org.sagebionetworks.repo.model.grid.patch.LogicalTimestamp;
import org.sagebionetworks.repo.model.grid.patch.compact.LogicalTimestampCompactSerializable;
import org.sagebionetworks.repo.model.table.ColumnConstants;
import org.sagebionetworks.repo.model.table.ColumnType;
import org.sagebionetworks.table.cluster.ColumnTypeInfo;
import org.sagebionetworks.table.cluster.MySqlColumnType;
import org.sagebionetworks.table.cluster.utils.CSVUtils;
import org.sagebionetworks.util.PaginationIterator;
import org.sagebionetworks.util.ValidateArgument;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import au.com.bytecode.opencsv.CSVReader;

@Service
public class GridImportDatabaseSupport {

	private static final int BATCH_SIZE = 1000;
	
	static final String TEMP_TABLE_CSV = "GRID_CSV_IMPORT_DATA";
	static final String TEMP_TABLE_GRID = "GRID_VIEW_DATA";
	
	private static final String TEMP_TABLE_GRID_EXTRA_COL = "EXTRA";
	
	private JdbcTemplate jdbcTemplate;
	
	public GridImportDatabaseSupport(JdbcTemplate gridDatabaseJdbcTemplate) {
		this.jdbcTemplate = gridDatabaseJdbcTemplate;
	}
	
	@GridTransaction(readOnly = false)
	public ColumnMapping[] createTemporaryTableFromCsv(CSVReader csvReader, GridHeader gridHeader, List<String> upsertKey) throws IOException {
		ValidateArgument.required(csvReader, "csvReader");
		ValidateArgument.required(gridHeader, "gridHeader");
		ValidateArgument.requiredNotEmpty(upsertKey, "upsertKey");
		
		String[] headerRow = csvReader.readNext();
		
		ValidateArgument.required(headerRow, "The CSV file cannot be empty.");
		
		// The first row will give us the upsert key schema since no key value can be null
		String[] row = csvReader.readNext();
		
		ColumnMapping[] columnMapping = getCsvColumnMapping(gridHeader, headerRow, upsertKey, row);
		
		createTemporaryTable(TEMP_TABLE_CSV, columnMapping);
		
		List<Object[]> batch = new ArrayList<>(BATCH_SIZE);
		
		// Add the first data row to the batch
		batch.add(translateCsvRow(row, columnMapping));
		
		while ((row = csvReader.readNext()) != null) {
			batch.add(translateCsvRow(row, columnMapping));
			if (batch.size() >= BATCH_SIZE) {
				batchInsert(TEMP_TABLE_CSV, batch, columnMapping);
			}
		}

		batchInsert(TEMP_TABLE_CSV, batch, columnMapping);
		
		return columnMapping;
	}
	
	@GridTransaction(readOnly = false)
	public void createTemporaryTableFromGrid(Iterator<RowView> rowViewIterator, GridHeader gridHeader, List<String> upsertKey) {
		ValidateArgument.required(gridHeader, "gridHeader");
		ValidateArgument.required(rowViewIterator, "rowViewIterator");
		ValidateArgument.requiredNotEmpty(upsertKey, "upsertKey");
		
		if (!rowViewIterator.hasNext()) {
			// No data, nothing to do
			return;
		}
		
		// Use the first row to help determine the upsert key schema
		RowView sampleRow = rowViewIterator.next();
		
		// We do not need all the columns from the grid, we just need the upsert key columns
		ColumnMapping[] gridColumnMapping = getGridColumnMapping(gridHeader, upsertKey, sampleRow);
		
		createTemporaryTable(TEMP_TABLE_GRID, gridColumnMapping);
		
		List<Object[]> batch = new ArrayList<>(BATCH_SIZE);
		
		// Add the first row to the batch
		batch.add(translateGridRow(sampleRow, gridColumnMapping));
		
		while (rowViewIterator.hasNext()) {
			RowView rowView = rowViewIterator.next();
			
			batch.add(translateGridRow(rowView, gridColumnMapping));
			
			if (batch.size() >= BATCH_SIZE) {
				batchInsert(TEMP_TABLE_GRID, batch, gridColumnMapping);
			}
		}
		
		batchInsert(TEMP_TABLE_GRID, batch, gridColumnMapping);
	}
	
	@GridTransaction(readOnly = true)
	public PaginationIterator<Object[]> getTemporaryTableIterator(String tableName) {
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
	
	void batchInsert(String tableName, List<Object[]> batch, ColumnMapping[] columnMapping) {
		if (batch.isEmpty()) {
			return;
		}
		
		StringJoiner columnNames = new StringJoiner(", ");
		StringJoiner valuePlaceholders = new StringJoiner(", ");
		
		for (ColumnMapping mapping : columnMapping) {
			columnNames.add(mapping.dbColumnName);
			valuePlaceholders.add("?");
		}
		
		String sql = "INSERT INTO " + tableName + " (" + columnNames.toString() + ") VALUES (" + valuePlaceholders.toString() + ")";
		
		jdbcTemplate.batchUpdate(sql, batch);
		
		batch.clear();
	}
	
	void createTemporaryTable(String tableName, ColumnMapping[] columnMapping) {
		StringJoiner columnDefinitions = new StringJoiner(", ");
		StringJoiner primaryKeyColumns = new StringJoiner(", ");
		
		for (ColumnMapping mapping : columnMapping) {
			
			StringBuilder columnDefinition = new StringBuilder();
			
			columnDefinition.append(mapping.dbColumnName);
			columnDefinition.append(" ");
			
			MySqlColumnType sqlType;
			
			if (mapping.isUpsertKey) {
				sqlType = ColumnTypeInfo.getInfoForType(mapping.type).getMySqlType();
			} else {
				// Store non upsert key as TEXT to avoid size issues (we do not index these columns)
				sqlType = MySqlColumnType.TEXT;
			}
			
			columnDefinition.append(sqlType.name());
			
			if (mapping.isUpsertKey) {
				if (sqlType.hasSize()) {
					columnDefinition.append("(");
					columnDefinition.append(ColumnConstants.MAX_MYSQL_VARCHAR_INDEX_LENGTH);
					columnDefinition.append(")");
				}
				columnDefinition.append(" NOT NULL");
			} else {
				columnDefinition.append(" NULL");
			}
			
			columnDefinitions.add(columnDefinition.toString());
			
			if (mapping.isUpsertKey) {
				primaryKeyColumns.add(mapping.dbColumnName);
			}
		}
		
		columnDefinitions.add("INDEX upsertKeyIndex(" + primaryKeyColumns.toString() + ")");
				
		String sql = "CREATE TEMPORARY TABLE " + tableName + " (" + columnDefinitions.toString() + ")";
		
		jdbcTemplate.update(sql);
	}
	
	static Object[] translateGridRow(RowView rowView, ColumnMapping[] columnMapping) {
		// The id of the "rows" array
		LogicalTimestamp arrId = rowView.getArrNodeId();
		// The id of the vector that holds the row data
		LogicalTimestamp rowVecId = rowView.getRowObject().getData().getVectorId();
		// The actual JSON array of cell values
		JSONArray cellValues = rowView.getRowObject().getData().getCells();
		
		Object[] values = new Object[columnMapping.length];
		
		for (int i = 0; i < columnMapping.length; i++) {
			ColumnMapping mapping = columnMapping[i];
			
			if (mapping.dbColumnName.equals(TEMP_TABLE_GRID_EXTRA_COL)) {
				// This is an extra column to hold any additional grid information, we store it as a JSON array
				values[i] = new JSONArray()
					.put(LogicalTimestampCompactSerializable.serialize(arrId))
					.put(LogicalTimestampCompactSerializable.serialize(rowVecId))
					.toString();
			} else {
				if (cellValues.isNull(mapping.index)) {
					values[i] = null;
				} else {
					values[i] = cellValues.get(mapping.index);
				}
			}
		}
		
		return values;
	}
	
	static Object[] translateCsvRow(String[] row, ColumnMapping[] columnMapping) {
		Object[] values = new Object[columnMapping.length];
		
		for (int i = 0; i < columnMapping.length; i++) {
			ColumnMapping mapping = columnMapping[i];
			String stringValue = row[mapping.index];
			if (mapping.translator != null) {				
				values[i] = mapping.translator.translateNullable(stringValue).getValue();
			} else {
				values[i] = stringValue;
			}
		}
		
		return values;
	}
	
	static ColumnMapping[] getGridColumnMapping(GridHeader gridHeader, List<String> upsertKey, RowView sampleRow) {
		ColumnMapping[] columnMapping = new ColumnMapping[upsertKey.size() + 1];

		Map<String, Integer> gridColumnIndex = new HashMap<>();
		
		for (int i = 0; i < gridHeader.getOrderedColumns().size(); i++) {
			Column column = gridHeader.getOrderedColumns().get(i);
			gridColumnIndex.put(column.getName(), i);
		}
		
		JSONArray cellValues = sampleRow.getRowObject().getData().getCells();

		for (int i = 0; i < upsertKey.size(); i++) {
			String columnName = upsertKey.get(i);
			
			int gridIndex = gridColumnIndex.getOrDefault(columnName, -1);
			
			ValidateArgument.requirement(gridIndex >= 0, "The upsert key column \"" + columnName + "\" does not exist in the grid.");
			ValidateArgument.requirement(!cellValues.isNull(gridIndex), "The upsert key cannot have null values.");
			
			Object value = cellValues.get(gridIndex);
			
			ColumnType columnType = getColumnTypeFromGridValue(value);
			
			columnMapping[i] = new ColumnMapping(getDatabaseColumnName(i), columnType, gridIndex, true);
		}
		
		// Add new extra column to hold any additional grid information
		columnMapping[upsertKey.size()] = new ColumnMapping(TEMP_TABLE_GRID_EXTRA_COL, ColumnType.JSON, -1, false);

		return columnMapping;
	}
	
	static ColumnType getColumnTypeFromGridValue(Object value) {
		if (value instanceof Boolean) {
			return ColumnType.BOOLEAN;
		} else if (value instanceof Integer || value instanceof Long) {
			return ColumnType.INTEGER;
		} else if (value instanceof Float || value instanceof Double) {
			return ColumnType.DOUBLE;
		} else if (value instanceof JSONArray || value instanceof JSONObject) {
			return ColumnType.JSON;
		} else {
			return ColumnType.STRING;
		}
	}
	
	// Given a CSV header and a ordered upsert key, return an array of column mapping that is ordered first by
	// the upsert key order and then by the remaining CSV columns. Uses the sample row to help determine the column type.
	static ColumnMapping[] getCsvColumnMapping(GridHeader gridHeader, String[] csvHeader, List<String> upsertKey, String[] sampleRow) {
		
		Map<String, Integer> csvColumnIndex = new HashMap<>();
		
		for (int i = 0; i < csvHeader.length; i++) {
			csvColumnIndex.put(csvHeader[i], i);
		}
		
		List<ColumnMapping> columnMapping = new ArrayList<>();		
		
		int columnIndex = 0;
		
		// First map the upsert key columns in the order given by the upsert key		
		for (String columnName : upsertKey) {
			
			int csvIndex = csvColumnIndex.getOrDefault(columnName, -1);
			
			ValidateArgument.requirement(csvIndex >= 0, "The upsert key column \"" + columnName + "\" does not exist in the CSV file.");
			
			String value = sampleRow[csvIndex];
			
			ValidateArgument.requirement(value != null && !value.isBlank(), "The upsert key cannot have null or empty values.");
			
			ColumnType columnType = CSVUtils.checkType(value, null).getColumnType();
			
			columnMapping.add(new ColumnMapping(getDatabaseColumnName(columnIndex), columnType, csvIndex, true));
			columnIndex++;
		}
		
		Set<String> upsertKeySet = new HashSet<>(upsertKey);
		Set<String> gridColumnSet = gridHeader.getOrderedColumns().stream().map(Column::getName).collect(Collectors.toSet());
		
		// Next map the remaining CSV columns in the order they appear in the CSV file
		for (int csvIndex = 0; csvIndex < csvHeader.length; csvIndex++) {
			String columnName = csvHeader[csvIndex];
			
			if (upsertKeySet.contains(columnName)) {
				// This column is already mapped as part of the upsert key
				continue;
			}
			
			if (!gridColumnSet.contains(columnName)) {
				// This column is not part of the grid schema
				continue;
			}
			
			// We do not need to know the type for non upsert key columns at this time
			columnMapping.add(new ColumnMapping(getDatabaseColumnName(columnIndex), null, csvIndex, false));
			columnIndex++;
		}
		
		return columnMapping.toArray(new ColumnMapping[0]);
	}
	
	static String getDatabaseColumnName(int index) {
		return "`C" + index + "`";
	}
	
	static class ColumnMapping {
		private String dbColumnName;
		private ColumnType type;
		private int index;
		private boolean isUpsertKey;
		private Translator translator;
		
		ColumnMapping(String dbColumnName, ColumnType type, int index, boolean isUpsertKey) {
			this.dbColumnName = dbColumnName;
			this.type = type;
			this.index = index;
			this.isUpsertKey = isUpsertKey;
			this.translator = type == null ? null : ColumnTypeToConType.lookUpType(type).getTranslator();
		}
	}
}
