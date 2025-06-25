package org.sagebionetworks.repo.model.grid.node;

import java.util.Objects;

import org.sagebionetworks.repo.model.grid.patch.LogicalTimestamp;

public class ObjectNode implements Node {

	private LogicalTimestamp id;
	private String key;
	private LogicalTimestamp value;

	@Override
	public LogicalTimestamp getId() {
		return id;
	}

	public String getKey() {
		return key;
	}

	public ObjectNode setKey(String key) {
		this.key = key;
		return this;
	}

	public LogicalTimestamp getValue() {
		return value;
	}

	public ObjectNode setValue(LogicalTimestamp value) {
		this.value = value;
		return this;
	}

	public ObjectNode setId(LogicalTimestamp id) {
		this.id = id;
		return this;
	}

	@Override
	public int hashCode() {
		return Objects.hash(id, key, value);
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (getClass() != obj.getClass())
			return false;
		ObjectNode other = (ObjectNode) obj;
		return Objects.equals(id, other.id) && Objects.equals(key, other.key) && Objects.equals(value, other.value);
	}

	@Override
	public String toString() {
		return "ObjectNode [id=" + id + ", key=" + key + ", value=" + value + "]";
	}

}
