package org.sagebionetworks.repo.manager.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Unit tests for {@link SearchFieldRewriter}, the JsonNode-tree column-name → column-id
 * rewriter that operates on caller-supplied opaque OpenSearch DSL payloads. Each test pins
 * a single clause type or response shape; the integration paths are covered by the
 * autowired tests against a live AOSS.
 */
public class SearchFieldRewriterTest {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private static final Map<String, String> NAME_TO_ID = Map.of(
			"title", "100",
			"abstract", "200",
			"year", "300",
			"score", "400");

	private static final Function<String, String> RESOLVE = name -> NAME_TO_ID.getOrDefault(name, name);

	private static JsonNode parse(String json) {
		try {
			return MAPPER.readTree(json);
		} catch (JsonProcessingException e) {
			throw new AssertionError("invalid test JSON: " + json, e);
		}
	}

	// ---------- query rewrites ----------

	@Test
	public void testRewriteQueryWithMatchClauseRewritesFieldKey() {
		JsonNode dsl = parse("{\"match\":{\"title\":\"amyloid\"}}");
		// call under test
		SearchFieldRewriter.rewriteQuery(dsl, RESOLVE);

		assertEquals("100", dsl.path("match").fields().next().getKey());
		assertEquals("amyloid", dsl.path("match").path("100").asText());
	}

	@Test
	public void testRewriteQueryWithMatchPreservesKeywordSubField() {
		JsonNode dsl = parse("{\"term\":{\"title.keyword\":\"alpha\"}}");
		// call under test — the .keyword sub-field selector survives the rewrite
		SearchFieldRewriter.rewriteQuery(dsl, RESOLVE);

		assertTrue(dsl.path("term").has("100.keyword"),
				"term clause must rewrite to 100.keyword: " + dsl);
	}

	@Test
	public void testRewriteQueryWithExistsClauseRewritesFieldString() {
		JsonNode dsl = parse("{\"exists\":{\"field\":\"abstract\"}}");
		// call under test
		SearchFieldRewriter.rewriteQuery(dsl, RESOLVE);

		assertEquals("200", dsl.path("exists").path("field").asText());
	}

	@Test
	public void testRewriteQueryWithMultiMatchRewritesFieldsArray() {
		JsonNode dsl = parse("{\"multi_match\":{\"query\":\"amyloid\","
				+ "\"fields\":[\"title^2\",\"abstract\"]}}");
		// call under test — boost suffix is preserved.
		SearchFieldRewriter.rewriteQuery(dsl, RESOLVE);

		JsonNode fields = dsl.path("multi_match").path("fields");
		assertEquals("100^2", fields.get(0).asText());
		assertEquals("200", fields.get(1).asText());
	}

	@Test
	public void testRewriteQueryWithBoolRecursesIntoMustAndFilter() {
		JsonNode dsl = parse("{\"bool\":{"
				+ "\"must\":[{\"match\":{\"title\":\"amyloid\"}}],"
				+ "\"filter\":[{\"range\":{\"year\":{\"gte\":2010}}}]}}");
		// call under test
		SearchFieldRewriter.rewriteQuery(dsl, RESOLVE);

		assertTrue(dsl.path("bool").path("must").get(0).path("match").has("100"));
		assertTrue(dsl.path("bool").path("filter").get(0).path("range").has("300"));
	}

	@Test
	public void testRewriteQueryWithUnknownFieldPassesThrough() {
		JsonNode dsl = parse("{\"match\":{\"unmapped\":\"x\"}}");
		// call under test
		SearchFieldRewriter.rewriteQuery(dsl, RESOLVE);

		// Unknown column names go to AOSS as-is so error messages surface the typo.
		assertTrue(dsl.path("match").has("unmapped"));
	}

	@Test
	public void testRewriteQueryWithMatchAllIsNoOp() {
		JsonNode dsl = parse("{\"match_all\":{}}");
		// call under test — no field references, nothing to rewrite.
		SearchFieldRewriter.rewriteQuery(dsl, RESOLVE);

		assertTrue(dsl.has("match_all"));
	}

	// ---------- aggregations rewrites ----------

	@Test
	public void testRewriteAggregationsRewritesFieldString() {
		JsonNode dsl = parse("{\"by_year\":{\"terms\":{\"field\":\"year\"}}}");
		// call under test
		SearchFieldRewriter.rewriteAggregations(dsl, RESOLVE);

		// The caller-chosen aggregation name (by_year) is a label, not a field reference;
		// only the inner `field` value is rewritten.
		assertEquals("300", dsl.path("by_year").path("terms").path("field").asText());
	}

	@Test
	public void testRewriteAggregationsRecursesIntoNestedAggs() {
		JsonNode dsl = parse("{\"by_year\":{\"terms\":{\"field\":\"year\"},"
				+ "\"aggs\":{\"avg_score\":{\"avg\":{\"field\":\"score\"}}}}}");
		// call under test
		SearchFieldRewriter.rewriteAggregations(dsl, RESOLVE);

		assertEquals("300", dsl.path("by_year").path("terms").path("field").asText());
		assertEquals("400", dsl.path("by_year").path("aggs").path("avg_score")
				.path("avg").path("field").asText());
	}

	// ---------- suggest rewrites ----------

	@Test
	public void testRewriteSuggestRewritesFieldStringInSuggesterDef() {
		JsonNode dsl = parse("{\"did_you_mean\":{\"text\":\"amiloid\","
				+ "\"term\":{\"field\":\"title\"}}}");
		// call under test
		SearchFieldRewriter.rewriteSuggest(dsl, RESOLVE);

		assertEquals("100", dsl.path("did_you_mean").path("term").path("field").asText());
	}

	@Test
	public void testRewriteSuggestSkipsTopLevelText() {
		// A top-level "text" applies to every suggestion — it's not itself a suggester
		// definition, so the rewriter must not descend into it.
		JsonNode dsl = parse("{\"text\":\"amiloid\","
				+ "\"did_you_mean\":{\"term\":{\"field\":\"title\"}}}");
		// call under test
		SearchFieldRewriter.rewriteSuggest(dsl, RESOLVE);

		assertEquals("amiloid", dsl.path("text").asText());
		assertEquals("100", dsl.path("did_you_mean").path("term").path("field").asText());
	}

	// ---------- response-side rewrites ----------

	@Test
	public void testRewriteAggregationResultsRewritesFieldStringInverse() {
		// AOSS returns the field reference as the column id; rewrite it back to the name.
		JsonNode response = parse("{\"by_year\":{\"meta\":{\"field\":\"300\"},"
				+ "\"buckets\":[]}}");
		// call under test — the inverse function is column-id → column-name.
		Map<String, String> idToName = Map.of("300", "year");
		SearchFieldRewriter.rewriteAggregationResults(response, id -> idToName.getOrDefault(id, id));

		assertEquals("year", response.path("by_year").path("meta").path("field").asText());
	}

	@Test
	public void testRewriteAggregationResultsLeavesOtherKeysAlone() {
		// Only the `field` string is treated as a field reference; other ids in the response
		// (`key`, `doc_count`, etc.) are values, not references.
		JsonNode response = parse("{\"by_year\":{\"buckets\":["
				+ "{\"key\":\"300\",\"doc_count\":5}]}}");
		Map<String, String> idToName = Map.of("300", "year");
		// call under test
		SearchFieldRewriter.rewriteAggregationResults(response, id -> idToName.getOrDefault(id, id));

		// "300" appears here as a bucket key (the value of the `year` column), not a field ref;
		// it must NOT be rewritten.
		assertEquals("300", response.path("by_year").path("buckets").get(0).path("key").asText());
	}

	// ---------- low-level helpers ----------

	@Test
	public void testRewriteFieldRefStripsAndReappliesKeywordSuffix() {
		assertEquals("100.keyword", SearchFieldRewriter.rewriteFieldRef("title.keyword", RESOLVE));
	}

	@Test
	public void testRewriteFieldRefStripsAndReappliesBoost() {
		assertEquals("100^3", SearchFieldRewriter.rewriteFieldRef("title^3", RESOLVE));
	}

	@Test
	public void testRewriteFieldRefHandlesKeywordPlusBoost() {
		assertEquals("100.keyword^2",
				SearchFieldRewriter.rewriteFieldRef("title.keyword^2", RESOLVE));
	}

	@Test
	public void testRewriteFieldRefPassesThroughUnknownColumn() {
		assertEquals("ghost.keyword", SearchFieldRewriter.rewriteFieldRef("ghost.keyword", RESOLVE));
	}

	@Test
	public void testRewriteFieldRefPassesThroughForeignSubField() {
		// Only `.keyword` is a recognized sub-field selector. Anything else — including the
		// historical `.searchable` from pre-DSL highlight code — is treated as part of the
		// column name (and therefore stays unmapped).
		String raw = "title.unknown_subfield";
		// call under test
		String result = SearchFieldRewriter.rewriteFieldRef(raw, RESOLVE);
		// "title.unknown_subfield" has no `nameToId` mapping → unchanged.
		assertEquals(raw, result);
		assertFalse(result.contains("100"));
	}

	@Test
	public void testRewriteFieldRefDoesNotRecognizeSearchableSuffix() {
		// `.searchable` was an internal sub-field of the legacy highlight code path. With the
		// DSL-pass-through query API in PLFM-9682 highlighting is deferred (PLFM-9683) and
		// nothing in the index emits .searchable any more, so the rewriter must not treat
		// it as a sub-field selector — `title.searchable` reads as a (nonexistent) column
		// name and passes through unmapped.
		String result = SearchFieldRewriter.rewriteFieldRef("title.searchable", RESOLVE);
		assertEquals("title.searchable", result);
	}
}
