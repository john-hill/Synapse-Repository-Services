package org.sagebionetworks.repo.manager.grid.internal.replica.change;

import org.json.JSONObject;
import org.sagebionetworks.repo.model.grid.patch.ConValue;
import org.sagebionetworks.repo.model.grid.patch.LogicalTimestamp;

public class AddColumn implements IntendedChange {
	
	private final LogicalTimestamp getColumnOrderArrId;
	private final ConValue columnIndex;
	
	public AddColumn(LogicalTimestamp getColumnOrderArrId, ConValue columnIndex) {
		super();
		this.getColumnOrderArrId = getColumnOrderArrId;
		this.columnIndex = columnIndex;
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
