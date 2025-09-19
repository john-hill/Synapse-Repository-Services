package org.sagebionetworks.repo.manager.grid.internal.replica.merge;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.json.JSONArray;
import org.sagebionetworks.repo.manager.grid.internal.replica.model.Column;
import org.sagebionetworks.repo.manager.grid.internal.replica.model.GridHeader;
import org.sagebionetworks.repo.manager.grid.internal.replica.model.RowView;
import org.sagebionetworks.repo.model.grid.patch.LogicalTimestamp;
import org.sagebionetworks.repo.model.grid.patch.compact.LogicalTimestampCompactSerializable;
import org.sagebionetworks.repo.model.table.ColumnType;
import org.sagebionetworks.util.ValidateArgument;

public class GridDataStream implements DataStream {
	
	private Iterator<RowView> rowViewIterator;
	private RowView currentRow;
	private ColumnMapping[] columnMapping;

	public GridDataStream(Iterator<RowView> rowViewIterator, GridHeader gridHeader, List<String> upsertKey) {
		this.rowViewIterator = rowViewIterator;
		this.currentRow = getNextRow();
		this.columnMapping = getColumnMapping(gridHeader, upsertKey);
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
	
	RowView getNextRow() {
		return rowViewIterator.hasNext() ? rowViewIterator.next() : null;
	}
	
	Object[] mapCurrentRow() {
		// The id of the "rows" array
		LogicalTimestamp arrId = currentRow.getArrNodeId();
		// The id of the vector that holds the row data
		LogicalTimestamp rowVecId = currentRow.getRowObject().getData().getVectorId();
		// The actual JSON array of cell values
		JSONArray cellValues = currentRow.getRowObject().getData().getCells();
		
		Object[] values = new Object[columnMapping.length];
		
		// First we map the upsert key columns
		int i;
		for (i = 0; i < columnMapping.length; i++) {
			ColumnMapping mapping = columnMapping[i];
			if (!mapping.isUpsertColumn()) {
				break;
			}
			values[i] = cellValues.get(mapping.getSourceIndex());
		}
		
		values[i++] = LogicalTimestampCompactSerializable.serialize(arrId);
		values[i++] = LogicalTimestampCompactSerializable.serialize(rowVecId);

		return values;
	}
	
	ColumnMapping[] getColumnMapping(GridHeader gridHeader, List<String> upsertKey) {
		ColumnMapping[] columnMapping = new ColumnMapping[upsertKey.size() + 2];

		Map<String, Integer> gridColumnIndex = new HashMap<>();
		
		for (int i = 0; i < gridHeader.getOrderedColumns().size(); i++) {
			Column column = gridHeader.getOrderedColumns().get(i);
			gridColumnIndex.put(column.getName(), i);
		}
		
		JSONArray cellValues = currentRow.getRowObject().getData().getCells();

		int i;
		
		for (i = 0; i < upsertKey.size(); i++) {
			String columnName = upsertKey.get(i);
			
			int gridIndex = gridColumnIndex.getOrDefault(columnName, -1);
			
			ValidateArgument.requirement(gridIndex >= 0, "The upsert key column \"" + columnName + "\" does not exist in the grid.");
			ValidateArgument.requirement(!cellValues.isNull(gridIndex), "The upsert key cannot have null values.");
			
			Object value = cellValues.get(gridIndex);
			
			ColumnType columnType = getColumnTypeFromGridValue(value);
			
			columnMapping[i] = new ColumnMapping(columnName, columnType, gridIndex, true);
		}
		
		// For the grid we also include the array id and row vector id reference
		columnMapping[i++] = new ColumnMapping("_arrId", ColumnType.JSON, -1, false);
		columnMapping[i++] = new ColumnMapping("_vecId", ColumnType.JSON, -1, false);

		return columnMapping;
	}
	
	
	
	static ColumnType getColumnTypeFromGridValue(Object value) {
		if (value instanceof Boolean) {
			return ColumnType.BOOLEAN;
		} else if (value instanceof Integer || value instanceof Long) {
			return ColumnType.INTEGER;
		} else if (value instanceof Float || value instanceof Double) {
			return ColumnType.DOUBLE;
		} else {
			return ColumnType.STRING;
		}
	}

}
