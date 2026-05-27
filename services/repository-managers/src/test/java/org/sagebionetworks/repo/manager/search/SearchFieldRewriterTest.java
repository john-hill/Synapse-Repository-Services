package org.sagebionetworks.repo.manager.search;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import org.junit.jupiter.api.Test;
import org.sagebionetworks.repo.manager.search.SearchFieldRewriter.RoutingContext;
import org.sagebionetworks.repo.manager.search.SearchFieldRewriter.RoutingMode;
import org.sagebionetworks.repo.manager.search.SearchFieldRewriter.Surface;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public class SearchFieldRewriterTest {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private static final Map<String, String> NAME_TO_ID = new LinkedHashMap<>();
	static {
		NAME_TO_ID.put("title", "100");
		NAME_TO_ID.put("name", "101");
		NAME_TO_ID.put("count", "102");
	}
	private static final Function<String, String> RESOLVE = NAME_TO_ID::get;

	private static final Map<String, String> ID_TO_NAME = new LinkedHashMap<>();
	static {
		ID_TO_NAME.put("100", "title");
		ID_TO_NAME.put("101", "name");
		ID_TO_NAME.put("102", "count");
	}
	private static final Function<String, String> REVERSE = ID_TO_NAME::get;

	private static JsonNode parse(String json) throws JsonProcessingException {
		return MAPPER.readTree(json);
	}

	// -----------------------------------------------------------------------------
	// rewriteFieldRef — pure textual handling
	// -----------------------------------------------------------------------------

	@Test
	public void testRewriteFieldRefWithKnownName() {
		assertEquals("100", SearchFieldRewriter.rewriteFieldRef("title", RESOLVE));
	}

	@Test
	public void testRewriteFieldRefWithKeywordSubField() {
		assertEquals("100.keyword", SearchFieldRewriter.rewriteFieldRef("title.keyword", RESOLVE));
	}

	@Test
	public void testRewriteFieldRefWithBoost() {
		assertEquals("100^3", SearchFieldRewriter.rewriteFieldRef("title^3", RESOLVE));
	}

	@Test
	public void testRewriteFieldRefWithKeywordAndBoost() {
		assertEquals("100.keyword^2",
				SearchFieldRewriter.rewriteFieldRef("title.keyword^2", RESOLVE));
	}

	@Test
	public void testRewriteFieldRefWithUnknownNamePassesThrough() {
		// Unknown names go to AOSS as-is so the error message surfaces the typo.
		assertEquals("ghost.keyword",
				SearchFieldRewriter.rewriteFieldRef("ghost.keyword", RESOLVE));
	}

	@Test
	public void testRewriteFieldRefWithUnrecognizedSubFieldPassesThrough() {
		// Only ".keyword" is recognized as a sub-field selector; anything else is part of
		// the column name (which must include the dot literally).
		assertEquals("title.searchable",
				SearchFieldRewriter.rewriteFieldRef("title.searchable", RESOLVE));
	}

	@Test
	public void testRewriteFieldRefWithNullReturnsNull() {
		assertEquals(null, SearchFieldRewriter.rewriteFieldRef(null, RESOLVE));
	}

	// -----------------------------------------------------------------------------
	// rewriteRequestFields — JsonNode tree mutation
	// -----------------------------------------------------------------------------

	@Test
	public void testRewriteRequestFieldsWithShorthandMatch() throws IOException {
		// Shorthand form: the inner object's single key IS the field name.
		JsonNode dsl = parse("{\"match\":{\"title\":\"amyloid\"}}");

		// call under test
		SearchFieldRewriter.rewriteRequestFields(dsl, RESOLVE);

		assertEquals("amyloid", dsl.get("match").get("100").asText());
		assertEquals(1, dsl.get("match").size());
	}

	@Test
	public void testRewriteRequestFieldsWithShorthandRangeKeepsValueObject() throws IOException {
		// Shorthand range with a nested operator object as the value.
		JsonNode dsl = parse("{\"range\":{\"count\":{\"gte\":1,\"lt\":10}}}");

		// call under test
		SearchFieldRewriter.rewriteRequestFields(dsl, RESOLVE);

		JsonNode rangeBody = dsl.get("range").get("102");
		assertEquals(1, rangeBody.get("gte").asInt());
		assertEquals(10, rangeBody.get("lt").asInt());
	}

	@Test
	public void testRewriteRequestFieldsWithShorthandUnknownNamePassesThrough() throws IOException {
		JsonNode dsl = parse("{\"term\":{\"ghost\":\"x\"}}");

		// call under test
		SearchFieldRewriter.rewriteRequestFields(dsl, RESOLVE);

		assertEquals("x", dsl.get("term").get("ghost").asText());
	}

	@Test
	public void testRewriteRequestFieldsWithShorthandKeywordSuffix() throws IOException {
		JsonNode dsl = parse("{\"term\":{\"title.keyword\":\"x\"}}");

		// call under test
		SearchFieldRewriter.rewriteRequestFields(dsl, RESOLVE);

		assertEquals("x", dsl.get("term").get("100.keyword").asText());
	}

	@Test
	public void testRewriteRequestFieldsWithLongFormFieldStillWorks() throws IOException {
		// Long-form with explicit "field" property — leaf rule rewrites the value, shorthand
		// rule sees the literal key "field" and skips it.
		JsonNode dsl = parse("{\"match\":{\"field\":\"title\",\"query\":\"a\"}}");

		// call under test
		SearchFieldRewriter.rewriteRequestFields(dsl, RESOLVE);

		assertEquals("100", dsl.get("match").get("field").asText());
		assertEquals("a", dsl.get("match").get("query").asText());
	}

	@Test
	public void testRewriteRequestFieldsWithLeafField() throws IOException {
		JsonNode dsl = parse("{\"match\":{\"field\":\"title\",\"query\":\"hi\"}}");

		// call under test
		SearchFieldRewriter.rewriteRequestFields(dsl, RESOLVE);

		assertEquals("100", dsl.get("match").get("field").asText());
		assertEquals("hi", dsl.get("match").get("query").asText());
	}

	@Test
	public void testRewriteRequestFieldsWithUnknownLeafFieldPassesThrough() throws IOException {
		JsonNode dsl = parse("{\"match\":{\"field\":\"ghost\",\"query\":\"hi\"}}");

		// call under test
		SearchFieldRewriter.rewriteRequestFields(dsl, RESOLVE);

		assertEquals("ghost", dsl.get("match").get("field").asText());
	}

	@Test
	public void testRewriteRequestFieldsWithKeywordAndBoostSuffixes() throws IOException {
		JsonNode dsl = parse("{\"term\":{\"field\":\"title.keyword\"}}");

		// call under test
		SearchFieldRewriter.rewriteRequestFields(dsl, RESOLVE);

		assertEquals("100.keyword", dsl.get("term").get("field").asText());
	}

	@Test
	public void testRewriteRequestFieldsWithFieldsArray() throws IOException {
		JsonNode dsl = parse("{\"multi_match\":{\"query\":\"hello\","
				+ "\"fields\":[\"title^3\",\"name.keyword\",\"ghost\"],"
				+ "\"max_expansions\":20}}");

		// call under test
		SearchFieldRewriter.rewriteRequestFields(dsl, RESOLVE);

		JsonNode mm = dsl.get("multi_match");
		assertEquals("100^3", mm.get("fields").get(0).asText());
		assertEquals("101.keyword", mm.get("fields").get(1).asText());
		// Unknown name passes through unchanged.
		assertEquals("ghost", mm.get("fields").get(2).asText());
		// Sibling properties are untouched.
		assertEquals("hello", mm.get("query").asText());
		assertEquals(20, mm.get("max_expansions").asInt());
	}

	@Test
	public void testRewriteRequestFieldsWithDeepNesting() throws IOException {
		JsonNode dsl = parse("{\"bool\":{"
				+ "\"must\":[{\"match\":{\"field\":\"title\",\"query\":\"a\"}}],"
				+ "\"filter\":[{\"term\":{\"field\":\"name.keyword\"}}],"
				+ "\"should\":[{\"dis_max\":{\"queries\":["
				+ "  {\"range\":{\"field\":\"count\",\"gt\":1}},"
				+ "  {\"exists\":{\"field\":\"title\"}}"
				+ "]}}]"
				+ "}}");

		// call under test
		SearchFieldRewriter.rewriteRequestFields(dsl, RESOLVE);

		JsonNode bool = dsl.get("bool");
		assertEquals("100", bool.get("must").get(0).get("match").get("field").asText());
		assertEquals("101.keyword", bool.get("filter").get(0).get("term").get("field").asText());
		JsonNode disMaxQs = bool.get("should").get(0).get("dis_max").get("queries");
		assertEquals("102", disMaxQs.get(0).get("range").get("field").asText());
		assertEquals("100", disMaxQs.get(1).get("exists").get("field").asText());
	}

	@Test
	public void testRewriteRequestFieldsWithAggregationSubAggs() throws IOException {
		JsonNode dsl = parse("{\"by_title\":{"
				+ "\"terms\":{\"field\":\"title\"},"
				+ "\"aggregations\":{\"inner\":{\"avg\":{\"field\":\"count\"}}}"
				+ "}}");

		// call under test
		SearchFieldRewriter.rewriteRequestFields(dsl, RESOLVE);

		JsonNode outer = dsl.get("by_title");
		assertEquals("100", outer.get("terms").get("field").asText());
		assertEquals("102",
				outer.get("aggregations").get("inner").get("avg").get("field").asText());
	}

	@Test
	public void testRewriteRequestFieldsWithSuggester() throws IOException {
		JsonNode dsl = parse("{\"text\":\"hi\",\"suggesters\":{"
				+ "\"s_term\":{\"text\":\"hi\",\"term\":{\"field\":\"title\"}},"
				+ "\"s_phrase\":{\"text\":\"hi\",\"phrase\":{\"field\":\"name\"}},"
				+ "\"s_completion\":{\"text\":\"hi\",\"completion\":{\"field\":\"count\"}}"
				+ "}}");

		// call under test
		SearchFieldRewriter.rewriteRequestFields(dsl, RESOLVE);

		JsonNode suggesters = dsl.get("suggesters");
		assertEquals("100", suggesters.get("s_term").get("term").get("field").asText());
		assertEquals("101", suggesters.get("s_phrase").get("phrase").get("field").asText());
		assertEquals("102", suggesters.get("s_completion").get("completion").get("field").asText());
	}

	@Test
	public void testRewriteRequestFieldsWithNullIsNoOp() {
		// call under test
		SearchFieldRewriter.rewriteRequestFields(null, RESOLVE);
	}

	@Test
	public void testRewriteRequestFieldsWithEmptyObjectIsNoOp() throws IOException {
		JsonNode dsl = parse("{}");

		// call under test
		SearchFieldRewriter.rewriteRequestFields(dsl, RESOLVE);

		assertEquals(0, dsl.size());
	}

	// -----------------------------------------------------------------------------
	// Parity coverage: every column-bearing surface across allowlisted kinds.
	// -----------------------------------------------------------------------------

	@Test
	public void testRewriteRequestFieldsWithEveryShorthandLeafKind() throws IOException {
		// One fixture per allowlisted shorthand-keyed leaf kind. Each carries the column
		// name as the inner object's single key; my walker must rewrite every one.
		JsonNode dsl = parse("{"
				+ "\"a\":{\"match\":{\"title\":\"x\"}},"
				+ "\"b\":{\"match_phrase\":{\"title\":\"x\"}},"
				+ "\"c\":{\"match_phrase_prefix\":{\"title\":\"x\"}},"
				+ "\"d\":{\"match_bool_prefix\":{\"title\":\"x\"}},"
				+ "\"e\":{\"term\":{\"title\":\"x\"}},"
				+ "\"f\":{\"terms\":{\"title\":[\"x\",\"y\"]}},"
				+ "\"g\":{\"range\":{\"count\":{\"gte\":1}}},"
				+ "\"h\":{\"prefix\":{\"title\":\"x\"}},"
				+ "\"i\":{\"wildcard\":{\"title\":\"x*y\"}},"
				+ "\"j\":{\"fuzzy\":{\"title\":\"x\"}}"
				+ "}");

		// call under test
		SearchFieldRewriter.rewriteRequestFields(dsl, RESOLVE);

		assertEquals("x", dsl.get("a").get("match").get("100").asText());
		assertEquals("x", dsl.get("b").get("match_phrase").get("100").asText());
		assertEquals("x", dsl.get("c").get("match_phrase_prefix").get("100").asText());
		assertEquals("x", dsl.get("d").get("match_bool_prefix").get("100").asText());
		assertEquals("x", dsl.get("e").get("term").get("100").asText());
		assertEquals(2, dsl.get("f").get("terms").get("100").size());
		assertEquals(1, dsl.get("g").get("range").get("102").get("gte").asInt());
		assertEquals("x", dsl.get("h").get("prefix").get("100").asText());
		assertEquals("x*y", dsl.get("i").get("wildcard").get("100").asText());
		assertEquals("x", dsl.get("j").get("fuzzy").get("100").asText());
	}

	@Test
	public void testRewriteRequestFieldsWithExistsLongFormOnly() throws IOException {
		// `exists` is long-form-only on the wire; only the `field` rule applies.
		JsonNode dsl = parse("{\"exists\":{\"field\":\"title\"}}");

		// call under test
		SearchFieldRewriter.rewriteRequestFields(dsl, RESOLVE);

		assertEquals("100", dsl.get("exists").get("field").asText());
	}

	@Test
	public void testRewriteRequestFieldsWithIdsHasNoColumnRef() throws IOException {
		// `ids.values` are document IDs, never column names — must pass through untouched.
		JsonNode dsl = parse("{\"ids\":{\"values\":[\"syn1\",\"syn2\"]}}");

		// call under test
		SearchFieldRewriter.rewriteRequestFields(dsl, RESOLVE);

		assertEquals("syn1", dsl.get("ids").get("values").get(0).asText());
		assertEquals("syn2", dsl.get("ids").get("values").get(1).asText());
	}

	@Test
	public void testRewriteRequestFieldsWithTermsAggOrderKeysLeftAlone() throws IOException {
		// `order` keys are pseudo-keys (_count / _key) or sub-agg names — never columns.
		// The walker must rewrite the explicit `field` and leave `order` keys verbatim,
		// even when an order key happens to collide with a known column name.
		JsonNode dsl = parse("{\"by_x\":{\"terms\":{"
				+ "\"field\":\"title\","
				+ "\"order\":{\"_count\":\"desc\",\"title\":\"asc\"}"
				+ "}}}");

		// call under test
		SearchFieldRewriter.rewriteRequestFields(dsl, RESOLVE);

		JsonNode terms = dsl.get("by_x").get("terms");
		assertEquals("100", terms.get("field").asText());
		assertEquals("desc", terms.get("order").get("_count").asText());
		assertEquals("asc", terms.get("order").get("title").asText());
	}

	@Test
	public void testRewriteRequestFieldsWithCallerNamedAggregationKeysLeftAlone() throws IOException {
		// Aggregation names are caller-chosen labels. Even if a caller names one "field"
		// or "fields", the walker must not treat the label as a column reference because
		// the value is an object/array of agg-definition shape, not a textual column ref.
		JsonNode dsl = parse("{\"field\":{\"terms\":{\"field\":\"title\"}},"
				+ "\"fields\":{\"avg\":{\"field\":\"count\"}}}");

		// call under test
		SearchFieldRewriter.rewriteRequestFields(dsl, RESOLVE);

		// Outer "field" / "fields" labels untouched.
		assertEquals(true, dsl.has("field"));
		assertEquals(true, dsl.has("fields"));
		// Inner column refs rewritten.
		assertEquals("100", dsl.get("field").get("terms").get("field").asText());
		assertEquals("102", dsl.get("fields").get("avg").get("field").asText());
	}

	@Test
	public void testRewriteRequestFieldsWithTermObjectValueShorthand() throws IOException {
		// `term` shorthand with a value object (e.g. {value: ..., boost: 2}).
		JsonNode dsl = parse("{\"term\":{\"title\":{\"value\":\"x\",\"boost\":2.0}}}");

		// call under test
		SearchFieldRewriter.rewriteRequestFields(dsl, RESOLVE);

		assertEquals("x", dsl.get("term").get("100").get("value").asText());
		assertEquals(2.0, dsl.get("term").get("100").get("boost").asDouble(), 0.0001);
	}

	@Test
	public void testRewriteRequestFieldsWithRangeAggregationLongFormNotMistakenForShorthand() throws IOException {
		// The literal "range" key also names an aggregation kind, but aggregations always
		// use long-form `field`. A `range` aggregation body like {field: "count", ranges: [...]}
		// has size > 1, so the shorthand handler skips it and rule 1 picks up the `field`.
		JsonNode dsl = parse("{\"price_buckets\":{\"range\":{"
				+ "\"field\":\"count\",\"ranges\":[{\"to\":10},{\"from\":10,\"to\":20}]"
				+ "}}}");

		// call under test
		SearchFieldRewriter.rewriteRequestFields(dsl, RESOLVE);

		assertEquals("102", dsl.get("price_buckets").get("range").get("field").asText());
		assertEquals(2, dsl.get("price_buckets").get("range").get("ranges").size());
	}

	@Test
	public void testRewriteRequestFieldsWithSingleFieldOnlyAggBodyDoesNotRewriteFieldKey() throws IOException {
		// Pathological: an aggregation body that happens to have only `field` — the shorthand
		// handler must still skip because the literal key is "field".
		JsonNode dsl = parse("{\"by_x\":{\"avg\":{\"field\":\"count\"}}}");

		// call under test
		SearchFieldRewriter.rewriteRequestFields(dsl, RESOLVE);

		// `field` key preserved, value rewritten via rule 1 — not via the shorthand rule.
		assertEquals("102", dsl.get("by_x").get("avg").get("field").asText());
	}

	@Test
	public void testRewriteRequestFieldsWithCompoundQueriesRecurseIntoLeaves() throws IOException {
		// bool / dis_max / constant_score / boosting all carry nested query slots that the
		// walker must descend into. Each slot uses a different key (must/should/queries/
		// filter/positive/negative); none of these are shorthand kinds, so the walker
		// recurses into them by the default branch.
		JsonNode dsl = parse("{\"bool\":{"
				+ "\"must\":[{\"match\":{\"title\":\"a\"}}],"
				+ "\"should\":[{\"dis_max\":{\"queries\":["
				+ "  {\"constant_score\":{\"filter\":{\"term\":{\"title\":\"x\"}}}},"
				+ "  {\"boosting\":{"
				+ "    \"positive\":{\"match\":{\"name\":\"y\"}},"
				+ "    \"negative\":{\"match\":{\"name\":\"z\"}},"
				+ "    \"negative_boost\":0.5}}"
				+ "]}}]"
				+ "}}");

		// call under test
		SearchFieldRewriter.rewriteRequestFields(dsl, RESOLVE);

		JsonNode bool = dsl.get("bool");
		assertEquals("a", bool.get("must").get(0).get("match").get("100").asText());
		JsonNode disMaxQs = bool.get("should").get(0).get("dis_max").get("queries");
		assertEquals("x", disMaxQs.get(0).get("constant_score").get("filter").get("term").get("100").asText());
		JsonNode boosting = disMaxQs.get(1).get("boosting");
		assertEquals("y", boosting.get("positive").get("match").get("101").asText());
		assertEquals("z", boosting.get("negative").get("match").get("101").asText());
	}

	// -----------------------------------------------------------------------------
	// rewriteAggregationResults — response side, column id → column name
	// -----------------------------------------------------------------------------

	@Test
	public void testRewriteAggregationResultsRewritesEmbeddedField() throws IOException {
		JsonNode response = parse("{\"by_title\":{\"buckets\":["
				+ "{\"key\":\"a\",\"doc_count\":3,\"avg_count\":{\"field\":\"102\",\"value\":1.5}}"
				+ "]}}");

		// call under test
		SearchFieldRewriter.rewriteAggregationResults(response, REVERSE);

		assertEquals("count", response.get("by_title").get("buckets").get(0)
				.get("avg_count").get("field").asText());
	}

	@Test
	public void testRewriteAggregationResultsLeavesAggregationKeysUnchanged() throws IOException {
		// Top-level keys are caller-chosen aggregation names, not column references.
		JsonNode response = parse("{\"100\":{\"value\":42,\"field\":\"100\"}}");

		// call under test
		SearchFieldRewriter.rewriteAggregationResults(response, REVERSE);

		// The top-level "100" key is left alone (it's a label); the embedded "field" is rewritten.
		assertEquals(true, response.has("100"));
		assertEquals("title", response.get("100").get("field").asText());
	}

	@Test
	public void testRewriteAggregationResultsWithUnmappedIdLeavesItAlone() throws IOException {
		JsonNode response = parse("{\"agg\":{\"field\":\"999\"}}");

		// call under test
		SearchFieldRewriter.rewriteAggregationResults(response, REVERSE);

		// idToName returned null; field is left as-is.
		assertEquals("999", response.get("agg").get("field").asText());
	}

	@Test
	public void testRewriteSuggestResultsDelegatesToAggregationWalk() throws IOException {
		JsonNode response = parse("{\"s\":[{\"options\":[{\"field\":\"100\",\"score\":1.0}]}]}");

		// call under test
		SearchFieldRewriter.rewriteSuggestResults(response, REVERSE);

		assertEquals("title", response.get("s").get(0).get("options").get(0).get("field").asText());
	}

	// -----------------------------------------------------------------------------
	// Round-trip: rewriteRequestFields + rewriteAggregationResults are inverses
	// -----------------------------------------------------------------------------

	@Test
	public void testRoundTripRequestThenResponseRestoresName() throws IOException {
		JsonNode dsl = parse("{\"terms\":{\"field\":\"title\"}}");

		// call under test
		SearchFieldRewriter.rewriteRequestFields(dsl, RESOLVE);
		assertEquals("100", dsl.get("terms").get("field").asText());

		SearchFieldRewriter.rewriteAggregationResults(dsl, REVERSE);
		assertEquals("title", dsl.get("terms").get("field").asText());
	}

	// -----------------------------------------------------------------------------
	// .keyword auto-routing — RoutingContext + Surface
	// -----------------------------------------------------------------------------

	/** title=100 (TEXT), name=101 (TEXT), count=102 (numeric), userId=103 (KEYWORD). */
	private static final Set<String> TEXT_IDS = new HashSet<>();
	static {
		TEXT_IDS.add("100");
		TEXT_IDS.add("101");
	}

	private static final RoutingContext ROUTING = new RoutingContext() {
		@Override public String mapName(String name) {
			return NAME_TO_ID.getOrDefault(name, name);
		}
		@Override public boolean isTextLike(String columnId) {
			return TEXT_IDS.contains(columnId);
		}
	};

	// rewriteFieldRef — explicit mode

	@Test
	public void testRewriteFieldRefWithKeywordModeOnTextColumnAppendsKeyword() {
		// call under test
		assertEquals("100.keyword",
				SearchFieldRewriter.rewriteFieldRef("title", ROUTING, RoutingMode.KEYWORD_FOR_TEXT));
	}

	@Test
	public void testRewriteFieldRefWithKeywordModeOnNumericColumnLeavesBare() {
		// count is not text-like — KEYWORD_FOR_TEXT must not append .keyword.
		// call under test
		assertEquals("102",
				SearchFieldRewriter.rewriteFieldRef("count", ROUTING, RoutingMode.KEYWORD_FOR_TEXT));
	}

	@Test
	public void testRewriteFieldRefWithKeywordModeIsIdempotentOnExplicitKeyword() {
		// Caller already wrote .keyword — auto-router must not double it.
		// call under test
		assertEquals("100.keyword",
				SearchFieldRewriter.rewriteFieldRef("title.keyword", ROUTING, RoutingMode.KEYWORD_FOR_TEXT));
	}

	@Test
	public void testRewriteFieldRefWithBareModeOnTextColumnLeavesBare() {
		// match-family routing must not append .keyword on text.
		// call under test
		assertEquals("100",
				SearchFieldRewriter.rewriteFieldRef("title", ROUTING, RoutingMode.BARE));
	}

	@Test
	public void testRewriteFieldRefWithKeywordModePreservesBoost() {
		// call under test
		assertEquals("100.keyword^3",
				SearchFieldRewriter.rewriteFieldRef("title^3", ROUTING, RoutingMode.KEYWORD_FOR_TEXT));
	}

	@Test
	public void testRewriteFieldRefWithUnknownNameAndKeywordModeNoAutoRoute() {
		// Without a resolved id we don't know the column type — must not auto-append.
		// call under test
		assertEquals("ghost",
				SearchFieldRewriter.rewriteFieldRef("ghost", ROUTING, RoutingMode.KEYWORD_FOR_TEXT));
	}

	// rewriteRequestFields — query surface

	@Test
	public void testRewriteRequestFieldsWithTermShorthandOnTextColumnRoutesKeyword() throws IOException {
		// term on a text column needs .keyword for exact match.
		JsonNode dsl = parse("{\"term\":{\"title\":\"My Project\"}}");

		// call under test
		SearchFieldRewriter.rewriteRequestFields(dsl, ROUTING, Surface.QUERY);

		assertEquals("My Project", dsl.get("term").get("100.keyword").asText());
	}

	@Test
	public void testRewriteRequestFieldsWithTermShorthandOnNumericColumnLeavesBare() throws IOException {
		JsonNode dsl = parse("{\"term\":{\"count\":7}}");

		// call under test
		SearchFieldRewriter.rewriteRequestFields(dsl, ROUTING, Surface.QUERY);

		assertEquals(7, dsl.get("term").get("102").asInt());
	}

	@Test
	public void testRewriteRequestFieldsWithMatchOnTextColumnLeavesBare() throws IOException {
		// match-family clauses always use the analyzed text field.
		JsonNode dsl = parse("{\"match\":{\"title\":\"amyloid\"}}");

		// call under test
		SearchFieldRewriter.rewriteRequestFields(dsl, ROUTING, Surface.QUERY);

		assertEquals("amyloid", dsl.get("match").get("100").asText());
	}

	@Test
	public void testRewriteRequestFieldsWithRangeOnTextColumnRoutesKeyword() throws IOException {
		JsonNode dsl = parse("{\"range\":{\"title\":{\"gte\":\"a\",\"lt\":\"m\"}}}");

		// call under test
		SearchFieldRewriter.rewriteRequestFields(dsl, ROUTING, Surface.QUERY);

		assertEquals("a", dsl.get("range").get("100.keyword").get("gte").asText());
	}

	@Test
	public void testRewriteRequestFieldsWithRangeOnNumericColumnLeavesBare() throws IOException {
		JsonNode dsl = parse("{\"range\":{\"count\":{\"gte\":1,\"lt\":10}}}");

		// call under test
		SearchFieldRewriter.rewriteRequestFields(dsl, ROUTING, Surface.QUERY);

		assertEquals(1, dsl.get("range").get("102").get("gte").asInt());
	}

	@Test
	public void testRewriteRequestFieldsWithPrefixWildcardFuzzyOnTextRouteKeyword() throws IOException {
		JsonNode dsl = parse("{"
				+ "\"a\":{\"prefix\":{\"title\":\"x\"}},"
				+ "\"b\":{\"wildcard\":{\"title\":\"x*y\"}},"
				+ "\"c\":{\"fuzzy\":{\"title\":\"x\"}},"
				+ "\"d\":{\"match_phrase_prefix\":{\"title\":\"x\"}}"
				+ "}");

		// call under test
		SearchFieldRewriter.rewriteRequestFields(dsl, ROUTING, Surface.QUERY);

		assertEquals("x", dsl.get("a").get("prefix").get("100.keyword").asText());
		assertEquals("x*y", dsl.get("b").get("wildcard").get("100.keyword").asText());
		assertEquals("x", dsl.get("c").get("fuzzy").get("100.keyword").asText());
		assertEquals("x", dsl.get("d").get("match_phrase_prefix").get("100.keyword").asText());
	}

	@Test
	public void testRewriteRequestFieldsWithMultiMatchOnTextLeavesBare() throws IOException {
		// multi_match is match-family — bare on every entry, even text columns.
		JsonNode dsl = parse("{\"multi_match\":{\"query\":\"hi\","
				+ "\"fields\":[\"title^3\",\"name\"]}}");

		// call under test
		SearchFieldRewriter.rewriteRequestFields(dsl, ROUTING, Surface.QUERY);

		assertEquals("100^3", dsl.get("multi_match").get("fields").get(0).asText());
		assertEquals("101", dsl.get("multi_match").get("fields").get(1).asText());
	}

	@Test
	public void testRewriteRequestFieldsWithIdsClauseUntouched() throws IOException {
		// `ids.values` are document IDs, never column names — auto-router must not attempt
		// to look them up nor route a sub-field.
		JsonNode dsl = parse("{\"ids\":{\"values\":[\"syn1\",\"syn2\"]}}");

		// call under test
		SearchFieldRewriter.rewriteRequestFields(dsl, ROUTING, Surface.QUERY);

		assertEquals("syn1", dsl.get("ids").get("values").get(0).asText());
		assertEquals("syn2", dsl.get("ids").get("values").get(1).asText());
	}

	@Test
	public void testRewriteRequestFieldsWithExistsOnTextLeavesBare() throws IOException {
		// exists works on either form; we route bare for stability.
		JsonNode dsl = parse("{\"exists\":{\"field\":\"title\"}}");

		// call under test
		SearchFieldRewriter.rewriteRequestFields(dsl, ROUTING, Surface.QUERY);

		assertEquals("100", dsl.get("exists").get("field").asText());
	}

	@Test
	public void testRewriteRequestFieldsWithBoolMixesRoutingByChildKind() throws IOException {
		// In the same bool: a match (bare) and a term (keyword) on the same text column.
		JsonNode dsl = parse("{\"bool\":{"
				+ "\"must\":[{\"match\":{\"title\":\"amyloid\"}}],"
				+ "\"filter\":[{\"term\":{\"title\":\"primary\"}}]"
				+ "}}");

		// call under test
		SearchFieldRewriter.rewriteRequestFields(dsl, ROUTING, Surface.QUERY);

		assertEquals("amyloid",
				dsl.get("bool").get("must").get(0).get("match").get("100").asText());
		assertEquals("primary",
				dsl.get("bool").get("filter").get(0).get("term").get("100.keyword").asText());
	}

	// rewriteRequestFields — aggregations surface (every kind routes for text)

	@Test
	public void testRewriteRequestFieldsWithEveryAggKindRoutesTextThroughKeyword() throws IOException {
		JsonNode dsl = parse("{"
				+ "\"a\":{\"terms\":{\"field\":\"title\"}},"
				+ "\"b\":{\"min\":{\"field\":\"title\"}},"
				+ "\"c\":{\"max\":{\"field\":\"title\"}},"
				+ "\"d\":{\"avg\":{\"field\":\"title\"}},"
				+ "\"e\":{\"sum\":{\"field\":\"title\"}},"
				+ "\"f\":{\"stats\":{\"field\":\"title\"}},"
				+ "\"g\":{\"extended_stats\":{\"field\":\"title\"}},"
				+ "\"h\":{\"value_count\":{\"field\":\"title\"}},"
				+ "\"i\":{\"cardinality\":{\"field\":\"title\"}},"
				+ "\"j\":{\"missing\":{\"field\":\"title\"}}"
				+ "}");

		// call under test
		SearchFieldRewriter.rewriteRequestFields(dsl, ROUTING, Surface.AGGREGATIONS);

		assertEquals("100.keyword", dsl.get("a").get("terms").get("field").asText());
		assertEquals("100.keyword", dsl.get("b").get("min").get("field").asText());
		assertEquals("100.keyword", dsl.get("c").get("max").get("field").asText());
		assertEquals("100.keyword", dsl.get("d").get("avg").get("field").asText());
		assertEquals("100.keyword", dsl.get("e").get("sum").get("field").asText());
		assertEquals("100.keyword", dsl.get("f").get("stats").get("field").asText());
		assertEquals("100.keyword", dsl.get("g").get("extended_stats").get("field").asText());
		assertEquals("100.keyword", dsl.get("h").get("value_count").get("field").asText());
		assertEquals("100.keyword", dsl.get("i").get("cardinality").get("field").asText());
		assertEquals("100.keyword", dsl.get("j").get("missing").get("field").asText());
	}

	@Test
	public void testRewriteRequestFieldsWithAggOnNumericColumnLeavesBare() throws IOException {
		JsonNode dsl = parse("{\"avg_count\":{\"avg\":{\"field\":\"count\"}}}");

		// call under test
		SearchFieldRewriter.rewriteRequestFields(dsl, ROUTING, Surface.AGGREGATIONS);

		assertEquals("102", dsl.get("avg_count").get("avg").get("field").asText());
	}

	@Test
	public void testRewriteRequestFieldsWithSubAggregationsRouteIndependently() throws IOException {
		JsonNode dsl = parse("{\"by_title\":{"
				+ "\"terms\":{\"field\":\"title\"},"
				+ "\"aggregations\":{\"avg_count\":{\"avg\":{\"field\":\"count\"}}}"
				+ "}}");

		// call under test
		SearchFieldRewriter.rewriteRequestFields(dsl, ROUTING, Surface.AGGREGATIONS);

		// Outer terms on text — routed.
		assertEquals("100.keyword", dsl.get("by_title").get("terms").get("field").asText());
		// Inner avg on numeric — bare.
		assertEquals("102",
				dsl.get("by_title").get("aggregations").get("avg_count").get("avg").get("field").asText());
	}

	// rewriteRequestFields — suggester surface (term/phrase use bare)

	@Test
	public void testRewriteRequestFieldsWithSuggesterOnTextLeavesBare() throws IOException {
		JsonNode dsl = parse("{\"text\":\"hi\",\"suggesters\":{"
				+ "\"s_term\":{\"text\":\"hi\",\"term\":{\"field\":\"title\"}},"
				+ "\"s_phrase\":{\"text\":\"hi\",\"phrase\":{\"field\":\"name\"}}"
				+ "}}");

		// call under test
		SearchFieldRewriter.rewriteRequestFields(dsl, ROUTING, Surface.SUGGESTER);

		assertEquals("100",
				dsl.get("suggesters").get("s_term").get("term").get("field").asText());
		assertEquals("101",
				dsl.get("suggesters").get("s_phrase").get("phrase").get("field").asText());
	}

	// Response side: .keyword stripped on the way out

	@Test
	public void testRewriteAggregationResultsStripsKeywordSuffix() throws IOException {
		// The server may have routed the request through .keyword; the response should echo
		// just the bare column name to the caller.
		JsonNode response = parse("{\"by_title\":{\"buckets\":["
				+ "{\"key\":\"a\",\"doc_count\":3,"
				+ "\"avg_count\":{\"field\":\"100.keyword\",\"value\":1.5}}"
				+ "]}}");

		// call under test
		SearchFieldRewriter.rewriteAggregationResults(response, REVERSE);

		assertEquals("title",
				response.get("by_title").get("buckets").get(0).get("avg_count").get("field").asText());
	}

	@Test
	public void testRewriteAggregationResultsBarePassesThroughUnchanged() throws IOException {
		// Numeric column response — no .keyword to strip, and the bare id resolves.
		JsonNode response = parse("{\"avg_count\":{\"field\":\"102\",\"value\":3.0}}");

		// call under test
		SearchFieldRewriter.rewriteAggregationResults(response, REVERSE);

		assertEquals("count", response.get("avg_count").get("field").asText());
	}

	// rewriteRequestFields — highlight surface (object-keyed `fields` map)

	@Test
	public void testRewriteRequestFieldsWithHighlightFieldsRewritesKeys() throws IOException {
		// highlight.fields is an object keyed by column name; each key must be rewritten
		// to its column id. The empty option blocks are kept as-is.
		JsonNode dsl = parse("{\"fields\":{\"title\":{},\"name\":{}}}");

		// call under test
		SearchFieldRewriter.rewriteRequestFields(dsl, ROUTING, Surface.HIGHLIGHT);

		// Both column names rewritten to their ids; option values preserved.
		assertEquals(true, dsl.get("fields").has("100"));
		assertEquals(true, dsl.get("fields").has("101"));
		assertEquals(false, dsl.get("fields").has("title"));
		assertEquals(false, dsl.get("fields").has("name"));
	}

	@Test
	public void testRewriteRequestFieldsWithHighlightFieldsLeavesBareForTextColumn() throws IOException {
		// Highlight uses the analyzed (bare) text field — no .keyword auto-routing.
		JsonNode dsl = parse("{\"fields\":{\"title\":{}}}");

		// call under test
		SearchFieldRewriter.rewriteRequestFields(dsl, ROUTING, Surface.HIGHLIGHT);

		assertEquals(true, dsl.get("fields").has("100"));
		// Must NOT be 100.keyword.
		assertEquals(false, dsl.get("fields").has("100.keyword"));
	}

	@Test
	public void testRewriteRequestFieldsWithHighlightQueryDescendsAsQuerySurface() throws IOException {
		// A nested highlight_query carries a Query subtree; the walker must switch surfaces
		// so the auto-router applies (term on text → .keyword).
		JsonNode dsl = parse("{\"fields\":{\"title\":{"
				+ "\"highlight_query\":{\"term\":{\"title\":\"abc\"}}"
				+ "}}}");

		// call under test
		SearchFieldRewriter.rewriteRequestFields(dsl, ROUTING, Surface.HIGHLIGHT);

		// title key rewritten to 100; the nested term on text must auto-route to .keyword.
		JsonNode innerTerm = dsl.get("fields").get("100").get("highlight_query").get("term");
		assertEquals("abc", innerTerm.get("100.keyword").asText());
	}

	@Test
	public void testRewriteRequestFieldsWithHighlightUnknownNamePassesThrough() throws IOException {
		// Unknown names go to AOSS as-is so the error message surfaces the typo.
		JsonNode dsl = parse("{\"fields\":{\"ghost\":{}}}");

		// call under test
		SearchFieldRewriter.rewriteRequestFields(dsl, ROUTING, Surface.HIGHLIGHT);

		assertEquals(true, dsl.get("fields").has("ghost"));
	}

	@Test
	public void testRoutedRoundTripRestoresBareName() throws IOException {
		// Caller writes the bare name; the request rewriter routes through .keyword; the
		// response rewriter strips .keyword and returns the bare name.
		JsonNode dsl = parse("{\"by_title\":{\"terms\":{\"field\":\"title\"}}}");

		// Request side
		SearchFieldRewriter.rewriteRequestFields(dsl, ROUTING, Surface.AGGREGATIONS);
		assertEquals("100.keyword", dsl.get("by_title").get("terms").get("field").asText());

		// Response side
		SearchFieldRewriter.rewriteAggregationResults(dsl, REVERSE);
		assertEquals("title", dsl.get("by_title").get("terms").get("field").asText());
	}
}
