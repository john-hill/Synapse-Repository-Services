package org.sagebionetworks.repo.manager.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.LinkedHashSet;
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
	public void testCollectRefsReturnsEmptyWhenNoRefs() {
		String json = "{\"analyzer\":{\"default\":{\"type\":\"custom\",\"tokenizer\":\"standard\"}}}";

		// call under test
		Set<String> refs = SearchAnalyzerJson.collectRefs(SearchAnalyzerJson.parse(json));

		assertTrue(refs.isEmpty());
	}

	@Test
	public void testCollectRefsIgnoresStringValuesInsideArrays() {
		// Strings inside chain arrays look textually similar to qnames but must NOT be treated
		// as refs — only object nodes with a "$ref" field are refs.
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
	public void testResolveRefsMissingTargetThrows() {
		String json = "{\"filter\":{\"ghost\":{\"$ref\":\"org-Ghost\"}}}";

		// call under test
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
				() -> SearchAnalyzerJson.resolveRefs(SearchAnalyzerJson.parse(json), qname -> null));

		assertTrue(e.getMessage().contains("Unresolved $ref"));
		assertTrue(e.getMessage().contains("org-Ghost"));
		assertTrue(e.getMessage().contains("/filter/ghost"),
				"Error must include the JSON pointer to the offending $ref: " + e.getMessage());
	}

	@Test
	public void testResolveRefsLeavesInlineFilterDefinitionsUntouched() throws Exception {
		// An inline filter definition (no $ref) sitting in the filter map must pass through.
		String json = "{\"filter\":{"
				+ "\"english_stop\":{\"type\":\"stop\",\"stopwords\":\"_english_\"},"
				+ "\"med_syn\":{\"$ref\":\"biomed-medical_terms\"}"
				+ "}}";
		JsonNode synonymDef = MAPPER.readTree("{\"type\":\"synonym_graph\",\"synonyms\":[\"a, b\"]}");

		// call under test
		JsonNode resolved = SearchAnalyzerJson.resolveRefs(SearchAnalyzerJson.parse(json),
				qname -> "biomed-medical_terms".equals(qname) ? synonymDef : null);

		assertEquals("stop", resolved.at("/filter/english_stop/type").asText());
		assertEquals(synonymDef, resolved.at("/filter/med_syn"));
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
