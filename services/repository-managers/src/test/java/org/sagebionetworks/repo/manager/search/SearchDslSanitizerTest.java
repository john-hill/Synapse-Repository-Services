package org.sagebionetworks.repo.manager.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public class SearchDslSanitizerTest {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	// -----------------------------------------------------------------------------
	// Copy-only sanitizer: forbidden keys are rejected because they are never allowlisted
	// -----------------------------------------------------------------------------

	@Test
	public void testSanitizeQueryWithSiblingScript() throws Exception {
		// A second top-level key alongside the clause makes the container ambiguous; rejected.
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				() -> SearchDslSanitizer.sanitizeQuery(MAPPER.readTree(
						"{\"bool\":{\"must\":{\"match_all\":{}}}, \"script\":{}}"), false));
		assertTrue(ex.getMessage().contains("script"));
	}

	@Test
	public void testSanitizeQueryWithScriptInsideLeafOptions() throws Exception {
		// A script sitting on an otherwise-allowed match clause's per-field options is not an
		// allowlisted option, so the rebuild rejects it.
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				() -> SearchDslSanitizer.sanitizeQuery(MAPPER.readTree(
						"{\"bool\":{\"must\":[{\"match\":{\"foo\":{\"query\":\"x\",\"script\":{}}}}]}}"),
						false));
		assertTrue(ex.getMessage().contains("script"));
	}

	@Test
	public void testSanitizeQueryWithIndexedShapeKindRejected() throws Exception {
		// `indexed_shape` only appears under geo_shape, which is not an allowlisted clause kind.
		assertThrows(IllegalArgumentException.class,
				() -> SearchDslSanitizer.sanitizeQuery(MAPPER.readTree(
						"{\"geo_shape\":{\"indexed_shape\":{}}}"), false));
	}

	@Test
	public void testSanitizeQueryWithRuntimeMappingsRejected() throws Exception {
		// `runtime_mappings` is not a query clause kind.
		assertThrows(IllegalArgumentException.class,
				() -> SearchDslSanitizer.sanitizeQuery(MAPPER.readTree(
						"{\"runtime_mappings\":{}}"), false));
	}

	@Test
	public void testSanitizeQueryWithBenignBodyRoundTripsUnchanged() throws Exception {
		// A supported clause survives the rebuild verbatim.
		JsonNode in = MAPPER.readTree("{\"match_all\":{}}");
		JsonNode out = SearchDslSanitizer.sanitizeQuery(in, false);
		assertEquals(in, out);
	}

	@Test
	public void testSanitizeAggregationsWithScriptInsideTermsRejected() throws Exception {
		// A terms aggregation's `script` is not an allowlisted body key, so it never enters the
		// rebuilt node — no Painless reaches AOSS.
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				() -> SearchDslSanitizer.sanitizeAggregations(MAPPER.readTree(
						"{\"my_agg\":{\"terms\":{\"field\":\"foo\",\"script\":{\"source\":\"painless\"}}}}")));
		assertTrue(ex.getMessage().contains("script"));
	}

	@Test
	public void testSanitizeAggregationsWithScriptInsideMetaRejected() throws Exception {
		// `meta` is copied wholesale (opaque), so a script hidden inside it must still be rejected.
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				() -> SearchDslSanitizer.sanitizeAggregations(MAPPER.readTree(
						"{\"my_agg\":{\"terms\":{\"field\":\"foo\"},\"meta\":{\"x\":{\"script\":{}}}}}")));
		assertTrue(ex.getMessage().contains("script"));
	}

	@Test
	public void testSanitizeQueryWithScriptClauseInArrayElementRejected() throws Exception {
		// A script clause as a sibling inside a bool.must array is rejected — `script` is not an
		// allowlisted clause kind.
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				() -> SearchDslSanitizer.sanitizeQuery(MAPPER.readTree(
						"{\"bool\":{\"must\":[{\"term\":{\"x\":\"y\"}},{\"script\":{}}]}}"), false));
		assertTrue(ex.getMessage().contains("script"));
	}

	@Test
	public void testSanitizeAggregationsWithExcessivelyNestedMetaRejected() throws Exception {
		// An opaque `meta` value nested past FORBIDDEN_SCAN_MAX_DEPTH must be rejected so a
		// pathological payload can't blow the stack during the copy.
		StringBuilder open = new StringBuilder();
		StringBuilder close = new StringBuilder();
		for (int i = 0; i <= SearchDslSanitizer.FORBIDDEN_SCAN_MAX_DEPTH + 5; i++) {
			open.append("{\"a\":");
			close.append("}");
		}
		open.append("1").append(close);
		String json = "{\"my_agg\":{\"terms\":{\"field\":\"foo\"},\"meta\":" + open + "}}";
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				() -> SearchDslSanitizer.sanitizeAggregations(MAPPER.readTree(json)));
		assertTrue(ex.getMessage().contains("too deeply"));
	}

	// -----------------------------------------------------------------------------
	// Copy-only sanitizer: an unrecognized sibling key is rejected (not dropped) per surface
	// -----------------------------------------------------------------------------

	@Test
	public void testSanitizeQueryWithUnknownLeafOptionRejected() throws Exception {
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				() -> SearchDslSanitizer.sanitizeQuery(MAPPER.readTree(
						"{\"match\":{\"field\":\"f\",\"query\":\"x\",\"bogus\":1}}"), false));
		assertTrue(ex.getMessage().contains("bogus"));
	}

	@Test
	public void testSanitizeQueryWithUnknownBoolSlotRejected() throws Exception {
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				() -> SearchDslSanitizer.sanitizeQuery(MAPPER.readTree(
						"{\"bool\":{\"must\":[{\"match_all\":{}}],\"bogus\":true}}"), false));
		assertTrue(ex.getMessage().contains("bogus"));
	}

	@Test
	public void testSanitizeQueryWithMultipleClauseKindsRejected() throws Exception {
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				() -> SearchDslSanitizer.sanitizeQuery(MAPPER.readTree(
						"{\"match_all\":{},\"term\":{\"f\":\"v\"}}"), false));
		assertTrue(ex.getMessage().contains("exactly one type"));
	}

	@Test
	public void testSanitizeAggregationsWithUnknownBodyKeyRejected() throws Exception {
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				() -> SearchDslSanitizer.sanitizeAggregations(MAPPER.readTree(
						"{\"a\":{\"terms\":{\"field\":\"f\",\"bogus\":1}}}")));
		assertTrue(ex.getMessage().contains("bogus"));
	}

	@Test
	public void testSanitizeAggregationsWithDisallowedKindRejected() throws Exception {
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				() -> SearchDslSanitizer.sanitizeAggregations(MAPPER.readTree(
						"{\"a\":{\"global\":{}}}")));
		assertTrue(ex.getMessage().contains("global"));
	}

	@Test
	public void testSanitizeHighlightWithUnknownKeyRejected() throws Exception {
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				() -> SearchDslSanitizer.sanitizeHighlight(MAPPER.readTree(
						"{\"fields\":{\"title\":{}},\"bogus\":1}")));
		assertTrue(ex.getMessage().contains("bogus"));
	}

	@Test
	public void testSanitizeHighlightWithUnknownPerFieldKeyRejected() throws Exception {
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				() -> SearchDslSanitizer.sanitizeHighlight(MAPPER.readTree(
						"{\"fields\":{\"title\":{\"bogus\":1}}}")));
		assertTrue(ex.getMessage().contains("bogus"));
	}

	@Test
	public void testSanitizeCollapseWithUnknownKeyRejected() throws Exception {
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				() -> SearchDslSanitizer.sanitizeCollapse(MAPPER.readTree(
						"{\"field\":\"f\",\"bogus\":1}")));
		assertTrue(ex.getMessage().contains("bogus"));
	}

	@Test
	public void testSanitizeRescoreWithUnknownKeyRejected() throws Exception {
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				() -> SearchDslSanitizer.sanitizeRescore(MAPPER.readTree(
						"{\"window_size\":50,\"bogus\":1}")));
		assertTrue(ex.getMessage().contains("bogus"));
	}

	@Test
	public void testSanitizeRescoreWithUnknownInnerQueryKeyRejected() throws Exception {
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				() -> SearchDslSanitizer.sanitizeRescore(MAPPER.readTree(
						"{\"window_size\":50,\"query\":{\"rescore_query\":{\"match_all\":{}},\"bogus\":1}}")));
		assertTrue(ex.getMessage().contains("bogus"));
	}

	@Test
	public void testSanitizeSourceWithUnknownObjectKeyRejected() throws Exception {
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				() -> SearchDslSanitizer.sanitizeSource(MAPPER.readTree(
						"{\"includes\":[\"a\"],\"bogus\":1}")));
		assertTrue(ex.getMessage().contains("bogus"));
	}

	@Test
	public void testSanitizeSortWithUnknownOptionKeyRejected() throws Exception {
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				() -> SearchDslSanitizer.sanitizeSort(MAPPER.readTree(
						"[{\"title\":{\"order\":\"asc\",\"bogus\":1}}]")));
		assertTrue(ex.getMessage().contains("bogus"));
	}

	@Test
	public void testSanitizeSortWithScriptSortRejected() throws Exception {
		// _script sorts run Painless and are not supported; rejected, not silently dropped.
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				() -> SearchDslSanitizer.sanitizeSort(MAPPER.readTree(
						"[{\"_script\":{\"type\":\"number\",\"script\":\"x\"}}]")));
		assertTrue(ex.getMessage().contains("_script"));
	}

	@Test
	public void testSanitizeSortWithGeoDistanceSortRejected() throws Exception {
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				() -> SearchDslSanitizer.sanitizeSort(MAPPER.readTree(
						"[{\"_geo_distance\":{\"loc\":[0,0]}}]")));
		assertTrue(ex.getMessage().contains("_geo_distance"));
	}

	// -----------------------------------------------------------------------------
	// Copy-only sanitizer: a valid request round-trips unchanged through each surface
	// -----------------------------------------------------------------------------

	@Test
	public void testSanitizeQueryWithCompoundRoundTripsUnchanged() throws Exception {
		// A bool with every slot, wrapping allowlisted leaves, survives the rebuild verbatim.
		JsonNode in = MAPPER.readTree("{\"bool\":{\"must\":[{\"match\":{\"f\":\"x\"}}],"
				+ "\"should\":[{\"term\":{\"g\":\"y\"}}],\"must_not\":[{\"exists\":{\"field\":\"h\"}}],"
				+ "\"filter\":[{\"range\":{\"n\":{\"gte\":0}}}],\"boost\":1.0,"
				+ "\"minimum_should_match\":1}}");
		assertEquals(in, SearchDslSanitizer.sanitizeQuery(in, false));
	}

	@Test
	public void testSanitizeAggregationsWithSubAggsRoundTripsUnchanged() throws Exception {
		JsonNode in = MAPPER.readTree("{\"by_year\":{\"terms\":{\"field\":\"year\",\"size\":10},"
				+ "\"aggregations\":{\"avg_score\":{\"avg\":{\"field\":\"score\"}}}}}");
		assertEquals(in, SearchDslSanitizer.sanitizeAggregations(in));
	}

	@Test
	public void testSanitizeHighlightWithQueryRoundTripsUnchanged() throws Exception {
		JsonNode in = MAPPER.readTree("{\"pre_tags\":[\"<em>\"],\"post_tags\":[\"</em>\"],"
				+ "\"highlight_query\":{\"match_all\":{}},"
				+ "\"fields\":{\"title\":{\"number_of_fragments\":3}}}");
		assertEquals(in, SearchDslSanitizer.sanitizeHighlight(in));
	}

	@Test
	public void testSanitizeRescoreRoundTripsUnchanged() throws Exception {
		JsonNode in = MAPPER.readTree("{\"window_size\":50,"
				+ "\"query\":{\"rescore_query\":{\"match_all\":{}},\"query_weight\":1.0}}");
		assertEquals(in, SearchDslSanitizer.sanitizeRescore(in));
	}

	@Test
	public void testSanitizeSortRoundTripsUnchanged() throws Exception {
		JsonNode in = MAPPER.readTree("[\"title\",{\"year\":{\"order\":\"desc\"}},\"_score\"]");
		assertEquals(in, SearchDslSanitizer.sanitizeSort(in));
	}

	@Test
	public void testSanitizeHighlightWithScriptInPerFieldRejected() throws Exception {
		// A script on a per-field highlight block is not an allowlisted key — rejected by rebuild.
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				() -> SearchDslSanitizer.sanitizeHighlight(MAPPER.readTree(
						"{\"fields\":{\"title\":{\"script\":{}}}}")));
		assertTrue(ex.getMessage().contains("script"));
	}

	// FieldCollapse with a missing or empty `field` is unreachable from the typed builder —
	// it throws MissingRequiredPropertyException at construction time. The validator's
	// defensive null/empty check stays for safety in case the OpenSearch client relaxes it.

	// -----------------------------------------------------------------------------
	// Top-level body allowlists (scanBodyTopLevelKeys / scanAutocompleteBodyTopLevelKeys)
	// -----------------------------------------------------------------------------

	@Test
	public void testScanBodyTopLevelKeysWithAllowedSubsetAccepted() throws Exception {
		// One body containing every BODY_ALLOWED_KEY except search_after.
		String json = "{\"query\":{},\"post_filter\":{},\"aggregations\":{},"
				+ "\"highlight\":{},\"collapse\":{},\"rescore\":{},\"sort\":[],\"_source\":{},"
				+ "\"from\":0,\"size\":10}";
		// call under test — must not throw
		SearchDslSanitizer.scanBodyTopLevelKeys(MAPPER.readTree(json));
	}

	@Test
	public void testScanBodyTopLevelKeysWithUnsupportedKeyRejected() throws Exception {
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				() -> SearchDslSanitizer.scanBodyTopLevelKeys(MAPPER.readTree(
						"{\"query\":{},\"explain\":true}")));
		assertTrue(ex.getMessage().contains("explain"));
	}

	@Test
	public void testScanBodyTopLevelKeysWithNullRejected() {
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				() -> SearchDslSanitizer.scanBodyTopLevelKeys(null));
		assertTrue(ex.getMessage().contains("body"));
	}

	@Test
	public void testScanBodyTopLevelKeysWithNonObjectRejected() throws Exception {
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				() -> SearchDslSanitizer.scanBodyTopLevelKeys(MAPPER.readTree("[]")));
		assertTrue(ex.getMessage().contains("body"));
	}

	@Test
	public void testScanBodyTopLevelKeysWithSearchAfterAndPositiveFromRejected() throws Exception {
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				() -> SearchDslSanitizer.scanBodyTopLevelKeys(MAPPER.readTree(
						"{\"query\":{},\"search_after\":[\"x\"],\"from\":5}")));
		assertTrue(ex.getMessage().contains("search_after"));
		assertTrue(ex.getMessage().contains("from"));
	}

	@Test
	public void testScanBodyTopLevelKeysWithSearchAfterAndZeroFromAccepted() throws Exception {
		// from=0 alongside search_after is fine — the cursor takes precedence.
		// call under test — must not throw
		SearchDslSanitizer.scanBodyTopLevelKeys(MAPPER.readTree(
				"{\"query\":{},\"search_after\":[\"x\"],\"from\":0}"));
	}

	@Test
	public void testScanAutocompleteBodyTopLevelKeysWithQueryAndSourceAccepted() throws Exception {
		// call under test — must not throw
		SearchDslSanitizer.scanAutocompleteBodyTopLevelKeys(MAPPER.readTree(
				"{\"query\":{},\"_source\":{}}"));
	}

	@Test
	public void testScanAutocompleteBodyTopLevelKeysWithDisallowedKeyRejected() throws Exception {
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				() -> SearchDslSanitizer.scanAutocompleteBodyTopLevelKeys(MAPPER.readTree(
						"{\"query\":{},\"aggregations\":{}}")));
		assertTrue(ex.getMessage().contains("aggregations"));
	}

	@Test
	public void testScanAutocompleteBodyTopLevelKeysWithNullRejected() {
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				() -> SearchDslSanitizer.scanAutocompleteBodyTopLevelKeys(null));
		assertTrue(ex.getMessage().contains("body"));
	}

	// -----------------------------------------------------------------------------
	// from / size / search_after resolution (resolveFrom / resolveSize /
	// validateSearchAfterShape)
	// -----------------------------------------------------------------------------

	@Test
	public void testResolveFromWithOmittedDefaultsToZero() throws Exception {
		// call under test
		assertEquals(0, SearchDslSanitizer.resolveFrom(MAPPER.readTree("{\"query\":{}}")));
	}

	@Test
	public void testResolveFromWithValidValue() throws Exception {
		// call under test
		assertEquals(5, SearchDslSanitizer.resolveFrom(MAPPER.readTree("{\"from\":5}")));
	}

	@Test
	public void testResolveFromWithNonIntegralRejected() throws Exception {
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				// call under test
				() -> SearchDslSanitizer.resolveFrom(MAPPER.readTree("{\"from\":1.5}")));
		assertTrue(ex.getMessage().contains("body.from must be an integer"));
	}

	@Test
	public void testResolveFromWithNegativeRejected() throws Exception {
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				// call under test
				() -> SearchDslSanitizer.resolveFrom(MAPPER.readTree("{\"from\":-1}")));
		assertTrue(ex.getMessage().contains("body.from must be between 0 and"));
	}

	@Test
	public void testResolveFromWithOverflowRejected() throws Exception {
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				// call under test
				() -> SearchDslSanitizer.resolveFrom(MAPPER.readTree(
						"{\"from\":" + (Integer.MAX_VALUE + 1L) + "}")));
		assertTrue(ex.getMessage().contains("body.from must be between 0 and"));
	}

	@Test
	public void testResolveSizeWithOmittedDefaultsToDefaultSize() throws Exception {
		// call under test
		assertEquals(25, SearchDslSanitizer.resolveSize(MAPPER.readTree("{\"query\":{}}"), 25, 100));
	}

	@Test
	public void testResolveSizeWithValidValue() throws Exception {
		// call under test
		assertEquals(50, SearchDslSanitizer.resolveSize(MAPPER.readTree("{\"size\":50}"), 25, 100));
	}

	@Test
	public void testResolveSizeWithValueAboveMaxClamps() throws Exception {
		// call under test
		assertEquals(100, SearchDslSanitizer.resolveSize(MAPPER.readTree("{\"size\":10000}"), 25, 100));
	}

	@Test
	public void testResolveSizeWithNonIntegralRejected() throws Exception {
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				// call under test
				() -> SearchDslSanitizer.resolveSize(MAPPER.readTree("{\"size\":1.5}"), 25, 100));
		assertTrue(ex.getMessage().contains("body.size must be an integer"));
	}

	@Test
	public void testResolveSizeWithNegativeRejected() throws Exception {
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				// call under test
				() -> SearchDslSanitizer.resolveSize(MAPPER.readTree("{\"size\":-1}"), 25, 100));
		assertTrue(ex.getMessage().contains("body.size must be non-negative"));
	}

	@Test
	public void testValidateSearchAfterShapeWithAbsentAccepted() throws Exception {
		// call under test — must not throw
		SearchDslSanitizer.validateSearchAfterShape(MAPPER.readTree("{\"query\":{}}"));
	}

	@Test
	public void testValidateSearchAfterShapeWithArrayAccepted() throws Exception {
		// call under test — must not throw
		SearchDslSanitizer.validateSearchAfterShape(MAPPER.readTree("{\"search_after\":[\"x\",100]}"));
	}

	@Test
	public void testValidateSearchAfterShapeWithNonArrayRejected() throws Exception {
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				// call under test
				() -> SearchDslSanitizer.validateSearchAfterShape(
						MAPPER.readTree("{\"search_after\":\"x\"}")));
		assertTrue(ex.getMessage().contains("body.search_after must be an array"));
	}

	// ---------- copyQueryContainer ----------

	@Test
	public void testCopyQueryContainerWithDepthOverLimitRejected() throws Exception {
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				// call under test
				() -> SearchDslSanitizer.copyQueryContainer(MAPPER.readTree("{\"match_all\":{}}"),
						SearchDslValidator.QUERY_MAX_DEPTH + 1, "query"));
		assertTrue(ex.getMessage().contains("nested too deeply"));
	}

	@Test
	public void testCopyQueryContainerWithDisallowedKindRejected() throws Exception {
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				// call under test
				() -> SearchDslSanitizer.copyQueryContainer(MAPPER.readTree("{\"span_near\":{}}"),
						1, "query"));
		assertTrue(ex.getMessage().contains("span_near"));
	}

	@Test
	public void testCopyQueryContainerWithBoolDispatches() throws Exception {
		JsonNode in = MAPPER.readTree("{\"bool\":{\"must\":[{\"match_all\":{}}]}}");
		// call under test
		assertEquals(in, SearchDslSanitizer.copyQueryContainer(in, 1, "query"));
	}

	@Test
	public void testCopyQueryContainerWithDisMaxDispatches() throws Exception {
		JsonNode in = MAPPER.readTree("{\"dis_max\":{\"queries\":[{\"match_all\":{}}]}}");
		// call under test
		assertEquals(in, SearchDslSanitizer.copyQueryContainer(in, 1, "query"));
	}

	@Test
	public void testCopyQueryContainerWithConstantScoreDispatches() throws Exception {
		JsonNode in = MAPPER.readTree("{\"constant_score\":{\"filter\":{\"match_all\":{}}}}");
		// call under test
		assertEquals(in, SearchDslSanitizer.copyQueryContainer(in, 1, "query"));
	}

	@Test
	public void testCopyQueryContainerWithBoostingDispatches() throws Exception {
		JsonNode in = MAPPER.readTree("{\"boosting\":{\"positive\":{\"match_all\":{}},"
				+ "\"negative\":{\"match_all\":{}},\"negative_boost\":0.5}}");
		// call under test
		assertEquals(in, SearchDslSanitizer.copyQueryContainer(in, 1, "query"));
	}

	@Test
	public void testCopyQueryContainerWithTermsDispatches() throws Exception {
		JsonNode in = MAPPER.readTree("{\"terms\":{\"f\":[\"x\",\"y\"]}}");
		// call under test
		assertEquals(in, SearchDslSanitizer.copyQueryContainer(in, 1, "query"));
	}

	@Test
	public void testCopyQueryContainerWithLeafDispatches() throws Exception {
		JsonNode in = MAPPER.readTree("{\"match\":{\"f\":\"x\"}}");
		// call under test
		assertEquals(in, SearchDslSanitizer.copyQueryContainer(in, 1, "query"));
	}

	// ---------- copyLeafQuery ----------

	@Test
	public void testCopyLeafQueryWithShorthandScalar() throws Exception {
		JsonNode in = MAPPER.readTree("{\"f\":\"x\"}");
		// call under test
		assertEquals(in, SearchDslSanitizer.copyLeafQuery("match", in, "query.match"));
	}

	@Test
	public void testCopyLeafQueryWithShorthandOptionsObject() throws Exception {
		JsonNode in = MAPPER.readTree("{\"f\":{\"query\":\"x\",\"boost\":2}}");
		// call under test
		assertEquals(in, SearchDslSanitizer.copyLeafQuery("match", in, "query.match"));
	}

	@Test
	public void testCopyLeafQueryWithLongFormFieldAndOptions() throws Exception {
		JsonNode in = MAPPER.readTree("{\"field\":\"f\",\"query\":\"x\"}");
		// call under test
		assertEquals(in, SearchDslSanitizer.copyLeafQuery("match", in, "query.match"));
	}

	@Test
	public void testCopyLeafQueryWithUnknownOptionRejected() throws Exception {
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				// call under test
				() -> SearchDslSanitizer.copyLeafQuery("match",
						MAPPER.readTree("{\"field\":\"f\",\"bogus\":1}"), "query.match"));
		assertTrue(ex.getMessage().contains("bogus"));
	}

	// ---------- copyTerms ----------

	@Test
	public void testCopyTermsWithInlineValuesRoundTrips() throws Exception {
		JsonNode in = MAPPER.readTree("{\"f\":[\"x\",\"y\"]}");
		// call under test
		assertEquals(in, SearchDslSanitizer.copyTerms(in, "query.terms"));
	}

	@Test
	public void testCopyTermsWithSiblingKeys() throws Exception {
		JsonNode in = MAPPER.readTree("{\"title\":[\"x\"],\"boost\":2,\"_name\":\"n\"}");
		// call under test
		assertEquals(in, SearchDslSanitizer.copyTerms(in, "query.terms"));
	}

	@Test
	public void testCopyTermsWithMultipleFieldsRejected() throws Exception {
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				// call under test
				() -> SearchDslSanitizer.copyTerms(MAPPER.readTree("{\"a\":[\"x\"],\"b\":[\"y\"]}"),
						"query.terms"));
		assertTrue(ex.getMessage().contains("only one field"));
	}

	@Test
	public void testCopyTermsWithLookupFormRejected() throws Exception {
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				// call under test
				() -> SearchDslSanitizer.copyTerms(
						MAPPER.readTree("{\"f\":{\"index\":\"i\",\"id\":\"1\"}}"), "query.terms"));
		assertTrue(ex.getMessage().contains("lookup"));
	}

	// ---------- copyBool / copyDisMax / copyConstantScore / copyBoosting ----------

	@Test
	public void testCopyBoolWithEverySlotRoundTrips() throws Exception {
		JsonNode in = MAPPER.readTree("{\"must\":[{\"match_all\":{}}],\"should\":[{\"match_all\":{}}],"
				+ "\"must_not\":[{\"match_all\":{}}],\"filter\":[{\"match_all\":{}}],\"boost\":1.0,"
				+ "\"minimum_should_match\":1}");
		// call under test
		assertEquals(in, SearchDslSanitizer.copyBool(in, 1, "query.bool"));
	}

	@Test
	public void testCopyBoolWithScalarOptions() throws Exception {
		JsonNode in = MAPPER.readTree("{\"_name\":\"n\",\"boost\":2,\"adjust_pure_negative\":true}");
		// call under test
		assertEquals(in, SearchDslSanitizer.copyBool(in, 1, "query.bool"));
	}

	@Test
	public void testCopyBoolWithUnknownKeyRejected() throws Exception {
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				// call under test
				() -> SearchDslSanitizer.copyBool(MAPPER.readTree("{\"bogus\":1}"), 1, "query.bool"));
		assertTrue(ex.getMessage().contains("bogus"));
	}

	@Test
	public void testCopyDisMaxWithQueriesRoundTrips() throws Exception {
		JsonNode in = MAPPER.readTree("{\"queries\":[{\"match_all\":{}}],\"tie_breaker\":0.5,"
				+ "\"boost\":1.0,\"_name\":\"n\"}");
		// call under test
		assertEquals(in, SearchDslSanitizer.copyDisMax(in, 1, "query.dis_max"));
	}

	@Test
	public void testCopyDisMaxWithUnknownKeyRejected() throws Exception {
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				// call under test
				() -> SearchDslSanitizer.copyDisMax(MAPPER.readTree(
						"{\"queries\":[{\"match_all\":{}}],\"bogus\":1}"), 1, "query.dis_max"));
		assertTrue(ex.getMessage().contains("bogus"));
	}

	@Test
	public void testCopyConstantScoreWithFilterRoundTrips() throws Exception {
		JsonNode in = MAPPER.readTree("{\"filter\":{\"match_all\":{}},\"boost\":1.0,\"_name\":\"n\"}");
		// call under test
		assertEquals(in, SearchDslSanitizer.copyConstantScore(in, 1, "query.constant_score"));
	}

	@Test
	public void testCopyConstantScoreWithUnknownKeyRejected() throws Exception {
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				// call under test
				() -> SearchDslSanitizer.copyConstantScore(MAPPER.readTree(
						"{\"filter\":{\"match_all\":{}},\"bogus\":1}"), 1, "query.constant_score"));
		assertTrue(ex.getMessage().contains("bogus"));
	}

	@Test
	public void testCopyBoostingWithPositiveNegativeRoundTrips() throws Exception {
		JsonNode in = MAPPER.readTree("{\"positive\":{\"match_all\":{}},\"negative\":{\"match_all\":{}},"
				+ "\"negative_boost\":0.5,\"boost\":1.0,\"_name\":\"n\"}");
		// call under test
		assertEquals(in, SearchDslSanitizer.copyBoosting(in, 1, "query.boosting"));
	}

	@Test
	public void testCopyBoostingWithUnknownKeyRejected() throws Exception {
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				// call under test
				() -> SearchDslSanitizer.copyBoosting(MAPPER.readTree(
						"{\"positive\":{\"match_all\":{}},\"bogus\":1}"), 1, "query.boosting"));
		assertTrue(ex.getMessage().contains("bogus"));
	}

	// ---------- copyQueryListOrSingle ----------

	@Test
	public void testCopyQueryListOrSingleWithArray() throws Exception {
		JsonNode in = MAPPER.readTree("[{\"match_all\":{}},{\"term\":{\"f\":\"v\"}}]");
		// call under test
		assertEquals(in, SearchDslSanitizer.copyQueryListOrSingle(in, 1, "query.bool.must"));
	}

	@Test
	public void testCopyQueryListOrSingleWithSingleObject() throws Exception {
		JsonNode in = MAPPER.readTree("{\"match_all\":{}}");
		// call under test
		assertEquals(in, SearchDslSanitizer.copyQueryListOrSingle(in, 1, "query.bool.must"));
	}

	// ---------- copyAggregationContainer ----------

	@Test
	public void testCopyAggregationContainerWithSubAggsAndMeta() throws Exception {
		JsonNode in = MAPPER.readTree("{\"terms\":{\"field\":\"f\"},"
				+ "\"aggregations\":{\"sub\":{\"avg\":{\"field\":\"s\"}}},\"meta\":{\"x\":1}}");
		// call under test
		assertEquals(in, SearchDslSanitizer.copyAggregationContainer(in, "aggregations.a"));
	}

	@Test
	public void testCopyAggregationContainerWithMultipleTypesRejected() throws Exception {
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				// call under test
				() -> SearchDslSanitizer.copyAggregationContainer(
						MAPPER.readTree("{\"terms\":{},\"avg\":{}}"), "aggregations.a"));
		assertTrue(ex.getMessage().contains("more than one type"));
	}

	@Test
	public void testCopyAggregationContainerWithNoTypeRejected() throws Exception {
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				// call under test
				() -> SearchDslSanitizer.copyAggregationContainer(
						MAPPER.readTree("{\"meta\":{}}"), "aggregations.a"));
		assertTrue(ex.getMessage().contains("no recognized aggregation type"));
	}

	// ---------- copyAggregationBody ----------

	@Test
	public void testCopyAggregationBodyWithEachAllowedKind() throws Exception {
		// Every allowlisted kind reaches its own switch arm. Guard against AGG_BODY_KEYS so a
		// newly-added aggregation kind forces a matching case here.
		JsonNode empty = MAPPER.readTree("{}");
		Set<String> covered = new HashSet<>();
		for (String kind : SearchDslSanitizer.AGG_BODY_KEYS.keySet()) {
			// call under test
			SearchDslSanitizer.copyAggregationBody(kind, empty, "aggregations.a." + kind);
			covered.add(kind);
		}
		assertEquals(SearchDslSanitizer.AGG_BODY_KEYS.keySet(), covered);
	}

	@Test
	public void testCopyAggregationBodyWithDisallowedKindRejected() throws Exception {
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				// call under test
				() -> SearchDslSanitizer.copyAggregationBody("global", MAPPER.readTree("{}"),
						"aggregations.a.global"));
		assertTrue(ex.getMessage().contains("global"));
	}

	// ---------- copyHighlightLevel ----------

	@Test
	public void testCopyHighlightLevelWithTopLevelFieldsAndQuery() throws Exception {
		JsonNode in = MAPPER.readTree("{\"pre_tags\":[\"<em>\"],\"highlight_query\":{\"match_all\":{}},"
				+ "\"fields\":{\"title\":{\"number_of_fragments\":3}}}");
		// call under test
		assertEquals(in, SearchDslSanitizer.copyHighlightLevel(in, true, "highlight"));
	}

	@Test
	public void testCopyHighlightLevelWithPerFieldKeys() throws Exception {
		JsonNode in = MAPPER.readTree("{\"number_of_fragments\":3,\"fragment_size\":100}");
		// call under test
		assertEquals(in, SearchDslSanitizer.copyHighlightLevel(in, false, "highlight.fields.title"));
	}

	@Test
	public void testCopyHighlightLevelWithUnknownTopKeyRejected() throws Exception {
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				// call under test
				() -> SearchDslSanitizer.copyHighlightLevel(MAPPER.readTree("{\"bogus\":1}"), true,
						"highlight"));
		assertTrue(ex.getMessage().contains("bogus"));
	}

	@Test
	public void testCopyHighlightLevelWithUnknownPerFieldKeyRejected() throws Exception {
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				// call under test
				() -> SearchDslSanitizer.copyHighlightLevel(MAPPER.readTree("{\"bogus\":1}"), false,
						"highlight.fields.title"));
		assertTrue(ex.getMessage().contains("bogus"));
	}

	// ---------- copyRescoreQuery ----------

	@Test
	public void testCopyRescoreQueryWithRescoreQueryRoundTrips() throws Exception {
		JsonNode in = MAPPER.readTree("{\"rescore_query\":{\"match_all\":{}},\"query_weight\":1.0}");
		// call under test
		assertEquals(in, SearchDslSanitizer.copyRescoreQuery(in, "rescore.query"));
	}

	@Test
	public void testCopyRescoreQueryWithScoreKeys() throws Exception {
		JsonNode in = MAPPER.readTree("{\"query_weight\":1,\"rescore_query_weight\":2,"
				+ "\"score_mode\":\"total\"}");
		// call under test
		assertEquals(in, SearchDslSanitizer.copyRescoreQuery(in, "rescore.query"));
	}

	@Test
	public void testCopyRescoreQueryWithUnknownKeyRejected() throws Exception {
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				// call under test
				() -> SearchDslSanitizer.copyRescoreQuery(MAPPER.readTree("{\"bogus\":1}"),
						"rescore.query"));
		assertTrue(ex.getMessage().contains("bogus"));
	}

	// ---------- copySortElement / copySortObject ----------

	@Test
	public void testCopySortElementWithString() throws Exception {
		JsonNode in = MAPPER.readTree("\"title\"");
		// call under test
		assertEquals(in, SearchDslSanitizer.copySortElement(in, "sort[0]"));
	}

	@Test
	public void testCopySortElementWithObject() throws Exception {
		JsonNode in = MAPPER.readTree("{\"year\":{\"order\":\"asc\"}}");
		// call under test
		assertEquals(in, SearchDslSanitizer.copySortElement(in, "sort[0]"));
	}

	@Test
	public void testCopySortElementWithInvalidNodeRejected() throws Exception {
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				// call under test
				() -> SearchDslSanitizer.copySortElement(MAPPER.readTree("123"), "sort[0]"));
		assertTrue(ex.getMessage().contains("field name or a sort options object"));
	}

	@Test
	public void testCopySortObjectWithScore() throws Exception {
		JsonNode in = MAPPER.readTree("{\"_score\":{\"order\":\"desc\"}}");
		// call under test
		assertEquals(in, SearchDslSanitizer.copySortObject(in, "sort[0]"));
	}

	@Test
	public void testCopySortObjectWithUnderscoreKeyRejected() throws Exception {
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				// call under test
				() -> SearchDslSanitizer.copySortObject(MAPPER.readTree("{\"_script\":{}}"), "sort[0]"));
		assertTrue(ex.getMessage().contains("_script"));
	}

	@Test
	public void testCopySortObjectWithFieldOptionsObject() throws Exception {
		JsonNode in = MAPPER.readTree("{\"year\":{\"order\":\"asc\",\"mode\":\"min\"}}");
		// call under test
		assertEquals(in, SearchDslSanitizer.copySortObject(in, "sort[0]"));
	}

	@Test
	public void testCopySortObjectWithScalarFieldValue() throws Exception {
		JsonNode in = MAPPER.readTree("{\"year\":\"asc\"}");
		// call under test
		assertEquals(in, SearchDslSanitizer.copySortObject(in, "sort[0]"));
	}

	// ---------- copyAllowlistedKeys ----------

	@Test
	public void testCopyAllowlistedKeysWithAllowedKeys() throws Exception {
		JsonNode in = MAPPER.readTree("{\"a\":1,\"b\":2}");
		// call under test
		assertEquals(in, SearchDslSanitizer.copyAllowlistedKeys(in, Set.of("a", "b"), "surface", "p"));
	}

	@Test
	public void testCopyAllowlistedKeysWithDisallowedKeyRejected() throws Exception {
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				// call under test
				() -> SearchDslSanitizer.copyAllowlistedKeys(MAPPER.readTree("{\"a\":1,\"c\":3}"),
						Set.of("a"), "surface", "p"));
		assertTrue(ex.getMessage().contains("c"));
	}

	// ---------- copyOpaque ----------

	@Test
	public void testCopyOpaqueWithDepthOverLimitRejected() throws Exception {
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				// call under test
				() -> SearchDslSanitizer.copyOpaque(MAPPER.readTree("{\"a\":1}"), "meta",
						SearchDslSanitizer.FORBIDDEN_SCAN_MAX_DEPTH + 1));
		assertTrue(ex.getMessage().contains("too deeply"));
	}

	@Test
	public void testCopyOpaqueWithForbiddenKeyRejected() throws Exception {
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				// call under test
				() -> SearchDslSanitizer.copyOpaque(MAPPER.readTree("{\"script\":{}}"), "meta"));
		assertTrue(ex.getMessage().contains("script"));
	}

	@Test
	public void testCopyOpaqueWithNestedObjectAndArrayRoundTrips() throws Exception {
		JsonNode in = MAPPER.readTree("{\"a\":[1,2,{\"b\":\"c\"}]}");
		// call under test
		assertEquals(in, SearchDslSanitizer.copyOpaque(in, "meta"));
	}

	@Test
	public void testCopyOpaqueWithScalar() throws Exception {
		JsonNode in = MAPPER.readTree("5");
		// call under test
		assertEquals(in, SearchDslSanitizer.copyOpaque(in, "meta"));
	}

	// ---------- singleClauseKind ----------

	@Test
	public void testSingleClauseKindWithEmptyRejected() throws Exception {
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				// call under test
				() -> SearchDslSanitizer.singleClauseKind(MAPPER.readTree("{}"), "query"));
		assertTrue(ex.getMessage().contains("a query clause is required"));
	}

	@Test
	public void testSingleClauseKindWithMultipleKeysRejected() throws Exception {
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				// call under test
				() -> SearchDslSanitizer.singleClauseKind(MAPPER.readTree("{\"a\":1,\"b\":2}"), "query"));
		assertTrue(ex.getMessage().contains("exactly one type"));
	}

	@Test
	public void testSingleClauseKindWithSingle() throws Exception {
		// call under test
		assertEquals("match_all",
				SearchDslSanitizer.singleClauseKind(MAPPER.readTree("{\"match_all\":{}}"), "query"));
	}

	// ---------- requireObject ----------

	@Test
	public void testRequireObjectWithNullRejected() {
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				// call under test
				() -> SearchDslSanitizer.requireObject(null, "query"));
		assertTrue(ex.getMessage().contains("expected a JSON object"));
	}

	@Test
	public void testRequireObjectWithNonObjectRejected() throws Exception {
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				// call under test
				() -> SearchDslSanitizer.requireObject(MAPPER.readTree("[]"), "query"));
		assertTrue(ex.getMessage().contains("expected a JSON object"));
	}
}
