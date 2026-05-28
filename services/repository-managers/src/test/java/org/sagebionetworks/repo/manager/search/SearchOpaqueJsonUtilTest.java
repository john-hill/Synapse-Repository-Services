package org.sagebionetworks.repo.manager.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Iterator;
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
import org.opensearch.client.opensearch._types.SortOrder;
import org.opensearch.client.opensearch.core.SearchRequest;
import org.opensearch.client.opensearch.core.search.TrackHits;
import org.opensearch.client.opensearch.indices.IndexSettingsAnalysis;
import org.sagebionetworks.repo.model.search.SearchQueryPart;
import org.sagebionetworks.repo.model.search.table.TextAnalyzer;
import org.sagebionetworks.schema.adapter.JSONObjectAdapter;
import org.sagebionetworks.schema.adapter.org.json.JSONObjectAdapterImpl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

/**
 * Unit tests for {@link SearchOpaqueJsonUtil}, organized by the concerns the util
 * exposes: shape conversion ({@link SearchOpaqueJsonUtil#parse},
 * {@link SearchOpaqueJsonUtil#fromJsonString},
 * package-private {@link SearchOpaqueJsonUtil#asJsonString}); reference detection
 * ({@link SearchOpaqueJsonUtil#readRef(Object)},
 * {@link SearchOpaqueJsonUtil#readRef(JsonNode)},
 * {@link SearchOpaqueJsonUtil#collectRefs}); inline materialization
 * ({@link SearchOpaqueJsonUtil#toInline}); and the analyzer-typed
 * splice + deserialize surface
 * ({@link SearchOpaqueJsonUtil#resolveAnalyzerSettings}).
 */
public class SearchOpaqueJsonUtilTest {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	/** Test-only RoutingContext that maps via {@code nameToId} and reports every column as
	 *  non-text — produces a name-only walker, behaviorally equivalent on a numeric schema. */
	private static SearchFieldRewriter.RoutingContext nameOnly(Function<String, String> nameToId) {
		return new SearchFieldRewriter.RoutingContext() {
			@Override public String mapName(String name) {
				String mapped = nameToId.apply(name);
				return mapped == null ? name : mapped;
			}
			@Override public boolean isTextLike(String columnId) { return false; }
		};
	}

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

	@Test
	public void testToInlineConvertsJsonObjectAdapterToTypedPojo() throws Exception {
		// JSONObjectAdapter is the wire-deserialized shape controllers receive for opaque-Object
		// fields after JSON binding. Without the JSONObjectAdapter branch in toInline, this falls
		// through to MAPPER.convertValue, which tries to serialize JSONObjectAdapterImpl via
		// Jackson (no BeanSerializer) and throws "No serializer found for class JSONObjectAdapterImpl".
		JSONObjectAdapter adapter = new JSONObjectAdapterImpl(
				"{\"organizationName\":\"biomed\",\"name\":\"publications\"}");

		// call under test
		TextAnalyzer ta = SearchOpaqueJsonUtil.toInline(adapter, TextAnalyzer.class);

		assertEquals("biomed", ta.getOrganizationName());
		assertEquals("publications", ta.getName());
	}

	@Test
	public void testToInlineRejectsMalformedJsonObjectAdapter() throws Exception {
		// A JSONObjectAdapter whose payload doesn't deserialize as the target POJO must surface
		// as IllegalArgumentException — same contract as Map / JSONObject inputs.
		JSONObjectAdapter adapter = new JSONObjectAdapterImpl(
				"{\"createdBy\":{\"not\":\"a-string\"}}");

		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				() -> SearchOpaqueJsonUtil.toInline(adapter, TextAnalyzer.class));

		assertTrue(ex.getMessage().contains("Invalid inline TextAnalyzer"), ex.getMessage());
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
	public void testFromJsonpTreeDeserializesTypedQuery() throws Exception {
		JsonNode node = new ObjectMapper().readTree("{\"match\":{\"abstract\":\"amyloid\"}}");
		// call under test
		org.opensearch.client.opensearch._types.query_dsl.Query query = SearchOpaqueJsonUtil.fromJsonpTree(
				node, org.opensearch.client.opensearch._types.query_dsl.Query._DESERIALIZER);
		assertTrue(query.isMatch());
		assertEquals("abstract", query.match().field());
	}

	@Test
	public void testToJsonpTreeSerializesTypedValue() {
		org.opensearch.client.opensearch._types.FieldValue value =
				org.opensearch.client.opensearch._types.FieldValue.of(42L);
		// call under test
		JsonNode node = SearchOpaqueJsonUtil.toJsonpTree(value);
		assertEquals(42L, node.asLong());
	}

	@Test
	public void testJsonpTreeRoundTrips() {
		org.opensearch.client.opensearch._types.aggregations.Aggregation agg =
				org.opensearch.client.opensearch._types.aggregations.Aggregation.of(a -> a.avg(av -> av.field("score")));
		// call under test — serialize then deserialize back through the typed bridge
		JsonNode node = SearchOpaqueJsonUtil.toJsonpTree(agg);
		org.opensearch.client.opensearch._types.aggregations.Aggregation back = SearchOpaqueJsonUtil.fromJsonpTree(
				node, org.opensearch.client.opensearch._types.aggregations.Aggregation._DESERIALIZER);
		assertTrue(back.isAvg());
		assertEquals("score", back.avg().field());
	}

	// ===================== buildTypedQuery =====================

	@Test
	public void testBuildTypedQueryWithAllowedClauseRewritesNameToId() {
		// Caller supplies the column by name; the util validates, rewrites to column id,
		// and hands back the typed Query.
		SearchFieldRewriter.RoutingContext ctx = nameOnly(
				name -> "title".equals(name) ? "100" : name);

		// call under test
		org.opensearch.client.opensearch._types.query_dsl.Query q = SearchOpaqueJsonUtil.buildTypedQuery(
				"{\"match\":{\"title\":\"amyloid\"}}", ctx);

		assertTrue(q.isMatch());
		assertEquals("100", q.match().field());
	}

	@Test
	public void testBuildTypedQueryWithDisallowedClauseRejected() {
		// `script` is not allowlisted — must be rejected with HTTP 400 before reaching AOSS.
		// call under test
		assertThrows(IllegalArgumentException.class,
				() -> SearchOpaqueJsonUtil.buildTypedQuery(
						"{\"script\":{\"script\":\"doc['x'].value\"}}",
						nameOnly(Function.identity())));
	}

	// ===================== buildTypedAggregations =====================

	@Test
	public void testBuildTypedAggregationsWithAllowedAggsRewritesNameToId() {
		SearchFieldRewriter.RoutingContext ctx = nameOnly(
				name -> "year".equals(name) ? "300" : name);

		// call under test
		Map<String, org.opensearch.client.opensearch._types.aggregations.Aggregation> aggs =
				SearchOpaqueJsonUtil.buildTypedAggregations(
						"{\"by_year\":{\"terms\":{\"field\":\"year\"}}}", ctx);

		assertNotNull(aggs.get("by_year"));
		assertEquals("300", aggs.get("by_year").terms().field());
	}

	@Test
	public void testBuildTypedAggregationsWithDisallowedAggsRejected() {
		// scripted_metric is not allowlisted.
		// call under test
		assertThrows(IllegalArgumentException.class,
				() -> SearchOpaqueJsonUtil.buildTypedAggregations(
						"{\"x\":{\"scripted_metric\":{\"init_script\":\"\"}}}",
						nameOnly(Function.identity())));
	}

	@Test
	public void testBuildTypedAggregationsRoutesTextColumnsThroughKeyword() {
		// Year is text-like — auto-router must append .keyword for the aggregation.
		SearchFieldRewriter.RoutingContext ctx = new SearchFieldRewriter.RoutingContext() {
			@Override public String mapName(String name) {
				return "year".equals(name) ? "300" : name;
			}
			@Override public boolean isTextLike(String columnId) {
				return "300".equals(columnId);
			}
		};

		// call under test
		Map<String, org.opensearch.client.opensearch._types.aggregations.Aggregation> aggs =
				SearchOpaqueJsonUtil.buildTypedAggregations(
						"{\"by_year\":{\"terms\":{\"field\":\"year\"}}}", ctx);

		assertEquals("300.keyword", aggs.get("by_year").terms().field());
	}

	// ===================== buildTypedSuggester =====================

	@Test
	public void testBuildTypedSuggesterWithAllowedSuggesterRewritesNameToId() {
		SearchFieldRewriter.RoutingContext ctx = nameOnly(
				name -> "title".equals(name) ? "100" : name);

		// call under test
		org.opensearch.client.opensearch.core.search.Suggester suggester =
				SearchOpaqueJsonUtil.buildTypedSuggester(
						"{\"did_you_mean\":{\"text\":\"amiloid\",\"term\":{\"field\":\"title\"}}}",
						ctx);

		assertNotNull(suggester);
		assertTrue(suggester.suggesters().containsKey("did_you_mean"));
		assertEquals("100", suggester.suggesters().get("did_you_mean").term().field());
	}

	@Test
	public void testBuildTypedSuggesterWithDisallowedSuggesterRejected() {
		// `phrase` carrying a `collate` script is rejected.
		// call under test
		assertThrows(IllegalArgumentException.class,
				() -> SearchOpaqueJsonUtil.buildTypedSuggester(
						"{\"x\":{\"phrase\":{\"field\":\"title\",\"collate\":{\"script\":\"x\"}}}}",
						nameOnly(Function.identity())));
	}

	// ===================== buildTypedHighlight =====================

	@Test
	public void testBuildTypedHighlightWithFieldsRewritesKeyToId() {
		SearchFieldRewriter.RoutingContext ctx = nameOnly(
				name -> "title".equals(name) ? "100" : name);

		// call under test
		org.opensearch.client.opensearch.core.search.Highlight highlight =
				SearchOpaqueJsonUtil.buildTypedHighlight(
						"{\"fields\":{\"title\":{}}}",
						ctx);

		assertNotNull(highlight);
		assertTrue(highlight.fields().containsKey("100"));
		assertTrue(!highlight.fields().containsKey("title"));
	}

	@Test
	public void testBuildTypedHighlightWithSemanticTypeRejected() {
		// call under test
		assertThrows(IllegalArgumentException.class,
				() -> SearchOpaqueJsonUtil.buildTypedHighlight(
						"{\"type\":\"semantic\",\"fields\":{\"title\":{}}}",
						nameOnly(Function.identity())));
	}

	@Test
	public void testBuildTypedHighlightWithEmbeddedScriptRejected() {
		// call under test
		assertThrows(IllegalArgumentException.class,
				() -> SearchOpaqueJsonUtil.buildTypedHighlight(
						"{\"fields\":{\"title\":{\"script\":{}}}}",
						nameOnly(Function.identity())));
	}

	// ===================== buildTypedFieldCollapse =====================

	@Test
	public void testBuildTypedFieldCollapseRewritesNameToId() {
		SearchFieldRewriter.RoutingContext ctx = nameOnly(
				name -> "projectId".equals(name) ? "200" : name);

		// call under test
		org.opensearch.client.opensearch.core.search.FieldCollapse collapse =
				SearchOpaqueJsonUtil.buildTypedFieldCollapse(
						"{\"field\":\"projectId\"}", ctx);

		assertEquals("200", collapse.field());
	}

	@Test
	public void testBuildTypedFieldCollapseRoutesTextColumnsThroughKeyword() {
		// Collapse needs doc values, like aggregations — text columns must auto-route
		// through .keyword.
		SearchFieldRewriter.RoutingContext ctx = new SearchFieldRewriter.RoutingContext() {
			@Override public String mapName(String name) {
				return "tag".equals(name) ? "300" : name;
			}
			@Override public boolean isTextLike(String columnId) {
				return "300".equals(columnId);
			}
		};

		// call under test
		org.opensearch.client.opensearch.core.search.FieldCollapse collapse =
				SearchOpaqueJsonUtil.buildTypedFieldCollapse(
						"{\"field\":\"tag\"}", ctx);

		assertEquals("300.keyword", collapse.field());
	}

	@Test
	public void testBuildTypedFieldCollapseWithInnerHitsRejected() {
		// inner_hits is not surfaced on SearchQueryResults today.
		// call under test
		assertThrows(IllegalArgumentException.class,
				() -> SearchOpaqueJsonUtil.buildTypedFieldCollapse(
						"{\"field\":\"projectId\",\"inner_hits\":{\"name\":\"latest\",\"size\":3}}",
						nameOnly(Function.identity())));
	}

	// ===================== buildTypedRescore =====================

	@Test
	public void testBuildTypedRescoreRewritesInnerQueryNameToId() {
		// rescore_query is a full Query subtree — field references inside must be rewritten
		// the same way as the top-level query.
		SearchFieldRewriter.RoutingContext ctx = nameOnly(
				name -> "title".equals(name) ? "100" : name);

		// call under test
		org.opensearch.client.opensearch.core.search.Rescore rescore =
				SearchOpaqueJsonUtil.buildTypedRescore(
						"{\"window_size\":50,\"query\":{\"rescore_query\":{\"match_phrase\":{\"title\":\"alzheimers\"}}}}",
						ctx);

		assertEquals(50, rescore.windowSize().intValue());
		assertEquals("100", rescore.query().rescoreQuery().matchPhrase().field());
	}

	@Test
	public void testBuildTypedRescoreWithDisallowedInnerClauseRejected() {
		// script_score inside rescore_query must be rejected by the same allowlist as
		// the top-level query.
		// call under test
		assertThrows(IllegalArgumentException.class,
				() -> SearchOpaqueJsonUtil.buildTypedRescore(
						"{\"window_size\":50,\"query\":{\"rescore_query\":{\"script_score\":{\"query\":{\"match_all\":{}},\"script\":\"x\"}}}}",
						nameOnly(Function.identity())));
	}

	@Test
	public void testBuildTypedRescoreWithExcessiveWindowSizeRejected() {
		// call under test
		assertThrows(IllegalArgumentException.class,
				() -> SearchOpaqueJsonUtil.buildTypedRescore(
						"{\"window_size\":5000,\"query\":{\"rescore_query\":{\"match_all\":{}}}}",
						nameOnly(Function.identity())));
	}

	// ===================== buildTypedSearchAfter =====================

	@Test
	public void testBuildTypedSearchAfterWithNullReturnsEmpty() {
		// call under test
		assertTrue(SearchOpaqueJsonUtil.buildTypedSearchAfter(null).isEmpty());
	}

	@Test
	public void testBuildTypedSearchAfterWithEmptyReturnsEmpty() {
		// call under test
		assertTrue(SearchOpaqueJsonUtil.buildTypedSearchAfter(Collections.emptyList()).isEmpty());
	}

	@Test
	public void testBuildTypedSearchAfterWithMixedScalarsRoundTrips() {
		// Mixed shape: long sort key plus string sort key — same shape we emit on
		// nextSearchAfter for a multi-field sort.
		List<Object> cursor = Arrays.<Object>asList(42L, "syn123");

		// call under test
		List<org.opensearch.client.opensearch._types.FieldValue> typed =
				SearchOpaqueJsonUtil.buildTypedSearchAfter(cursor);

		assertEquals(2, typed.size());
		assertEquals(42L, typed.get(0).longValue());
		assertEquals("syn123", typed.get(1).stringValue());
	}

	// ===================== applyBodyToRequest =====================

	private static final int APPLY_DEFAULT_SIZE = 10;
	private static final int APPLY_MAX_SIZE = 1000;

	private static SearchRequest applyBody(String json,
			Set<SearchQueryPart> options) {
		SearchRequest.Builder req =
				new SearchRequest.Builder().index("test-index");
		SearchOpaqueJsonUtil.applyBodyToRequest(json, nameOnly(Function.identity()), req, options,
				APPLY_DEFAULT_SIZE, APPLY_MAX_SIZE);
		return req.build();
	}

	private static SearchRequest applyAutocompleteBody(String json,
			Set<SearchQueryPart> options) {
		SearchRequest.Builder req =
				new SearchRequest.Builder().index("test-index");
		SearchOpaqueJsonUtil.applyAutocompleteBodyToRequest(json, nameOnly(Function.identity()), req,
				options, APPLY_DEFAULT_SIZE);
		return req.build();
	}

	/**
	 * Single round trip exercising every key in {@link SearchDslValidator#BODY_ALLOWED_KEYS}
	 * (except the mutually-exclusive {@code search_after} / {@code from > 0} pairing — covered
	 * separately) in one body. The {@code aggs} alias is exercised in its own test below so
	 * this case can use {@code aggregations} and still hit every key.
	 *
	 * <p>Coverage guard: any key in BODY_ALLOWED_KEYS that this fixture forgets to populate
	 * causes the assertion to fail, so a future schema relaxation that adds a new top-level
	 * key surfaces here until the fixture is updated.</p>
	 */
	@Test
	public void testApplyBodyToRequestWithEveryTopLevelKeyRoundTrips() {
		String json = "{"
				+ "\"query\":{\"match_all\":{}},"
				+ "\"post_filter\":{\"match_all\":{}},"
				+ "\"aggregations\":{\"by_year\":{\"terms\":{\"field\":\"year\"}}},"
				+ "\"suggest\":{\"did_you_mean\":{\"text\":\"x\",\"term\":{\"field\":\"title\"}}},"
				+ "\"highlight\":{\"fields\":{\"title\":{}}},"
				+ "\"collapse\":{\"field\":\"projectId\"},"
				+ "\"rescore\":{\"window_size\":50,\"query\":{\"rescore_query\":{\"match_all\":{}}}},"
				+ "\"sort\":[\"title\"],"
				+ "\"_source\":[\"title\"],"
				+ "\"from\":5,"
				+ "\"size\":50"
				+ "}";

		// call under test
		SearchRequest req = applyBody(json,
				EnumSet.allOf(SearchQueryPart.class));

		assertNotNull(req.query());
		assertNotNull(req.postFilter());
		assertEquals(1, req.aggregations().size());
		assertNotNull(req.suggest());
		assertNotNull(req.highlight());
		assertNotNull(req.collapse());
		assertEquals(1, req.rescore().size());
		assertEquals(1, req.sort().size());
		assertNotNull(req.source());
		assertEquals(5, req.from().intValue());
		assertEquals(50, req.size().intValue());

		// Coverage guard: every key in BODY_ALLOWED_KEYS must be reachable across this
		// suite. This body covers everything except {search_after, aggs} — search_after
		// is mutually exclusive with from > 0 (covered in
		// testApplyBodyToRequestWithSearchAfterCursor) and aggs is the alias for
		// aggregations (covered in testApplyBodyToRequestWithAggsAliasOnly). Adding a
		// new top-level allowlisted key fails this assertion until a test for it is
		// added.
		Set<String> exercised = new LinkedHashSet<>();
		JsonNode parsed = SearchOpaqueJsonUtil.parse(json);
		Iterator<String> names = parsed.fieldNames();
		while (names.hasNext()) {
			exercised.add(names.next());
		}
		exercised.add("search_after");
		exercised.add("aggs");
		assertEquals(SearchDslValidator.BODY_ALLOWED_KEYS, exercised,
				"every BODY_ALLOWED_KEY must be exercised across the round-trip suite");
	}

	@Test
	public void testApplyBodyToRequestWithSearchAfterCursor() {
		// search_after replaces from — verify the cursor is propagated and from is forced to 0.
		String json = "{\"query\":{\"match_all\":{}},\"search_after\":[\"abc\",100]}";

		SearchRequest req = applyBody(json,
				EnumSet.of(SearchQueryPart.HITS));

		assertEquals(2, req.searchAfter().size());
		assertEquals(0, req.from().intValue());
	}

	@Test
	public void testApplyBodyToRequestWithSearchAfterAndPositiveFromRejected() {
		// scanBodyTopLevelKeys rejects this combination; covers that branch directly.
		String json = "{\"query\":{\"match_all\":{}},\"search_after\":[\"abc\"],\"from\":5}";

		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				() -> applyBody(json,
						EnumSet.of(SearchQueryPart.HITS)));
		assertTrue(ex.getMessage().contains("search_after"));
		assertTrue(ex.getMessage().contains("from"));
	}

	@Test
	public void testApplyBodyToRequestWithAggsAliasOnly() {
		// `aggs` and `aggregations` are aliases; this covers the alias branch of parseAggregations.
		String json = "{\"query\":{\"match_all\":{}},\"aggs\":{\"by_year\":{\"terms\":{\"field\":\"year\"}}}}";

		SearchRequest req = applyBody(json,
				EnumSet.of(SearchQueryPart.HITS));
		assertEquals(1, req.aggregations().size());
		assertNotNull(req.aggregations().get("by_year"));
	}

	@Test
	public void testApplyBodyToRequestWithBothAggregationsAndAggsRejected() {
		// scanBodyTopLevelKeys rejects supplying both forms.
		String json = "{\"query\":{\"match_all\":{}},"
				+ "\"aggregations\":{\"a\":{\"terms\":{\"field\":\"x\"}}},"
				+ "\"aggs\":{\"a\":{\"terms\":{\"field\":\"x\"}}}}";

		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				() -> applyBody(json,
						EnumSet.of(SearchQueryPart.HITS)));
		assertTrue(ex.getMessage().contains("aggregations"));
		assertTrue(ex.getMessage().contains("aggs"));
	}

	@Test
	public void testApplyBodyToRequestWithoutHitsForcesSizeZeroAndSkipsHitsOnlySlots() {
		// HITS absent: applyBodyToRequest must force size=0 and skip _source / sort /
		// search_after / highlight / collapse / rescore (every "meaningless without hits"
		// slot). post_filter / aggregations / suggest still run.
		String json = "{"
				+ "\"query\":{\"match_all\":{}},"
				+ "\"post_filter\":{\"match_all\":{}},"
				+ "\"aggregations\":{\"a\":{\"terms\":{\"field\":\"year\"}}},"
				+ "\"suggest\":{\"s\":{\"text\":\"x\",\"term\":{\"field\":\"title\"}}},"
				+ "\"highlight\":{\"fields\":{\"title\":{}}},"
				+ "\"collapse\":{\"field\":\"projectId\"},"
				+ "\"rescore\":{\"window_size\":50,\"query\":{\"rescore_query\":{\"match_all\":{}}}},"
				+ "\"sort\":[\"title\"],"
				+ "\"_source\":[\"title\"],"
				+ "\"size\":50,"
				+ "\"search_after\":[\"abc\"]"
				+ "}";

		// call under test — TOTAL_HITS only, no HITS
		SearchRequest req = applyBody(json,
				EnumSet.of(SearchQueryPart.TOTAL_HITS));

		assertEquals(0, req.size().intValue(), "size forced to 0 when HITS is absent");
		assertNull(req.highlight(), "highlight skipped without HITS");
		assertNull(req.collapse(), "collapse skipped without HITS");
		assertTrue(req.rescore() == null || req.rescore().isEmpty(), "rescore skipped without HITS");
		assertNull(req.source(), "_source skipped without HITS");
		assertTrue(req.sort() == null || req.sort().isEmpty(), "sort skipped without HITS");
		assertTrue(req.searchAfter() == null || req.searchAfter().isEmpty(),
				"search_after skipped without HITS");
		// post_filter / aggregations / suggest still run because they are not gated by HITS.
		assertNotNull(req.postFilter());
		assertEquals(1, req.aggregations().size());
		assertNotNull(req.suggest());
	}

	@Test
	public void testApplyBodyToRequestTrackTotalHitsCountWhenTotalHitsRequested() {
		String json = "{\"query\":{\"match_all\":{}}}";

		SearchRequest req = applyBody(json,
				EnumSet.of(SearchQueryPart.HITS,
						SearchQueryPart.TOTAL_HITS));

		TrackHits track = req.trackTotalHits();
		assertNotNull(track);
		assertTrue(track.isCount(), "TOTAL_HITS requested → track_total_hits.count=Integer.MAX_VALUE");
		assertEquals(Integer.MAX_VALUE, track.count().intValue());
	}

	@Test
	public void testApplyBodyToRequestTrackTotalHitsDisabledWhenTotalHitsAbsent() {
		String json = "{\"query\":{\"match_all\":{}}}";

		SearchRequest req = applyBody(json,
				EnumSet.of(SearchQueryPart.HITS));

		TrackHits track = req.trackTotalHits();
		assertNotNull(track);
		assertTrue(track.isEnabled(), "TOTAL_HITS absent → track_total_hits.enabled=false");
		assertEquals(Boolean.FALSE, track.enabled());
	}

	@Test
	public void testApplyBodyToRequestWithMissingQueryRejected() {
		// parseRequiredQuery must throw — query is mandatory.
		String json = "{\"size\":10}";

		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				() -> applyBody(json,
						EnumSet.of(SearchQueryPart.HITS)));
		assertTrue(ex.getMessage().contains("body.query"));
	}

	@Test
	public void testApplyBodyToRequestWithUnsupportedTopLevelKeyRejected() {
		// scanBodyTopLevelKeys's allowlist branch.
		String json = "{\"query\":{\"match_all\":{}},\"explain\":true}";

		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				() -> applyBody(json,
						EnumSet.of(SearchQueryPart.HITS)));
		assertTrue(ex.getMessage().contains("explain"));
	}

	@Test
	public void testApplyBodyToRequestDefaultsAndClampsSize() {
		// Body omits from — defaults to 0; size above maxSize must clamp.
		String json = "{\"query\":{\"match_all\":{}},\"size\":10000}";

		SearchRequest req = applyBody(json,
				EnumSet.of(SearchQueryPart.HITS));

		assertEquals(0, req.from().intValue(), "from defaults to 0");
		assertEquals(APPLY_MAX_SIZE, req.size().intValue(), "size clamps at maxSize");
	}

	@Test
	public void testApplyBodyToRequestDefaultsSizeWhenOmitted() {
		String json = "{\"query\":{\"match_all\":{}}}";

		SearchRequest req = applyBody(json,
				EnumSet.of(SearchQueryPart.HITS));

		assertEquals(APPLY_DEFAULT_SIZE, req.size().intValue(),
				"size omitted → defaultSize");
	}

	@Test
	public void testApplyBodyToRequestWithoutSortInjectsScoreDescending() {
		// parseSort returns the relevance-default singleton when "sort" is absent.
		String json = "{\"query\":{\"match_all\":{}}}";

		SearchRequest req = applyBody(json,
				EnumSet.of(SearchQueryPart.HITS));

		assertEquals(1, req.sort().size());
		assertEquals("_score", req.sort().get(0).field().field());
		assertEquals(SortOrder.Desc,
				req.sort().get(0).field().order());
	}

	@Test
	public void testApplyBodyToRequestWithBareScoreSortStringPassesThrough() {
		// Top-level "sort":"_score" hits the early-out branch in parseSort that does not
		// wrap-and-walk the value. This covers the !"_score".equals(node.asText()) branch.
		String json = "{\"query\":{\"match_all\":{}},\"sort\":\"_score\"}";

		SearchRequest req = applyBody(json,
				EnumSet.of(SearchQueryPart.HITS));

		// The bare-string sort goes to fromJsonpTree directly — the result is a SortOptions
		// for "_score" without an explicit order.
		assertEquals(1, req.sort().size());
	}

	@Test
	public void testApplyAutocompleteBodyToRequestWithQueryAndSourceOnlyAccepted() {
		String json = "{\"query\":{\"prefix\":{\"title\":\"alz\"}},\"_source\":[\"title\"]}";

		// call under test
		SearchRequest req = applyAutocompleteBody(json,
				EnumSet.of(SearchQueryPart.HITS));

		assertNotNull(req.query());
		assertNotNull(req.source());
		// Autocomplete narrows the surface — these slots must NOT be populated.
		assertNull(req.postFilter(), "autocomplete must not surface post_filter");
		assertTrue(req.aggregations() == null || req.aggregations().isEmpty(),
				"autocomplete must not surface aggregations");
		assertNull(req.suggest(), "autocomplete must not surface suggest");
		assertNull(req.highlight(), "autocomplete must not surface highlight");
		assertNull(req.collapse(), "autocomplete must not surface collapse");
		assertTrue(req.rescore() == null || req.rescore().isEmpty(),
				"autocomplete must not surface rescore");
	}

	@Test
	public void testApplyAutocompleteBodyToRequestRejectsAggregationsKey() {
		// Autocomplete's narrow allowlist excludes aggregations.
		String json = "{\"query\":{\"prefix\":{\"title\":\"alz\"}},"
				+ "\"aggregations\":{\"a\":{\"terms\":{\"field\":\"year\"}}}}";

		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				() -> applyAutocompleteBody(json,
						EnumSet.of(SearchQueryPart.HITS)));
		assertTrue(ex.getMessage().contains("aggregations"));
	}

	@Test
	public void testApplyAutocompleteBodyToRequestRejectsHighlightKey() {
		String json = "{\"query\":{\"prefix\":{\"title\":\"alz\"}},"
				+ "\"highlight\":{\"fields\":{\"title\":{}}}}";

		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				() -> applyAutocompleteBody(json,
						EnumSet.of(SearchQueryPart.HITS)));
		assertTrue(ex.getMessage().contains("highlight"));
	}

	@Test
	public void testApplyAutocompleteBodyToRequestRejectsSortKey() {
		String json = "{\"query\":{\"prefix\":{\"title\":\"alz\"}},\"sort\":[\"title\"]}";

		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				() -> applyAutocompleteBody(json,
						EnumSet.of(SearchQueryPart.HITS)));
		assertTrue(ex.getMessage().contains("sort"));
	}

	@Test
	public void testApplyAutocompleteBodyToRequestRejectsDisallowedTopLevelQueryKind() {
		// match_all is in the general allowlist but not in ALLOWED_AUTOCOMPLETE_TOP_LEVEL.
		String json = "{\"query\":{\"match_all\":{}}}";

		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				() -> applyAutocompleteBody(json,
						EnumSet.of(SearchQueryPart.HITS)));
		assertTrue(ex.getMessage().contains("autocomplete"));
	}

	@Test
	public void testApplyAutocompleteBodyToRequestForcesSizeZeroWithoutHits() {
		String json = "{\"query\":{\"prefix\":{\"title\":\"alz\"}}}";

		SearchRequest req = applyAutocompleteBody(json,
				EnumSet.noneOf(SearchQueryPart.class));

		assertEquals(0, req.size().intValue(), "size forced to 0 without HITS");
	}

	// ===================== integer-parser boundaries =====================

	@Test
	public void testApplyBodyToRequestWithNegativeFromRejected() {
		String json = "{\"query\":{\"match_all\":{}},\"from\":-1}";

		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				() -> applyBody(json,
						EnumSet.of(SearchQueryPart.HITS)));
		assertTrue(ex.getMessage().contains("from"));
	}

	@Test
	public void testApplyBodyToRequestWithFromAboveIntegerMaxRejected() {
		String json = "{\"query\":{\"match_all\":{}},\"from\":2147483648}";

		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				() -> applyBody(json,
						EnumSet.of(SearchQueryPart.HITS)));
		assertTrue(ex.getMessage().contains("from"));
	}

	@Test
	public void testApplyBodyToRequestWithNonIntegralFromRejected() {
		String json = "{\"query\":{\"match_all\":{}},\"from\":\"five\"}";

		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				() -> applyBody(json,
						EnumSet.of(SearchQueryPart.HITS)));
		assertTrue(ex.getMessage().contains("from"));
	}

	@Test
	public void testApplyBodyToRequestWithNegativeSizeRejected() {
		String json = "{\"query\":{\"match_all\":{}},\"size\":-1}";

		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				() -> applyBody(json,
						EnumSet.of(SearchQueryPart.HITS)));
		assertTrue(ex.getMessage().contains("size"));
	}

	@Test
	public void testApplyBodyToRequestWithNonIntegralSizeRejected() {
		String json = "{\"query\":{\"match_all\":{}},\"size\":\"ten\"}";

		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				() -> applyBody(json,
						EnumSet.of(SearchQueryPart.HITS)));
		assertTrue(ex.getMessage().contains("size"));
	}

	@Test
	public void testApplyBodyToRequestWithNonArraySearchAfterRejected() {
		String json = "{\"query\":{\"match_all\":{}},\"search_after\":\"abc\"}";

		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				() -> applyBody(json,
						EnumSet.of(SearchQueryPart.HITS)));
		assertTrue(ex.getMessage().contains("search_after"));
	}

	@Test
	public void testApplyBodyToRequestWithNullFromAndSizeUsesDefaults() {
		// Explicit null on from/size goes through the !node.isNull() branch as a no-op.
		String json = "{\"query\":{\"match_all\":{}},\"from\":null,\"size\":null}";

		SearchRequest req = applyBody(json,
				EnumSet.of(SearchQueryPart.HITS));

		assertEquals(0, req.from().intValue(), "null from → 0");
		assertEquals(APPLY_DEFAULT_SIZE, req.size().intValue(), "null size → defaultSize");
	}

	// ===================== JSONObjectAdapter inline analyzer =====================

	@Test
	public void testToInlineAnalyzerSettingsWithJSONObjectAdapter() throws Exception {
		// JSONObjectAdapter is the wire-deserialized shape that controllers receive after JSON
		// binding for opaque-Object slots; verify the analyzer-settings path accepts it the
		// same way it accepts a Map / JSONObject.
		JSONObjectAdapter adapter = new JSONObjectAdapterImpl(
				"{\"analyzer\":{\"default\":{\"type\":\"custom\",\"tokenizer\":\"standard\"}}}");

		// call under test
		IndexSettingsAnalysis settings = SearchOpaqueJsonUtil.toInlineAnalyzerSettings(adapter, qname -> null);

		assertNotNull(settings);
		assertNotNull(settings.analyzer().get("default"));
	}

	@Test
	public void testCollectRefsDeduplicatesAcrossNestedObjects() {
		// Same qname appearing under multiple branches must be returned once, in walk order.
		JsonNode root = SearchOpaqueJsonUtil.parse(
				"{\"a\":{\"$ref\":\"org.sagebionetworks-X\"},"
						+ "\"b\":{\"c\":{\"$ref\":\"org.sagebionetworks-X\"}},"
						+ "\"d\":{\"$ref\":\"org.sagebionetworks-Y\"}}");

		// call under test
		Set<String> refs = SearchOpaqueJsonUtil.collectRefs(root);

		assertEquals(2, refs.size(), "duplicates across branches collapse");
		assertTrue(refs.contains("org.sagebionetworks-X"));
		assertTrue(refs.contains("org.sagebionetworks-Y"));
	}
}
