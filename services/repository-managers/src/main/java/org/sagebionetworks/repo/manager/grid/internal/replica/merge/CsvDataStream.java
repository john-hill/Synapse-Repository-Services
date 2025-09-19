package org.sagebionetworks.repo.manager.grid.internal.replica.merge;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.sagebionetworks.repo.manager.grid.internal.replica.model.Column;
import org.sagebionetworks.repo.manager.grid.internal.replica.model.GridHeader;
import org.sagebionetworks.repo.model.table.ColumnType;
import org.sagebionetworks.table.cluster.utils.CSVUtils;
import org.sagebionetworks.util.ValidateArgument;

import au.com.bytecode.opencsv.CSVReader;

public class CsvDataStream implements DataStream {
	
	private CSVReader csvReader;
	private String[] currentRow;
	private String[] csvHeader;
	private ColumnMapping[] columnMapping;
	private Set<String> gridColumnNames;
	
	public CsvDataStream(CSVReader csvReader, GridHeader gridHeader, List<String> upsertKey) {
		this.csvReader = csvReader;
		this.csvHeader = getNextRow();
		this.currentRow = getNextRow();
		ValidateArgument.requirement(currentRow != null, "The CSV file cannot be empty.");
		this.gridColumnNames = gridHeader.getOrderedColumns().stream().map(Column::getName).collect(Collectors.toSet());
		this.columnMapping = getColumnMapping(upsertKey);
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
			
			if (mapping.getTranslator() == null) {
				values[i] = stringValue;
			} else {
				values[i] = mapping.getTranslator().translateNullable(stringValue).getValue();
			}
		}
		
		return values;
	}
	
	ColumnMapping[] getColumnMapping(List<String> upsertKey) {
		String[] sampleRow = currentRow;
		
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
			
			String value = checkUpsertKeyValue(sampleRow[csvIndex]);
			
			ColumnType columnType = CSVUtils.checkType(value, null).getColumnType();
			
			columnMapping.add(new ColumnMapping(columnName, columnType, csvIndex, true));
			
			mapped[csvIndex] = true;
		}
		
		// Now maps the remaining columns in order
		for (int i = 0; i < csvHeader.length; i++) {
			String columnName = csvHeader[i];
			
			if (mapped[i]) {
				continue;
			}
			
			if (!gridColumnNames.contains(columnName)) {
				continue;
			}
			
			// We do not need the type at this time
			columnMapping.add(new ColumnMapping(columnName, null, i, false));
		}
		
		return columnMapping.toArray(new ColumnMapping[0]);
	}
	
	static String checkUpsertKeyValue(String value) {
		ValidateArgument.requirement(value != null && !value.isBlank(), "The upsert key cannot have null or empty values.");
		return value;
	}

}
