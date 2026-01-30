package org.sagebionetworks.repo.manager.grid.internal.replica.change;

import org.json.JSONObject;
import org.sagebionetworks.repo.model.grid.patch.LogicalTimestamp;

public class DeleteColumn implements IntendedChange {
	
	private final LogicalTimestamp getColumnOrderArrId;
	private final LogicalTimestamp columnOrderNodeId;
	
	
	public DeleteColumn(LogicalTimestamp getColumnOrderArrId, LogicalTimestamp columnOrderNodeId) {
		super();
		this.getColumnOrderArrId = getColumnOrderArrId;
		this.columnOrderNodeId = columnOrderNodeId;
	}

	@Override
	public IntendedChangeType getType() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public JSONObject toJson() {
		// TODO Auto-generated method stub
		return null;
	}

}
