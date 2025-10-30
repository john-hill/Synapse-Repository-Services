package org.sagebionetworks.repo.model.grid.patch;

import java.util.Objects;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

public class ConValue {
	
	private final ConType type;
	/** an optional JSON/CBOR value, which is the value of the con node when it is not undefined and is not a timestamp.
	 * Implementation note: when the value is `undefined`, this will be a Java `null`. When the value is a JSON `null`, this will be a JSONObject.NULL.
	 */
	private final Object value;

	public ConValue(ConType type, Object value) {
		super();
		this.type = type;
		if (ConType.NULL.equals(type)) {
			this.value = JSONObject.NULL;
		} else if (ConType.UNDEFINED.equals(type)) {
			this.value = null;
		} else if (ConType.LONG.equals(type) && value instanceof Integer) {
			this.value = Long.valueOf((Integer) value);
		} else {
			this.value = value;
		}
	}

	public ConType getType() {
		return type;
	}

	public Object getValue() {
		return value;
	}

	/**
	 * If true, this value is an undefined literal value.
	 */
	public boolean isUndefined() {
		return ConType.UNDEFINED.equals(type);
	}


	@Override
	public int hashCode() {
		return Objects.hash(type, value);
	}

	private static boolean valueEquals(Object value, Object otherValue) {
		// For JSONObject and JSONArray, `equals` does not work; use `similar` to do a deep comparison.
		if (value instanceof JSONObject && otherValue instanceof JSONObject) {
			return ((JSONObject) value).similar(otherValue);
		}
		if (value instanceof JSONArray && otherValue instanceof JSONArray) {
			return ((JSONArray) value).similar(otherValue);
		}

		return Objects.equals(value, otherValue);
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (getClass() != obj.getClass())
			return false;
		ConValue other = (ConValue) obj;
		Object otherValue = ((ConValue) obj).value;
		return type == other.type && valueEquals(value, otherValue);
	}

	@Override
	public String toString() {
		return "ConValue [type=" + type + ", value=" + value + "]";
	}

	public String toJson() {
		if (ConType.UNDEFINED.equals(type)) {
			return null;
		}
		if (ConType.NULL.equals(type)) {
			return "null";
		}
		if (value instanceof String) {
			ObjectMapper mapper = new ObjectMapper();
			try {
				return mapper.writeValueAsString(value);
			} catch (JsonProcessingException e) {
				throw new RuntimeException("Failed to convert constant value to JSON", e);
			}
		}
		return value.toString();
	}

	public static ConValue fromJsonString(String json) {
		Object value;
		if (json == null) {
			value = null;
		} else {
			value = (new JSONTokener(json).nextValue());
		}
		ConType type = ConType.fromValue(value);

		return new ConValue(type, value);
	}

	/**
	 * Returns the 'partial' compact serialization of this ConValue (the last 1-2 parts).
	 *
	 * This omits the non-value-related information that is stored in a Constant node (i.e. prefix indicating that the node is a constant, and ID of the node).
	 * @return
	 */
	public JSONArray toCompact() {
		JSONArray compact = new JSONArray();

		// UNDEFINED and TIMESTAMP always have first element 0
		if (ConType.UNDEFINED.equals(type) || ConType.TIMESTAMP.equals(type)) {
			compact.put(0);
		}

		// Append the value
		Object value = this.value;
		if (ConType.UNDEFINED.equals(type)) {
			// Value for UNDEFINED is always 0
			value = 0;
		} else if (ConType.TIMESTAMP.equals(type)) {
			// Serialize the TIMESTAMP to a JSONArray
			LogicalTimestamp ts = (LogicalTimestamp) this.value;
			JSONArray timestamp =  new JSONArray();
			timestamp.put(ts.getReplicaId());
			timestamp.put(ts.getSequenceNumber());
			value = timestamp;
		} else if (ConType.NULL.equals(type) || this.value == null) {
			value = JSONObject.NULL;
		}
		compact.put(value);
		return compact;
	}

	public static ConValue fromCompact(JSONArray json) {
		if (json.length() == 2) {
			// It is either timestamp or undefined
			if (json.optInt(0) == 0 && json.optJSONArray(1) != null) {
				JSONArray timestampVal = json.getJSONArray(1);
				LogicalTimestamp ts = new LogicalTimestamp().setReplicaId(timestampVal.getLong(0)).setSequenceNumber(timestampVal.getLong(1));
				return new ConValue(ConType.TIMESTAMP, ts);
			}  else if (json.optInt(0) == 0 && json.optInt(1) == 0) {
				return new ConValue(ConType.UNDEFINED, null);

			} else{
				throw new IllegalArgumentException("Invalid compact ConValue: " + json);
			}
		} else if (json.length() == 1) {
			// could be null or other value
			return new ConValue(ConType.fromValue(json.get(0)), json.get(0));
		}
		throw new IllegalArgumentException("Invalid compact ConValue: " + json);
	}

}
