package org.sagebionetworks.repo.manager.grid.internal.replica.change;

import java.util.Objects;

import org.json.JSONObject;
import org.sagebionetworks.repo.model.grid.patch.ConValue;
import org.sagebionetworks.repo.model.grid.patch.LogicalTimestamp;
import org.sagebionetworks.util.ValidateArgument;

public class AddColumn implements IntendedChange {

	private final LogicalTimestamp columnOrderArrId;
	private final ConValue columnIndex;

	public AddColumn(LogicalTimestamp columnOrderArrId, ConValue columnIndex) {
		ValidateArgument.required(columnIndex, "colulmnIndex");
		ValidateArgument.required(columnOrderArrId, "columnOrderArrId");
		this.columnOrderArrId = columnOrderArrId;
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

	@Override
	public int hashCode() {
		return Objects.hash(columnIndex, columnOrderArrId);
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (getClass() != obj.getClass())
			return false;
		AddColumn other = (AddColumn) obj;
		return Objects.equals(columnIndex, other.columnIndex)
				&& Objects.equals(columnOrderArrId, other.columnOrderArrId);
	}

	@Override
	public String toString() {
		return "AddColumn [columnOrderArrId=" + columnOrderArrId + ", columnIndex=" + columnIndex + "]";
	}

}
