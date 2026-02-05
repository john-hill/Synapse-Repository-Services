package org.sagebionetworks.repo.manager.grid.internal.replica.change;

import java.util.Objects;

import org.json.JSONObject;
import org.sagebionetworks.repo.model.grid.patch.LogicalTimestamp;
import org.sagebionetworks.repo.model.grid.patch.compact.LogicalTimestampCompactSerializable;

public class DeleteRowChange implements IntendedChange {

	private final LogicalTimestamp arrId;

	public DeleteRowChange(LogicalTimestamp arrId) {
		super();
		this.arrId = arrId;
	}

	@Override
	public IntendedChangeType getType() {
		return IntendedChangeType.delete_row;
	}

	@Override
	public JSONObject toJson() {
		JSONObject json = new JSONObject();
		json.put("a", LogicalTimestampCompactSerializable.serialize(arrId));
		return json;
	}

	@Override
	public int hashCode() {
		return Objects.hash(arrId);
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (getClass() != obj.getClass())
			return false;
		DeleteRowChange other = (DeleteRowChange) obj;
		return Objects.equals(arrId, other.arrId);
	}

	@Override
	public String toString() {
		return "DeleteRowChange [arrId=" + arrId + "]";
	}

}
