package org.sagebionetworks.repo.model.grid.node;

import java.util.Objects;

import org.sagebionetworks.repo.model.grid.patch.ConType;
import org.sagebionetworks.repo.model.grid.patch.ConValue;
import org.sagebionetworks.repo.model.grid.patch.LogicalTimestamp;

public class ConstantNode implements Node, HasJsonValue<ConstantNode> {

	/** a timestamp, which is the ID of the con node. */
	private LogicalTimestamp id;
	/** an optional JSON/CBOR value, which is the value of the con node when it is not undefined and is not a timestamp.
	 * Implementation note: when the value is `undefined`, this will be a Java `null`. When the value is a JSON `null`, this will be a JSONObject.NULL.
	 */
	private ConValue value;

	public ConValue getConValue() {
		return value;
	}

	public ConstantNode setValue(ConValue value) {
		this.value = value;
		return this;
	}

	public ConstantNode setValue(Object value) {
		this.value = new ConValue(ConType.fromValue(value), value);
		return this;
	}

	public ConstantNode setId(LogicalTimestamp id) {
		this.id = id;
		return this;
	}


	@Override
	public ConstantNode setValueFromJson(String jsonString) {
		this.value = ConValue.fromJsonString(jsonString);
		return this;
	}

	@Override
	public String getValueAsJson() {
		return value.toJson();
	}

	@Override
	public LogicalTimestamp getId() {
		return id;
	}

	@Override
	public int hashCode() {
		return Objects.hash(id, value);
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (getClass() != obj.getClass())
			return false;
		ConstantNode other = (ConstantNode) obj;
		return Objects.equals(id, other.id) && Objects.equals(value, other.value);
	}

	@Override
	public String toString() {
		return "ConstantNode [id=" + id + ", value=" + value + "]";
	}

}
