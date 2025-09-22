package org.sagebionetworks.repo.manager.grid.internal.replica.merge;

import java.util.Arrays;
import java.util.Iterator;

import org.json.JSONArray;
import org.sagebionetworks.repo.manager.grid.internal.replica.model.RowView;
import org.sagebionetworks.repo.model.grid.patch.LogicalTimestamp;
import org.sagebionetworks.repo.model.grid.patch.compact.LogicalTimestampCompactSerializable;

public class GridDataStream implements DataStream {
	
	private Iterator<RowView> rowViewIterator;
	private RowView currentRow;
	private ColumnMapping[] upsertKey;

	public GridDataStream(Iterator<RowView> rowViewIterator, ColumnMapping[] columnMapping) {
		this.rowViewIterator = rowViewIterator;
		this.currentRow = getNextRow();
		this.upsertKey = Arrays.stream(columnMapping).filter(ColumnMapping::isUpsertColumn).toArray(ColumnMapping[]::new);
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
		
		Object[] values = new Object[upsertKey.length + 2];
		
		// First we map the upsert key columns
		for (int i = 0; i < upsertKey.length; i++) {
			values[i] = cellValues.get(upsertKey[i].getGridIndex());
		}
		
		values[upsertKey.length] = LogicalTimestampCompactSerializable.serialize(arrId);
		values[upsertKey.length + 1] = LogicalTimestampCompactSerializable.serialize(rowVecId);

		return values;
	}

}
