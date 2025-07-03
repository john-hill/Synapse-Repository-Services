package org.sagebionetworks.repo.model.grid.node;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import org.json.JSONObject;
import org.sagebionetworks.repo.model.grid.patch.LogicalTimestamp;
import org.sagebionetworks.repo.model.grid.patch.compact.LogicalTimestampCompactSerializable;

public class ObjectNode implements Node, HasJsonValue<ObjectNode> {

	private LogicalTimestamp id;
	private Map<String, LogicalTimestamp> value;

	@Override
	public LogicalTimestamp getId() {
		return id;
	}

	public Map<String, LogicalTimestamp> getValue() {
		return value;
	}

	public ObjectNode setValue(Map<String, LogicalTimestamp> map) {
		this.value = map;
		return this;
	}

	public ObjectNode setId(LogicalTimestamp id) {
		this.id = id;
		return this;
	}

	/**
	 * Set the value from the passed JSON string.
	 * 
	 * @param json
	 * @return
	 */
	public ObjectNode setValueFromJson(String json) {
		if ("{}".equals(json)) {
			this.value = null;
			return this;
		}
		JSONObject obj = new JSONObject(json);
		if (obj.length() > 0) {
			this.value = new LinkedHashMap<>(obj.length());
			obj.keySet().stream().forEach(k -> {
				value.put(k, LogicalTimestampCompactSerializable.deserialize(obj.getJSONArray(k)));
			});
		}
		return this;
	}

	/**
	 * Get the JSON representation of the value.
	 * 
	 * @return
	 */
	public String getValueAsJson() {
		if (value == null) {
			return "{}";
		}
		JSONObject ob = new JSONObject();
		if (value != null) {
			value.forEach((k, v) -> {
				ob.put(k, LogicalTimestampCompactSerializable.serialize(v));
			});
		}
		return ob.toString();
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
		ObjectNode other = (ObjectNode) obj;
		return Objects.equals(id, other.id) && Objects.equals(value, other.value);
	}

	@Override
	public String toString() {
		return "ObjectNode [id=" + id + ", map=" + value + "]";
	}

}
