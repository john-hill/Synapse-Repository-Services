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

import org.opensearch.client.opensearch._types.analysis.TokenFilter;
import org.opensearch.client.opensearch._types.analysis.TokenFilterDefinition;
import org.opensearch.client.opensearch.indices.IndexSettingsAnalysis;

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
		IndexSettingsAnalysis resolved = SearchAnalyzerJson.resolveRefs(
				SearchAnalyzerJson.parse(json), resolver);

		// The resolved tree deserializes through the OpenSearch Java client, so the substituted
		// SynonymSet definition lands as a typed TokenFilter under filter.med_syn.
		TokenFilter med = resolved.filter().get("med_syn");
		assertNotNull(med);
		TokenFilterDefinition def = med.definition();
		assertNotNull(def);
		assertTrue(def.isSynonymGraph(), "med_syn must deserialize as the synonym_graph variant");
	}

	@Test
	public void testResolveRefsLeavesNonRefContentUntouched() throws Exception {
		String json = "{\"analyzer\":{\"default\":{\"type\":\"custom\",\"tokenizer\":\"standard\","
				+ "\"filter\":[\"lowercase\",\"my_filter\"]}},"
				+ "\"filter\":{\"my_filter\":{\"$ref\":\"org-X\"}}}";
		JsonNode resolverDef = MAPPER.readTree("{\"type\":\"stop\",\"stopwords\":\"_english_\"}");

		// call under test
		IndexSettingsAnalysis resolved = SearchAnalyzerJson.resolveRefs(
				SearchAnalyzerJson.parse(json),
				qname -> "org-X".equals(qname) ? resolverDef : null);

		// my_filter resolves to a typed stop filter
		TokenFilterDefinition myFilterDef = resolved.filter().get("my_filter").definition();
		assertNotNull(myFilterDef);
		assertTrue(myFilterDef.isStop(), "my_filter must deserialize as the stop variant");
		// analyzer.default chain is preserved verbatim — no rewrite happens at this layer
		assertTrue(resolved.analyzer().get("default").isCustom());
		assertEquals("standard", resolved.analyzer().get("default").custom().tokenizer());
		assertEquals(Arrays.asList("lowercase", "my_filter"),
				resolved.analyzer().get("default").custom().filter());
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
		IndexSettingsAnalysis resolved = SearchAnalyzerJson.resolveRefs(
				SearchAnalyzerJson.parse(json),
				qname -> "biomed-medical_terms".equals(qname) ? synonymDef : null);

		assertTrue(resolved.filter().get("english_stop").definition().isStop());
		assertTrue(resolved.filter().get("med_syn").definition().isSynonymGraph());
	}

	@Test
	public void testResolveRefsWithNoRefsReturnsTypedAnalysis() throws Exception {
		String json = "{\"analyzer\":{\"default\":{\"type\":\"custom\",\"tokenizer\":\"standard\"}}}";

		// call under test
		IndexSettingsAnalysis resolved = SearchAnalyzerJson.resolveRefs(
				SearchAnalyzerJson.parse(json), qname -> null);

		assertTrue(resolved.analyzer().get("default").isCustom());
		assertEquals("standard", resolved.analyzer().get("default").custom().tokenizer());
	}
}
