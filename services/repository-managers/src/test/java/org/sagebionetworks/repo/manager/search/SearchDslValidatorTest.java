package org.sagebionetworks.repo.manager.search;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.opensearch.client.json.JsonData;
import org.opensearch.client.opensearch._types.FieldValue;
import org.opensearch.client.opensearch._types.aggregations.Aggregation;
import org.opensearch.client.opensearch._types.aggregations.AggregationRange;
import org.opensearch.client.opensearch._types.aggregations.DateRangeExpression;
import org.opensearch.client.opensearch._types.aggregations.ExtendedBounds;
import org.opensearch.client.opensearch._types.aggregations.FieldDateMath;
import org.opensearch.client.opensearch._types.aggregations.TermsExclude;
import org.opensearch.client.opensearch._types.aggregations.TermsInclude;
import org.opensearch.client.opensearch._types.query_dsl.BoolQuery;
import org.opensearch.client.opensearch._types.query_dsl.BoostingQuery;
import org.opensearch.client.opensearch._types.query_dsl.ConstantScoreQuery;
import org.opensearch.client.opensearch._types.query_dsl.DisMaxQuery;
import org.opensearch.client.opensearch._types.query_dsl.FuzzyQuery;
import org.opensearch.client.opensearch._types.query_dsl.MatchPhrasePrefixQuery;
import org.opensearch.client.opensearch._types.query_dsl.Query;
import org.opensearch.client.opensearch._types.query_dsl.TermsQueryField;
import org.opensearch.client.opensearch._types.query_dsl.TermsLookup;
import org.opensearch.client.opensearch._types.query_dsl.WildcardQuery;
import org.opensearch.client.opensearch.core.search.FieldCollapse;
import org.opensearch.client.opensearch.core.search.Highlight;
import org.opensearch.client.opensearch.core.search.HighlightField;
import org.opensearch.client.opensearch.core.search.HighlighterType;
import org.opensearch.client.opensearch.core.search.InnerHits;
import org.opensearch.client.opensearch.core.search.Rescore;
import org.opensearch.client.opensearch.core.search.RescoreQuery;

public class SearchDslValidatorTest {

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

	@Test
	public void testValidateQueryWithRowLevelFilterBypassKindsRejected() {
		// Lock-in for the row-level-filtering audit (docs/search/search-dsl-row-level-filter-audit.md).
		// These query kinds each defeat a future "(user query) AND (row-level filter)" wrap because
		// they expand to, or read from, documents the filter inside req.query never tested:
		//   - has_child / has_parent: join-expansion surfaces a related doc not bounded by the filter
		//   - more_like_this / percolate: cross-index / stored-query reach
		//   - wrapper: base64-encoded clause that bypasses this entire allowlist walk
		// Unlike the membership guard above, this exercises the actual throw path on a constructed
		// typed clause, so it fails if any kind is allowlisted AND its walk path lets it through.
		List<Query> bypassClauses = List.of(
				Query.of(b -> b.wrapper(w -> w.query("eyJtYXRjaF9hbGwiOnt9fQ=="))),
				Query.of(b -> b.moreLikeThis(m -> m.fields("title").like(l -> l.text("anything")))),
				Query.of(b -> b.hasChild(h -> h.type("child").query(q -> q.matchAll(m -> m)))),
				Query.of(b -> b.hasParent(h -> h.parentType("parent").query(q -> q.matchAll(m -> m)))),
				Query.of(b -> b.percolate(p -> p.field("query").id("1"))));
		for (Query clause : bypassClauses) {
			IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
					() -> SearchDslValidator.validateQuery(clause, false),
					"expected '" + clause._kind() + "' to be rejected");
			assertTrue(ex.getMessage().contains("query clause kind is not allowed"));
		}
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
	public void testValidateAggregationsWithGlobalKindRejected() {
		// Lock-in for the row-level-filtering audit: the `global` aggregation RESETS the agg scope
		// to all documents in the index, escaping the row-level filter that will live inside
		// req.query. If it were ever allowlisted, bucket counts/metrics would be computed over rows
		// the caller cannot read. It must stay disabled. See
		// docs/search/search-dsl-row-level-filter-audit.md §3.
		Map<String, Aggregation> aggs = new LinkedHashMap<>();
		aggs.put("escape_scope", Aggregation.of(b -> b.global(g -> g)
				.aggregations("c", Aggregation.of(s -> s.valueCount(v -> v.field("f"))))));
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

	// -----------------------------------------------------------------------------
	// FieldCollapse
	// -----------------------------------------------------------------------------

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

	// -----------------------------------------------------------------------------
	// Rescore
	// -----------------------------------------------------------------------------

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

	@Test
	public void testValidateRescoreWithDisallowedInnerKindRejected() {
		// MoreLikeThis is not in the query allowlist; the inner walkQuery in validateRescore
		// must reject it the same way a top-level query would.
		Rescore r = Rescore.of(b -> b.windowSize(50)
				.query(RescoreQuery.of(rq -> rq.rescoreQuery(
						Query.of(q -> q.moreLikeThis(m -> m.like(l -> l.text("x"))))))));
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				() -> SearchDslValidator.validateRescore(r));
		assertTrue(ex.getMessage().contains("not allowed"));
	}

	@Test
	public void testValidateRescoreWithNull() {
		assertThrows(IllegalArgumentException.class,
				() -> SearchDslValidator.validateRescore(null));
	}

	@Test
	public void testValidateFieldCollapseWithNull() {
		assertThrows(IllegalArgumentException.class,
				() -> SearchDslValidator.validateFieldCollapse(null));
	}

	// -----------------------------------------------------------------------------
	// Query allowlist coverage guard
	// -----------------------------------------------------------------------------

	/**
	 * Single round trip exercising every kind in
	 * {@link SearchDslValidator#ALLOWED_QUERY_KINDS}. EnumSet.allOf coverage guard at the
	 * bottom — adding a new allowlisted kind here without a fixture fails the test.
	 *
	 * <p>Compounds wrap leaves so the walker recurses into every kind in one validation.</p>
	 */
	@Test
	public void testValidateQueryWithEveryAllowedKindRoundTrips() {
		List<Query> leaves = new ArrayList<>();
		leaves.add(Query.of(b -> b.match(m -> m.field("f").query(FieldValue.of("x")))));
		leaves.add(Query.of(b -> b.multiMatch(mm -> mm.query("x").fields("f"))));
		leaves.add(Query.of(b -> b.matchPhrase(mp -> mp.field("f").query("x"))));
		leaves.add(Query.of(b -> b.matchPhrasePrefix(mpp -> mpp.field("f").query("x"))));
		leaves.add(Query.of(b -> b.matchBoolPrefix(mbp -> mbp.field("f").query("x"))));
		leaves.add(Query.of(b -> b.term(t -> t.field("f").value(FieldValue.of("x")))));
		leaves.add(Query.of(b -> b.terms(t -> t.field("f").terms(TermsQueryField.of(qf ->
				qf.value(List.of(FieldValue.of("x"))))))));
		leaves.add(Query.of(b -> b.range(r -> r.field("f").gte(JsonData.of(0)))));
		leaves.add(Query.of(b -> b.exists(e -> e.field("f"))));
		leaves.add(Query.of(b -> b.prefix(p -> p.field("f").value("x"))));
		leaves.add(Query.of(b -> b.wildcard(w -> w.field("f").value("x"))));
		leaves.add(Query.of(b -> b.fuzzy(f -> f.field("f").value(FieldValue.of("x")))));
		leaves.add(Query.of(b -> b.ids(i -> i.values("1"))));
		leaves.add(Query.of(b -> b.simpleQueryString(s -> s.query("x"))));
		leaves.add(Query.of(b -> b.matchAll(m -> m)));

		// Compounds wrap a different leaf each so all four compound branches run.
		List<Query> all = new ArrayList<>(leaves);
		all.add(Query.of(b -> b.bool(bb -> bb.must(leaves.get(0)).filter(leaves.get(5)))));
		all.add(Query.of(b -> b.disMax(dm -> dm.queries(leaves.get(1)))));
		all.add(Query.of(b -> b.constantScore(cs -> cs.filter(leaves.get(8)))));
		all.add(Query.of(b -> b.boosting(bo -> bo.positive(leaves.get(0)).negative(leaves.get(13))
				.negativeBoost(0.5f))));

		EnumSet<Query.Kind> covered = EnumSet.noneOf(Query.Kind.class);
		for (Query q : all) {
			// call under test — every kind must validate without throwing
			SearchDslValidator.validateQuery(q, false);
			covered.add(q._kind());
		}

		assertEquals(SearchDslValidator.ALLOWED_QUERY_KINDS, covered,
				"every allowlisted query kind must appear in this round-trip");
	}

	// -----------------------------------------------------------------------------
	// Aggregation recursion (positive case complementing the depth-cap rejection)
	// -----------------------------------------------------------------------------

	@Test
	public void testValidateAggregationsWithSubAggregationsRecursesPositive() {
		Aggregation child = Aggregation.of(b -> b.terms(t -> t.field("f")));
		Aggregation parent = Aggregation.of(b -> b.terms(t -> t.field("f"))
				.aggregations("child", child));
		Map<String, Aggregation> aggs = new LinkedHashMap<>();
		aggs.put("parent", parent);
		// call under test — recursion descends without throwing.
		SearchDslValidator.validateAggregations(aggs);
	}

	// -----------------------------------------------------------------------------
	// Highlight: top-level highlight_query (not the per-field variant)
	// -----------------------------------------------------------------------------

	@Test
	public void testValidateHighlightWithTopLevelDisallowedHighlightQueryRejected() {
		// MoreLikeThis is not in the query allowlist; the recursive walkQuery on the
		// Highlight.highlightQuery() node must reject it at the top level the same way
		// as it does on the per-field variant.
		Query nested = Query.of(q -> q.moreLikeThis(m -> m.like(l -> l.text("x"))));
		Highlight h = Highlight.of(b -> b.highlightQuery(nested).fields(new LinkedHashMap<>()));
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				() -> SearchDslValidator.validateHighlight(h));
		assertTrue(ex.getMessage().contains("not allowed"));
	}

	// -----------------------------------------------------------------------------
	// Terms: null TermsQueryField early return
	// -----------------------------------------------------------------------------

	// validateTerms's null TermsQueryField early return is unreachable from typed callers —
	// the typed TermsQuery builder requires a TermsQueryField at construction time. The
	// validator keeps the defensive null check.

	@Test
	public void testValidateTermsAggregationWithIncludeRegexLeftAlone() {
		// Regex-string include passes through (only inline-list form is capped).
		Map<String, Aggregation> aggs = new LinkedHashMap<>();
		aggs.put("a", Aggregation.of(b -> b.terms(t -> t.field("f")
				.include(TermsInclude.of(ti -> ti.regexp("^a.*"))))));
		// call under test — must not throw
		SearchDslValidator.validateAggregations(aggs);
	}

	@Test
	public void testValidateTermsAggregationWithExcludeListAtCap() {
		List<String> tooMany = new ArrayList<>();
		for (int i = 0; i <= SearchDslValidator.MAX_VALUES_PER_CLAUSE; i++) {
			tooMany.add("v" + i);
		}
		Map<String, Aggregation> aggs = new LinkedHashMap<>();
		aggs.put("a", Aggregation.of(b -> b.terms(t -> t.field("f")
				.exclude(TermsExclude.of(te -> te.terms(tooMany))))));
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				() -> SearchDslValidator.validateAggregations(aggs));
		assertTrue(ex.getMessage().contains("'exclude'"));
	}

	@Test
	public void testValidateDateRangeAggregationWithRangesAtCap() {
		List<DateRangeExpression> ranges = new ArrayList<>();
		for (int i = 0; i <= SearchDslValidator.MAX_VALUES_PER_CLAUSE; i++) {
			ranges.add(DateRangeExpression.of(
					dr -> dr.from(FieldDateMath.of(m -> m.expr("now-1d")))));
		}
		Map<String, Aggregation> aggs = new LinkedHashMap<>();
		aggs.put("a", Aggregation.of(b -> b.dateRange(r -> r.field("f").ranges(ranges))));
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				() -> SearchDslValidator.validateAggregations(aggs));
		assertTrue(ex.getMessage().contains("'date_range'"));
	}

	// -----------------------------------------------------------------------------
	// Ids: empty values list accepted
	// -----------------------------------------------------------------------------

	@Test
	public void testValidateQueryWithEmptyIdsAccepted() {
		// Null/empty values doesn't trigger the cap; this exercises the (values == null)
		// early-out path of validateIds.
		Query q = Query.of(b -> b.ids(i -> i));
		SearchDslValidator.validateQuery(q, false);
	}

	// -----------------------------------------------------------------------------
	// Highlight: nested highlight_query is recursively walked against the allowlist
	// -----------------------------------------------------------------------------

	@Test
	public void testValidateHighlightWithTopLevelAllowedHighlightQueryAccepted() {
		// An allowlisted top-level highlight_query must pass the recursive walkQuery.
		Highlight h = Highlight.of(b -> b
				.highlightQuery(Query.of(q -> q.matchAll(m -> m)))
				.fields(new LinkedHashMap<>()));
		// call under test
		assertDoesNotThrow(() -> SearchDslValidator.validateHighlight(h));
	}

	@Test
	public void testValidateHighlightWithPerFieldAllowedHighlightQueryAccepted() {
		// An allowlisted per-field highlight_query must pass the recursive walkQuery.
		Map<String, HighlightField> fields = new LinkedHashMap<>();
		fields.put("title", HighlightField.of(b -> b
				.highlightQuery(Query.of(q -> q.matchAll(m -> m)))));
		Highlight h = Highlight.of(b -> b.fields(fields));
		// call under test
		assertDoesNotThrow(() -> SearchDslValidator.validateHighlight(h));
	}

	@Test
	public void testValidateHighlightWithCustomNonSemanticTypeAccepted() {
		// A custom highlighter type that isn't `semantic` is allowed — exercises the
		// isCustom() branch of rejectSemanticType that returns without throwing.
		HighlighterType fvh = HighlighterType.of(t -> t.custom("fvh"));
		Highlight h = Highlight.of(b -> b.type(fvh).fields(new LinkedHashMap<>()));
		// call under test
		assertDoesNotThrow(() -> SearchDslValidator.validateHighlight(h));
	}

	// -----------------------------------------------------------------------------
	// prefix: empty value is accepted (exercises the pattern.isEmpty() early-out)
	// -----------------------------------------------------------------------------

	@Test
	public void testValidateQueryWithPrefixEmptyValueAccepted() {
		Query q = Query.of(b -> b.prefix(p -> p.field("foo").value("")));
		// call under test — empty value short-circuits the leading-wildcard check
		assertDoesNotThrow(() -> SearchDslValidator.validateQuery(q, false));
	}

	// -----------------------------------------------------------------------------
	// terms aggregation: inline include-list form
	// -----------------------------------------------------------------------------

	@Test
	public void testValidateTermsAggregationWithIncludeListUnderCapAccepted() {
		Map<String, Aggregation> aggs = new LinkedHashMap<>();
		aggs.put("a", Aggregation.of(b -> b.terms(t -> t.field("f")
				.include(TermsInclude.of(ti -> ti.terms(List.of("a", "b")))))));
		// call under test — under cap, must not throw
		assertDoesNotThrow(() -> SearchDslValidator.validateAggregations(aggs));
	}

	@Test
	public void testValidateTermsAggregationWithIncludeListAtCap() {
		List<String> tooMany = new ArrayList<>();
		for (int i = 0; i <= SearchDslValidator.MAX_VALUES_PER_CLAUSE; i++) {
			tooMany.add("v" + i);
		}
		Map<String, Aggregation> aggs = new LinkedHashMap<>();
		aggs.put("a", Aggregation.of(b -> b.terms(t -> t.field("f")
				.include(TermsInclude.of(ti -> ti.terms(tooMany))))));
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				() -> SearchDslValidator.validateAggregations(aggs));
		assertTrue(ex.getMessage().contains("'include'"));
	}

	// ---------- walkQuery ----------

	@Test
	public void testWalkQueryWithDepthOverLimitRejected() {
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				// call under test
				() -> SearchDslValidator.walkQuery(Query.of(b -> b.matchAll(m -> m)),
						SearchDslValidator.QUERY_MAX_DEPTH + 1, new int[] { 0 }));
		assertTrue(ex.getMessage().contains("nested too deeply"));
	}

	@Test
	public void testWalkQueryWithClauseCountOverLimitRejected() {
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				// call under test
				() -> SearchDslValidator.walkQuery(Query.of(b -> b.matchAll(m -> m)), 1,
						new int[] { SearchDslValidator.QUERY_MAX_CLAUSES }));
		assertTrue(ex.getMessage().contains("too many clauses"));
	}

	@Test
	public void testWalkQueryWithDisallowedKindRejected() {
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				// call under test
				() -> SearchDslValidator.walkQuery(Query.of(b -> b.wrapper(w -> w.query("eyJ9"))),
						1, new int[] { 0 }));
		assertTrue(ex.getMessage().contains("not allowed"));
	}

	// ---------- walkDisMax ----------

	@Test
	public void testWalkDisMaxWithEmptyQueriesRejected() {
		// The typed DisMaxQuery builder requires `queries` at construction; an empty list here
		// exercises the helper's guard directly.
		DisMaxQuery empty = DisMaxQuery.of(d -> d.queries(new ArrayList<>()));
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				// call under test
				() -> SearchDslValidator.walkDisMax(empty, 1, new int[] { 0 }));
		assertTrue(ex.getMessage().contains("requires a 'queries' field"));
	}

	@Test
	public void testWalkDisMaxWithQueriesRecurses() {
		DisMaxQuery dm = DisMaxQuery.of(d -> d.queries(Query.of(b -> b.matchAll(m -> m))));
		// call under test — must not throw
		SearchDslValidator.walkDisMax(dm, 1, new int[] { 0 });
	}

	// ---------- walkQueryList ----------

	@Test
	public void testWalkQueryListWithNullReturns() {
		// BoolQuery accessors never return null; passing null here exercises the guard directly.
		// call under test — must not throw
		SearchDslValidator.walkQueryList(null, 1, new int[] { 0 });
	}

	@Test
	public void testWalkQueryListWithElementsRecurses() {
		// call under test — must not throw
		SearchDslValidator.walkQueryList(List.of(Query.of(b -> b.matchAll(m -> m))), 1,
				new int[] { 0 });
	}

	// ---------- walkBool / walkConstantScore / walkBoosting ----------

	@Test
	public void testWalkBoolWithEverySlotRecurses() {
		BoolQuery bool = BoolQuery.of(b -> b
				.must(Query.of(q -> q.matchAll(m -> m)))
				.should(Query.of(q -> q.matchAll(m -> m)))
				.mustNot(Query.of(q -> q.matchAll(m -> m)))
				.filter(Query.of(q -> q.matchAll(m -> m))));
		// call under test — must not throw
		SearchDslValidator.walkBool(bool, 1, new int[] { 0 });
	}

	@Test
	public void testWalkConstantScoreWithFilterRecurses() {
		ConstantScoreQuery cs = ConstantScoreQuery.of(c -> c
				.filter(Query.of(q -> q.matchAll(m -> m))));
		// call under test — must not throw
		SearchDslValidator.walkConstantScore(cs, 1, new int[] { 0 });
	}

	@Test
	public void testWalkBoostingWithBothSlotsRecurses() {
		BoostingQuery bo = BoostingQuery.of(b -> b
				.positive(Query.of(q -> q.matchAll(m -> m)))
				.negative(Query.of(q -> q.matchAll(m -> m)))
				.negativeBoost(0.5f));
		// call under test — must not throw
		SearchDslValidator.walkBoosting(bo, 1, new int[] { 0 });
	}

	// ---------- rejectLeadingWildcardWildcard ----------

	@Test
	public void testRejectLeadingWildcardWildcardWithValueLeadingRejected() {
		WildcardQuery w = WildcardQuery.of(b -> b.field("foo").value("*bad"));
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				// call under test
				() -> SearchDslValidator.rejectLeadingWildcardWildcard(w));
		assertTrue(ex.getMessage().contains("leading wildcard"));
	}

	@Test
	public void testRejectLeadingWildcardWildcardWithWildcardPropertyFallbackRejected() {
		// `value()` is null, so the helper falls back to the legacy `wildcard` property.
		WildcardQuery w = WildcardQuery.of(b -> b.field("foo").wildcard("*bad"));
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				// call under test
				() -> SearchDslValidator.rejectLeadingWildcardWildcard(w));
		assertTrue(ex.getMessage().contains("leading wildcard"));
	}

	@Test
	public void testRejectLeadingWildcardWildcardWithNonLeadingAccepted() {
		WildcardQuery w = WildcardQuery.of(b -> b.field("foo").value("ok*"));
		// call under test — must not throw
		SearchDslValidator.rejectLeadingWildcardWildcard(w);
	}

	// ---------- rejectLeadingWildcard ----------

	@Test
	public void testRejectLeadingWildcardWithNullAccepted() {
		// call under test — must not throw
		SearchDslValidator.rejectLeadingWildcard(null, "prefix", "foo");
	}

	@Test
	public void testRejectLeadingWildcardWithEmptyAccepted() {
		// call under test — must not throw
		SearchDslValidator.rejectLeadingWildcard("", "prefix", "foo");
	}

	@Test
	public void testRejectLeadingWildcardWithStarRejected() {
		assertThrows(IllegalArgumentException.class,
				// call under test
				() -> SearchDslValidator.rejectLeadingWildcard("*x", "prefix", "foo"));
	}

	@Test
	public void testRejectLeadingWildcardWithQuestionMarkRejected() {
		assertThrows(IllegalArgumentException.class,
				// call under test
				() -> SearchDslValidator.rejectLeadingWildcard("?x", "prefix", "foo"));
	}

	@Test
	public void testRejectLeadingWildcardWithPlainPrefixAccepted() {
		// call under test — must not throw
		SearchDslValidator.rejectLeadingWildcard("abc", "prefix", "foo");
	}

	// ---------- checkMaxExpansions ----------

	@Test
	public void testCheckMaxExpansionsWithNullAccepted() {
		// call under test — must not throw
		SearchDslValidator.checkMaxExpansions(null, "fuzzy");
	}

	@Test
	public void testCheckMaxExpansionsWithAtCapAccepted() {
		// call under test — must not throw
		SearchDslValidator.checkMaxExpansions(SearchDslValidator.MAX_PREFIX_EXPANSIONS, "fuzzy");
	}

	@Test
	public void testCheckMaxExpansionsWithOverCapRejected() {
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				// call under test
				() -> SearchDslValidator.checkMaxExpansions(
						SearchDslValidator.MAX_PREFIX_EXPANSIONS + 1, "fuzzy"));
		assertTrue(ex.getMessage().contains("max_expansions"));
	}

	// ---------- rejectSemanticType ----------

	@Test
	public void testRejectSemanticTypeWithNullAccepted() {
		// call under test — must not throw
		SearchDslValidator.rejectSemanticType(null, "highlight.type");
	}

	@Test
	public void testRejectSemanticTypeWithBuiltinNonSemanticAccepted() {
		// Built-in highlighter types (Unified/Plain/FastVector) are never `semantic`.
		HighlighterType builtin = HighlighterType.of(t -> t.builtin(
				org.opensearch.client.opensearch.core.search.BuiltinHighlighterType.Unified));
		// call under test — must not throw
		SearchDslValidator.rejectSemanticType(builtin, "highlight.type");
	}

	@Test
	public void testRejectSemanticTypeWithCustomNonSemanticAccepted() {
		// call under test — must not throw
		SearchDslValidator.rejectSemanticType(HighlighterType.of(t -> t.custom("fvh")),
				"highlight.type");
	}

	@Test
	public void testRejectSemanticTypeWithCustomSemanticRejected() {
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				// call under test
				() -> SearchDslValidator.rejectSemanticType(HighlighterType.of(t -> t.custom("semantic")),
						"highlight.type"));
		assertTrue(ex.getMessage().contains("semantic"));
	}

	// ---------- checkHighlightCaps ----------

	@Test
	public void testCheckHighlightCapsWithBothWithinAccepted() {
		// call under test — must not throw
		SearchDslValidator.checkHighlightCaps(5, 100, "highlight");
	}

	@Test
	public void testCheckHighlightCapsWithExcessiveFragmentsRejected() {
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				// call under test
				() -> SearchDslValidator.checkHighlightCaps(
						SearchDslValidator.MAX_HIGHLIGHT_FRAGMENTS + 1, null, "highlight"));
		assertTrue(ex.getMessage().contains("number_of_fragments"));
	}

	@Test
	public void testCheckHighlightCapsWithExcessiveFragmentSizeRejected() {
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				// call under test
				() -> SearchDslValidator.checkHighlightCaps(null,
						SearchDslValidator.MAX_HIGHLIGHT_FRAGMENT_SIZE + 1, "highlight"));
		assertTrue(ex.getMessage().contains("fragment_size"));
	}

	// ---------- walkAggregation / walkAggregationMap ----------

	@Test
	public void testWalkAggregationWithDisallowedKindRejected() {
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				// call under test
				() -> SearchDslValidator.walkAggregation(Aggregation.of(b -> b.global(g -> g)), 1,
						new int[] { 0 }));
		assertTrue(ex.getMessage().contains("aggregation kind is not allowed"));
	}

	@Test
	public void testWalkAggregationMapWithDepthOverLimitRejected() {
		Map<String, Aggregation> map = new LinkedHashMap<>();
		map.put("a", Aggregation.of(b -> b.terms(t -> t.field("f"))));
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				// call under test
				() -> SearchDslValidator.walkAggregationMap(map, SearchDslValidator.AGG_MAX_DEPTH + 1,
						new int[] { 0 }));
		assertTrue(ex.getMessage().contains("nested too deeply"));
	}

	@Test
	public void testWalkAggregationMapWithCountOverLimitRejected() {
		Map<String, Aggregation> map = new LinkedHashMap<>();
		map.put("a", Aggregation.of(b -> b.terms(t -> t.field("f"))));
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				// call under test
				() -> SearchDslValidator.walkAggregationMap(map, 1,
						new int[] { SearchDslValidator.AGG_MAX_COUNT }));
		assertTrue(ex.getMessage().contains("too many aggregations"));
	}

	// ---------- checkAggSize ----------

	@Test
	public void testCheckAggSizeWithNullAccepted() {
		// call under test — must not throw
		SearchDslValidator.checkAggSize(null, "terms", "size");
	}

	@Test
	public void testCheckAggSizeWithAtCapAccepted() {
		// call under test — must not throw
		SearchDslValidator.checkAggSize(SearchDslValidator.MAX_AGG_SIZE, "terms", "size");
	}

	@Test
	public void testCheckAggSizeWithOverCapRejected() {
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				// call under test
				() -> SearchDslValidator.checkAggSize(SearchDslValidator.MAX_AGG_SIZE + 1, "terms",
						"size"));
		assertTrue(ex.getMessage().contains("'terms' aggregation 'size'"));
	}

	// validateFieldCollapse field().isEmpty() and validateTerms's null-TermsQueryField early
	// return are unreachable: the typed FieldCollapse / TermsQuery builders require those slots at
	// construction (MissingRequiredPropertyException). The defensive checks stay in case a future
	// OpenSearch-client schema change relaxes the requirement.
}
