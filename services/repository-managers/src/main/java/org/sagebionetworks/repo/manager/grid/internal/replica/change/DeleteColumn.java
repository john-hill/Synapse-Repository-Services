package org.sagebionetworks.repo.manager.grid.internal.replica.change;

import java.util.Objects;

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

	@Override
	public int hashCode() {
		return Objects.hash(columnOrderNodeId, getColumnOrderArrId);
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (getClass() != obj.getClass())
			return false;
		DeleteColumn other = (DeleteColumn) obj;
		return Objects.equals(columnOrderNodeId, other.columnOrderNodeId)
				&& Objects.equals(getColumnOrderArrId, other.getColumnOrderArrId);
	}

	@Override
	public String toString() {
		return "DeleteColumn [getColumnOrderArrId=" + getColumnOrderArrId + ", columnOrderNodeId=" + columnOrderNodeId
				+ "]";
	}

}
