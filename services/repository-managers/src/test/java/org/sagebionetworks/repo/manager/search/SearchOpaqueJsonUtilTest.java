package org.sagebionetworks.repo.manager.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.opensearch.client.opensearch._types.analysis.TokenFilter;
import org.opensearch.client.opensearch._types.analysis.TokenFilterDefinition;
import org.opensearch.client.opensearch.indices.IndexSettingsAnalysis;
import org.sagebionetworks.repo.model.search.table.TextAnalyzer;
import org.sagebionetworks.schema.adapter.JSONObjectAdapter;
import org.sagebionetworks.schema.adapter.org.json.JSONObjectAdapterImpl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

/**
 * Unit tests for {@link SearchOpaqueJsonUtil}, organized by the concerns the util
 * exposes: shape conversion ({@link SearchOpaqueJsonUtil#parse},
 * {@link SearchOpaqueJsonUtil#toJsonString}, {@link SearchOpaqueJsonUtil#fromJsonString},
 * package-private {@link SearchOpaqueJsonUtil#asJsonString}); reference detection
 * ({@link SearchOpaqueJsonUtil#readRef(Object)},
 * {@link SearchOpaqueJsonUtil#readRef(JsonNode)},
 * {@link SearchOpaqueJsonUtil#collectRefs}); inline materialization
 * ({@link SearchOpaqueJsonUtil#toInline}); {@code $ref} splicing
 * ({@link SearchOpaqueJsonUtil#spliceRefsInFilterMap}); and the analyzer-typed
 * splice + deserialize surface
 * ({@link SearchOpaqueJsonUtil#resolveAnalyzerSettings}).
 */
public class SearchOpaqueJsonUtilTest {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	// ===================== shape conversion =====================

	@Test
	public void testParseWithStringJson() {
		JsonNode root = SearchOpaqueJsonUtil.parse(
				"{\"analyzer\":{\"default\":{\"type\":\"custom\",\"tokenizer\":\"standard\"}}}");

		assertNotNull(root);
		assertEquals("standard", root.at("/analyzer/default/tokenizer").asText());
	}

	@Test
	public void testParseWithMapInput() {
		// settings is schema-typed as opaque object; the POJO surfaces it as a Map for
		// programmatic callers. parse(Object) handles that shape exactly like a String.
		Map<String, Object> settings = Map.of(
				"analyzer", Map.of("default", Map.of("type", "custom", "tokenizer", "standard")));

		JsonNode node = SearchOpaqueJsonUtil.parse(settings);

		assertEquals("standard", node.at("/analyzer/default/tokenizer").asText());
	}

	@Test
	public void testParseWithJsonObjectInput() {
		// Post-DAO shape: an org.json.JSONObject surfaces from the opaque-Object column codec.
		JSONObject settings = new JSONObject().put("k", "v");

		JsonNode node = SearchOpaqueJsonUtil.parse(settings);

		assertEquals("v", node.at("/k").asText());
	}

	@Test
	public void testParseWithMalformedJsonThrows() {
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
				() -> SearchOpaqueJsonUtil.parse("{not valid"));

		assertTrue(e.getMessage().startsWith("Invalid JSON"),
				"error must surface a user-facing message: " + e.getMessage());
	}

	@Test
	public void testParseWithNullThrows() {
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
				() -> SearchOpaqueJsonUtil.parse(null));

		assertTrue(e.getMessage().contains("required"));
	}

	// --- toJsonString ---

	@Test
	public void testToJsonStringWithNullPassesThrough() {
		assertNull(SearchOpaqueJsonUtil.toJsonString(null));
	}

	@Test
	public void testToJsonStringWithStringIsIdempotent() {
		// Strings pass through verbatim; no parse + reserialize round-trip.
		assertEquals("{\"x\":1}", SearchOpaqueJsonUtil.toJsonString("{\"x\":1}"));
	}

	@Test
	public void testToJsonStringWithMap() {
		assertEquals("{\"k\":\"v\"}", SearchOpaqueJsonUtil.toJsonString(Map.of("k", "v")));
	}

	// --- fromJsonString ---

	@Test
	public void testFromJsonStringWithNullPassesThrough() {
		assertNull(SearchOpaqueJsonUtil.fromJsonString(null));
	}

	@Test
	public void testFromJsonStringRoundTripsObjectAsMap() {
		Object result = SearchOpaqueJsonUtil.fromJsonString("{\"k\":\"v\"}");

		assertTrue(result instanceof Map, "JSON object must land in a Map");
		assertEquals("v", ((Map<?, ?>) result).get("k"));
	}

	@Test
	public void testFromJsonStringRoundTripsArrayAsList() {
		Object result = SearchOpaqueJsonUtil.fromJsonString("[1,2,3]");

		assertTrue(result instanceof List, "JSON array must land in a List");
		assertEquals(3, ((List<?>) result).size());
	}

	@Test
	public void testFromJsonStringRejectsMalformedJson() {
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
				() -> SearchOpaqueJsonUtil.fromJsonString("{not_valid"));

		assertTrue(e.getMessage().contains("Invalid JSON"));
	}

	// --- asJsonString (package-private dispatch) ---

	@Test
	public void testAsJsonStringWithStringPassesThrough() {
		// String inputs are JSON literals; the helper must not double-encode.
		String s = "{\"a\":1}";

		assertSame(s, SearchOpaqueJsonUtil.asJsonString(s));
	}

	@Test
	public void testAsJsonStringWithJsonObject() {
		String out = SearchOpaqueJsonUtil.asJsonString(new JSONObject().put("a", 1));

		assertEquals(1, new JSONObject(out).getInt("a"));
	}

	@Test
	public void testAsJsonStringWithJsonArray() {
		String out = SearchOpaqueJsonUtil.asJsonString(new JSONArray().put(1).put(2));

		assertEquals(2, new JSONArray(out).length());
	}

	@Test
	public void testAsJsonStringWithJsonObjectAdapter() throws Exception {
		// Post-controller shape: schema-to-pojo wraps inbound objects in JSONObjectAdapter.
		JSONObjectAdapter adapter = new JSONObjectAdapterImpl("{\"a\":1}");

		String out = SearchOpaqueJsonUtil.asJsonString(adapter);

		assertEquals(1, new JSONObject(out).getInt("a"));
	}

	@Test
	public void testAsJsonStringWithMap() {
		Map<String, Object> in = new LinkedHashMap<>();
		in.put("a", 1);

		String out = SearchOpaqueJsonUtil.asJsonString(in);

		assertEquals(1, new JSONObject(out).getInt("a"));
	}

	@Test
	public void testAsJsonStringWithCollection() {
		String out = SearchOpaqueJsonUtil.asJsonString(Arrays.asList(1, 2, 3));

		assertEquals(3, new JSONArray(out).length());
	}

	@Test
	public void testAsJsonStringWithScalar() {
		// Scalars route through Jackson's writeValueAsString and emit a JSON scalar literal.
		assertEquals("42", SearchOpaqueJsonUtil.asJsonString(42));
		assertEquals("true", SearchOpaqueJsonUtil.asJsonString(Boolean.TRUE));
	}

	@Test
	public void testAsJsonStringWithNonSerializableThrows() {
		// Self-referential structure isn't serializable as JSON; surfaced as
		// IllegalArgumentException with a "Invalid JSON" prefix so the boundary
		// failure is identifiable.
		Object[] selfRef = new Object[1];
		selfRef[0] = selfRef;

		IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
				() -> SearchOpaqueJsonUtil.asJsonString(selfRef));

		assertTrue(e.getMessage().contains("Invalid JSON"),
				"surfaced error must carry the Invalid JSON marker: " + e.getMessage());
	}

	// ===================== reference detection =====================

	// --- readRef(Object) ---

	@Test
	public void testReadRefObjectWithMapRef() {
		assertEquals("biomed-FOO",
				SearchOpaqueJsonUtil.readRef(Map.of("$ref", "biomed-FOO")));
	}

	@Test
	public void testReadRefObjectWithJsonObjectRef() {
		// JSONObject is the post-DAO shape of an opaque-Object {"$ref": "..."} value.
		assertEquals("biomed-FOO",
				SearchOpaqueJsonUtil.readRef(new JSONObject().put("$ref", "biomed-FOO")));
	}

	@Test
	public void testReadRefObjectRejectsMultiKeyMap() {
		// Multi-key map — $ref alongside other fields — is inline, not a ref.
		assertNull(SearchOpaqueJsonUtil.readRef(
				Map.of("$ref", "biomed-FOO", "extra", "stuff")));
	}

	@Test
	public void testReadRefObjectRejectsMultiKeyJsonObject() {
		JSONObject inline = new JSONObject().put("$ref", "biomed-FOO").put("extra", "stuff");
		assertNull(SearchOpaqueJsonUtil.readRef(inline));
	}

	@Test
	public void testReadRefObjectRejectsEmptyMap() {
		assertNull(SearchOpaqueJsonUtil.readRef(Collections.emptyMap()));
	}

	@Test
	public void testReadRefObjectRejectsEmptyJsonObject() {
		assertNull(SearchOpaqueJsonUtil.readRef(new JSONObject()));
	}

	@Test
	public void testReadRefObjectRejectsRefWithNonStringValueInMap() {
		// $ref values must be strings; a numeric value isn't a qname.
		assertNull(SearchOpaqueJsonUtil.readRef(Map.of("$ref", 7)));
	}

	@Test
	public void testReadRefObjectRejectsRefWithNonStringValueInJsonObject() {
		assertNull(SearchOpaqueJsonUtil.readRef(new JSONObject().put("$ref", 7)));
	}

	@Test
	public void testReadRefObjectRejectsString() {
		// A bare String scalar (e.g. a plain qname) is not a ref object — it's a scalar.
		assertNull(SearchOpaqueJsonUtil.readRef("biomed-FOO"));
	}

	@Test
	public void testReadRefObjectRejectsScalarTypes() {
		assertNull(SearchOpaqueJsonUtil.readRef(42));
		assertNull(SearchOpaqueJsonUtil.readRef(Boolean.TRUE));
	}

	@Test
	public void testReadRefObjectRejectsNull() {
		assertNull(SearchOpaqueJsonUtil.readRef((Object) null));
	}

	@Test
	public void testReadRefObjectRejectsMapWithSizeOneButWrongKey() {
		// One-key Map whose key isn't $ref is not a ref.
		assertNull(SearchOpaqueJsonUtil.readRef(Map.of("type", "stop")));
	}

	// --- readRef(JsonNode) ---

	@Test
	public void testReadRefJsonNodeWithRef() throws Exception {
		JsonNode node = MAPPER.readTree("{\"$ref\":\"biomed-FOO\"}");

		assertEquals("biomed-FOO", SearchOpaqueJsonUtil.readRef(node));
	}

	@Test
	public void testReadRefJsonNodeWithNullReturnsNull() {
		assertNull(SearchOpaqueJsonUtil.readRef((JsonNode) null));
	}

	@Test
	public void testReadRefJsonNodeWithNonObjectReturnsNull() {
		// A non-object JsonNode (a string, a number, an array) is not a ref.
		assertNull(SearchOpaqueJsonUtil.readRef(JsonNodeFactory.instance.textNode("biomed-FOO")));
		assertNull(SearchOpaqueJsonUtil.readRef(JsonNodeFactory.instance.numberNode(42)));
		assertNull(SearchOpaqueJsonUtil.readRef(JsonNodeFactory.instance.arrayNode()));
	}

	@Test
	public void testReadRefJsonNodeWithMultiKeyObjectReturnsNull() throws Exception {
		JsonNode node = MAPPER.readTree("{\"$ref\":\"x\",\"type\":\"stop\"}");

		assertNull(SearchOpaqueJsonUtil.readRef(node));
	}

	@Test
	public void testReadRefJsonNodeWithEmptyObjectReturnsNull() throws Exception {
		assertNull(SearchOpaqueJsonUtil.readRef(MAPPER.readTree("{}")));
	}

	@Test
	public void testReadRefJsonNodeWithoutRefKeyReturnsNull() throws Exception {
		// One-key object whose key isn't $ref is not a ref.
		JsonNode node = MAPPER.readTree("{\"type\":\"stop\"}");

		assertNull(SearchOpaqueJsonUtil.readRef(node));
	}

	@Test
	public void testReadRefJsonNodeWithNonTextualRefValueReturnsNull() throws Exception {
		JsonNode node = MAPPER.readTree("{\"$ref\":42}");

		assertNull(SearchOpaqueJsonUtil.readRef(node));
	}

	// --- collectRefs ---

	@Test
	public void testCollectRefsReturnsRefsInWalkOrderDeduplicated() {
		String json = "{"
				+ "\"filter\":{"
				+ "\"a_syn\":{\"$ref\":\"org-A\"},"
				+ "\"b_syn\":{\"$ref\":\"org-B\"},"
				+ "\"c_syn\":{\"$ref\":\"org-A\"}"
				+ "}}";

		Set<String> refs = SearchOpaqueJsonUtil.collectRefs(SearchOpaqueJsonUtil.parse(json));

		assertEquals(new LinkedHashSet<>(Arrays.asList("org-A", "org-B")), refs);
	}

	@Test
	public void testCollectRefsReturnsEmptyWhenNoRefs() {
		Set<String> refs = SearchOpaqueJsonUtil.collectRefs(SearchOpaqueJsonUtil.parse(
				"{\"analyzer\":{\"default\":{\"type\":\"custom\",\"tokenizer\":\"standard\"}}}"));

		assertTrue(refs.isEmpty());
	}

	@Test
	public void testCollectRefsIgnoresStringValuesInsideArrays() {
		// Strings inside chain arrays look textually similar to qnames but must NOT be
		// treated as refs — only object nodes with a "$ref" field are refs.
		String json = "{\"analyzer\":{\"default\":{\"type\":\"custom\",\"tokenizer\":\"standard\","
				+ "\"filter\":[\"lowercase\",\"org-pretend\"]}}}";

		Set<String> refs = SearchOpaqueJsonUtil.collectRefs(SearchOpaqueJsonUtil.parse(json));

		assertTrue(refs.isEmpty());
	}

	@Test
	public void testCollectRefsWithNullRootReturnsEmpty() {
		// Defensive null guard so callers that already failed to parse don't NPE.
		assertTrue(SearchOpaqueJsonUtil.collectRefs(null).isEmpty());
	}

	@Test
	public void testCollectRefsIgnoresNonTextualRefValue() throws Exception {
		// A {"$ref": <number>} entry is malformed; collectRefs must not coerce a number
		// to a bogus qname.
		JsonNode root = MAPPER.readTree("{\"filter\":{\"bogus\":{\"$ref\":42}}}");

		assertTrue(SearchOpaqueJsonUtil.collectRefs(root).isEmpty());
	}

	// ===================== inline materialization =====================

	@Test
	public void testToInlineWithNullReturnsNull() {
		assertNull(SearchOpaqueJsonUtil.toInline(null, TextAnalyzer.class));
	}

	@Test
	public void testToInlineConvertsMapToTypedPojo() {
		TextAnalyzer ta = SearchOpaqueJsonUtil.toInline(
				Map.of("organizationName", "biomed", "name", "publications",
						"settings", Map.of("k", "v")),
				TextAnalyzer.class);

		assertEquals("biomed", ta.getOrganizationName());
		assertEquals("publications", ta.getName());
		assertTrue(ta.getSettings() instanceof Map);
	}

	@Test
	public void testToInlineConvertsJsonObjectToTypedPojo() {
		TextAnalyzer ta = SearchOpaqueJsonUtil.toInline(
				new JSONObject().put("organizationName", "biomed").put("name", "publications"),
				TextAnalyzer.class);

		assertEquals("biomed", ta.getOrganizationName());
		assertEquals("publications", ta.getName());
	}

	@Test
	public void testToInlineConvertsJsonArrayToTypedList() {
		// JSONArray inputs route through readValue(toString) for the same canonical form.
		List<?> list = SearchOpaqueJsonUtil.toInline(
				new JSONArray().put("a").put("b"), List.class);

		assertEquals(Arrays.asList("a", "b"), list);
	}

	@Test
	public void testToInlineConvertsListToTypedList() {
		List<?> list = SearchOpaqueJsonUtil.toInline(Arrays.asList("a", "b"), List.class);

		assertEquals(Arrays.asList("a", "b"), list);
	}

	@Test
	public void testToInlineRejectsIncompatibleMapShape() {
		// A map whose fields cannot deserialize as the target POJO surfaces a user-facing
		// IllegalArgumentException at create / update time rather than later corruption.
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				() -> SearchOpaqueJsonUtil.toInline(Map.of("createdBy", Map.of("not", "a-string")),
						TextAnalyzer.class));

		assertTrue(ex.getMessage().contains("Invalid inline TextAnalyzer"), ex.getMessage());
	}

	@Test
	public void testToInlineRejectsMalformedJsonObject() throws Exception {
		// JSONObject input that doesn't deserialize as the target POJO must surface as
		// IllegalArgumentException — same contract as Map inputs.
		JSONObject incompatible = new JSONObject().put("createdBy",
				new JSONObject().put("not", "a-string"));

		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				() -> SearchOpaqueJsonUtil.toInline(incompatible, TextAnalyzer.class));

		assertTrue(ex.getMessage().contains("Invalid inline TextAnalyzer"), ex.getMessage());
	}

	// ===================== $ref splicing =====================

	@Test
	public void testSpliceRefsInFilterMapWithNullRootIsNoOp() {
		// Null in is a no-op; the analyzer codec relies on this null-safety so a
		// pre-parse failure doesn't NPE the splice phase.
		SearchOpaqueJsonUtil.spliceRefsInFilterMap(null, "filter",
				q -> { throw new AssertionError("resolver must not be called"); });
	}

	@Test
	public void testSpliceRefsInFilterMapWithMissingFilterMapIsNoOp() {
		// A settings tree without a `filter` map at all: splice phase exits cleanly
		// without invoking the resolver and without mutating the tree.
		JsonNode root = SearchOpaqueJsonUtil.parse(
				"{\"analyzer\":{\"default\":{\"type\":\"custom\"}}}");

		SearchOpaqueJsonUtil.spliceRefsInFilterMap(root, "filter",
				q -> { throw new AssertionError("resolver must not be called"); });

		assertNull(root.get("filter"));
	}

	@Test
	public void testSpliceRefsInFilterMapWithNonObjectFilterValueIsNoOp() throws Exception {
		// `filter` present but not an object — splice exits cleanly. The eventual typed
		// deserializer is what reports the shape error.
		JsonNode root = MAPPER.readTree("{\"filter\":\"not_an_object\"}");

		SearchOpaqueJsonUtil.spliceRefsInFilterMap(root, "filter",
				q -> { throw new AssertionError("resolver must not be called"); });

		assertEquals("\"not_an_object\"", root.get("filter").toString());
	}

	@Test
	public void testSpliceRefsInFilterMapReplacesRefEntries() throws Exception {
		JsonNode root = SearchOpaqueJsonUtil.parse(
				"{\"filter\":{\"med\":{\"$ref\":\"biomed-medical\"}}}");
		JsonNode replacement = MAPPER.readTree("{\"type\":\"synonym_graph\"}");

		SearchOpaqueJsonUtil.spliceRefsInFilterMap(root, "filter", q -> replacement);

		assertEquals(replacement, root.get("filter").get("med"));
	}

	@Test
	public void testSpliceRefsInFilterMapLeavesInlineEntriesUntouched() throws Exception {
		// Inline (non-ref) entries pass through unchanged; resolver must not be called.
		String inlineJson = "{\"type\":\"stop\",\"stopwords\":\"_english_\"}";
		JsonNode root = SearchOpaqueJsonUtil.parse("{\"filter\":{\"stop\":" + inlineJson + "}}");

		SearchOpaqueJsonUtil.spliceRefsInFilterMap(root, "filter",
				q -> { throw new AssertionError("resolver must not be called"); });

		assertEquals(MAPPER.readTree(inlineJson), root.get("filter").get("stop"));
	}

	@Test
	public void testSpliceRefsInFilterMapMissingTargetThrowsWithPointer() {
		JsonNode root = SearchOpaqueJsonUtil.parse(
				"{\"filter\":{\"ghost\":{\"$ref\":\"org-Ghost\"}}}");

		IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
				() -> SearchOpaqueJsonUtil.spliceRefsInFilterMap(root, "filter", q -> null));

		assertTrue(e.getMessage().contains("Unresolved $ref"));
		assertTrue(e.getMessage().contains("org-Ghost"));
		assertTrue(e.getMessage().contains("/filter/ghost"),
				"error must include the JSON pointer to the offending entry: " + e.getMessage());
	}

	@Test
	public void testSpliceRefsInFilterMapHonorsCustomFilterKey() throws Exception {
		// filterKey is a parameter — caller can name a different top-level slot. Verifies
		// the helper isn't hardwired to "filter" so future callers aren't blocked.
		JsonNode root = SearchOpaqueJsonUtil.parse(
				"{\"customSlot\":{\"med\":{\"$ref\":\"biomed-medical\"}}}");
		JsonNode replacement = MAPPER.readTree("{\"type\":\"synonym_graph\"}");

		SearchOpaqueJsonUtil.spliceRefsInFilterMap(root, "customSlot", q -> replacement);

		assertEquals(replacement, root.get("customSlot").get("med"));
	}

	// ===================== analyzer-typed splice + deserialize =====================

	@Test
	public void testResolveAnalyzerSettingsWithNullRootReturnsNull() {
		// Null in, null out — paired with spliceRefsInFilterMap's null-root short-circuit
		// so callers that already failed to parse don't NPE here.
		assertNull(SearchOpaqueJsonUtil.resolveAnalyzerSettings(null, q -> null));
	}

	@Test
	public void testResolveAnalyzerSettingsWithNoFilterMapDeserializes() {
		// A settings tree with no `filter` map at all must still deserialize — the splice
		// phase short-circuits and the typed deserializer handles the rest.
		JsonNode root = SearchOpaqueJsonUtil.parse(
				"{\"analyzer\":{\"default\":{\"type\":\"custom\",\"tokenizer\":\"standard\"}}}");

		IndexSettingsAnalysis resolved = SearchOpaqueJsonUtil.resolveAnalyzerSettings(
				root, q -> { throw new AssertionError("resolver must not be called"); });

		assertNotNull(resolved.analyzer().get("default"));
		assertTrue(resolved.analyzer().get("default").isCustom());
	}

	@Test
	public void testResolveAnalyzerSettingsWithEmptyFilterMapDeserializes() {
		// Empty filter map: splice phase iterates zero entries and the deserializer
		// produces an empty typed filter map.
		JsonNode root = SearchOpaqueJsonUtil.parse(
				"{\"filter\":{},\"analyzer\":{\"default\":{\"type\":\"custom\",\"tokenizer\":\"standard\"}}}");

		IndexSettingsAnalysis resolved = SearchOpaqueJsonUtil.resolveAnalyzerSettings(
				root, q -> { throw new AssertionError("resolver must not be called"); });

		assertTrue(resolved.filter().isEmpty());
	}

	@Test
	public void testResolveAnalyzerSettingsSplicesSingleRef() throws Exception {
		// Splice phase resolves the ref, replacing the {"$ref": ...} object with the
		// resolved synonym definition before the typed deserializer runs.
		JsonNode root = SearchOpaqueJsonUtil.parse(
				"{\"filter\":{\"med_syn\":{\"$ref\":\"biomed-medical_terms\"}}}");
		JsonNode synonymDef = MAPPER.readTree(
				"{\"type\":\"synonym_graph\",\"synonyms\":[\"a, b\"]}");

		Function<String, JsonNode> resolver = qname ->
				"biomed-medical_terms".equals(qname) ? synonymDef : null;

		IndexSettingsAnalysis resolved = SearchOpaqueJsonUtil.resolveAnalyzerSettings(root, resolver);

		TokenFilter med = resolved.filter().get("med_syn");
		assertNotNull(med);
		TokenFilterDefinition def = med.definition();
		assertNotNull(def);
		assertTrue(def.isSynonymGraph());
	}

	@Test
	public void testResolveAnalyzerSettingsSplicesMultipleRefsInSamePass() throws Exception {
		// Multiple refs in the same filter map all resolve in one pass; the resolver is
		// called once per ref and the final tree carries each substituted definition.
		JsonNode root = SearchOpaqueJsonUtil.parse(
				"{\"filter\":{"
				+ "\"med\":{\"$ref\":\"biomed-medical\"},"
				+ "\"stop\":{\"$ref\":\"biomed-stop\"}"
				+ "}}");
		JsonNode medDef = MAPPER.readTree("{\"type\":\"synonym_graph\",\"synonyms\":[\"a, b\"]}");
		JsonNode stopDef = MAPPER.readTree("{\"type\":\"stop\",\"stopwords\":\"_english_\"}");

		IndexSettingsAnalysis resolved = SearchOpaqueJsonUtil.resolveAnalyzerSettings(root,
				qname -> {
					switch (qname) {
						case "biomed-medical": return medDef;
						case "biomed-stop": return stopDef;
						default: return null;
					}
				});

		assertTrue(resolved.filter().get("med").definition().isSynonymGraph());
		assertTrue(resolved.filter().get("stop").definition().isStop());
	}

	@Test
	public void testResolveAnalyzerSettingsLeavesInlineEntriesUntouched() throws Exception {
		// An inline filter definition (no $ref) sitting alongside a ref must not be
		// touched. The resolver must be called exactly once — for the ref entry — and the
		// inline entry must round-trip verbatim.
		JsonNode root = SearchOpaqueJsonUtil.parse(
				"{\"filter\":{"
				+ "\"english_stop\":{\"type\":\"stop\",\"stopwords\":\"_english_\"},"
				+ "\"med_syn\":{\"$ref\":\"biomed-medical\"}"
				+ "}}");
		JsonNode synonymDef = MAPPER.readTree(
				"{\"type\":\"synonym_graph\",\"synonyms\":[\"a, b\"]}");

		int[] resolverCallCount = { 0 };
		IndexSettingsAnalysis resolved = SearchOpaqueJsonUtil.resolveAnalyzerSettings(root,
				qname -> {
					resolverCallCount[0]++;
					return "biomed-medical".equals(qname) ? synonymDef : null;
				});

		assertEquals(1, resolverCallCount[0],
				"resolver must be called exactly once — for the ref entry only");
		assertTrue(resolved.filter().get("english_stop").definition().isStop());
		assertTrue(resolved.filter().get("med_syn").definition().isSynonymGraph());
	}

	@Test
	public void testResolveAnalyzerSettingsMissingTargetThrowsWithPointer() {
		// A null resolver result for a present $ref must surface an IllegalArgumentException
		// that names both the qname and the JSON pointer to the offending entry — this is
		// what shows up at the user/API boundary on a missing SynonymSet.
		JsonNode root = SearchOpaqueJsonUtil.parse(
				"{\"filter\":{\"ghost\":{\"$ref\":\"org-Ghost\"}}}");

		IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
				() -> SearchOpaqueJsonUtil.resolveAnalyzerSettings(root, qname -> null));

		assertTrue(e.getMessage().contains("Unresolved $ref"));
		assertTrue(e.getMessage().contains("org-Ghost"));
		assertTrue(e.getMessage().contains("/filter/ghost"),
				"message must include the JSON pointer to the offending entry: " + e.getMessage());
	}

	@Test
	public void testResolveAnalyzerSettingsWithNonObjectFilterValueIgnoresEntry() {
		// A filter entry whose value is not an object (e.g. an inline `stop` definition
		// shape with a top-level type) is left alone by the splice. The resolver must not
		// be called.
		JsonNode root = SearchOpaqueJsonUtil.parse(
				"{\"filter\":{\"my_filter\":{\"type\":\"stop\",\"stopwords\":\"_english_\"}}}");

		IndexSettingsAnalysis resolved = SearchOpaqueJsonUtil.resolveAnalyzerSettings(root,
				q -> { throw new AssertionError("resolver must not be called for non-ref entries"); });

		assertNotNull(resolved.filter().get("my_filter"));
	}

	@Test
	public void testResolveAnalyzerSettingsWithMultiKeyObjectIsNotARef() {
		// readRef rejects objects whose size != 1, so a filter entry that has $ref alongside
		// other keys is treated as inline — the resolver must not be invoked. The typed
		// deserializer ignores the extra $ref key and parses the entry as a regular filter.
		JsonNode root = SearchOpaqueJsonUtil.parse(
				"{\"filter\":{\"my_filter\":{\"$ref\":\"org-X\",\"type\":\"stop\","
				+ "\"stopwords\":\"_english_\"}}}");

		IndexSettingsAnalysis resolved = SearchOpaqueJsonUtil.resolveAnalyzerSettings(root,
				q -> { throw new AssertionError("resolver must not be called for non-ref shape"); });

		assertNotNull(resolved.filter().get("my_filter"));
	}

	@Test
	public void testResolveAnalyzerSettingsWithNonTextualRefValueIsNotARef() {
		// A {"$ref": <number>} entry doesn't match the textual-$ref shape. readRef returns
		// null, the splice skips it, and the typed deserializer surfaces the shape error.
		JsonNode root = SearchOpaqueJsonUtil.parse(
				"{\"filter\":{\"bogus\":{\"$ref\":42}}}");

		assertThrows(IllegalArgumentException.class,
				() -> SearchOpaqueJsonUtil.resolveAnalyzerSettings(root,
						q -> { throw new AssertionError("resolver must not be called"); }));
	}

	@Test
	public void testResolveAnalyzerSettingsWithNonObjectFilterMapDeserializerThrows() {
		// A non-object filter value (string, number) at the top-level FILTER_KEY position
		// fails the splice's isObject() guard, so resolveAnalyzerSettings skips substitution
		// and lets the typed deserializer surface the shape error — wrapped as an
		// IllegalArgumentException by the analyzer-typed deserialize step.
		JsonNode root = SearchOpaqueJsonUtil.parse("{\"filter\":\"not_an_object\"}");

		IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
				() -> SearchOpaqueJsonUtil.resolveAnalyzerSettings(root, q -> null));

		assertTrue(e.getMessage().contains("Invalid analyzer settings"),
				"deserialize errors must be rewrapped as Invalid analyzer settings: " + e.getMessage());
	}

	@Test
	public void testResolveAnalyzerSettingsWithMalformedFilterDefinitionThrows() throws Exception {
		// The splice itself succeeds (the spliced node is valid JSON), but the OpenSearch
		// typed deserializer rejects the resulting filter shape ("type": "not_a_real_filter")
		// — that error must be rewrapped as IllegalArgumentException so the manager-side
		// validation surfaces it at create / update time.
		JsonNode root = SearchOpaqueJsonUtil.parse(
				"{\"filter\":{\"bad\":{\"$ref\":\"org-bad\"}}}");
		JsonNode badDef = MAPPER.readTree("{\"type\":\"not_a_real_filter_kind\"}");

		IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
				() -> SearchOpaqueJsonUtil.resolveAnalyzerSettings(root,
						qname -> badDef));

		assertTrue(e.getMessage().contains("Invalid analyzer settings"),
				"typed-deserializer errors must surface as Invalid analyzer settings: " + e.getMessage());
	}

	@Test
	public void testResolveAnalyzerSettingsAnalyzerChainArrayPreservedVerbatim() {
		// The splice only touches the filter map. Analyzer-chain arrays (which can contain
		// strings that look qname-shaped) must not be rewritten.
		JsonNode root = SearchOpaqueJsonUtil.parse(
				"{\"analyzer\":{\"default\":{\"type\":\"custom\",\"tokenizer\":\"standard\","
				+ "\"filter\":[\"lowercase\",\"my_filter\"]}},"
				+ "\"filter\":{\"my_filter\":{\"$ref\":\"org-X\"}}}");
		JsonNode resolved = SearchOpaqueJsonUtil.parse(
				"{\"type\":\"stop\",\"stopwords\":\"_english_\"}");

		IndexSettingsAnalysis result = SearchOpaqueJsonUtil.resolveAnalyzerSettings(
				root, qname -> "org-X".equals(qname) ? resolved : null);

		assertEquals(Arrays.asList("lowercase", "my_filter"),
				result.analyzer().get("default").custom().filter());
	}

	// ===================== toInlineAnalyzerSettings =====================

	@Test
	public void testToInlineAnalyzerSettingsWithBareBlockMap() {
		// Curator-supplied bare OpenSearch settings.analysis block (no envelope).
		// Single round-trip exercising every allowed root key (char_filter / tokenizer /
		// filter / analyzer) so a regression in any one branch shows up here.
		Map<String, Object> bareBlock = Map.of(
				"char_filter", Map.of("strip", Map.of("type", "html_strip")),
				"tokenizer",   Map.of("std",   Map.of("type", "standard")),
				"filter",      Map.of("english_stop", Map.of("type", "stop", "stopwords", "_english_")),
				"analyzer",    Map.of("default", Map.of(
						"type", "custom",
						"tokenizer", "std",
						"char_filter", List.of("strip"),
						"filter", List.of("lowercase", "english_stop"))));

		// call under test
		IndexSettingsAnalysis result = SearchOpaqueJsonUtil.toInlineAnalyzerSettings(bareBlock,
				qname -> null);

		assertNotNull(result);
		assertTrue(result.charFilter().containsKey("strip"));
		assertTrue(result.tokenizer().containsKey("std"));
		assertTrue(result.filter().containsKey("english_stop"));
		assertEquals("std", result.analyzer().get("default").custom().tokenizer());
	}

	@Test
	public void testToInlineAnalyzerSettingsWithJSONObject() {
		JSONObject bareBlock = new JSONObject().put("analyzer", new JSONObject()
				.put("default", new JSONObject()
						.put("type", "custom")
						.put("tokenizer", "standard")));

		// call under test
		IndexSettingsAnalysis result = SearchOpaqueJsonUtil.toInlineAnalyzerSettings(bareBlock,
				qname -> null);

		assertNotNull(result);
		assertEquals("standard", result.analyzer().get("default").custom().tokenizer());
	}

	@Test
	public void testToInlineAnalyzerSettingsWithNullReturnsNull() {
		// call under test
		assertNull(SearchOpaqueJsonUtil.toInlineAnalyzerSettings(null, qname -> null));
	}

	@Test
	public void testToInlineAnalyzerSettingsWithUnresolvedRefThrows() {
		// At create-time the resolver always returns null, so any $ref inside the inline
		// literal surfaces as IllegalArgumentException — matching the schema contract that
		// SynonymSet refs are not permitted inside an inline analyzer slot.
		Map<String, Object> withRef = Map.of(
				"filter",   Map.of("syn", Map.of("$ref", "org-NOT_RESOLVABLE")),
				"analyzer", Map.of("default", Map.of(
						"type", "custom",
						"tokenizer", "standard",
						"filter", List.of("lowercase", "syn"))));

		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				// call under test
				() -> SearchOpaqueJsonUtil.toInlineAnalyzerSettings(withRef, qname -> null));
		assertTrue(ex.getMessage().contains("org-NOT_RESOLVABLE"),
				"expected message to identify the unresolved ref, got: " + ex.getMessage());
	}

	@Test
	public void testToInlineAnalyzerSettingsWithResolverSplicesRef() {
		// At build-time the resolver returns the SynonymSet definition; the spliced filter
		// map must reflect the resolved type, proving the resolver is honored.
		Map<String, Object> withRef = Map.of(
				"filter",   Map.of("syn", Map.of("$ref", "biomed-medical_terms")),
				"analyzer", Map.of("default", Map.of(
						"type", "custom",
						"tokenizer", "standard",
						"filter", List.of("lowercase", "syn"))));
		JsonNode synonymSet = SearchOpaqueJsonUtil.parse(
				"{\"type\":\"synonym_graph\",\"synonyms\":[\"a, b\"]}");

		// call under test
		IndexSettingsAnalysis result = SearchOpaqueJsonUtil.toInlineAnalyzerSettings(withRef,
				qname -> "biomed-medical_terms".equals(qname) ? synonymSet : null);

		TokenFilter spliced = result.filter().get("syn");
		assertNotNull(spliced);
		TokenFilterDefinition def = spliced.definition();
		assertNotNull(def, "spliced filter should be a typed token-filter definition");
	}

	// ===================== constants =====================

	@Test
	public void testRefKeyConstant() {
		// REF_KEY must stay $ref to remain compatible with the OpenSearch / curator-facing
		// convention. If this constant ever drifts, every $ref recognition path breaks.
		assertEquals("$ref", SearchOpaqueJsonUtil.REF_KEY);
	}

	@Test
	public void testFilterKeyConstant() {
		// FILTER_KEY must stay `filter` to match the OpenSearch-canonical name. If this
		// constant ever drifts, every analyzer $ref splice in the project breaks.
		assertEquals("filter", SearchOpaqueJsonUtil.FILTER_KEY);
	}
}
