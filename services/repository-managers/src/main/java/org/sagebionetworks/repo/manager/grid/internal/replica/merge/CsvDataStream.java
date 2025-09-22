package org.sagebionetworks.repo.manager.grid.internal.replica.merge;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.sagebionetworks.repo.manager.grid.internal.replica.model.Column;
import org.sagebionetworks.repo.manager.grid.internal.replica.model.GridHeader;
import org.sagebionetworks.repo.manager.grid.row.translator.ColumnTypeToConType;
import org.sagebionetworks.repo.manager.grid.row.translator.Translator;
import org.sagebionetworks.repo.model.table.ColumnModel;
import org.sagebionetworks.repo.model.table.ColumnType;
import org.sagebionetworks.util.ValidateArgument;

import au.com.bytecode.opencsv.CSVReader;

public class CsvDataStream implements DataStream {
	
	private CSVReader csvReader;
	private String[] currentRow;
	private String[] csvHeader;
	private ColumnMapping[] columnMapping;
	private Translator[] columnTranslators;
	
	public CsvDataStream(CSVReader csvReader, List<ColumnModel> schema, GridHeader gridHeader, List<String> upsertKey) {
		this.csvReader = csvReader;
		// Skip the header
		this.csvHeader = getNextRow();
		// Move the cursor to the first row
		this.currentRow = getNextRow();
		ValidateArgument.requirement(currentRow != null, "The CSV file cannot be empty.");
		ValidateArgument.requirement(csvHeader.length == schema.size(), "The number of columns in the CSV file does not match the schema.");
		this.columnMapping = getColumnMapping(gridHeader, upsertKey, schema);
		this.columnTranslators = Arrays.stream(columnMapping).map(mapping -> 
			ColumnTypeToConType.lookUpType(mapping.getType()).getTranslator()
		).toArray(Translator[]::new);
	}

	@Override
	public boolean hasNext() {
		return currentRow != null;
	}

	@Override
	public Object[] next() {
		Object[] mappedRow = mapCurrentRow();

		this.currentRow = getNextRow();
		
		return mappedRow;
	}
	
	@Override
	public ColumnMapping[] getColumnMapping() {
		return columnMapping;
	}
	
	String[] getNextRow() {
		try {
			return csvReader.readNext();
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}
	
	Object[] mapCurrentRow() {
		Object[] values = new Object[columnMapping.length];
		
		// First we map the upsert key columns
		for (int i = 0; i < columnMapping.length; i++) {
			ColumnMapping mapping = columnMapping[i];
			
			String stringValue = currentRow[mapping.getSourceIndex()];
			
			if (mapping.isUpsertColumn()) {
				stringValue = checkUpsertKeyValue(stringValue);
			}
			
			values[i] = columnTranslators[i].translateNullable(stringValue).getValue();
		}
		
		return values;
	}
	
	ColumnMapping[] getColumnMapping(GridHeader gridHeader, List<String> upsertKey, List<ColumnModel> schema) {
		
		Map<String, Integer> csvColumnIndex = new HashMap<>();
		
		for (int i = 0; i < csvHeader.length; i++) {
			csvColumnIndex.put(csvHeader[i], i);
		}
		
		List<ColumnMapping> columnMapping = new ArrayList<>();	
		
		boolean[] mapped = new boolean[csvHeader.length];
		
		// First map the upsert key columns
		for (String columnName : upsertKey) {
			
			int csvIndex = csvColumnIndex.getOrDefault(columnName, -1);
			
			ValidateArgument.requirement(csvIndex >= 0, "The upsert key column \"" + columnName + "\" does not exist in the CSV file.");
			
			ColumnType columnType = schema.get(csvIndex).getColumnType();
			
			columnMapping.add(new ColumnMapping(columnName, columnType, csvIndex, true));
			
			mapped[csvIndex] = true;
		}
		
		Set<String> gridColumnNames = gridHeader.getOrderedColumns().stream().map(Column::getName).collect(Collectors.toSet());
		// Now maps the remaining columns in order
		for (int i = 0; i < csvHeader.length; i++) {
			String columnName = csvHeader[i];
			
			if (mapped[i]) {
				continue;
			}
			
			if (!gridColumnNames.contains(columnName)) {
				continue;
			}

			ColumnType columnType = schema.get(i).getColumnType();
			
			columnMapping.add(new ColumnMapping(columnName, columnType, i, false));
		}
		
		return columnMapping.toArray(new ColumnMapping[0]);
	}
	
	static String checkUpsertKeyValue(String value) {
		ValidateArgument.requirement(value != null && !value.isBlank(), "The upsert key cannot have null or empty values.");
		return value;
	}

}
