package org.sagebionetworks.repo.model.dbo.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.sagebionetworks.schema.adapter.JSONObjectAdapter;
import org.sagebionetworks.schema.adapter.org.json.JSONObjectAdapterImpl;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Unit tests for {@link OpaqueJsonColumnCodecUtil}, covering each branch of
 * {@link OpaqueJsonColumnCodecUtil#deserialize(String, String)} and
 * {@link OpaqueJsonColumnCodecUtil#serialize(Object, String)}, plus the
 * {@link OpaqueJsonColumnCodecUtil#toJsonNode(Object)} dispatch underneath.
 */
public class OpaqueJsonColumnCodecUtilTest {

	private static final String FIELD = "TestField.value";

	// ---------- deserialize ----------

	@Test
	public void testDeserializeWithNull() {
		assertNull(OpaqueJsonColumnCodecUtil.deserialize(null, FIELD));
	}

	@Test
	public void testDeserializeWithJsonObject() {
		Object out = OpaqueJsonColumnCodecUtil.deserialize("{\"a\":1,\"b\":\"two\"}", FIELD);

		assertTrue(out instanceof JSONObject, "expected JSONObject, got " + out.getClass());
		JSONObject obj = (JSONObject) out;
		assertEquals(1, obj.getInt("a"));
		assertEquals("two", obj.getString("b"));
	}

	@Test
	public void testDeserializeWithJsonArray() {
		Object out = OpaqueJsonColumnCodecUtil.deserialize("[1,2,3]", FIELD);

		assertTrue(out instanceof JSONArray, "expected JSONArray, got " + out.getClass());
		JSONArray arr = (JSONArray) out;
		assertEquals(3, arr.length());
		assertEquals(2, arr.getInt(1));
	}

	@Test
	public void testDeserializeWithStringScalar() {
		// org.json represents bare-string JSON values as java.lang.String
		assertEquals("hello", OpaqueJsonColumnCodecUtil.deserialize("\"hello\"", FIELD));
	}

	@Test
	public void testDeserializeWithBooleanScalar() {
		assertEquals(Boolean.TRUE, OpaqueJsonColumnCodecUtil.deserialize("true", FIELD));
	}

	@Test
	public void testDeserializeWithNumberScalar() {
		assertEquals(Integer.valueOf(42), OpaqueJsonColumnCodecUtil.deserialize("42", FIELD));
	}

	@Test
	public void testDeserializeWithMalformedJsonThrows() {
		IllegalStateException e = assertThrows(IllegalStateException.class,
				() -> OpaqueJsonColumnCodecUtil.deserialize("{not_valid", FIELD));
		assertTrue(e.getMessage().contains(FIELD), "message should name the field: " + e.getMessage());
	}

	// ---------- deserializeList ----------

	@Test
	public void testDeserializeListWithNull() {
		assertNull(OpaqueJsonColumnCodecUtil.deserializeList(null, FIELD));
	}

	@Test
	public void testDeserializeListWithEmptyArray() {
		assertEquals(java.util.Collections.emptyList(),
				OpaqueJsonColumnCodecUtil.deserializeList("[]", FIELD));
	}

	@Test
	public void testDeserializeListWithObjectElements() {
		// Each element of an opaque-Object array must independently be a JSONObject /
		// JSONArray / scalar — not LinkedHashMap — so the schema-to-pojo array writer's
		// putObject can serialize it.
		java.util.List<Object> out = OpaqueJsonColumnCodecUtil.deserializeList(
				"[{\"$ref\":\"org1-name1\"},{\"$ref\":\"org2-name2\"}]", FIELD);

		assertEquals(2, out.size());
		assertTrue(out.get(0) instanceof JSONObject, "expected JSONObject element");
		assertEquals("org1-name1", ((JSONObject) out.get(0)).getString("$ref"));
		assertEquals("org2-name2", ((JSONObject) out.get(1)).getString("$ref"));
	}

	@Test
	public void testDeserializeListWithScalarElements() {
		// Bare-string elements (non-ref qualified names) are valid per the schema.
		java.util.List<Object> out = OpaqueJsonColumnCodecUtil.deserializeList(
				"[\"org1-name1\",\"org2-name2\"]", FIELD);

		assertEquals(Arrays.asList("org1-name1", "org2-name2"), out);
	}

	@Test
	public void testDeserializeListWithNonArrayThrows() {
		IllegalStateException e = assertThrows(IllegalStateException.class,
				() -> OpaqueJsonColumnCodecUtil.deserializeList("{\"a\":1}", FIELD));
		assertTrue(e.getMessage().contains(FIELD),
				"message should name the field: " + e.getMessage());
		assertTrue(e.getMessage().contains("Expected a JSON array"),
				"message should explain the shape mismatch: " + e.getMessage());
	}

	@Test
	public void testDeserializeListWithMalformedJsonThrows() {
		IllegalStateException e = assertThrows(IllegalStateException.class,
				() -> OpaqueJsonColumnCodecUtil.deserializeList("[1,", FIELD));
		assertTrue(e.getMessage().contains(FIELD),
				"message should name the field: " + e.getMessage());
	}

	// ---------- serialize ----------

	@Test
	public void testSerializeWithNull() {
		assertNull(OpaqueJsonColumnCodecUtil.serialize(null, FIELD));
	}

	@Test
	public void testSerializeWithStringEncodesAsJsonScalar() {
		// String inputs are JSON scalars — never raw-JSON passthrough. To store a JSON
		// object the caller must pass a Map / JSONObject; passing a String containing
		// JSON would store it as a (silly) escaped scalar string.
		String out = OpaqueJsonColumnCodecUtil.serialize("biomed-publications", FIELD);

		assertEquals("\"biomed-publications\"", out);
	}

	@Test
	public void testSerializeWithJsonObject() {
		JSONObject in = new JSONObject().put("a", 1);

		String out = OpaqueJsonColumnCodecUtil.serialize(in, FIELD);

		assertEquals(1, new JSONObject(out).getInt("a"));
	}

	@Test
	public void testSerializeWithJsonArray() {
		JSONArray in = new JSONArray().put(1).put(2);

		String out = OpaqueJsonColumnCodecUtil.serialize(in, FIELD);

		JSONArray round = new JSONArray(out);
		assertEquals(2, round.length());
		assertEquals(2, round.getInt(1));
	}

	@Test
	public void testSerializeWithJsonObjectAdapter() throws Exception {
		// JSONObjectAdapter is the shape produced by the schema-to-pojo wire deserializer
		// when a request body arrives at a controller.
		JSONObjectAdapter adapter = new JSONObjectAdapterImpl("{\"a\":1}");

		String out = OpaqueJsonColumnCodecUtil.serialize(adapter, FIELD);

		assertEquals(1, new JSONObject(out).getInt("a"));
	}

	@Test
	public void testSerializeWithMap() {
		Map<String, Object> in = new LinkedHashMap<>();
		in.put("a", 1);
		in.put("b", "two");

		String out = OpaqueJsonColumnCodecUtil.serialize(in, FIELD);

		JSONObject round = new JSONObject(out);
		assertEquals(1, round.getInt("a"));
		assertEquals("two", round.getString("b"));
	}

	@Test
	public void testSerializeWithCollection() {
		String out = OpaqueJsonColumnCodecUtil.serialize(Arrays.asList(1, 2, 3), FIELD);

		JSONArray round = new JSONArray(out);
		assertEquals(3, round.length());
		assertEquals(3, round.getInt(2));
	}

	@Test
	public void testSerializeWithScalarNumber() {
		// Non-String scalars route through valueToTree and emit a JSON scalar.
		assertEquals("42", OpaqueJsonColumnCodecUtil.serialize(42, FIELD));
	}

	@Test
	public void testSerializeWithScalarBoolean() {
		assertEquals("true", OpaqueJsonColumnCodecUtil.serialize(Boolean.TRUE, FIELD));
	}

	// ---------- toJsonNode dispatch branches ----------

	@Test
	public void testToJsonNodeWithStringEncodesAsScalar() throws Exception {
		// String inputs are JSON scalars; never treated as raw JSON.
		JsonNode node = OpaqueJsonColumnCodecUtil.toJsonNode("biomed-publications");

		assertTrue(node.isTextual());
		assertEquals("biomed-publications", node.asText());
	}

	@Test
	public void testToJsonNodeWithJsonObjectRoutesThroughReadTree() throws Exception {
		JsonNode node = OpaqueJsonColumnCodecUtil.toJsonNode(new JSONObject().put("a", 1));

		assertEquals(1, node.get("a").asInt());
	}

	@Test
	public void testToJsonNodeWithJsonArrayRoutesThroughReadTree() throws Exception {
		JsonNode node = OpaqueJsonColumnCodecUtil.toJsonNode(new JSONArray().put(1).put(2));

		assertTrue(node.isArray());
		assertEquals(2, node.size());
	}

	@Test
	public void testToJsonNodeWithJsonObjectAdapterRoutesThroughReadTree() throws Exception {
		JSONObjectAdapter adapter = new JSONObjectAdapterImpl("{\"a\":1}");

		JsonNode node = OpaqueJsonColumnCodecUtil.toJsonNode(adapter);

		assertEquals(1, node.get("a").asInt());
	}

	@Test
	public void testToJsonNodeWithMapRoutesThroughValueToTree() throws Exception {
		Map<String, Object> in = new LinkedHashMap<>();
		in.put("a", 1);

		JsonNode node = OpaqueJsonColumnCodecUtil.toJsonNode(in);

		assertEquals(1, node.get("a").asInt());
	}
}
