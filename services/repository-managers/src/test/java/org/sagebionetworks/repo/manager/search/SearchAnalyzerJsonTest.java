package org.sagebionetworks.repo.manager.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Unit tests for {@link SearchAnalyzerJson}: the opaque-JSON parse / collectRefs /
 * resolveRefs surface that owns the TextAnalyzer settings contract.
 */
public class SearchAnalyzerJsonTest {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	@Test
	public void testParseWithValidJson() {
		String json = "{\"analyzer\":{\"default\":{\"type\":\"custom\",\"tokenizer\":\"standard\"}}}";

		// call under test
		JsonNode root = SearchAnalyzerJson.parse(json);

		assertNotNull(root);
		assertEquals("standard", root.at("/analyzer/default/tokenizer").asText());
	}

	@Test
	public void testParseWithMalformedJsonThrows() {
		// call under test
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
				() -> SearchAnalyzerJson.parse("{not valid"));
		assertTrue(e.getMessage().startsWith("Invalid JSON"),
				"Error must surface a user-facing message: " + e.getMessage());
	}

	@Test
	public void testParseWithNullThrows() {
		// call under test
		assertThrows(IllegalArgumentException.class, () -> SearchAnalyzerJson.parse(null));
	}

	@Test
	public void testCollectRefsReturnsAllRefsInWalkOrderWithoutDuplicates() {
		String json = "{"
				+ "\"filter\":{"
				+ "\"a_syn\":{\"$ref\":\"org-A\"},"
				+ "\"b_syn\":{\"$ref\":\"org-B\"},"
				+ "\"c_syn\":{\"$ref\":\"org-A\"}"
				+ "}}";

		// call under test
		Set<String> refs = SearchAnalyzerJson.collectRefs(SearchAnalyzerJson.parse(json));

		assertEquals(new LinkedHashSet<>(Arrays.asList("org-A", "org-B")), refs);
	}

	@Test
	public void testCollectRefsWithNestedRefs() {
		// $ref inside a nested object is also picked up.
		String json = "{\"outer\":{\"inner\":{\"$ref\":\"org-Nested\"}}}";

		// call under test
		Set<String> refs = SearchAnalyzerJson.collectRefs(SearchAnalyzerJson.parse(json));

		assertEquals(new LinkedHashSet<>(Arrays.asList("org-Nested")), refs);
	}

	@Test
	public void testCollectRefsReturnsEmptyWhenNoRefs() {
		String json = "{\"analyzer\":{\"default\":{\"type\":\"custom\",\"tokenizer\":\"standard\"}}}";

		// call under test
		Set<String> refs = SearchAnalyzerJson.collectRefs(SearchAnalyzerJson.parse(json));

		assertTrue(refs.isEmpty());
	}

	@Test
	public void testCollectRefsIgnoresStringValuesInsideArrays() {
		// Strings inside chain arrays look textually similar to qnames but must NOT be treated
		// as refs — only object nodes with a single "$ref" key are refs.
		String json = "{"
				+ "\"analyzer\":{\"default\":{\"type\":\"custom\",\"tokenizer\":\"standard\","
				+ "\"filter\":[\"lowercase\",\"org-pretend\"]}}}";

		// call under test
		Set<String> refs = SearchAnalyzerJson.collectRefs(SearchAnalyzerJson.parse(json));

		assertTrue(refs.isEmpty(), "chain-array strings must not be picked up as refs: " + refs);
	}

	@Test
	public void testResolveRefsSingleSubstitution() throws Exception {
		String json = "{\"filter\":{\"med_syn\":{\"$ref\":\"biomed-medical_terms\"}}}";
		JsonNode synonymDef = MAPPER.readTree("{\"type\":\"synonym_graph\",\"synonyms\":[\"a, b\"]}");
		Function<String, JsonNode> resolver = qname ->
				"biomed-medical_terms".equals(qname) ? synonymDef : null;

		// call under test
		JsonNode resolved = SearchAnalyzerJson.resolveRefs(SearchAnalyzerJson.parse(json), resolver);

		assertEquals(synonymDef, resolved.at("/filter/med_syn"));
	}

	@Test
	public void testResolveRefsLeavesNonRefContentUntouched() throws Exception {
		String json = "{\"analyzer\":{\"default\":{\"type\":\"custom\",\"tokenizer\":\"standard\","
				+ "\"filter\":[\"lowercase\",\"my_filter\"]}},"
				+ "\"filter\":{\"my_filter\":{\"$ref\":\"org-X\"}}}";
		JsonNode resolverDef = MAPPER.readTree("{\"type\":\"stop\",\"stopwords\":\"_english_\"}");

		// call under test
		JsonNode resolved = SearchAnalyzerJson.resolveRefs(SearchAnalyzerJson.parse(json),
				qname -> "org-X".equals(qname) ? resolverDef : null);

		assertEquals(resolverDef, resolved.at("/filter/my_filter"));
		assertEquals("standard", resolved.at("/analyzer/default/tokenizer").asText());
		// chain array unchanged
		assertEquals("lowercase", resolved.at("/analyzer/default/filter/0").asText());
		assertEquals("my_filter", resolved.at("/analyzer/default/filter/1").asText());
	}

	@Test
	public void testResolveRefsNestedRefResolvesTransitively() throws Exception {
		// Resolving a $ref whose target itself contains a $ref. Allowed (no cycle), and the
		// inner ref must be replaced too.
		String json = "{\"filter\":{\"outer\":{\"$ref\":\"org-Outer\"}}}";
		JsonNode outerDef = MAPPER.readTree("{\"$ref\":\"org-Inner\"}");
		JsonNode innerDef = MAPPER.readTree("{\"type\":\"stop\"}");
		Map<String, JsonNode> targets = new HashMap<>();
		targets.put("org-Outer", outerDef);
		targets.put("org-Inner", innerDef);

		// call under test
		JsonNode resolved = SearchAnalyzerJson.resolveRefs(SearchAnalyzerJson.parse(json),
				targets::get);

		assertEquals(innerDef, resolved.at("/filter/outer"));
	}

	@Test
	public void testResolveRefsMutuallyRecursiveCycleThrows() throws Exception {
		// org-A → org-B → org-A → ... — must be rejected.
		String json = "{\"filter\":{\"top\":{\"$ref\":\"org-A\"}}}";
		JsonNode aDef = MAPPER.readTree("{\"$ref\":\"org-B\"}");
		JsonNode bDef = MAPPER.readTree("{\"$ref\":\"org-A\"}");
		Map<String, JsonNode> targets = new HashMap<>();
		targets.put("org-A", aDef);
		targets.put("org-B", bDef);

		// call under test
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
				() -> SearchAnalyzerJson.resolveRefs(SearchAnalyzerJson.parse(json),
						targets::get));

		assertTrue(e.getMessage().contains("Circular $ref"));
	}

	@Test
	public void testResolveRefsMissingTargetThrows() {
		String json = "{\"filter\":{\"ghost\":{\"$ref\":\"org-Ghost\"}}}";

		// call under test
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
				() -> SearchAnalyzerJson.resolveRefs(SearchAnalyzerJson.parse(json), qname -> null));

		assertTrue(e.getMessage().contains("Unresolved $ref"));
		assertTrue(e.getMessage().contains("org-Ghost"));
	}

	@Test
	public void testResolveRefsUnresolvedNestedIncludesJsonPointer() {
		// $ref two levels deep — error must name the JSON-pointer path so the curator can
		// locate it without scanning the full settings tree.
		String json = "{\"filter\":{\"med_syn\":{\"$ref\":\"biomed-medical_terms\"}}}";

		// call under test
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
				() -> SearchAnalyzerJson.resolveRefs(SearchAnalyzerJson.parse(json), qname -> null));

		assertTrue(e.getMessage().contains("Unresolved $ref"));
		assertTrue(e.getMessage().contains("biomed-medical_terms"));
		assertTrue(e.getMessage().contains("/filter/med_syn"),
				"Error must include the JSON pointer to the offending $ref: " + e.getMessage());
	}

	@Test
	public void testResolveRefsCircularInsideArrayIncludesArrayIndex() throws Exception {
		// $ref nested inside an array element — the JSON pointer must use the array index
		// so a curator with several refs in the same chain knows which one cycled.
		String json = "{\"things\":[{\"name\":\"first\"},{\"$ref\":\"org-A\"}]}";
		JsonNode aDef = MAPPER.readTree("{\"$ref\":\"org-A\"}");

		// call under test
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
				() -> SearchAnalyzerJson.resolveRefs(SearchAnalyzerJson.parse(json),
						qname -> "org-A".equals(qname) ? aDef : null));

		assertTrue(e.getMessage().contains("Circular $ref"));
		assertTrue(e.getMessage().contains("org-A"));
		assertTrue(e.getMessage().contains("/things/1"),
				"Error must include the array index in the JSON pointer: " + e.getMessage());
	}

	@Test
	public void testResolveRefsWithNoRefsReturnsRootUnchanged() throws Exception {
		String json = "{\"analyzer\":{\"default\":{\"type\":\"custom\",\"tokenizer\":\"standard\"}}}";
		JsonNode root = SearchAnalyzerJson.parse(json);

		// call under test
		JsonNode resolved = SearchAnalyzerJson.resolveRefs(root, qname -> null);

		assertEquals(MAPPER.readTree(json), resolved);
	}
}
