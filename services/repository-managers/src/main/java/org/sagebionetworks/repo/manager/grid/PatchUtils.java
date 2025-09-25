package org.sagebionetworks.repo.manager.grid;

import org.json.JSONArray;
import org.json.JSONObject;
import org.sagebionetworks.repo.model.grid.patch.ConType;

public class PatchUtils {

	/**
	 * This is a limit set by AWS <a href=
	 * "https://docs.aws.amazon.com/apigateway/latest/developerguide/apigateway-execution-service-websocket-limits-table.html">apigateway-execution-service-websocket-limits-table</a>
	 */
	public static long MAX_BYTES_PER_PATCH = 128_000L;

	/**
	 * Given an expected maximum row size, calculate the number of rows that can be
	 * added to a patch while remaining under the AWS websocket limits.
	 * 
	 * @param maxRowSizeBytes
	 * @return
	 */
	public static int calculateRowsPerPatch(Long maxRowSizeBytes) {
		// Note: We estimate a 10% overhead to serialize a row as a patch.
		return maxRowSizeBytes >= MAX_BYTES_PER_PATCH ? 1
				: Math.max(1, Long.valueOf(MAX_BYTES_PER_PATCH / plusTenPerent(maxRowSizeBytes)).intValue());
	}

	/**
	 * Add 10% to the provided value.
	 * 
	 * @param value
	 * @return
	 */
	public static Long plusTenPerent(Long value) {
		return value + Double.valueOf(value * 0.1).longValue();
	}
	
	public static ConType getConType(Object value) {
		if (value == null) {
			return ConType.NULL;
		}
		if (value instanceof Boolean) {
			return ConType.BOOLEAN;
		}
		if (value instanceof Long || value instanceof Integer) {
			return ConType.LONG;
		}
		if (value instanceof Double || value instanceof Float) {
			return ConType.DOUBLE;
		}
		if (value instanceof String) {
			return ConType.STRING;
		}
		if (value instanceof JSONArray) {
			return ConType.JSON_ARRAY;
		}
		if (value instanceof JSONObject) {
			return ConType.JSON_OBJECT;
		}
		return ConType.UNDEFINED;
	}

}
