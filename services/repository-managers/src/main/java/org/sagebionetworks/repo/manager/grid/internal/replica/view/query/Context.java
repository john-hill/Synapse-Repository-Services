package org.sagebionetworks.repo.manager.grid.internal.replica.view.query;

import org.sagebionetworks.repo.manager.grid.internal.replica.model.Column;
import org.sagebionetworks.repo.manager.grid.internal.replica.model.GridHeader;

public class Context {

	private final GridHeader header;

	public Context(GridHeader header) {
		super();
		this.header = header;
	}

	public GridHeader getHeader() {
		return header;
	}

	/**
	 * 
	 * @param columnName
	 * @return The {@link Column#getVectorIndex() vector index} for the given column name.
	 * @throws IllegalArgumentException if the column name is not found.
	 */
	public Integer getColumnVectorIndexForName(String columnName) {
		return header.getOrderedColumns().stream().filter(c -> c.getName().equals(columnName))
				.map(c -> c.getVectorIndex()).findFirst()
				.orElseThrow(() -> new IllegalArgumentException("Column name not found: " + columnName));
	}

	/**
	 * 
	 * @param columnName
	 * @return The zero-based index in the ordered columns list for the given column name.
	 * @throws IllegalArgumentException if the column name is not found.
	 */
	public Integer getColumnIndexForName(String columnName) {
		for (int i = 0; i < header.getOrderedColumns().size(); i++) {
			if (header.getOrderedColumns().get(i).getName().equals(columnName)) {
				return i;
			}
		}
		throw new IllegalArgumentException("Column name not found: " + columnName);
	}
	
}
