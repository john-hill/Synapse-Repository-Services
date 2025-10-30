package org.sagebionetworks.repo.manager.grid.internal.replica.view.query;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.sagebionetworks.repo.manager.grid.internal.replica.model.GridHeader;

public class Context {
	
	private static Map<String, Integer> buildColumnNamesToIndexMap(GridHeader header) {
		if (header.getOrderedColumns() == null || header.getOrderedColumns().isEmpty()) {
			return Collections.emptyMap();
		}
		
		Map<String, Integer> map = new HashMap<>(header.getOrderedColumns().size());
		
		for (int i = 0; i < header.getOrderedColumns().size(); i++) {
			map.put(header.getOrderedColumns().get(i).getName(), i);
		}
		
		return map;
	}	

	private final GridHeader header;
	private final Map<String, Integer> columnNameToIndexMap;

	public Context(GridHeader header) {
		this.header = header;
		this.columnNameToIndexMap = buildColumnNamesToIndexMap(header);
	}

	public GridHeader getHeader() {
		return header;
	}

	/**
	 * 
	 * @param columnName
	 * @return The zero-based index in the ordered columns list for the given column name.
	 * @throws IllegalArgumentException if the column name is not found.
	 */
	public Integer getColumnIndexForName(String columnName) {
		Integer index = columnNameToIndexMap.get(columnName);
		if (index == null) {
			throw new IllegalArgumentException("Column name not found: " + columnName);
		}
		return index;
	}
	
	
}
