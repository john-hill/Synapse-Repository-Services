package org.sagebionetworks.repo.manager.grid.internal.replica.merge;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.IntStream;

import org.sagebionetworks.repo.manager.grid.internal.replica.model.Column;
import org.sagebionetworks.repo.model.table.ColumnModel;
import org.sagebionetworks.repo.model.table.ColumnType;
import org.sagebionetworks.util.ValidateArgument;

class ColumnMapping {
	
	private final String columnName;
	private final ColumnType type;
	private final int csvIndex;
	private final int gridIndex;
	private final boolean isUpsertColumn;

	ColumnMapping(String columnName, ColumnType type, int csvIndex, int gridIndex, boolean isUpsertColumn) {
		this.columnName = columnName;
		this.type = type;
		this.csvIndex = csvIndex;
		this.gridIndex = gridIndex;
		this.isUpsertColumn = isUpsertColumn;
	}

	public String getColumnName() {
		return columnName;
	}

	public ColumnType getType() {
		return type;
	}

	public int getCsvIndex() {
		return csvIndex;
	}

	public int getGridIndex() {
		return gridIndex;
	}

	public boolean isUpsertColumn() {
		return isUpsertColumn;
	}

	@Override
	public int hashCode() {
		return Objects.hash(columnName, csvIndex, gridIndex, isUpsertColumn, type);
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj) {
			return true;
		}
		if (!(obj instanceof ColumnMapping)) {
			return false;
		}
		ColumnMapping other = (ColumnMapping) obj;
		return Objects.equals(columnName, other.columnName) && csvIndex == other.csvIndex && gridIndex == other.gridIndex && isUpsertColumn == other.isUpsertColumn
			&& type == other.type;
	}

	@Override
	public String toString() {
		return "ColumnMapping [columnName=" + columnName + ", type=" + type + ", csvIndex=" + csvIndex + ", gridIndex=" + gridIndex + ", isUpsertColumn=" + isUpsertColumn + "]";
	}
	
	// Compute an ordered mapping by upsert key first and then the rest of the columns of the CSV that exist in the grid.
	static ColumnMapping[] getColumnMapping(List<ColumnModel> csvSchema, List<Column> gridSchema, List<String> upsertKey) {
		List<ColumnMapping> columnMapping = new ArrayList<>();
		
		Map<String, Integer> csvColumnIndex = new HashMap<>(csvSchema.size());
		
		IntStream.range(0, csvSchema.size())
			.forEach(i -> csvColumnIndex.put(csvSchema.get(i).getName(), i));
		
		Map<String, Integer> gridColumnIndex = new HashMap<>();
		
		IntStream.range(0, gridSchema.size())
			.forEach(i -> gridColumnIndex.put(gridSchema.get(i).getName(), i));
			
		// We first map by the upsert key order
		for (int i = 0; i < upsertKey.size(); i++) {
			String columnName = upsertKey.get(i);
			
			int csvIndex = csvColumnIndex.getOrDefault(columnName, -1);
			
			ValidateArgument.requirement(csvIndex >= 0, "The upsert key column \"" + columnName + "\" does not exist in the CSV schema.");
			
			int gridIndex = gridColumnIndex.getOrDefault(columnName, -1);
			
			ValidateArgument.requirement(gridIndex >= 0, "The upsert key column \"" + columnName + "\" does not exist in the grid schema.");
			
			columnMapping.add(new ColumnMapping(columnName, csvSchema.get(csvIndex).getColumnType(), csvIndex, gridIndex, true));
		}
		
		// Now maps the rest of the columns
		for (int csvIndex = 0; csvIndex < csvSchema.size(); csvIndex++) {
			String columnName = csvSchema.get(csvIndex).getName();
			
			if (upsertKey.contains(columnName)) {
				continue;
			}
			
			int gridIndex = gridColumnIndex.getOrDefault(columnName, -1);
			
			// We ignore columns that do not exist in the grid
			if (gridIndex < 0) {
				continue;
			}
			
			columnMapping.add(new ColumnMapping(columnName, csvSchema.get(csvIndex).getColumnType(), csvIndex, gridIndex, false));
		}
		
		return columnMapping.toArray(new ColumnMapping[0]);
	}

}