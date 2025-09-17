package org.sagebionetworks.repo.manager.grid.internal.replica.merge;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.stream.Collectors;

import org.json.JSONArray;
import org.sagebionetworks.repo.manager.grid.internal.replica.model.Column;
import org.sagebionetworks.repo.manager.grid.internal.replica.model.GridHeader;
import org.sagebionetworks.repo.manager.grid.internal.replica.model.RowView;
import org.sagebionetworks.repo.manager.grid.internal.replica.view.GridReplicaViewManager;
import org.sagebionetworks.repo.manager.grid.internal.replica.view.filter.MultiValuesViewFilter;
import org.sagebionetworks.repo.manager.grid.internal.replica.view.filter.ViewFilter;
import org.sagebionetworks.repo.manager.grid.row.translator.ColumnTypeToConType;
import org.sagebionetworks.repo.manager.grid.row.translator.Translator;
import org.sagebionetworks.repo.model.grid.patch.ConValue;
import org.sagebionetworks.repo.model.table.ColumnModel;
import org.sagebionetworks.table.cluster.utils.CSVUtils;
import org.sagebionetworks.util.ValidateArgument;

public class BatchMergeProcessor {

	private static final int CSV_BATCH_SIZE = 1000;
	
	private GridReplicaViewManager gridViewManager;
	private GridHeader gridHeader;
	private ColumnMapping[] csvColumns;
	private ColumnMapping[] upsertColumns;
	private List<String[]> batch;
	private ColumnModel[] schema;
	private Translator[] translators;
	private boolean schemaChecked = false;
	private int processedCount = 0;
	private int updatedCount = 0;
	private int createdCount = 0;
	
	public BatchMergeProcessor(GridReplicaViewManager gridViewManager, GridHeader gridHeader, String[] csvHeader, List<String> upsertKey) {
		ValidateArgument.required(gridViewManager, "gridViewManager");
		ValidateArgument.required(gridHeader, "gridHeader");
		ValidateArgument.required(csvHeader, "csvHeader");
		ValidateArgument.required(upsertKey, "upsertKey");
		ValidateArgument.requirement(csvHeader.length >= gridHeader.getOrderedColumns().size(), "The CSV file must have at least as many columns as the grid.");
		
		this.gridViewManager = gridViewManager;
		this.gridHeader = gridHeader;
		this.schema = new ColumnModel[csvHeader.length];
		this.translators = new Translator[csvHeader.length];
		this.csvColumns = getColumnMapping(gridHeader, csvHeader);
		this.upsertColumns = getColumnMapping(gridHeader, upsertKey.toArray(new String[0]));
		this.batch = new ArrayList<>(CSV_BATCH_SIZE);
	}
	
	public void next(String[] row) {
		
		batch.add(row);
		
		processedCount++;
		
		if (batch.size() >= CSV_BATCH_SIZE) {
			flush();
		}
	}
	
	public int getProcessedCount() {
		return processedCount;
	}
	
	public int getUpdatedCount() {
		return updatedCount;
	}
	
	public int getCreatedCount() {
		return createdCount;
	}
	
	public void flush() {
		
		if (batch.isEmpty()) {
			return;
		}
		
		// The upsert keys in the batch to search
		List<Object[]> upsertKeys = new ArrayList<>(batch.size());
		
		Map<String, ConValue[]> rowsMap = new HashMap<>(batch.size());
		
		for (String[] row : batch) {
			
			// Makes sure the schema and translators are up-to-date while scanning the CSV file
			checkSchema(row);
			
			// Translate the CSV row to the appropriate types and ordered by the grid columns
			ConValue[] translatedRow = translateRow(row);
			
			Object[] upsertKey = new Object[upsertColumns.length];
			
			UpsertKeyBuilder keyBuilder = new UpsertKeyBuilder();

			for (int i = 0; i < upsertColumns.length; i++) {
				ColumnMapping upsertColumn = upsertColumns[i];
				ConValue value = translatedRow[upsertColumn.gridIndex];
				
				if (value.getValue() == null) {
					throw new IllegalArgumentException("The upsert key column: "+upsertColumn.column.getName()+" cannot be null.");
				}
				
				upsertKey[i] = value.getValue();
				keyBuilder.append(value.getValue());
			}

			upsertKeys.add(upsertKey);
			rowsMap.put(keyBuilder.build(), translatedRow);
		}
		
		List<Column> upsertColumnsList = Arrays.stream(upsertColumns)
			.map(c -> c.column)
			.collect(Collectors.toList());
		
		ViewFilter filter = new MultiValuesViewFilter(upsertColumnsList, upsertKeys);
		
		Iterator<RowView> it = gridViewManager.getQueryIterator(gridHeader, List.of(filter));
		
		// Now find all the matching rows in the grid for the batch
		while (it.hasNext()) {
			RowView matchingRow = it.next();
			
			JSONArray rowCells = matchingRow.getCells();
			
			UpsertKeyBuilder keyBuilder = new UpsertKeyBuilder();
			
			for (ColumnMapping upsertColumn : upsertColumns) {
				Object cellValue = rowCells.get(upsertColumn.gridIndex);		
				keyBuilder.append(cellValue);
			}
			
			// Remove the matching row from the map so that what is left are the new rows to insert
			ConValue[] updatedValues = rowsMap.remove(keyBuilder.build());
			
			updateRow(matchingRow, updatedValues);
		}

		// All the remaining rows in the map are new and need to be inserted
		rowsMap.values().forEach(this::insertRow);

		batch.clear();
	}
	
	void updateRow(RowView currentRow, ConValue[] translatedRow) {
		// TODO Use patch builder? Which replica id? One row at the time?
		updatedCount++;
	}
	
	void insertRow(ConValue[] translatedRow) {
		// TODO
		createdCount++;
	}
	
	ConValue[] translateRow(String[] row) {
		ConValue[] translatedRow = new ConValue[row.length];
		for (int i = 0; i < row.length; i++) {
			ConValue translatedCell = translators[i].translateNullable(row[i]);
			// Keeps the order of the columns in the grid
			translatedRow[csvColumns[i].gridIndex] = translatedCell;
		}
		return translatedRow;
	}

	void checkSchema(String[] row) {
		if (schemaChecked) {
			return;
		}

		// Makes sure the schema is updated while scanning the CSV file
		CSVUtils.checkTypes(row, schema);

		boolean stableSchema = true;

		for (int i = 0; i < schema.length; i++) {
			ColumnModel columnModel = schema[i];
			if (columnModel == null || columnModel.getColumnType() == null) {
				translators[i] = ColumnTypeToConType.STRING.getTranslator();
				stableSchema = false;
				break;
			}
			translators[i] = ColumnTypeToConType.lookUpType(columnModel.getColumnType()).getTranslator();
		}

		schemaChecked = stableSchema;
		
	}
	
	static ColumnMapping[] getColumnMapping(GridHeader gridHeader, String[] columnNames) {
		ColumnMapping[] columnMappings = new ColumnMapping[columnNames.length];
		List<Column> gridColumns = gridHeader.getOrderedColumns();
		
		Map<String, Integer> gridColumnIndexMap = new HashMap<>();
		
		for (int i = 0; i < gridColumns.size(); i++) {
			gridColumnIndexMap.put(gridColumns.get(i).getName(), i);
		}
		
		for (int i = 0; i < columnNames.length; i++) {
			String columnName = columnNames[i];

			int gridIndex = gridColumnIndexMap.getOrDefault(columnName, -1);

			if (gridIndex < 0) {
				throw new IllegalArgumentException("The column: " + columnName + " was not found in the grid schema.");
			}

			columnMappings[i] = new ColumnMapping(gridColumns.get(gridIndex), gridIndex);
		}
		
		return columnMappings;
	}
	
	static class ColumnMapping {
		
		private final Column column;
		// The index of this column in the ordered columns of the grid
		private final int gridIndex;
		
		ColumnMapping(Column column, int gridIndex) {
			this.column = column;
			this.gridIndex = gridIndex;
		}
	}
	
	static class UpsertKeyBuilder {
		
		private StringJoiner joiner;
		
		UpsertKeyBuilder() {
			joiner = new StringJoiner("|");
		}
		
		void append(Object value) {
			joiner.add(value.toString());
		}
		
		String build() {
			return joiner.toString();
		}		
	}
}
