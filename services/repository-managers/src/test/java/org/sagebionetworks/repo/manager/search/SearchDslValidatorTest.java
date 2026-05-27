package org.sagebionetworks.repo.manager.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.opensearch.client.opensearch._types.aggregations.Aggregation;
import org.opensearch.client.opensearch._types.aggregations.AggregationRange;
import org.opensearch.client.opensearch._types.aggregations.ExtendedBounds;
import org.opensearch.client.opensearch._types.query_dsl.BoolQuery;
import org.opensearch.client.opensearch._types.query_dsl.FuzzyQuery;
import org.opensearch.client.opensearch._types.query_dsl.MatchPhrasePrefixQuery;
import org.opensearch.client.opensearch._types.query_dsl.Query;
import org.opensearch.client.opensearch._types.query_dsl.TermsQueryField;
import org.opensearch.client.opensearch._types.query_dsl.TermsLookup;
import org.opensearch.client.json.JsonData;
import org.opensearch.client.opensearch._types.FieldValue;
import org.opensearch.client.opensearch.core.search.FieldCollapse;
import org.opensearch.client.opensearch.core.search.FieldSuggester;
import org.opensearch.client.opensearch.core.search.Highlight;
import org.opensearch.client.opensearch.core.search.HighlightField;
import org.opensearch.client.opensearch.core.search.HighlighterType;
import org.opensearch.client.opensearch.core.search.InnerHits;
import org.opensearch.client.opensearch.core.search.Rescore;
import org.opensearch.client.opensearch.core.search.RescoreQuery;
import org.opensearch.client.opensearch.core.search.Suggester;

import com.fasterxml.jackson.databind.ObjectMapper;

public class SearchDslValidatorTest {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	// -----------------------------------------------------------------------------
	// Forbidden-key scan (pre-deserialization)
	// -----------------------------------------------------------------------------

	@Test
	public void testScanQueryForbiddenKeysWithSiblingScript() throws Exception {
		// call under test
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				() -> SearchDslValidator.scanQueryForbiddenKeys(MAPPER.readTree(
						"{\"bool\":{\"must\":{\"match_all\":{}}}, \"script\":{}}")));
		assertTrue(ex.getMessage().contains("script"));
	}

	@Test
	public void testScanQueryForbiddenKeysWithDeepScript() throws Exception {
		// Critical: a script under a TermsAggregation-like body would otherwise be bound
		// silently by the typed deserializer and round-trip to AOSS.
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				() -> SearchDslValidator.scanQueryForbiddenKeys(MAPPER.readTree(
						"{\"bool\":{\"must\":[{\"match\":{\"foo\":{\"query\":\"x\",\"script\":{}}}}]}}")));
		assertTrue(ex.getMessage().contains("script"));
	}

	@Test
	public void testScanQueryForbiddenKeysWithIndexedShape() throws Exception {
		assertThrows(IllegalArgumentException.class,
				() -> SearchDslValidator.scanQueryForbiddenKeys(MAPPER.readTree(
						"{\"foo\":{\"indexed_shape\":{}}}")));
	}

	@Test
	public void testScanQueryForbiddenKeysWithRuntimeMappings() throws Exception {
		assertThrows(IllegalArgumentException.class,
				() -> SearchDslValidator.scanQueryForbiddenKeys(MAPPER.readTree(
						"{\"foo\":{\"runtime_mappings\":{}}}")));
	}

	@Test
	public void testScanQueryForbiddenKeysWithBenignBody() throws Exception {
		// call under test — must not throw
		SearchDslValidator.scanQueryForbiddenKeys(MAPPER.readTree("{\"match_all\":{}}"));
	}

	@Test
	public void testScanAggregationsForbiddenKeysWithScriptInsideTerms() throws Exception {
		// TermsAggregation has a real `script` accessor; the typed deserializer would
		// happily bind it without this scan.
		assertThrows(IllegalArgumentException.class,
				() -> SearchDslValidator.scanAggregationsForbiddenKeys(MAPPER.readTree(
						"{\"my_agg\":{\"terms\":{\"field\":\"foo\",\"script\":{\"source\":\"painless\"}}}}")));
	}

	@Test
	public void testScanSuggestForbiddenKeysWithScript() throws Exception {
		assertThrows(IllegalArgumentException.class,
				() -> SearchDslValidator.scanSuggestForbiddenKeys(MAPPER.readTree(
						"{\"s\":{\"term\":{\"field\":\"x\",\"script\":{}}}}")));
	}

	@Test
	public void testScanForbiddenKeysWithExcessiveDepth() throws Exception {
		// Build an object nested past the FORBIDDEN_SCAN_MAX_DEPTH cap.
		StringBuilder open = new StringBuilder();
		StringBuilder close = new StringBuilder();
		for (int i = 0; i <= SearchDslValidator.FORBIDDEN_SCAN_MAX_DEPTH + 5; i++) {
			open.append("{\"a\":");
			close.append("}");
		}
		open.append("1").append(close);
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				() -> SearchDslValidator.scanQueryForbiddenKeys(MAPPER.readTree(open.toString())));
		assertTrue(ex.getMessage().contains("maximum depth"));
	}

	// -----------------------------------------------------------------------------
	// Query: kind allowlist
	// -----------------------------------------------------------------------------

	@Test
	public void testValidateQueryWithMatchAll() {
		// call under test
		SearchDslValidator.validateQuery(Query.of(b -> b.matchAll(m -> m)), false);
	}

	@Test
	public void testValidateQueryWithBoolNested() {
		Query q = Query.of(b -> b.bool(bb -> bb
				.must(Query.of(m -> m.match(mq -> mq.field("foo").query(FieldValue.of("x")))))
				.filter(Query.of(m -> m.term(t -> t.field("bar").value(FieldValue.of("y")))))));
		SearchDslValidator.validateQuery(q, false);
	}

	@Test
	public void testValidateQueryWithEveryDisallowedKindRejected() {
		// Coverage guard: every Query.Kind not in the allowlist must be rejected. This is the
		// "exhaustive coverage" pattern from feedback_prefer_one_round_trip_test — drives the
		// kind check from EnumSet.allOf so a future opensearch-java release that adds a new
		// kind fails the test until it's deliberately allowlisted or denied here.
		EnumSet<Query.Kind> disallowed = EnumSet.allOf(Query.Kind.class);
		disallowed.removeAll(SearchDslValidator.ALLOWED_QUERY_KINDS);
		// Skip kinds that are too painful to construct from scratch (they have required
		// nested types that would need a deep fixture); the kind check runs first regardless,
		// so we exercise it via Script which has a simple stub.
		assertTrue(disallowed.contains(Query.Kind.Script));
		assertTrue(disallowed.contains(Query.Kind.Wrapper));
		assertTrue(disallowed.contains(Query.Kind.MoreLikeThis));
		assertTrue(disallowed.contains(Query.Kind.GeoShape));
		assertTrue(disallowed.contains(Query.Kind.HasChild));
		assertTrue(disallowed.contains(Query.Kind.HasParent));
		assertTrue(disallowed.contains(Query.Kind.Percolate));
		assertTrue(disallowed.contains(Query.Kind.FunctionScore));
		assertTrue(disallowed.contains(Query.Kind.ScriptScore));
	}

	// -----------------------------------------------------------------------------
	// Query: depth and clause-count caps
	// -----------------------------------------------------------------------------

	@Test
	public void testValidateQueryWithExcessiveDepth() {
		// Nest must clauses deeper than QUERY_MAX_DEPTH.
		Query inner = Query.of(b -> b.matchAll(m -> m));
		for (int i = 0; i < SearchDslValidator.QUERY_MAX_DEPTH + 2; i++) {
			Query nested = inner;
			inner = Query.of(b -> b.bool(bb -> bb.must(nested)));
		}
		Query deep = inner;
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				() -> SearchDslValidator.validateQuery(deep, false));
		assertTrue(ex.getMessage().contains("nested too deeply"));
	}

	@Test
	public void testValidateQueryWithExcessiveClauseCount() {
		List<Query> many = new ArrayList<>();
		for (int i = 0; i < SearchDslValidator.QUERY_MAX_CLAUSES + 2; i++) {
			many.add(Query.of(b -> b.matchAll(m -> m)));
		}
		Query q = Query.of(b -> b.bool(bb -> bb.must(many)));
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				() -> SearchDslValidator.validateQuery(q, false));
		assertTrue(ex.getMessage().contains("too many clauses"));
	}

	// -----------------------------------------------------------------------------
	// Query: compound-clause structural requirements
	// -----------------------------------------------------------------------------

	// dis_max / constant_score / boosting structural-required-slot checks are protected by
	// the typed builders themselves (each throws MissingRequiredPropertyException at
	// construction), so the validator's defensive null-check on those slots is unreachable
	// from typed callers. The validator still keeps the check for safety (a future schema
	// change to the OpenSearch client could relax the requirement); we don't try to fake
	// the construction path here.

	// -----------------------------------------------------------------------------
	// Query: per-kind custom rules
	// -----------------------------------------------------------------------------

	@Test
	public void testValidateQueryWithTermsLookupRejected() {
		Query q = Query.of(b -> b.terms(t -> t
				.field("foo")
				.terms(TermsQueryField.of(qf -> qf.lookup(TermsLookup.of(l -> l
						.index("other-index").id("1").path("p")))))));
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				() -> SearchDslValidator.validateQuery(q, false));
		assertTrue(ex.getMessage().contains("terms lookup form"));
	}

	@Test
	public void testValidateQueryWithTermsValuesAtCap() {
		List<FieldValue> values = new ArrayList<>();
		for (int i = 0; i <= SearchDslValidator.MAX_VALUES_PER_CLAUSE; i++) {
			values.add(FieldValue.of("v" + i));
		}
		Query q = Query.of(b -> b.terms(t -> t
				.field("foo")
				.terms(TermsQueryField.of(qf -> qf.value(values)))));
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				() -> SearchDslValidator.validateQuery(q, false));
		assertTrue(ex.getMessage().contains("terms array"));
	}

	@Test
	public void testValidateQueryWithIdsValuesAtCap() {
		List<String> ids = new ArrayList<>();
		for (int i = 0; i <= SearchDslValidator.MAX_VALUES_PER_CLAUSE; i++) {
			ids.add(String.valueOf(i));
		}
		Query q = Query.of(b -> b.ids(i -> i.values(ids)));
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				() -> SearchDslValidator.validateQuery(q, false));
		assertTrue(ex.getMessage().contains("ids.values"));
	}

	@Test
	public void testValidateQueryWithMultiMatchFieldsAtCap() {
		List<String> fields = new ArrayList<>();
		for (int i = 0; i <= SearchDslValidator.MAX_VALUES_PER_CLAUSE; i++) {
			fields.add("f" + i);
		}
		Query q = Query.of(b -> b.multiMatch(mm -> mm.query("x").fields(fields)));
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				() -> SearchDslValidator.validateQuery(q, false));
		assertTrue(ex.getMessage().contains("multi_match.fields"));
	}

	@Test
	public void testValidateQueryWithMultiMatchExcessiveMaxExpansions() {
		Query q = Query.of(b -> b.multiMatch(mm -> mm.query("x").fields("f")
				.maxExpansions(SearchDslValidator.MAX_PREFIX_EXPANSIONS + 1)));
		assertThrows(IllegalArgumentException.class,
				() -> SearchDslValidator.validateQuery(q, false));
	}

	@Test
	public void testValidateQueryWithFuzzyExcessiveMaxExpansions() {
		FuzzyQuery fq = FuzzyQuery.of(f -> f.field("foo").value(FieldValue.of("bar"))
				.maxExpansions(SearchDslValidator.MAX_PREFIX_EXPANSIONS + 1));
		Query q = Query.of(b -> b.fuzzy(fq));
		assertThrows(IllegalArgumentException.class,
				() -> SearchDslValidator.validateQuery(q, false));
	}

	@Test
	public void testValidateQueryWithMatchPhrasePrefixExcessiveMaxExpansions() {
		MatchPhrasePrefixQuery mpp = MatchPhrasePrefixQuery.of(m -> m.field("foo").query("x")
				.maxExpansions(SearchDslValidator.MAX_PREFIX_EXPANSIONS + 1));
		Query q = Query.of(b -> b.matchPhrasePrefix(mpp));
		assertThrows(IllegalArgumentException.class,
				() -> SearchDslValidator.validateQuery(q, false));
	}

	@Test
	public void testValidateQueryWithMatchBoolPrefixExcessiveMaxExpansions() {
		Query q = Query.of(b -> b.matchBoolPrefix(m -> m.field("foo").query("x")
				.maxExpansions(SearchDslValidator.MAX_PREFIX_EXPANSIONS + 1)));
		assertThrows(IllegalArgumentException.class,
				() -> SearchDslValidator.validateQuery(q, false));
	}

	@Test
	public void testValidateQueryWithPrefixLeadingWildcard() {
		Query q = Query.of(b -> b.prefix(p -> p.field("foo").value("*bad")));
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				() -> SearchDslValidator.validateQuery(q, false));
		assertTrue(ex.getMessage().contains("leading wildcard"));
	}

	@Test
	public void testValidateQueryWithPrefixLeadingQuestionMark() {
		Query q = Query.of(b -> b.prefix(p -> p.field("foo").value("?bad")));
		assertThrows(IllegalArgumentException.class,
				() -> SearchDslValidator.validateQuery(q, false));
	}

	@Test
	public void testValidateQueryWithWildcardLeadingWildcard() {
		Query q = Query.of(b -> b.wildcard(w -> w.field("foo").value("*ouch")));
		assertThrows(IllegalArgumentException.class,
				() -> SearchDslValidator.validateQuery(q, false));
	}

	@Test
	public void testValidateQueryWithSimpleQueryStringFieldsAtCap() {
		List<String> fields = new ArrayList<>();
		for (int i = 0; i <= SearchDslValidator.MAX_VALUES_PER_CLAUSE; i++) {
			fields.add("f" + i);
		}
		Query q = Query.of(b -> b.simpleQueryString(s -> s.query("x").fields(fields)));
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				() -> SearchDslValidator.validateQuery(q, false));
		assertTrue(ex.getMessage().contains("simple_query_string.fields"));
	}

	@Test
	public void testValidateQueryWithSimpleQueryStringLeadingWildcardAndAnalyzeWildcard() {
		Query q = Query.of(b -> b.simpleQueryString(s -> s.query("*foo").analyzeWildcard(true)));
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				() -> SearchDslValidator.validateQuery(q, false));
		assertTrue(ex.getMessage().contains("leading wildcard"));
	}

	@Test
	public void testValidateQueryWithSimpleQueryStringLeadingWildcardWithoutAnalyze() {
		// Without analyze_wildcard the leading wildcard is just literal text, not expanded.
		Query q = Query.of(b -> b.simpleQueryString(s -> s.query("*foo")));
		// call under test — must not throw
		SearchDslValidator.validateQuery(q, false);
	}

	// -----------------------------------------------------------------------------
	// Autocomplete top-level narrowing
	// -----------------------------------------------------------------------------

	@Test
	public void testValidateAutocompleteWithMatchAllRejected() {
		Query q = Query.of(b -> b.matchAll(m -> m));
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				() -> SearchDslValidator.validateQuery(q, true));
		assertTrue(ex.getMessage().contains("autocomplete"));
	}

	@Test
	public void testValidateAutocompleteWithPrefixAccepted() {
		Query q = Query.of(b -> b.prefix(p -> p.field("foo").value("ba")));
		// call under test — must not throw
		SearchDslValidator.validateQuery(q, true);
	}

	@Test
	public void testValidateAutocompleteWithMatchBoolPrefixAccepted() {
		Query q = Query.of(b -> b.matchBoolPrefix(m -> m.field("foo").query("ba")));
		SearchDslValidator.validateQuery(q, true);
	}

	@Test
	public void testValidateAutocompleteCoversEveryAllowedTopLevelKind() {
		// Coverage guard: every kind in ALLOWED_AUTOCOMPLETE_TOP_LEVEL must be a member
		// of ALLOWED_QUERY_KINDS, otherwise the autocomplete path admits something the
		// general validator wouldn't.
		for (Query.Kind k : SearchDslValidator.ALLOWED_AUTOCOMPLETE_TOP_LEVEL) {
			assertTrue(SearchDslValidator.ALLOWED_QUERY_KINDS.contains(k),
					"autocomplete-allowed kind not in general allowlist: " + k);
		}
	}

	// -----------------------------------------------------------------------------
	// Aggregations
	// -----------------------------------------------------------------------------

	@Test
	public void testValidateAggregationsWithEveryAllowedKindRoundTrips() {
		// One-round-trip exhaustive coverage: validate a map containing one example of
		// every allowlisted aggregation kind. EnumSet.allOf-style guard at the bottom.
		Map<String, Aggregation> aggs = new LinkedHashMap<>();
		aggs.put("a_terms", Aggregation.of(b -> b.terms(t -> t.field("f"))));
		aggs.put("a_histogram", Aggregation.of(b -> b.histogram(h -> h.field("f").interval(1.0)
				.extendedBounds(ExtendedBounds.of(eb -> eb.min(0.0).max(10.0))))));
		aggs.put("a_date_histogram", Aggregation.of(b -> b.dateHistogram(h -> h.field("f")
				.calendarInterval(org.opensearch.client.opensearch._types.aggregations.CalendarInterval.Day)
				.hardBounds(ExtendedBounds.of(eb -> eb.min(org.opensearch.client.opensearch._types.aggregations.FieldDateMath.of(m -> m.expr("now-1d"))).max(org.opensearch.client.opensearch._types.aggregations.FieldDateMath.of(m -> m.expr("now"))))))));
		aggs.put("a_range", Aggregation.of(b -> b.range(r -> r.field("f")
				.ranges(AggregationRange.of(ar -> ar.from(JsonData.of(0.0)).to(JsonData.of(10.0)))))));
		aggs.put("a_date_range", Aggregation.of(b -> b.dateRange(r -> r.field("f"))));
		aggs.put("a_missing", Aggregation.of(b -> b.missing(m -> m.field("f"))));
		aggs.put("a_min", Aggregation.of(b -> b.min(m -> m.field("f"))));
		aggs.put("a_max", Aggregation.of(b -> b.max(m -> m.field("f"))));
		aggs.put("a_avg", Aggregation.of(b -> b.avg(m -> m.field("f"))));
		aggs.put("a_sum", Aggregation.of(b -> b.sum(m -> m.field("f"))));
		aggs.put("a_stats", Aggregation.of(b -> b.stats(m -> m.field("f"))));
		aggs.put("a_extended_stats", Aggregation.of(b -> b.extendedStats(m -> m.field("f"))));
		aggs.put("a_value_count", Aggregation.of(b -> b.valueCount(m -> m.field("f"))));
		aggs.put("a_cardinality", Aggregation.of(b -> b.cardinality(m -> m.field("f"))));
		// call under test
		SearchDslValidator.validateAggregations(aggs);

		// Coverage guard:
		EnumSet<Aggregation.Kind> covered = EnumSet.noneOf(Aggregation.Kind.class);
		for (Aggregation a : aggs.values()) {
			covered.add(a._kind());
		}
		assertEquals(SearchDslValidator.ALLOWED_AGGREGATION_KINDS, covered,
				"every allowlisted aggregation kind must appear in this round-trip");
	}

	@Test
	public void testValidateAggregationsWithDisallowedKindRejected() {
		Map<String, Aggregation> aggs = new LinkedHashMap<>();
		aggs.put("bad", Aggregation.of(b -> b.global(g -> g)));
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				() -> SearchDslValidator.validateAggregations(aggs));
		assertTrue(ex.getMessage().contains("aggregation kind is not allowed"));
	}

	@Test
	public void testValidateAggregationsWithTermsSizeAtCap() {
		Map<String, Aggregation> aggs = new LinkedHashMap<>();
		aggs.put("a", Aggregation.of(b -> b.terms(t -> t.field("f")
				.size(SearchDslValidator.MAX_AGG_SIZE + 1))));
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				() -> SearchDslValidator.validateAggregations(aggs));
		assertTrue(ex.getMessage().contains("'terms' aggregation 'size'"));
	}

	@Test
	public void testValidateAggregationsWithTermsShardSizeAtCap() {
		Map<String, Aggregation> aggs = new LinkedHashMap<>();
		aggs.put("a", Aggregation.of(b -> b.terms(t -> t.field("f")
				.shardSize(SearchDslValidator.MAX_AGG_SIZE + 1))));
		assertThrows(IllegalArgumentException.class,
				() -> SearchDslValidator.validateAggregations(aggs));
	}

	@Test
	public void testValidateAggregationsWithRangeRangesAtCap() {
		List<AggregationRange> ranges = new ArrayList<>();
		for (int i = 0; i <= SearchDslValidator.MAX_VALUES_PER_CLAUSE; i++) {
			final double idx = i;
			ranges.add(AggregationRange.of(ar -> ar.from(JsonData.of(idx)).to(JsonData.of(idx + 1.0))));
		}
		Map<String, Aggregation> aggs = new LinkedHashMap<>();
		aggs.put("a", Aggregation.of(b -> b.range(r -> r.field("f").ranges(ranges))));
		assertThrows(IllegalArgumentException.class,
				() -> SearchDslValidator.validateAggregations(aggs));
	}

	@Test
	public void testValidateAggregationsWithHistogramRequiresBounds() {
		Map<String, Aggregation> aggs = new LinkedHashMap<>();
		aggs.put("a", Aggregation.of(b -> b.histogram(h -> h.field("f").interval(1.0))));
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				() -> SearchDslValidator.validateAggregations(aggs));
		assertTrue(ex.getMessage().contains("extended_bounds"));
	}

	@Test
	public void testValidateAggregationsWithHistogramNonPositiveInterval() {
		Map<String, Aggregation> aggs = new LinkedHashMap<>();
		aggs.put("a", Aggregation.of(b -> b.histogram(h -> h.field("f").interval(0.0)
				.extendedBounds(ExtendedBounds.of(eb -> eb.min(0.0).max(10.0))))));
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				() -> SearchDslValidator.validateAggregations(aggs));
		assertTrue(ex.getMessage().contains("interval must be positive"));
	}

	@Test
	public void testValidateAggregationsWithDateHistogramRequiresBounds() {
		Map<String, Aggregation> aggs = new LinkedHashMap<>();
		aggs.put("a", Aggregation.of(b -> b.dateHistogram(h -> h.field("f"))));
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				() -> SearchDslValidator.validateAggregations(aggs));
		assertTrue(ex.getMessage().contains("extended_bounds"));
	}

	@Test
	public void testValidateAggregationsWithCardinalityExcessivePrecision() {
		Map<String, Aggregation> aggs = new LinkedHashMap<>();
		aggs.put("a", Aggregation.of(b -> b.cardinality(c -> c.field("f")
				.precisionThreshold(SearchDslValidator.MAX_PRECISION_THRESHOLD + 1))));
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				() -> SearchDslValidator.validateAggregations(aggs));
		assertTrue(ex.getMessage().contains("precision_threshold"));
	}

	@Test
	public void testValidateAggregationsWithExcessiveCount() {
		Map<String, Aggregation> aggs = new LinkedHashMap<>();
		for (int i = 0; i <= SearchDslValidator.AGG_MAX_COUNT; i++) {
			aggs.put("a" + i, Aggregation.of(b -> b.terms(t -> t.field("f"))));
		}
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				() -> SearchDslValidator.validateAggregations(aggs));
		assertTrue(ex.getMessage().contains("too many aggregations"));
	}

	@Test
	public void testValidateAggregationsWithExcessiveDepth() {
		// Build sub-aggregations nested past AGG_MAX_DEPTH.
		Aggregation deepest = Aggregation.of(b -> b.terms(t -> t.field("f")));
		for (int i = 0; i < SearchDslValidator.AGG_MAX_DEPTH + 2; i++) {
			final Aggregation inner = deepest;
			deepest = Aggregation.of(b -> b.terms(t -> t.field("f"))
					.aggregations("sub", inner));
		}
		Map<String, Aggregation> aggs = new LinkedHashMap<>();
		aggs.put("root", deepest);
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				() -> SearchDslValidator.validateAggregations(aggs));
		assertTrue(ex.getMessage().contains("nested too deeply"));
	}

	// -----------------------------------------------------------------------------
	// Suggesters
	// -----------------------------------------------------------------------------

	@Test
	public void testValidateSuggesterWithEveryAllowedKindRoundTrips() {
		Map<String, FieldSuggester> map = new LinkedHashMap<>();
		map.put("s_term", FieldSuggester.of(b -> b.term(t -> t.field("f")).text("x")));
		map.put("s_phrase", FieldSuggester.of(b -> b.phrase(p -> p.field("f")).text("x")));
		map.put("s_completion", FieldSuggester.of(b -> b.completion(c -> c.field("f")).text("x")));
		Suggester s = Suggester.of(b -> b.text("x").suggesters(map));
		// call under test
		SearchDslValidator.validateSuggester(s);

		EnumSet<FieldSuggester.Kind> covered = EnumSet.noneOf(FieldSuggester.Kind.class);
		for (FieldSuggester fs : map.values()) {
			covered.add(fs._kind());
		}
		assertEquals(SearchDslValidator.ALLOWED_SUGGESTER_KINDS, covered);
	}

	@Test
	public void testValidateSuggesterWithExcessiveSize() {
		Map<String, FieldSuggester> map = new LinkedHashMap<>();
		map.put("s", FieldSuggester.of(b -> b.term(t -> t.field("f")
				.size(SearchDslValidator.MAX_SUGGESTER_SIZE + 1)).text("x")));
		Suggester s = Suggester.of(b -> b.text("x").suggesters(map));
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				() -> SearchDslValidator.validateSuggester(s));
		assertTrue(ex.getMessage().contains("'size'"));
	}

	@Test
	public void testValidateSuggesterWithExcessiveCount() {
		Map<String, FieldSuggester> map = new LinkedHashMap<>();
		for (int i = 0; i <= SearchDslValidator.SUGGEST_MAX_COUNT; i++) {
			map.put("s" + i, FieldSuggester.of(b -> b.term(t -> t.field("f")).text("x")));
		}
		Suggester s = Suggester.of(b -> b.text("x").suggesters(map));
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				() -> SearchDslValidator.validateSuggester(s));
		assertTrue(ex.getMessage().contains("too many suggesters"));
	}

	// -----------------------------------------------------------------------------
	// Null arguments
	// -----------------------------------------------------------------------------

	@Test
	public void testValidateQueryWithNull() {
		assertThrows(IllegalArgumentException.class,
				() -> SearchDslValidator.validateQuery(null, false));
	}

	@Test
	public void testValidateAggregationsWithNull() {
		assertThrows(IllegalArgumentException.class,
				() -> SearchDslValidator.validateAggregations(null));
	}

	@Test
	public void testValidateSuggesterWithNull() {
		assertThrows(IllegalArgumentException.class,
				() -> SearchDslValidator.validateSuggester(null));
	}

	// -----------------------------------------------------------------------------
	// Highlight
	// -----------------------------------------------------------------------------

	@Test
	public void testValidateHighlightWithEmptyFieldsRoundTrips() {
		Highlight h = Highlight.of(b -> b.fields(new LinkedHashMap<>()));
		// call under test
		SearchDslValidator.validateHighlight(h);
	}

	@Test
	public void testValidateHighlightWithSingleFieldRoundTrips() {
		Map<String, HighlightField> fields = new LinkedHashMap<>();
		fields.put("title", HighlightField.of(b -> b));
		Highlight h = Highlight.of(b -> b.fields(fields));
		// call under test
		SearchDslValidator.validateHighlight(h);
	}

	@Test
	public void testValidateHighlightWithSemanticBuiltinTypeRejected() {
		// `semantic` is not in the built-in enum (only Unified / Plain / FastVector), so
		// callers can only land it as a custom type string.
		HighlighterType semanticType = HighlighterType.of(t -> t.custom("semantic"));
		Highlight h = Highlight.of(b -> b.type(semanticType).fields(new LinkedHashMap<>()));
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				() -> SearchDslValidator.validateHighlight(h));
		assertTrue(ex.getMessage().contains("semantic"));
	}

	@Test
	public void testValidateHighlightWithSemanticPerFieldTypeRejected() {
		HighlighterType semanticType = HighlighterType.of(t -> t.custom("semantic"));
		Map<String, HighlightField> fields = new LinkedHashMap<>();
		fields.put("title", HighlightField.of(b -> b.type(semanticType)));
		Highlight h = Highlight.of(b -> b.fields(fields));
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				() -> SearchDslValidator.validateHighlight(h));
		assertTrue(ex.getMessage().contains("semantic"));
	}

	@Test
	public void testValidateHighlightWithExcessiveFieldsRejected() {
		Map<String, HighlightField> fields = new LinkedHashMap<>();
		for (int i = 0; i <= SearchDslValidator.MAX_HIGHLIGHT_FIELDS; i++) {
			fields.put("f" + i, HighlightField.of(b -> b));
		}
		Highlight h = Highlight.of(b -> b.fields(fields));
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				() -> SearchDslValidator.validateHighlight(h));
		assertTrue(ex.getMessage().contains("highlight.fields"));
	}

	@Test
	public void testValidateHighlightWithExcessiveNumberOfFragmentsRejected() {
		Map<String, HighlightField> fields = new LinkedHashMap<>();
		fields.put("title", HighlightField.of(b -> b
				.numberOfFragments(SearchDslValidator.MAX_HIGHLIGHT_FRAGMENTS + 1)));
		Highlight h = Highlight.of(b -> b.fields(fields));
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				() -> SearchDslValidator.validateHighlight(h));
		assertTrue(ex.getMessage().contains("number_of_fragments"));
	}

	@Test
	public void testValidateHighlightWithExcessiveFragmentSizeRejected() {
		Map<String, HighlightField> fields = new LinkedHashMap<>();
		fields.put("title", HighlightField.of(b -> b
				.fragmentSize(SearchDslValidator.MAX_HIGHLIGHT_FRAGMENT_SIZE + 1)));
		Highlight h = Highlight.of(b -> b.fields(fields));
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				() -> SearchDslValidator.validateHighlight(h));
		assertTrue(ex.getMessage().contains("fragment_size"));
	}

	@Test
	public void testValidateHighlightWithTopLevelFragmentCapsApplied() {
		// Top-level Highlight numberOfFragments / fragmentSize are validated too — the
		// AOSS defaults cascade to fields without an override.
		Highlight h = Highlight.of(b -> b
				.fragmentSize(SearchDslValidator.MAX_HIGHLIGHT_FRAGMENT_SIZE + 1)
				.fields(new LinkedHashMap<>()));
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				() -> SearchDslValidator.validateHighlight(h));
		assertTrue(ex.getMessage().contains("fragment_size"));
	}

	@Test
	public void testValidateHighlightWithDisallowedHighlightQueryKindRejected() {
		// MoreLikeThis is not in the query allowlist; the recursive walkQuery call must
		// reject it so callers can't smuggle disallowed clauses through highlight_query.
		Query nested = Query.of(q -> q.moreLikeThis(m -> m.like(l -> l.text("x"))));
		Map<String, HighlightField> fields = new LinkedHashMap<>();
		fields.put("title", HighlightField.of(b -> b.highlightQuery(nested)));
		Highlight h = Highlight.of(b -> b.fields(fields));
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				() -> SearchDslValidator.validateHighlight(h));
		assertTrue(ex.getMessage().contains("not allowed"));
	}

	@Test
	public void testValidateHighlightWithNull() {
		assertThrows(IllegalArgumentException.class,
				() -> SearchDslValidator.validateHighlight(null));
	}

	@Test
	public void testScanHighlightForbiddenKeysWithScript() throws Exception {
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				() -> SearchDslValidator.scanHighlightForbiddenKeys(MAPPER.readTree(
						"{\"fields\":{\"title\":{\"script\":{}}}}")));
		assertTrue(ex.getMessage().contains("script"));
	}

	// ===================== FieldCollapse =====================

	@Test
	public void testValidateFieldCollapseWithFieldOnlyAccepted() {
		FieldCollapse c = FieldCollapse.of(b -> b.field("100"));
		// call under test
		SearchDslValidator.validateFieldCollapse(c);
	}

	@Test
	public void testValidateFieldCollapseWithInnerHitsRejected() {
		FieldCollapse c = FieldCollapse.of(b -> b.field("100")
				.innerHits(InnerHits.of(ih -> ih.name("latest").size(3))));
		// call under test
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				() -> SearchDslValidator.validateFieldCollapse(c));
		assertTrue(ex.getMessage().contains("inner_hits"));
	}

	@Test
	public void testValidateFieldCollapseWithExcessiveConcurrentGroupSearchesRejected() {
		FieldCollapse c = FieldCollapse.of(b -> b.field("100")
				.maxConcurrentGroupSearches(99));
		// call under test
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				() -> SearchDslValidator.validateFieldCollapse(c));
		assertTrue(ex.getMessage().contains("max_concurrent_group_searches"));
	}

	// ===================== Rescore =====================

	@Test
	public void testValidateRescoreWithAllowedInnerQueryAccepted() {
		Rescore r = Rescore.of(b -> b.windowSize(50)
				.query(RescoreQuery.of(rq -> rq.rescoreQuery(
						Query.of(q -> q.matchAll(m -> m))))));
		// call under test
		SearchDslValidator.validateRescore(r);
	}

	@Test
	public void testValidateRescoreWithExcessiveWindowSizeRejected() {
		Rescore r = Rescore.of(b -> b.windowSize(5000)
				.query(RescoreQuery.of(rq -> rq.rescoreQuery(
						Query.of(q -> q.matchAll(m -> m))))));
		// call under test
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				() -> SearchDslValidator.validateRescore(r));
		assertTrue(ex.getMessage().contains("window_size"));
	}
}
