package org.sagebionetworks.repo.manager.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.json.JsonData;
import org.opensearch.client.opensearch._types.ErrorCause;
import org.opensearch.client.opensearch._types.ErrorResponse;
import org.opensearch.client.opensearch._types.FieldSort;
import org.opensearch.client.opensearch._types.FieldValue;
import org.opensearch.client.opensearch._types.OpenSearchException;
import org.opensearch.client.opensearch._types.ShardSearchFailure;
import org.opensearch.client.opensearch._types.ShardStatistics;
import org.opensearch.client.opensearch._types.SortOptions;
import org.opensearch.client.opensearch._types.SortOrder;
import org.opensearch.client.opensearch._types.aggregations.Aggregation;
import org.opensearch.client.opensearch._types.aggregations.LongTermsBucketKey;
import org.opensearch.client.opensearch._types.analysis.Analyzer;
import org.opensearch.client.opensearch._types.analysis.CustomAnalyzer;
import org.opensearch.client.opensearch._types.query_dsl.BoolQuery;
import org.opensearch.client.opensearch._types.query_dsl.Query;
import org.opensearch.client.opensearch._types.query_dsl.RangeQuery;
import org.opensearch.client.opensearch._types.query_dsl.TermsQuery;
import org.opensearch.client.opensearch._types.query_dsl.TextQueryType;
import org.opensearch.client.opensearch.core.BulkRequest;
import org.opensearch.client.opensearch.core.BulkResponse;
import org.opensearch.client.opensearch.core.DeleteRequest;
import org.opensearch.client.opensearch.core.DeleteResponse;
import org.opensearch.client.opensearch.core.IndexRequest;
import org.opensearch.client.opensearch.core.IndexResponse;
import org.opensearch.client.opensearch.core.SearchRequest;
import org.opensearch.client.opensearch.core.SearchResponse;
import org.opensearch.client.opensearch.core.bulk.BulkOperation;
import org.opensearch.client.opensearch.core.bulk.BulkResponseItem;
import org.opensearch.client.opensearch.core.search.HighlightField;
import org.opensearch.client.opensearch.core.search.HitsMetadata;
import org.opensearch.client.opensearch.core.search.TotalHits;
import org.opensearch.client.opensearch.core.search.TotalHitsRelation;
import org.opensearch.client.opensearch.core.search.TrackHits;
import org.opensearch.client.opensearch.indices.CreateIndexRequest;
import org.opensearch.client.opensearch.indices.IndexSettingsAnalysis;
import org.opensearch.client.opensearch.indices.OpenSearchIndicesClient;
import org.sagebionetworks.repo.model.search.FacetRequest;
import org.sagebionetworks.repo.model.search.FacetSortField;
import org.sagebionetworks.repo.model.search.KeyRange;
import org.sagebionetworks.repo.model.search.KeyValues;
import org.sagebionetworks.repo.model.search.SearchQuery;
import org.sagebionetworks.repo.model.search.SearchQueryPart;
import org.sagebionetworks.repo.model.search.SearchQueryType;
import org.sagebionetworks.repo.model.search.SortDirection;
import org.sagebionetworks.repo.model.search.SortField;
import org.sagebionetworks.repo.model.search.table.ColumnAnalyzerOverride;
import org.sagebionetworks.repo.model.search.table.ColumnAnalyzerOverrideEntry;
import org.sagebionetworks.repo.model.table.ColumnModel;
import org.sagebionetworks.repo.model.table.ColumnType;
import org.sagebionetworks.repo.model.table.FacetColumnResultValueCount;
import org.sagebionetworks.repo.model.table.FacetColumnResultValues;
import org.sagebionetworks.repo.model.table.FacetType;
import org.sagebionetworks.table.cluster.ColumnTypeInfo;
import org.sagebionetworks.table.query.util.ColumnTypeListMappings;
import org.sagebionetworks.workers.util.aws.message.RecoverableMessageException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Direct unit tests for the package-protected helpers on {@link OpenSearchManagerImpl}.
 * The helpers were widened from {@code private} to package-private so they can be exercised
 * per-branch here — verifying behavior on each branch directly rather than only transitively
 * through {@code search()} / {@code autocomplete()} (which is the concern of the AutoWired IT).
 */
@ExtendWith(MockitoExtension.class)
public class OpenSearchManagerImplTest {

	@Mock
	private OpenSearchClient openSearchClient;
	@Mock
	private OpenSearchIndicesClient indicesClient;

	@InjectMocks
	private OpenSearchManagerImpl manager;

	private static final ObjectMapper MAPPER = new ObjectMapper();

	/**
	 * Test helper: turn a settings JSON string into the typed {@link IndexSettingsAnalysis}
	 * the manager API takes, going through the same {@link SearchAnalyzerJsonUtil} entry point
	 * the production lifecycle uses. Tests in this class don't exercise SynonymSet $refs,
	 * so the resolver returns null and a $ref would correctly raise.
	 */
	private static IndexSettingsAnalysis toAnalysis(String settingsJson) {
		try {
			return SearchOpaqueJsonUtil.resolveAnalyzerSettings(MAPPER.readTree(settingsJson), qname -> null);
		} catch (java.io.IOException e) {
			throw new RuntimeException(e);
		}
	}

	private long originalBulkInitialBackoffMs;
	private long originalProbeInitialBackoffMs;
	private long originalCountProbeInitialBackoffMs;
	private long originalSentinelCleanupInitialBackoffMs;

	@BeforeEach
	public void setUp() {
		// Drop bulk-index retry backoff to 1ms in tests so retry-exhaustion paths don't
		// actually sleep ~21s per invocation. Restored in @AfterEach.
		originalBulkInitialBackoffMs = OpenSearchManagerImpl.BULK_INDEX_INITIAL_BACKOFF_MS;
		OpenSearchManagerImpl.BULK_INDEX_INITIAL_BACKOFF_MS = 1L;
		originalProbeInitialBackoffMs = OpenSearchManagerImpl.INDEX_WRITABLE_INITIAL_BACKOFF_MS;
		OpenSearchManagerImpl.INDEX_WRITABLE_INITIAL_BACKOFF_MS = 1L;
		originalCountProbeInitialBackoffMs = OpenSearchManagerImpl.COUNT_PROBE_INITIAL_BACKOFF_MS;
		OpenSearchManagerImpl.COUNT_PROBE_INITIAL_BACKOFF_MS = 1L;
		originalSentinelCleanupInitialBackoffMs = OpenSearchManagerImpl.SENTINEL_CLEANUP_INITIAL_BACKOFF_MS;
		OpenSearchManagerImpl.SENTINEL_CLEANUP_INITIAL_BACKOFF_MS = 1L;
	}

	@AfterEach
	public void tearDown() {
		OpenSearchManagerImpl.BULK_INDEX_INITIAL_BACKOFF_MS = originalBulkInitialBackoffMs;
		OpenSearchManagerImpl.INDEX_WRITABLE_INITIAL_BACKOFF_MS = originalProbeInitialBackoffMs;
		OpenSearchManagerImpl.COUNT_PROBE_INITIAL_BACKOFF_MS = originalCountProbeInitialBackoffMs;
		OpenSearchManagerImpl.SENTINEL_CLEANUP_INITIAL_BACKOFF_MS = originalSentinelCleanupInitialBackoffMs;
	}

	// --- toLong ---

	@ParameterizedTest(name = "toLong({0}) = {1}")
	@MethodSource("toLongProvider")
	public void testToLong(Object input, Long expected) {
		// call under test
		assertEquals(expected, manager.toLong(input));
	}

	static Stream<Arguments> toLongProvider() {
		return Stream.of(
				Arguments.of(Integer.valueOf(42), Long.valueOf(42)),         // Number branch — Integer
				Arguments.of(Long.valueOf(42), Long.valueOf(42)),            // Number branch — Long
				Arguments.of("42", Long.valueOf(42)),                        // String branch — parseable
				Arguments.of("not-a-number", null),                          // String branch — NumberFormatException → null
				Arguments.of(null, null));                                   // String branch — String.valueOf(null) = "null" → NFE → null
	}

	// --- toAossKey ---

	@Test
	public void testToAossKeyEncodesDots() {
		// AOSS rejects '.' inside settings keys (it treats them as JSON-path separators), so
		// the qname-to-AOSS-key translation encodes dots at the wire boundary.
		assertEquals("org__dot__sagebionetworks-SCIENTIFIC",
				OpenSearchManagerImpl.toAossKey("org.sagebionetworks-SCIENTIFIC"));
	}

	@Test
	public void testToAossKeyWithoutDotsIsUnchanged() {
		// Underscores in the qname are preserved verbatim — the dot-encoding scheme is bijective
		// so qnames with underscores can never collide with qnames containing dots.
		assertEquals("biomed-medical_terms",
				OpenSearchManagerImpl.toAossKey("biomed-medical_terms"));
	}

	@Test
	public void testToAossKeyEncodingIsBijective() {
		// Two qnames that differ only in `.` vs `_` placement must encode to different
		// AOSS keys, otherwise the analysis registry collapses them to a single namespaced
		// component and the wrong TextAnalyzer wins.
		assertNotEquals(
				OpenSearchManagerImpl.toAossKey("org.sage-A.B"),
				OpenSearchManagerImpl.toAossKey("org_sage-A_B"));
	}

	@Test
	public void testToAossKeyWithNullReturnsNull() {
		assertNull(OpenSearchManagerImpl.toAossKey(null));
	}

	// --- rewriteOwnedReferences ---

	@Test
	public void testRewriteOwnedReferencesRewritesOwnedReferences() {
		// A CustomAnalyzer with a tokenizer reference, char_filter chain, and filter chain
		// mixing owned and built-in names. Owned names get namespaced; built-ins pass through.
		Analyzer entry = Analyzer.of(b -> b.custom(c -> c
				.tokenizer("std")
				.charFilter(Arrays.asList("strip_html", "icu_normalizer"))
				.filter(Arrays.asList("lowercase", "my_syn", "english_stop"))));
		Set<String> ownedCharFilters = new HashSet<>();
		ownedCharFilters.add("strip_html");
		Set<String> ownedTokenizers = new HashSet<>();
		ownedTokenizers.add("std");
		Set<String> ownedFilters = new HashSet<>();
		ownedFilters.add("my_syn");
		ownedFilters.add("english_stop");

		// call under test
		Analyzer rewritten = OpenSearchManagerImpl.rewriteOwnedReferences(entry,
				"biomed-publications", ownedCharFilters, ownedFilters, ownedTokenizers);

		assertTrue(rewritten.isCustom());
		CustomAnalyzer custom = rewritten.custom();
		assertEquals("biomed-publications__std", custom.tokenizer());
		assertEquals(Arrays.asList("biomed-publications__strip_html", "icu_normalizer"),
				custom.charFilter());
		assertEquals(Arrays.asList("lowercase", "biomed-publications__my_syn",
				"biomed-publications__english_stop"), custom.filter());
	}

	@Test
	public void testRewriteOwnedReferencesWithBuiltInsOnlyIsIdempotent() {
		Analyzer entry = Analyzer.of(b -> b.custom(c -> c
				.tokenizer("standard")
				.filter(Arrays.asList("lowercase", "english_stop"))));

		// call under test — no owned names anywhere; all references should pass through.
		Analyzer rewritten = OpenSearchManagerImpl.rewriteOwnedReferences(entry, "org-X",
				new HashSet<>(), new HashSet<>(), new HashSet<>());

		assertTrue(rewritten.isCustom());
		assertEquals("standard", rewritten.custom().tokenizer());
		assertEquals(Arrays.asList("lowercase", "english_stop"), rewritten.custom().filter());
	}

	@Test
	public void testRewriteOwnedReferencesPassesThroughNonCustomAnalyzers() {
		// A built-in analyzer (KeywordAnalyzer/StandardAnalyzer/etc.) has no chain and no
		// references to local components — return as-is.
		Analyzer keyword = Analyzer.of(b -> b.keyword(k -> k));

		// call under test
		Analyzer rewritten = OpenSearchManagerImpl.rewriteOwnedReferences(keyword, "org-X",
				new HashSet<>(), new HashSet<>(), new HashSet<>());

		assertTrue(rewritten.isKeyword());
	}

	// --- isConcurrentDeleteError ---

	@Test
	public void testIsConcurrentDeleteErrorMatchesAOSSMarker() {
		// Belt-and-braces: the static helper recognizes AOSS's "concurrent deletes" rejection
		// so the lifecycle worker can map it to a recoverable SQS retry.
		OpenSearchException e = new OpenSearchException(
				ErrorResponse.of(b -> b.status(400)
						.error(c -> c.type("any").reason("concurrent deletes detected"))));
		assertTrue(OpenSearchManagerImpl.isConcurrentDeleteError(e));
	}

	@Test
	public void testIsConcurrentDeleteErrorWithUnrelatedReasonReturnsFalse() {
		OpenSearchException e = new OpenSearchException(
				ErrorResponse.of(b -> b.status(400)
						.error(c -> c.type("validation_exception").reason("some other rejection"))));
		assertFalse(OpenSearchManagerImpl.isConcurrentDeleteError(e));
	}

	// --- isRetryableItemStatus ---

	@Test
	public void testIsRetryableItemStatusFor429() {
		assertTrue(OpenSearchManagerImpl.isRetryableItemStatus(429));
	}

	@Test
	public void testIsRetryableItemStatusForServerErrors() {
		assertTrue(OpenSearchManagerImpl.isRetryableItemStatus(500));
		assertTrue(OpenSearchManagerImpl.isRetryableItemStatus(503));
		assertTrue(OpenSearchManagerImpl.isRetryableItemStatus(599));
	}

	@Test
	public void testIsRetryableItemStatusForClientErrorsIsFalse() {
		// 4xx other than 429 are permanent — bad request, conflict, etc. shouldn't be retried.
		assertFalse(OpenSearchManagerImpl.isRetryableItemStatus(400));
		assertFalse(OpenSearchManagerImpl.isRetryableItemStatus(404));
		assertFalse(OpenSearchManagerImpl.isRetryableItemStatus(409));
	}

	@Test
	public void testIsRetryableItemStatusForSuccessIsFalse() {
		assertFalse(OpenSearchManagerImpl.isRetryableItemStatus(200));
		assertFalse(OpenSearchManagerImpl.isRetryableItemStatus(201));
	}

	// --- buildPermanentFailureMessage ---

	@Test
	public void testBuildPermanentFailureMessageWithNoSamplesReturnsSummary() {
		assertEquals("the summary",
				OpenSearchManagerImpl.buildPermanentFailureMessage("the summary", Collections.emptyList()));
	}

	@Test
	public void testBuildPermanentFailureMessageAppendsSamples() {
		String result = OpenSearchManagerImpl.buildPermanentFailureMessage(
				"summary", Arrays.asList("doc1 failed", "doc2 failed"));
		assertEquals("summary. Sample failures:\n - doc1 failed\n - doc2 failed", result);
	}

	@Test
	public void testBuildPermanentFailureMessageTruncatesAtCap() {
		// Build a sample big enough to push past MAX_BULK_ERROR_MESSAGE_CHARS so the helper
		// substring-truncates the tail with the marker.
		StringBuilder huge = new StringBuilder();
		for (int i = 0; i < OpenSearchManagerImpl.MAX_BULK_ERROR_MESSAGE_CHARS; i++) {
			huge.append('x');
		}
		String result = OpenSearchManagerImpl.buildPermanentFailureMessage(
				"summary", Collections.singletonList(huge.toString()));
		assertEquals(OpenSearchManagerImpl.MAX_BULK_ERROR_MESSAGE_CHARS, result.length());
		assertTrue(result.endsWith(OpenSearchManagerImpl.TRUNCATION_MARKER));
	}

	// --- buildOverrideMap ---

	@Test
	public void testBuildOverrideMapWithNullReturnsEmpty() {
		// call under test
		assertTrue(manager.buildOverrideMap(null, Collections.emptyMap()).isEmpty());
	}

	@Test
	public void testBuildOverrideMapResolvesNameToId() {
		ColumnAnalyzerOverrideEntry entry = new ColumnAnalyzerOverrideEntry()
				.setColumnName("geneName")
				.setAnalyzer(new org.json.JSONObject().put("$ref", "org.sage-AUTOCOMPLETE"));
		ColumnAnalyzerOverride override = new ColumnAnalyzerOverride()
				.setOverrides(Collections.singletonList(entry));
		Map<String, String> nameToId = new HashMap<>();
		nameToId.put("geneName", "111");

		// call under test — "geneName" resolves to id "111"
		Map<String, ColumnAnalyzerOverrideEntry> map = manager.buildOverrideMap(
				Collections.singletonList(override), nameToId);

		assertEquals(1, map.size());
		assertEquals(entry, map.get("111"));
	}

	@Test
	public void testBuildOverrideMapSkipsUnknownColumnName() {
		ColumnAnalyzerOverrideEntry entry = new ColumnAnalyzerOverrideEntry()
				.setColumnName("unknownColumn")
				.setAnalyzer(new org.json.JSONObject().put("$ref", "org.sage-AUTOCOMPLETE"));
		ColumnAnalyzerOverride override = new ColumnAnalyzerOverride()
				.setOverrides(Collections.singletonList(entry));

		// call under test — "unknownColumn" is not in nameToId, so the entry is silently dropped
		Map<String, ColumnAnalyzerOverrideEntry> map = manager.buildOverrideMap(
				Collections.singletonList(override), Collections.emptyMap());

		assertTrue(map.isEmpty());
	}

	@Test
	public void testBuildOverrideMapFirstEntryWinsOnDuplicate() {
		// Two overrides targeting the same column — the first one wins (putIfAbsent)
		ColumnAnalyzerOverrideEntry first = new ColumnAnalyzerOverrideEntry()
				.setColumnName("geneName").setAnalyzer(new org.json.JSONObject().put("$ref", "FIRST"));
		ColumnAnalyzerOverrideEntry second = new ColumnAnalyzerOverrideEntry()
				.setColumnName("geneName").setAnalyzer(new org.json.JSONObject().put("$ref", "SECOND"));
		Map<String, String> nameToId = new HashMap<>();
		nameToId.put("geneName", "111");

		// call under test
		Map<String, ColumnAnalyzerOverrideEntry> map = manager.buildOverrideMap(
				Arrays.asList(
						new ColumnAnalyzerOverride().setOverrides(Collections.singletonList(first)),
						new ColumnAnalyzerOverride().setOverrides(Collections.singletonList(second))),
				nameToId);

		assertEquals(first, map.get("111"));
	}

	@Test
	public void testBuildOverrideMapSkipsOverridesWithNullEntries() {
		ColumnAnalyzerOverride override = new ColumnAnalyzerOverride();
		// overrides list is null

		// call under test
		Map<String, ColumnAnalyzerOverrideEntry> map = manager.buildOverrideMap(
				Collections.singletonList(override), Collections.emptyMap());

		assertTrue(map.isEmpty());
	}

	// --- getFilterFieldName ---

	@Test
	public void testGetFilterFieldNameForUnknownColumnReturnsId() {
		// Defensive: an unknown column id (e.g. system field _row_id) should pass through
		// unmodified rather than throwing.
		assertEquals("999", manager.getFilterFieldName("999", Collections.emptyMap()));
	}

	@Test
	public void testGetFilterFieldNameForTextColumnAppendsKeyword() {
		Map<String, ColumnModel> columnMap = new HashMap<>();
		columnMap.put("111",
				new ColumnModel().setId("111").setName("name").setColumnType(ColumnType.STRING));

		// call under test — text types route to the `.keyword` sub-field for filtering
		assertEquals("111.keyword", manager.getFilterFieldName("111", columnMap));
	}

	@Test
	public void testGetFilterFieldNameForLinkColumnAppendsKeyword() {
		Map<String, ColumnModel> columnMap = new HashMap<>();
		columnMap.put("222",
				new ColumnModel().setId("222").setName("link").setColumnType(ColumnType.LINK));

		// call under test — LINK shares the TEXT mapping, so .keyword sub-field for exact match
		assertEquals("222.keyword", manager.getFilterFieldName("222", columnMap));
	}

	@Test
	public void testGetFilterFieldNameForNumericColumnReturnsId() {
		Map<String, ColumnModel> columnMap = new HashMap<>();
		columnMap.put("333",
				new ColumnModel().setId("333").setName("age").setColumnType(ColumnType.INTEGER));

		// call under test — numeric columns don't need a sub-field; id returned as-is
		assertEquals("333", manager.getFilterFieldName("333", columnMap));
	}

	// --- buildSortOptions ---

	@Test
	public void testBuildSortOptionsWithNullReturnsScoreDesc() {
		// When no sort fields are specified, default to _score DESC.
		// call under test
		List<SortOptions> sorted = manager.buildSortOptions(null,
				Collections.emptyMap(), Collections.emptyMap());

		assertEquals(1, sorted.size());
		FieldSort fs = sorted.get(0).field();
		assertEquals("_score", fs.field());
		assertEquals(SortOrder.Desc, fs.order());
	}

	@Test
	public void testBuildSortOptionsPreservesScoreFieldNameUntouched() {
		// _score must NOT be translated through nameToId — it's a pseudo-field
		SortField sf = new SortField().setColumnName("_score").setDirection(SortDirection.DESC);

		// call under test
		List<SortOptions> sorted = manager.buildSortOptions(Collections.singletonList(sf),
				Collections.emptyMap(), Collections.emptyMap());

		assertEquals("_score", sorted.get(0).field().field());
	}

	@Test
	public void testBuildSortOptionsTranslatesColumnNameToFilterFieldName() {
		SortField sf = new SortField().setColumnName("name").setDirection(SortDirection.ASC);
		Map<String, ColumnModel> columnMap = new HashMap<>();
		columnMap.put("111",
				new ColumnModel().setId("111").setName("name").setColumnType(ColumnType.STRING));
		Map<String, String> nameToId = new HashMap<>();
		nameToId.put("name", "111");

		// call under test — STRING column routes to id.keyword for sorting
		List<SortOptions> sorted = manager.buildSortOptions(Collections.singletonList(sf),
				columnMap, nameToId);

		assertEquals("111.keyword", sorted.get(0).field().field());
		assertEquals(SortOrder.Asc, sorted.get(0).field().order());
	}

	// Note: convertResponse and convertHit each consume OpenSearch client value types
	// (SearchResponse<Map>, Hit<Map>) that must be constructed through the client's builder
	// API. Those helpers are exercised end-to-end by the AutoWired IT.

	// convertFieldValue stringifies a single AOSS _source value for SearchFieldValue.value.
	// Lists and maps (the *_LIST and JSON column types) must be written as canonical JSON so
	// clients can parse them back; scalars must use String.valueOf so a raw String column is
	// not double-quoted. PLFM-9625 was the latter branch silently using Java's List.toString
	// (`[a, b]`) instead of JSON.

	@Test
	public void testConvertFieldValueWithNull() {
		// call under test
		assertNull(OpenSearchManagerImpl.convertFieldValue(null));
	}

	@Test
	public void testConvertFieldValueWithString() {
		// call under test
		assertEquals("alpha", OpenSearchManagerImpl.convertFieldValue("alpha"));
	}

	@Test
	public void testConvertFieldValueWithInteger() {
		// call under test
		assertEquals("123", OpenSearchManagerImpl.convertFieldValue(123));
	}

	@Test
	public void testConvertFieldValueWithLong() {
		// call under test
		assertEquals("1609459200000", OpenSearchManagerImpl.convertFieldValue(1609459200000L));
	}

	@Test
	public void testConvertFieldValueWithDouble() {
		// call under test
		assertEquals("1.5", OpenSearchManagerImpl.convertFieldValue(1.5));
	}

	@Test
	public void testConvertFieldValueWithBoolean() {
		// call under test
		assertEquals("true", OpenSearchManagerImpl.convertFieldValue(Boolean.TRUE));
	}

	@Test
	public void testConvertFieldValueWithListOfStrings() {
		// PLFM-9625: List values must round-trip as canonical JSON, not Java List.toString().
		// call under test
		assertEquals("[\"alpha\",\"beta\"]",
				OpenSearchManagerImpl.convertFieldValue(Arrays.asList("alpha", "beta")));
	}

	@Test
	public void testConvertFieldValueWithListOfStringsContainingComma() {
		// The ticket's motivating case: a list element contains a comma, so the buggy
		// `[alpha, b,c]` form would be unparseable. JSON quoting must preserve element boundaries.
		// call under test
		assertEquals("[\"alpha\",\"b,c\"]",
				OpenSearchManagerImpl.convertFieldValue(Arrays.asList("alpha", "b,c")));
	}

	@Test
	public void testConvertFieldValueWithListOfIntegers() {
		// call under test
		assertEquals("[1,2,3]",
				OpenSearchManagerImpl.convertFieldValue(Arrays.asList(1, 2, 3)));
	}

	@Test
	public void testConvertFieldValueWithMap() {
		// JSON column type: AOSS returns a Map; must be re-serialized as canonical JSON.
		// LinkedHashMap pins key order so the asserted JSON is deterministic.
		LinkedHashMap<String, Object> map = new LinkedHashMap<>();
		map.put("a", 1);
		map.put("b", "x");

		// call under test
		assertEquals("{\"a\":1,\"b\":\"x\"}", OpenSearchManagerImpl.convertFieldValue(map));
	}

	@Test
	public void testConvertFieldValueWithListOfLargeLongsPreservesPrecision() {
		// Synapse entity / file-handle ids routinely exceed 2^53, so list serialization must
		// preserve full 64-bit precision. Jackson does this; org.json (which we no longer use)
		// coerces every numeric through double and would silently truncate the trailing bit.
		long beyondDouble = 9007199254740993L;  // 2^53 + 1; not exactly representable as double
		assertEquals("[9007199254740993,9007199254740994]",
				OpenSearchManagerImpl.convertFieldValue(Arrays.asList(beyondDouble, beyondDouble + 1L)));
	}

	@Test
	public void testConvertFieldValueWithMapOfLargeLongsPreservesPrecision() {
		// Same precision requirement applies to JSON column maps.
		LinkedHashMap<String, Object> map = new LinkedHashMap<>();
		map.put("id", 9007199254740993L);

		// call under test
		assertEquals("{\"id\":9007199254740993}", OpenSearchManagerImpl.convertFieldValue(map));
	}

	@Test
	public void testDescribeErrorWithSingleCause() {
		ErrorCause cause = ErrorCause.of(b -> b
				.type("mapper_parsing_exception")
				.reason("failed to parse field [123]"));

		// call under test
		String desc = OpenSearchManagerImpl.describeError(cause);

		assertEquals("mapper_parsing_exception: failed to parse field [123]", desc);
	}

	@Test
	public void testDescribeErrorWalksCausedByChain() {
		// AOSS typically returns a generic outer reason; the actual cause is in caused_by.
		ErrorCause inner = ErrorCause.of(b -> b
				.type("illegal_state_exception")
				.reason("Position increment must be non-negative"));
		ErrorCause outer = ErrorCause.of(b -> b
				.type("?")
				.reason("Internal error occurred while processing request")
				.causedBy(inner));

		// call under test
		String desc = OpenSearchManagerImpl.describeError(outer);

		assertEquals(
				"?: Internal error occurred while processing request"
						+ " caused by illegal_state_exception: Position increment must be non-negative",
				desc);
	}

	@Test
	public void testDescribeErrorWithNullReturnsPlaceholder() {
		// call under test
		assertEquals("?", OpenSearchManagerImpl.describeError(null));
	}

	@Test
	public void testDescribeErrorWithRootCause() {
		// AOSS sometimes leaves the outer reason generic and puts the real diagnostic in
		// root_cause[]. Surface it so the failure is debuggable.
		ErrorCause rootCause = ErrorCause.of(b -> b
				.type("illegal_argument_exception")
				.reason("analyzer [synapse_analyzer_1] not found"));
		ErrorCause outer = ErrorCause.of(b -> b
				.type("?")
				.reason("Internal error occurred while processing request")
				.rootCause(rootCause));

		// call under test
		String desc = OpenSearchManagerImpl.describeError(outer);

		assertEquals(
				"?: Internal error occurred while processing request"
						+ " [rootCause=illegal_argument_exception: analyzer [synapse_analyzer_1] not found]",
				desc);
	}

	@Test
	public void testCreateIndexHappyPathRegistersResolvedAnalyzersAndReturnsAppliedJson() throws IOException {
		// Happy-path createIndex with a single resolved analyzer carrying owned filter +
		// analyzer.default + analyzer.default_search entries, and one STRING column bound
		// to that analyzer as both the index default and the column-type default. The
		// applied JSON returned by the manager must include:
		//   - the namespaced filter under settings.analysis.filter.{aossKey}__english_stop
		//   - the bare reserved analyzer.default (promoted from primary's default entry)
		//   - the bare reserved analyzer.default_search (promoted from primary's default_search)
		//   - the field mapping for the STRING column under mappings.properties.{colId}
		String indexName = "search-index-syn1";
		// SCIENTIFIC is the column-type default for STRING; binding the test analyzer at that
		// qname collapses both the index-default and column-type-default to the same registered
		// analyzer so the per-column "was not registered" guard is satisfied.
		String qname = "org.sagebionetworks-SCIENTIFIC";
		String aossKey = OpenSearchManagerImpl.toAossKey(qname);
		String settingsJson = "{"
				+ "\"filter\":{\"english_stop\":{\"type\":\"stop\",\"stopwords\":\"_english_\"}},"
				+ "\"analyzer\":{"
					+ "\"default\":{\"type\":\"custom\",\"tokenizer\":\"standard\",\"filter\":[\"english_stop\"]},"
					+ "\"default_search\":{\"type\":\"custom\",\"tokenizer\":\"keyword\"}"
				+ "}}";
		Map<String, IndexSettingsAnalysis> resolvedAnalyzers = Collections.singletonMap(qname,
				toAnalysis(settingsJson));

		List<ColumnModel> columns = Collections.singletonList(
				new ColumnModel().setId("100").setName("title").setColumnType(ColumnType.STRING));

		when(openSearchClient.indices()).thenReturn(indicesClient);
		org.opensearch.client.opensearch.indices.CreateIndexResponse okResponse =
				org.opensearch.client.opensearch.indices.CreateIndexResponse.of(b -> b
						.acknowledged(true).shardsAcknowledged(true).index(indexName));
		ArgumentCaptor<CreateIndexRequest> requestCaptor = ArgumentCaptor.forClass(CreateIndexRequest.class);
		when(indicesClient.create(requestCaptor.capture())).thenReturn(okResponse);

		// call under test
		Optional<String> appliedJson = manager.createIndex(indexName, columns, qname,
				Collections.emptyList(), resolvedAnalyzers);

		assertTrue(appliedJson.isPresent());
		String applied = appliedJson.get();

		// The applied analysis block must register the namespaced filter and surface the
		// primary analyzer's default / default_search entries at the bare reserved keys.
		assertTrue(applied.contains("\"" + aossKey + "__english_stop\""),
				"Owned filter must be registered under namespaced key: " + applied);
		assertTrue(applied.contains("\"default\""),
				"Reserved analyzer.default must be present: " + applied);
		assertTrue(applied.contains("\"default_search\""),
				"Reserved analyzer.default_search must be present (asymmetric search): " + applied);
		// The STRING column must land in the mappings.properties block under its column id.
		assertTrue(applied.contains("\"100\""),
				"Field mapping for the STRING column must be registered under its id: " + applied);

		// And the captured request must target the right index name.
		assertEquals(indexName, requestCaptor.getValue().index());
	}

	@Test
	public void testCreateIndexBindsSymmetricFieldSearchAnalyzerToIndexAnalyzer() throws IOException {
		// When a non-primary TextAnalyzer (one with no default_search of its own) is bound to
		// a field via ColumnAnalyzerOverride, the field must set BOTH analyzer and
		// search_analyzer to the same namespaced registry key. Otherwise the index-wide
		// `default_search` (registered for the primary analyzer) hijacks the field at query
		// time per OpenSearch's analyzer precedence rules — the per-field `analyzer` mapping
		// is rule 4, but the index `default_search` is rule 3, so rule 3 wins without an
		// explicit per-field `search_analyzer` (rule 2).
		String indexName = "search-index-syn1";
		// Primary analyzer (declares default_search); collapsed to the column-type default for STRING.
		String primaryQname = "org.sagebionetworks-SCIENTIFIC";
		String primarySettings = "{"
				+ "\"analyzer\":{"
					+ "\"default\":{\"type\":\"custom\",\"tokenizer\":\"standard\"},"
					+ "\"default_search\":{\"type\":\"custom\",\"tokenizer\":\"keyword\"}"
				+ "}}";
		// Override analyzer (symmetric — no default_search). Bound to a specific field below.
		String overrideQname = "biomed-pubs";
		String overrideAossKey = OpenSearchManagerImpl.toAossKey(overrideQname);
		String overrideSettings = "{"
				+ "\"analyzer\":{\"default\":{\"type\":\"custom\",\"tokenizer\":\"whitespace\"}}}";
		Map<String, IndexSettingsAnalysis> resolvedAnalyzers = new HashMap<>();
		resolvedAnalyzers.put(primaryQname, toAnalysis(primarySettings));
		resolvedAnalyzers.put(overrideQname, toAnalysis(overrideSettings));

		List<ColumnModel> columns = Collections.singletonList(
				new ColumnModel().setId("100").setName("title").setColumnType(ColumnType.STRING));
		ColumnAnalyzerOverride override = new ColumnAnalyzerOverride();
		ColumnAnalyzerOverrideEntry entry = new ColumnAnalyzerOverrideEntry();
		entry.setColumnName("title");
		entry.setAnalyzer(new org.json.JSONObject().put("$ref", overrideQname));
		override.setOverrides(Collections.singletonList(entry));


		when(openSearchClient.indices()).thenReturn(indicesClient);
		org.opensearch.client.opensearch.indices.CreateIndexResponse okResponse =
				org.opensearch.client.opensearch.indices.CreateIndexResponse.of(b -> b
						.acknowledged(true).shardsAcknowledged(true).index(indexName));
		when(indicesClient.create(any(CreateIndexRequest.class))).thenReturn(okResponse);

		// call under test
		Optional<String> appliedJson = manager.createIndex(indexName, columns, primaryQname,
				Collections.singletonList(override), resolvedAnalyzers);

		assertTrue(appliedJson.isPresent());
		// Parse the applied JSON and assert on the typed shape rather than JSON-token order
		// (the Java client doesn't guarantee a stable property order for text-field properties).
		JsonNode field100 = MAPPER.readTree(appliedJson.get())
				.at("/mappings/properties/100");
		assertEquals("text", field100.path("type").asText());
		// The field must bind analyzer AND search_analyzer both to the same namespaced key.
		// Without the explicit search_analyzer the index-wide default_search would win at query time.
		assertEquals(overrideAossKey, field100.path("analyzer").asText());
		assertEquals(overrideAossKey, field100.path("search_analyzer").asText());
	}

	@Test
	public void testCreateIndexBindsAsymmetricFieldSearchAnalyzerToDefaultSearchKey() throws IOException {
		// When the override TextAnalyzer declares its own default_search, the field's
		// search_analyzer must bind to that entry's namespaced registry key (not the bare qname).
		String indexName = "search-index-syn1";
		String primaryQname = "org.sagebionetworks-SCIENTIFIC";
		String primarySettings = "{\"analyzer\":{\"default\":{\"type\":\"custom\",\"tokenizer\":\"standard\"}}}";
		String overrideQname = "biomed-pubs";
		String overrideAossKey = OpenSearchManagerImpl.toAossKey(overrideQname);
		String overrideSettings = "{"
				+ "\"analyzer\":{"
					+ "\"default\":{\"type\":\"custom\",\"tokenizer\":\"whitespace\"},"
					+ "\"default_search\":{\"type\":\"custom\",\"tokenizer\":\"keyword\"}"
				+ "}}";
		Map<String, IndexSettingsAnalysis> resolvedAnalyzers = new HashMap<>();
		resolvedAnalyzers.put(primaryQname, toAnalysis(primarySettings));
		resolvedAnalyzers.put(overrideQname, toAnalysis(overrideSettings));

		List<ColumnModel> columns = Collections.singletonList(
				new ColumnModel().setId("100").setName("title").setColumnType(ColumnType.STRING));
		ColumnAnalyzerOverride override = new ColumnAnalyzerOverride();
		ColumnAnalyzerOverrideEntry entry = new ColumnAnalyzerOverrideEntry();
		entry.setColumnName("title");
		entry.setAnalyzer(new org.json.JSONObject().put("$ref", overrideQname));
		override.setOverrides(Collections.singletonList(entry));


		when(openSearchClient.indices()).thenReturn(indicesClient);
		org.opensearch.client.opensearch.indices.CreateIndexResponse okResponse =
				org.opensearch.client.opensearch.indices.CreateIndexResponse.of(b -> b
						.acknowledged(true).shardsAcknowledged(true).index(indexName));
		when(indicesClient.create(any(CreateIndexRequest.class))).thenReturn(okResponse);

		// call under test
		Optional<String> appliedJson = manager.createIndex(indexName, columns, primaryQname,
				Collections.singletonList(override), resolvedAnalyzers);

		assertTrue(appliedJson.isPresent());
		JsonNode field100 = MAPPER.readTree(appliedJson.get())
				.at("/mappings/properties/100");
		assertEquals("text", field100.path("type").asText());
		assertEquals(overrideAossKey, field100.path("analyzer").asText());
		assertEquals(overrideAossKey + "__default_search", field100.path("search_analyzer").asText());
	}

	@Test
	public void testCreateIndexWithOpenSearchException() throws IOException {
		String indexName = "search-index-syn1";
		ErrorCause inner = ErrorCause.of(b -> b
				.type("illegal_argument_exception")
				.reason("For input string: \"abc\""));
		ErrorCause outer = ErrorCause.of(b -> b
				.type("mapper_parsing_exception")
				.reason("failed to parse field [col_123] of type [long]")
				.causedBy(inner));
		OpenSearchException openSearchException = new OpenSearchException(
				ErrorResponse.of(er -> er.error(outer).status(400)));

		when(openSearchClient.indices()).thenReturn(indicesClient);
		when(indicesClient.create(argThat((CreateIndexRequest req) -> indexName.equals(req.index()))))
				.thenThrow(openSearchException);

		// call under test
		RuntimeException ex = assertThrows(RuntimeException.class,
				() -> manager.createIndex(indexName, Collections.emptyList(), null,
						Collections.emptyList(), Collections.emptyMap()));

		assertEquals(openSearchException, ex.getCause());
		assertEquals("Failed to create search index: " + indexName
				+ " (" + OpenSearchManagerImpl.describeError(outer) + ")",
				ex.getMessage());
	}

	@Test
	public void testCreateIndexWithResourceAlreadyExists() throws IOException {
		String indexName = "search-index-syn1";
		OpenSearchException openSearchException = new OpenSearchException(
				ErrorResponse.of(er -> er.error(ErrorCause.of(b -> b
						.type("resource_already_exists_exception")
						.reason("index already exists"))).status(400)));

		when(openSearchClient.indices()).thenReturn(indicesClient);
		when(indicesClient.create(argThat((CreateIndexRequest req) -> indexName.equals(req.index()))))
				.thenThrow(openSearchException);

		// call under test
		Optional<String> result = manager.createIndex(indexName, Collections.emptyList(), null,
				Collections.emptyList(), Collections.emptyMap());

		assertEquals(Optional.empty(), result);
	}

	private static BulkResponseItem okItem(String id) {
		return BulkResponseItem.of(b -> b
				.index("search-index-syn1")
				.id(id)
				.status(201)
				.operationType(org.opensearch.client.opensearch.core.bulk.OperationType.Index));
	}

	private static BulkResponseItem failedItem(String id, int status, String type, String reason) {
		return BulkResponseItem.of(b -> b
				.index("search-index-syn1")
				.id(id)
				.status(status)
				.operationType(org.opensearch.client.opensearch.core.bulk.OperationType.Index)
				.error(ErrorCause.of(e -> e.type(type).reason(reason))));
	}

	private static BulkOperation bulkOp(String id) {
		return BulkOperation.of(op -> op
				.index(idx -> idx.index("search-index-syn1").id(id).document(Map.of("_row_id", Long.parseLong(id)))));
	}

	private static BulkResponse bulkResponseOf(BulkResponseItem... items) {
		return BulkResponse.of(b -> b.errors(Arrays.stream(items).anyMatch(i -> i.error() != null))
				.took(1L).items(Arrays.asList(items)));
	}

	/**
	 * Build a {@link BulkResponse} whose items line up one-for-one with the operations in
	 * {@code request} — all failed with the given status/type/reason. Needed because
	 * {@code bulkIndex} may submit per-op requests after a partial batch failure.
	 */
	private static BulkResponse allFailedResponse(BulkRequest request, int status, String type, String reason) {
		BulkResponseItem[] items = request.operations().stream()
				.map(op -> {
					String id = op.index() != null ? op.index().id() : "?";
					return failedItem(id, status, type, reason);
				})
				.toArray(BulkResponseItem[]::new);
		return bulkResponseOf(items);
	}

	@Test
	public void testBulkIndexWithAllItemsSucceed() throws Exception {
		BulkResponse response = bulkResponseOf(okItem("1"), okItem("2"), okItem("3"));
		when(openSearchClient.bulk(argThat((BulkRequest req) -> req != null)))
				.thenReturn(response);

		// call under test
		long indexed = manager.bulkIndex("search-index-syn1",
				Arrays.asList(bulkOp("1"), bulkOp("2"), bulkOp("3")));

		assertEquals(3L, indexed);
	}

	@Test
	public void testBulkIndexWithAllRetryableFailuresExhaustsRetriesAndThrowsRecoverableMessageException() throws Exception {
		// Every per-op bulk response fails 429 for whatever doc ids were requested — covers both
		// the initial batch attempt and the per-document retries that follow.
		when(openSearchClient.bulk(argThat((BulkRequest req) -> req != null)))
				.thenAnswer(inv -> allFailedResponse(inv.getArgument(0), 429,
						"circuit_breaking_exception", "rate limited"));

		// call under test
		RecoverableMessageException ex = assertThrows(RecoverableMessageException.class,
				() -> manager.bulkIndex("search-index-syn1",
						Arrays.asList(bulkOp("1"), bulkOp("2"), bulkOp("3"))));
		assertTrue(ex.getMessage().contains(
				"failed after " + OpenSearchManagerImpl.BULK_INDEX_MAX_RETRIES + " attempts"),
				ex.getMessage());
		assertTrue(ex.getMessage().contains("3 document(s) still retryable out of 3"), ex.getMessage());
		// 1 batch attempt, then MAX_RETRIES-1 per-document attempts with 3 ops each.
		int expected = 1 + (OpenSearchManagerImpl.BULK_INDEX_MAX_RETRIES - 1) * 3;
		verify(openSearchClient, times(expected))
				.bulk(argThat((BulkRequest req) -> req != null));
	}

	@Test
	public void testBulkIndexWithMixedFailuresThrowsPermanentRuntimeException() throws Exception {
		BulkResponse response = bulkResponseOf(
				okItem("1"),
				failedItem("2", 429, "circuit_breaking_exception", "rate limited"),
				failedItem("3", 400, "mapper_parsing_exception", "failed to parse field [geneName]"));
		when(openSearchClient.bulk(argThat((BulkRequest req) -> req != null)))
				.thenReturn(response);

		// call under test
		RuntimeException ex = assertThrows(RuntimeException.class,
				() -> manager.bulkIndex("search-index-syn1",
						Arrays.asList(bulkOp("1"), bulkOp("2"), bulkOp("3"))));
		assertFalse(ex instanceof RecoverableMessageException,
				ex.getClass().getName() + ": " + ex.getMessage());
		assertTrue(ex.getMessage().contains("1 retryable"), ex.getMessage());
		assertTrue(ex.getMessage().contains("1 permanent"), ex.getMessage());
	}

	@ParameterizedTest
	@ValueSource(ints = {500, 502, 504})
	public void testBulkIndexWith5xxItemStatusExhaustsRetriesAndThrowsRecoverableMessageException(int status) throws Exception {
		// AOSS returns 500 with type="exception" and the generic "Internal error occurred while
		// processing request" reason when shard routing hasn't fully propagated after createIndex —
		// classified as retryable so the intra-batch retry loop backs off and resubmits the subset.
		when(openSearchClient.bulk(argThat((BulkRequest req) -> req != null)))
				.thenAnswer(inv -> allFailedResponse(inv.getArgument(0), status,
						"exception", "Internal error occurred while processing request"));

		// call under test
		RecoverableMessageException ex = assertThrows(RecoverableMessageException.class,
				() -> manager.bulkIndex("search-index-syn1",
						Arrays.asList(bulkOp("1"), bulkOp("2"), bulkOp("3"))));
		assertTrue(ex.getMessage().contains(
				"failed after " + OpenSearchManagerImpl.BULK_INDEX_MAX_RETRIES + " attempts"),
				ex.getMessage());
		assertTrue(ex.getMessage().contains("3 document(s) still retryable out of 3"), ex.getMessage());
		// 1 batch attempt, then MAX_RETRIES-1 per-document attempts with 3 ops each.
		int expected = 1 + (OpenSearchManagerImpl.BULK_INDEX_MAX_RETRIES - 1) * 3;
		verify(openSearchClient, times(expected))
				.bulk(argThat((BulkRequest req) -> req != null));
	}

	@Test
	public void testBulkIndexWithMixed500And400FailuresThrowsPermanentRuntimeException() throws Exception {
		BulkResponse response = bulkResponseOf(
				failedItem("1", 500, "exception", "Internal error occurred while processing request"),
				failedItem("2", 400, "mapper_parsing_exception", "failed to parse field [geneName]"));
		when(openSearchClient.bulk(argThat((BulkRequest req) -> req != null)))
				.thenReturn(response);

		// call under test
		RuntimeException ex = assertThrows(RuntimeException.class,
				() -> manager.bulkIndex("search-index-syn1",
						Arrays.asList(bulkOp("1"), bulkOp("2"))));
		assertFalse(ex instanceof RecoverableMessageException,
				ex.getClass().getName() + ": " + ex.getMessage());
		assertTrue(ex.getMessage().contains("1 retryable"), ex.getMessage());
		assertTrue(ex.getMessage().contains("1 permanent"), ex.getMessage());
	}

	@Test
	public void testBulkIndexPermanentMessageIncludesSampleFailures() throws Exception {
		BulkResponse response = bulkResponseOf(
				failedItem("1", 400, "mapper_parsing_exception", "failed to parse field [geneName]"),
				failedItem("2", 400, "mapper_parsing_exception", "failed to parse field [geneLength]"),
				failedItem("3", 400, "document_parsing_exception", "unexpected character"));
		when(openSearchClient.bulk(argThat((BulkRequest req) -> req != null)))
				.thenReturn(response);

		// call under test
		RuntimeException ex = assertThrows(RuntimeException.class,
				() -> manager.bulkIndex("search-index-syn1",
						Arrays.asList(bulkOp("1"), bulkOp("2"), bulkOp("3"))));
		assertFalse(ex instanceof RecoverableMessageException, ex.getClass().getName());
		String msg = ex.getMessage();
		assertTrue(msg.contains("Sample failures:"), msg);
		assertTrue(msg.contains("doc 1 [status=400]"), msg);
		assertTrue(msg.contains("doc 2 [status=400]"), msg);
		assertTrue(msg.contains("doc 3 [status=400]"), msg);
		assertTrue(msg.contains("failed to parse field [geneName]"), msg);
		assertTrue(msg.contains("failed to parse field [geneLength]"), msg);
		assertTrue(msg.contains("unexpected character"), msg);
	}

	@Test
	public void testBulkIndexPermanentMessageCapsAtFiveSamples() throws Exception {
		BulkResponseItem[] items = new BulkResponseItem[8];
		BulkOperation[] ops = new BulkOperation[8];
		for (int i = 0; i < 8; i++) {
			String id = String.valueOf(i + 1);
			items[i] = failedItem(id, 400, "mapper_parsing_exception", "field [c" + i + "]");
			ops[i] = bulkOp(id);
		}
		when(openSearchClient.bulk(argThat((BulkRequest req) -> req != null)))
				.thenReturn(bulkResponseOf(items));

		// call under test
		RuntimeException ex = assertThrows(RuntimeException.class,
				() -> manager.bulkIndex("search-index-syn1", Arrays.asList(ops)));
		String msg = ex.getMessage();
		assertTrue(msg.contains("doc 1 [status=400]"), msg);
		assertTrue(msg.contains("doc 5 [status=400]"), msg);
		assertFalse(msg.contains("doc 6 [status=400]"), msg);
		assertFalse(msg.contains("doc 8 [status=400]"), msg);
	}

	@Test
	public void testBulkIndexPermanentMessageIncludesOnlyPermanentSamples() throws Exception {
		BulkResponse response = bulkResponseOf(
				failedItem("1", 429, "circuit_breaking_exception", "rate limited"),
				failedItem("2", 429, "circuit_breaking_exception", "rate limited"),
				failedItem("3", 400, "mapper_parsing_exception", "failed to parse field [geneName]"),
				failedItem("4", 400, "mapper_parsing_exception", "failed to parse field [geneLength]"),
				failedItem("5", 400, "document_parsing_exception", "unexpected character"));
		when(openSearchClient.bulk(argThat((BulkRequest req) -> req != null)))
				.thenReturn(response);

		// call under test
		RuntimeException ex = assertThrows(RuntimeException.class,
				() -> manager.bulkIndex("search-index-syn1",
						Arrays.asList(bulkOp("1"), bulkOp("2"), bulkOp("3"), bulkOp("4"), bulkOp("5"))));
		String msg = ex.getMessage();
		assertTrue(msg.contains("2 retryable"), msg);
		assertTrue(msg.contains("3 permanent"), msg);
		assertTrue(msg.contains("doc 3 [status=400]"), msg);
		assertTrue(msg.contains("doc 4 [status=400]"), msg);
		assertTrue(msg.contains("doc 5 [status=400]"), msg);
		assertFalse(msg.contains("doc 1 [status=429]"), msg);
		assertFalse(msg.contains("doc 2 [status=429]"), msg);
		assertFalse(msg.contains("rate limited"), msg);
	}

	@Test
	public void testBulkIndexPermanentMessageTruncatesWhenOverBudget() throws Exception {
		char[] huge = new char[2000];
		Arrays.fill(huge, 'x');
		String bigReason = new String(huge);
		BulkResponse response = bulkResponseOf(
				failedItem("1", 400, "mapper_parsing_exception", bigReason),
				failedItem("2", 400, "mapper_parsing_exception", bigReason));
		when(openSearchClient.bulk(argThat((BulkRequest req) -> req != null)))
				.thenReturn(response);

		// call under test
		RuntimeException ex = assertThrows(RuntimeException.class,
				() -> manager.bulkIndex("search-index-syn1", Arrays.asList(bulkOp("1"), bulkOp("2"))));
		String msg = ex.getMessage();
		assertEquals(2500, msg.length(), "message length=" + msg.length());
		assertTrue(msg.endsWith("...[truncated]"), msg.substring(msg.length() - 20));
	}

	@ParameterizedTest
	@ValueSource(ints = {500, 502, 504})
	public void testBulkIndexWithEnvelope5xxExhaustsRetriesAndThrowsRecoverableMessageException(int status) throws Exception {
		ErrorResponse serverError = ErrorResponse.of(e -> e
				.error(err -> err.type("exception").reason("Internal error occurred while processing request"))
				.status(status));
		when(openSearchClient.bulk(argThat((BulkRequest req) -> req != null)))
				.thenThrow(new OpenSearchException(serverError));

		// call under test
		assertThrows(RecoverableMessageException.class,
				() -> manager.bulkIndex("search-index-syn1", Arrays.asList(bulkOp("1"))));
		verify(openSearchClient, times(OpenSearchManagerImpl.BULK_INDEX_MAX_RETRIES))
				.bulk(argThat((BulkRequest req) -> req != null));
	}

	@Test
	public void testBulkIndexWithEnvelope429ExhaustsRetriesAndThrowsRecoverableMessageException() throws Exception {
		ErrorResponse rateLimited = ErrorResponse.of(e -> e
				.error(err -> err.type("circuit_breaking_exception").reason("rate limited"))
				.status(429));
		when(openSearchClient.bulk(argThat((BulkRequest req) -> req != null)))
				.thenThrow(new OpenSearchException(rateLimited));

		// call under test
		assertThrows(RecoverableMessageException.class,
				() -> manager.bulkIndex("search-index-syn1", Arrays.asList(bulkOp("1"))));
		verify(openSearchClient, times(OpenSearchManagerImpl.BULK_INDEX_MAX_RETRIES))
				.bulk(argThat((BulkRequest req) -> req != null));
	}

	@Test
	public void testBulkIndexWithEnvelope400ThrowsPermanentRuntimeException() throws Exception {
		ErrorResponse badRequest = ErrorResponse.of(e -> e
				.error(err -> err.type("illegal_argument_exception").reason("bad request"))
				.status(400));
		when(openSearchClient.bulk(argThat((BulkRequest req) -> req != null)))
				.thenThrow(new OpenSearchException(badRequest));

		// call under test
		RuntimeException ex = assertThrows(RuntimeException.class,
				() -> manager.bulkIndex("search-index-syn1", Arrays.asList(bulkOp("1"))));
		assertFalse(ex instanceof RecoverableMessageException, ex.getClass().getName());
		verify(openSearchClient, times(1))
				.bulk(argThat((BulkRequest req) -> req != null));
	}

	@Test
	public void testBulkIndexWithIOExceptionExhaustsRetriesAndThrowsRecoverableMessageException() throws Exception {
		when(openSearchClient.bulk(argThat((BulkRequest req) -> req != null)))
				.thenThrow(new IOException("connection reset"));

		// call under test
		assertThrows(RecoverableMessageException.class,
				() -> manager.bulkIndex("search-index-syn1", Arrays.asList(bulkOp("1"))));
		verify(openSearchClient, times(OpenSearchManagerImpl.BULK_INDEX_MAX_RETRIES))
				.bulk(argThat((BulkRequest req) -> req != null));
	}

	@Test
	public void testBulkIndexWithEnvelopeStatusZeroExhaustsRetriesAndIsRecoverable() throws Exception {
		// An OpenSearchException whose status() == 0 means the transport never produced an
		// HTTP response — e.g. AwsSdk2Transport surfaced a connection-level failure as
		// OpenSearchException rather than IOException. Treating it like a 4xx would fail the
		// whole batch permanently on transient network blips, so it must retry.
		ErrorResponse noResponse = ErrorResponse.of(e -> e
				.error(err -> err.type("transport_exception").reason("no http response"))
				.status(0));
		when(openSearchClient.bulk(argThat((BulkRequest req) -> req != null)))
				.thenThrow(new OpenSearchException(noResponse));

		// call under test
		assertThrows(RecoverableMessageException.class,
				() -> manager.bulkIndex("search-index-syn1", Arrays.asList(bulkOp("1"))));
		verify(openSearchClient, times(OpenSearchManagerImpl.BULK_INDEX_MAX_RETRIES))
				.bulk(argThat((BulkRequest req) -> req != null));
	}

	@Test
	public void testBulkIndexWithEmptyOperationsReturnsZeroAndDoesNotCallClient() {
		// call under test
		long indexed = manager.bulkIndex("search-index-syn1", Collections.emptyList());

		assertEquals(0L, indexed);
		verifyZeroInteractions(openSearchClient);
	}

	@Test
	public void testBulkIndexWithTransientRetryableFailureRecoversOnSecondAttempt() throws Exception {
		// First attempt (batch): docs 1 and 3 fail 500, doc 2 succeeds. That triggers
		// per-document mode — the next two attempts submit docs 1 and 3 individually.
		BulkResponse firstResponse = bulkResponseOf(
				failedItem("1", 500, "exception", "Internal error occurred while processing request"),
				okItem("2"),
				failedItem("3", 503, "service_unavailable", "try later"));
		BulkResponse singleOk1 = bulkResponseOf(okItem("1"));
		BulkResponse singleOk3 = bulkResponseOf(okItem("3"));
		when(openSearchClient.bulk(argThat((BulkRequest req) -> req != null)))
				.thenReturn(firstResponse)
				.thenReturn(singleOk1)
				.thenReturn(singleOk3);

		// call under test
		long indexed = manager.bulkIndex("search-index-syn1",
				Arrays.asList(bulkOp("1"), bulkOp("2"), bulkOp("3")));

		assertEquals(3L, indexed);
		verify(openSearchClient, times(3))
				.bulk(argThat((BulkRequest req) -> req != null));
	}

	@Test
	public void testBulkIndexRetryResubmitsOnlyFailedOps() throws Exception {
		// Doc 2 succeeds on first batch attempt. On partial failure the retry switches to
		// per-document mode, so only docs 1 and 3 come back — each in its own single-op request.
		BulkResponse firstResponse = bulkResponseOf(
				failedItem("1", 500, "exception", "Internal error occurred while processing request"),
				okItem("2"),
				failedItem("3", 500, "exception", "Internal error occurred while processing request"));
		BulkResponse singleOk1 = bulkResponseOf(okItem("1"));
		BulkResponse singleOk3 = bulkResponseOf(okItem("3"));
		when(openSearchClient.bulk(argThat((BulkRequest req) -> req != null)))
				.thenReturn(firstResponse)
				.thenReturn(singleOk1)
				.thenReturn(singleOk3);

		// call under test
		manager.bulkIndex("search-index-syn1",
				Arrays.asList(bulkOp("1"), bulkOp("2"), bulkOp("3")));

		ArgumentCaptor<BulkRequest> captor = ArgumentCaptor.forClass(BulkRequest.class);
		verify(openSearchClient, times(3)).bulk(captor.capture());
		List<BulkRequest> requests = captor.getAllValues();
		assertEquals(3, requests.get(0).operations().size(), "first attempt submits all operations");
		assertEquals(1, requests.get(1).operations().size(), "per-doc retry submits one op");
		assertEquals(1, requests.get(2).operations().size(), "per-doc retry submits one op");
	}

	@Test
	public void testBulkIndexWithPermanentFailureDoesNotRetry() throws Exception {
		// Mixed 500 (retryable) + 400 (permanent): one permanent failure disqualifies the batch
		// from retrying, so bulk() is called exactly once.
		BulkResponse response = bulkResponseOf(
				failedItem("1", 500, "exception", "Internal error occurred while processing request"),
				failedItem("2", 400, "mapper_parsing_exception", "failed to parse field [geneName]"));
		when(openSearchClient.bulk(argThat((BulkRequest req) -> req != null)))
				.thenReturn(response);

		// call under test
		RuntimeException ex = assertThrows(RuntimeException.class,
				() -> manager.bulkIndex("search-index-syn1", Arrays.asList(bulkOp("1"), bulkOp("2"))));
		assertFalse(ex instanceof RecoverableMessageException, ex.getClass().getName());
		verify(openSearchClient, times(1))
				.bulk(argThat((BulkRequest req) -> req != null));
	}

	// --- callSearchApi: trackTotalHits wire behavior ---

	@SuppressWarnings({"rawtypes", "unchecked"})
	private SearchResponse<Map> emptySearchResponse() {
		TotalHits total = TotalHits.of(t -> t.value(0L).relation(TotalHitsRelation.Eq));
		HitsMetadata<Map> hits = HitsMetadata.of(h -> h.total(total).hits(Collections.emptyList()));
		return SearchResponse.searchResponseOf(r -> r
				.took(0L)
				.timedOut(false)
				.shards(s -> s.total(1).successful(1).failed(0))
				.hits(hits));
	}

	@Test
	public void testCallSearchApiWithTotalHitsSetsCountToIntMaxValue() throws IOException {
		when(openSearchClient.search(argThat((SearchRequest req) -> req != null), eq(Map.class)))
				.thenReturn(emptySearchResponse());

		// call under test
		manager.callSearchApi("my-index", new BoolQuery.Builder(),
				0, 10, Collections.emptyMap(), null /* suggester */, null /* returnFields */,
				Collections.emptyList(), Collections.emptyMap(),
				EnumSet.of(SearchQueryPart.TOTAL_HITS), null /* searchAfter */);

		ArgumentCaptor<SearchRequest> captor = ArgumentCaptor.forClass(SearchRequest.class);
		verify(openSearchClient).search(captor.capture(), eq(Map.class));
		TrackHits trackHits = captor.getValue().trackTotalHits();
		assertNotNull(trackHits, "trackTotalHits must be set when TOTAL_HITS requested");
		assertTrue(trackHits.isCount(), "must use count() variant, not enabled()");
		assertEquals(Integer.MAX_VALUE, trackHits.count());
	}

	@Test
	public void testCallSearchApiWithoutTotalHitsSetsEnabledFalse() throws IOException {
		when(openSearchClient.search(argThat((SearchRequest req) -> req != null), eq(Map.class)))
				.thenReturn(emptySearchResponse());

		// call under test
		manager.callSearchApi("my-index", new BoolQuery.Builder(),
				0, 10, Collections.emptyMap(), null /* suggester */, null /* returnFields */,
				Collections.emptyList(), Collections.emptyMap(),
				EnumSet.of(SearchQueryPart.HITS), null /* searchAfter */);

		ArgumentCaptor<SearchRequest> captor = ArgumentCaptor.forClass(SearchRequest.class);
		verify(openSearchClient).search(captor.capture(), eq(Map.class));
		TrackHits trackHits = captor.getValue().trackTotalHits();
		assertNotNull(trackHits, "trackTotalHits must be explicitly disabled");
		assertTrue(trackHits.isEnabled(), "must use enabled() variant");
		assertEquals(Boolean.FALSE, trackHits.enabled());
	}

	@Test
	public void testBulkIndexWithIOExceptionThenSuccessRecovers() throws Exception {
		// Transient network issue on first two attempts, then success — covers the IOException
		// branch of the retry loop.
		when(openSearchClient.bulk(argThat((BulkRequest req) -> req != null)))
				.thenThrow(new IOException("connection reset"))
				.thenThrow(new IOException("connection reset"))
				.thenReturn(bulkResponseOf(okItem("1")));

		// call under test
		long indexed = manager.bulkIndex("search-index-syn1", Arrays.asList(bulkOp("1")));

		assertEquals(1L, indexed);
		verify(openSearchClient, times(3))
				.bulk(argThat((BulkRequest req) -> req != null));
	}

	@Test
	public void testDescribeBulkItemFailureWithNoShardFailures() {
		BulkResponseItem item = BulkResponseItem.of(b -> b
				.index("search-index-syn1")
				.id("1")
				.status(500)
				.operationType(org.opensearch.client.opensearch.core.bulk.OperationType.Index)
				.error(ErrorCause.of(e -> e
						.type("exception")
						.reason("Internal error occurred while processing request"))));

		// call under test
		String result = OpenSearchManagerImpl.describeBulkItemFailure(item);

		assertEquals(
				"doc 1 [status=500]: exception: Internal error occurred while processing request",
				result);
	}

	@Test
	public void testDescribeBulkItemFailureWithShardFailuresPopulated() {
		ShardSearchFailure shardFailure = ShardSearchFailure.of(sf -> sf
				.shard(3)
				.index("search-index-syn1")
				.node("node-a")
				.reason(ErrorCause.of(e -> e
						.type("mapper_parsing_exception")
						.reason("failed to parse field [geneName]"))));
		ShardStatistics shards = ShardStatistics.of(s -> s
				.total(5).successful(4).failed(1).failures(shardFailure));

		BulkResponseItem item = BulkResponseItem.of(b -> b
				.index("search-index-syn1")
				.id("42")
				.status(400)
				.operationType(org.opensearch.client.opensearch.core.bulk.OperationType.Index)
				.shards(shards)
				.error(ErrorCause.of(e -> e
						.type("exception")
						.reason("Internal error occurred while processing request"))));

		// call under test
		String result = OpenSearchManagerImpl.describeBulkItemFailure(item);

		assertTrue(result.contains("doc 42 [status=400]"), result);
		assertTrue(result.contains("Internal error occurred while processing request"), result);
		assertTrue(result.contains("shardFailures="), result);
		assertTrue(result.contains("shard=3"), result);
		assertTrue(result.contains("index=search-index-syn1"), result);
		assertTrue(result.contains("node=node-a"), result);
		assertTrue(result.contains("mapper_parsing_exception"), result);
		assertTrue(result.contains("failed to parse field [geneName]"), result);
	}

	// --- waitForIndexWritable ---

	@SuppressWarnings({"rawtypes", "unchecked"})
	private static IndexResponse okIndexResponse() {
		return IndexResponse.of(b -> b
				.id(OpenSearchManagerImpl.READINESS_PROBE_DOC_ID)
				.index("search-index-syn1")
				.version(1L)
				.seqNo(0L)
				.primaryTerm(1L)
				.result(org.opensearch.client.opensearch._types.Result.Created)
				.shards(s -> s.total(1).successful(1).failed(0)));
	}

	private static DeleteResponse okDeleteResponse() {
		return DeleteResponse.of(b -> b
				.id(OpenSearchManagerImpl.READINESS_PROBE_DOC_ID)
				.index("search-index-syn1")
				.version(1L)
				.seqNo(1L)
				.primaryTerm(1L)
				.result(org.opensearch.client.opensearch._types.Result.Deleted)
				.shards(s -> s.total(1).successful(1).failed(0)));
	}

	@Test
	public void testWaitForIndexWritableWithImmediateSuccessDeletesSentinelAndReturns() throws Exception {
		when(openSearchClient.index(argThat((IndexRequest<?> req) -> req != null)))
				.thenReturn(okIndexResponse());
		when(openSearchClient.delete(argThat((DeleteRequest req) -> req != null)))
				.thenReturn(okDeleteResponse());

		// call under test
		manager.waitForIndexWritable("search-index-syn1");

		ArgumentCaptor<IndexRequest> indexCaptor = ArgumentCaptor.forClass(IndexRequest.class);
		verify(openSearchClient, times(1)).index(indexCaptor.capture());
		assertEquals("search-index-syn1", indexCaptor.getValue().index());
		assertEquals(OpenSearchManagerImpl.READINESS_PROBE_DOC_ID, indexCaptor.getValue().id());

		ArgumentCaptor<DeleteRequest> deleteCaptor = ArgumentCaptor.forClass(DeleteRequest.class);
		verify(openSearchClient, times(1)).delete(deleteCaptor.capture());
		assertEquals("search-index-syn1", deleteCaptor.getValue().index());
		assertEquals(OpenSearchManagerImpl.READINESS_PROBE_DOC_ID, deleteCaptor.getValue().id());
	}

	@Test
	public void testWaitForIndexWritableWithTransientFailureThenSuccess() throws Exception {
		OpenSearchException notFound = new OpenSearchException(
				ErrorResponse.of(er -> er.error(ErrorCause.of(e -> e
						.type("index_not_found_exception")
						.reason("no such index"))).status(404)));
		when(openSearchClient.index(argThat((IndexRequest<?> req) -> req != null)))
				.thenThrow(notFound)
				.thenThrow(notFound)
				.thenReturn(okIndexResponse());
		when(openSearchClient.delete(argThat((DeleteRequest req) -> req != null)))
				.thenReturn(okDeleteResponse());

		// call under test
		manager.waitForIndexWritable("search-index-syn1");

		verify(openSearchClient, times(3)).index(argThat((IndexRequest<?> req) -> req != null));
		verify(openSearchClient, times(1)).delete(argThat((DeleteRequest req) -> req != null));
	}

	@Test
	public void testWaitForIndexWritableExhaustsRetriesAndThrowsRecoverableMessageException() throws Exception {
		OpenSearchException notFound = new OpenSearchException(
				ErrorResponse.of(er -> er.error(ErrorCause.of(e -> e
						.type("index_not_found_exception")
						.reason("no such index"))).status(404)));
		when(openSearchClient.index(argThat((IndexRequest<?> req) -> req != null)))
				.thenThrow(notFound);

		// call under test
		RecoverableMessageException ex = assertThrows(RecoverableMessageException.class,
				() -> manager.waitForIndexWritable("search-index-syn1"));

		assertTrue(ex.getMessage().contains("did not accept writes within the retry budget"),
				ex.getMessage());
		verify(openSearchClient, times(OpenSearchManagerImpl.INDEX_WRITABLE_MAX_RETRIES))
				.index(argThat((IndexRequest<?> req) -> req != null));
		// No sentinel was ever written, so no cleanup delete is attempted.
		verify(openSearchClient, times(0)).delete(argThat((DeleteRequest req) -> req != null));
	}

	@Test
	public void testWaitForIndexWritableWithIOExceptionExhaustsRetries() throws Exception {
		when(openSearchClient.index(argThat((IndexRequest<?> req) -> req != null)))
				.thenThrow(new IOException("connection reset"));

		// call under test
		assertThrows(RecoverableMessageException.class,
				() -> manager.waitForIndexWritable("search-index-syn1"));

		verify(openSearchClient, times(OpenSearchManagerImpl.INDEX_WRITABLE_MAX_RETRIES))
				.index(argThat((IndexRequest<?> req) -> req != null));
	}

	@Test
	public void testWaitForIndexWritableSentinelCleanupFailureIsSwallowedAfterRetries() throws Exception {
		// Write succeeds, every cleanup delete fails. The probe must still return normally —
		// cleanup failures are non-fatal — but it must exhaust the cleanup retry budget first
		// so a transient delete failure doesn't immediately orphan the sentinel.
		when(openSearchClient.index(argThat((IndexRequest<?> req) -> req != null)))
				.thenReturn(okIndexResponse());
		when(openSearchClient.delete(argThat((DeleteRequest req) -> req != null)))
				.thenThrow(new IOException("cleanup failed"));

		// call under test
		manager.waitForIndexWritable("search-index-syn1");

		verify(openSearchClient, times(1)).index(argThat((IndexRequest<?> req) -> req != null));
		verify(openSearchClient, times(OpenSearchManagerImpl.SENTINEL_CLEANUP_MAX_RETRIES))
				.delete(argThat((DeleteRequest req) -> req != null));
	}

	@Test
	public void testWaitForIndexWritableSentinelCleanupRetriesAndSucceeds() throws Exception {
		// AOSS doesn't honor refresh=wait_for, so a single cleanup delete that fails on a
		// transient blip would orphan the sentinel. Verify the cleanup retries and lands on
		// the second attempt — only one orphan-window's worth of MATCH_ALL exposure.
		when(openSearchClient.index(argThat((IndexRequest<?> req) -> req != null)))
				.thenReturn(okIndexResponse());
		when(openSearchClient.delete(argThat((DeleteRequest req) -> req != null)))
				.thenThrow(new IOException("transient"))
				.thenReturn(okDeleteResponse());

		// call under test
		manager.waitForIndexWritable("search-index-syn1");

		verify(openSearchClient, times(1)).index(argThat((IndexRequest<?> req) -> req != null));
		verify(openSearchClient, times(2)).delete(argThat((DeleteRequest req) -> req != null));
	}

	// --- waitForDocumentCount ---

	@Test
	public void testWaitForDocumentCountWithImmediateMatchReturns() throws Exception {
		// Happy path: AOSS reports the expected count on the first attempt; no retry, no log noise.
		when(openSearchClient.count(any(org.opensearch.client.opensearch.core.CountRequest.class)))
				.thenReturn(org.opensearch.client.opensearch.core.CountResponse.of(b -> b
						.count(5L)
						.shards(s -> s.total(1).successful(1).failed(0))));

		// call under test
		manager.waitForDocumentCount("search-index-syn1", 5L);

		verify(openSearchClient, times(1))
				.count(any(org.opensearch.client.opensearch.core.CountRequest.class));
	}

	@Test
	public void testWaitForDocumentCountConvergesAfterUnderCount() throws Exception {
		// AOSS is eventually consistent: the first probe sees fewer documents than were
		// bulk-indexed; the second sees the full count and returns.
		when(openSearchClient.count(any(org.opensearch.client.opensearch.core.CountRequest.class)))
				.thenReturn(countResponse(2L))
				.thenReturn(countResponse(5L));

		// call under test
		manager.waitForDocumentCount("search-index-syn1", 5L);

		verify(openSearchClient, times(2))
				.count(any(org.opensearch.client.opensearch.core.CountRequest.class));
	}

	@Test
	public void testWaitForDocumentCountTreatsExcessAsConverged() throws Exception {
		// >= rather than == so a leftover readiness-probe sentinel cannot strand convergence
		// one short. An excess count is therefore a successful return, not a retry.
		when(openSearchClient.count(any(org.opensearch.client.opensearch.core.CountRequest.class)))
				.thenReturn(countResponse(6L));

		// call under test
		manager.waitForDocumentCount("search-index-syn1", 5L);

		verify(openSearchClient, times(1))
				.count(any(org.opensearch.client.opensearch.core.CountRequest.class));
	}

	@Test
	public void testWaitForDocumentCountExhaustsRetriesAndThrowsRecoverableMessageException() throws Exception {
		// Persistent under-count: every attempt sees fewer documents than expected. The probe
		// must throw RecoverableMessageException so the lifecycle worker re-queues the message
		// without writing FAILED — convergence is transient by definition.
		when(openSearchClient.count(any(org.opensearch.client.opensearch.core.CountRequest.class)))
				.thenReturn(countResponse(2L));

		// call under test
		RecoverableMessageException ex = assertThrows(RecoverableMessageException.class,
				() -> manager.waitForDocumentCount("search-index-syn1", 5L));

		assertTrue(ex.getMessage().contains("did not converge"), ex.getMessage());
		assertTrue(ex.getMessage().contains("2 of 5"), ex.getMessage());
		verify(openSearchClient, times(OpenSearchManagerImpl.COUNT_PROBE_MAX_RETRIES))
				.count(any(org.opensearch.client.opensearch.core.CountRequest.class));
	}

	@Test
	public void testWaitForDocumentCountWithIOExceptionExhaustsRetries() throws Exception {
		when(openSearchClient.count(any(org.opensearch.client.opensearch.core.CountRequest.class)))
				.thenThrow(new IOException("connection reset"));

		// call under test
		assertThrows(RecoverableMessageException.class,
				() -> manager.waitForDocumentCount("search-index-syn1", 5L));

		verify(openSearchClient, times(OpenSearchManagerImpl.COUNT_PROBE_MAX_RETRIES))
				.count(any(org.opensearch.client.opensearch.core.CountRequest.class));
	}

	@Test
	public void testWaitForDocumentCountWithOpenSearchExceptionExhaustsRetries() throws Exception {
		// AOSS may briefly return index_not_found_exception (or any other transient error) from
		// _count while shards are still propagating the freshly-written documents.
		ErrorResponse notFound = ErrorResponse.of(er -> er
				.error(ErrorCause.of(e -> e.type("index_not_found_exception").reason("no such index")))
				.status(404));
		when(openSearchClient.count(any(org.opensearch.client.opensearch.core.CountRequest.class)))
				.thenThrow(new OpenSearchException(notFound));

		// call under test
		assertThrows(RecoverableMessageException.class,
				() -> manager.waitForDocumentCount("search-index-syn1", 5L));

		verify(openSearchClient, times(OpenSearchManagerImpl.COUNT_PROBE_MAX_RETRIES))
				.count(any(org.opensearch.client.opensearch.core.CountRequest.class));
	}

	@Test
	public void testWaitForDocumentCountWithZeroExpectedShortCircuits() throws Exception {
		// An empty SearchIndex needs no probe — _count would just return 0 and we'd skip the
		// retry path anyway. Avoid the round-trip entirely.
		// call under test
		manager.waitForDocumentCount("search-index-syn1", 0L);

		verifyZeroInteractions(openSearchClient);
	}

	private static org.opensearch.client.opensearch.core.CountResponse countResponse(long count) {
		return org.opensearch.client.opensearch.core.CountResponse.of(b -> b
				.count(count)
				.shards(s -> s.total(1).successful(1).failed(0)));
	}

	// --- per-document fallback on partial batch failure ---

	@Test
	public void testBulkIndexSwitchesToPerDocumentModeAfterPartialBatchFailure() throws Exception {
		// First attempt (batch mode): doc 2 succeeds, docs 1 and 3 fail with 429 — retryable.
		// Second attempt (per-doc mode): two single-op bulk requests, both succeed.
		BulkResponse firstResponse = bulkResponseOf(
				failedItem("1", 429, "circuit_breaking_exception", "rate limited"),
				okItem("2"),
				failedItem("3", 429, "circuit_breaking_exception", "rate limited"));
		BulkResponse singleOk1 = bulkResponseOf(okItem("1"));
		BulkResponse singleOk3 = bulkResponseOf(okItem("3"));
		when(openSearchClient.bulk(argThat((BulkRequest req) -> req != null)))
				.thenReturn(firstResponse)
				.thenReturn(singleOk1)
				.thenReturn(singleOk3);

		// call under test
		long indexed = manager.bulkIndex("search-index-syn1",
				Arrays.asList(bulkOp("1"), bulkOp("2"), bulkOp("3")));

		assertEquals(3L, indexed);
		ArgumentCaptor<BulkRequest> captor = ArgumentCaptor.forClass(BulkRequest.class);
		verify(openSearchClient, times(3)).bulk(captor.capture());
		List<BulkRequest> requests = captor.getAllValues();
		assertEquals(3, requests.get(0).operations().size(), "first attempt submits batch of 3");
		assertEquals(1, requests.get(1).operations().size(), "per-doc retry submits one op");
		assertEquals(1, requests.get(2).operations().size(), "per-doc retry submits one op");
	}

	@Test
	public void testBulkIndexPerDocumentModeContinuesPartitioningAfterPartialFailure() throws Exception {
		// First attempt (batch): doc 2 succeeds, docs 1 and 3 fail 429 (retryable).
		// Second attempt (per-doc): single op for doc 1 fails 429; single op for doc 3 succeeds.
		// Third attempt (per-doc): single op for doc 1 succeeds.
		BulkResponse firstResponse = bulkResponseOf(
				failedItem("1", 429, "circuit_breaking_exception", "rate limited"),
				okItem("2"),
				failedItem("3", 429, "circuit_breaking_exception", "rate limited"));
		BulkResponse single1Failed = bulkResponseOf(
				failedItem("1", 429, "circuit_breaking_exception", "rate limited"));
		BulkResponse single3Ok = bulkResponseOf(okItem("3"));
		BulkResponse single1Ok = bulkResponseOf(okItem("1"));
		when(openSearchClient.bulk(argThat((BulkRequest req) -> req != null)))
				.thenReturn(firstResponse)
				.thenReturn(single1Failed)
				.thenReturn(single3Ok)
				.thenReturn(single1Ok);

		// call under test
		long indexed = manager.bulkIndex("search-index-syn1",
				Arrays.asList(bulkOp("1"), bulkOp("2"), bulkOp("3")));

		assertEquals(3L, indexed);
		verify(openSearchClient, times(4)).bulk(argThat((BulkRequest req) -> req != null));
	}

	@Test
	public void testBulkIndexPerDocumentModeEnvelopeFailureDoesNotResubmitSucceededDocs() throws Exception {
		// Partial 429 triggers per-doc mode with docs 1 and 3 outstanding.
		// In per-doc mode, doc 1 succeeds, doc 3's single-op request throws an envelope 503
		// (retryable). The next attempt must only resubmit doc 3 — doc 1 was already indexed.
		BulkResponse firstResponse = bulkResponseOf(
				failedItem("1", 429, "circuit_breaking_exception", "rate limited"),
				okItem("2"),
				failedItem("3", 429, "circuit_breaking_exception", "rate limited"));
		BulkResponse single1Ok = bulkResponseOf(okItem("1"));
		BulkResponse single3Ok = bulkResponseOf(okItem("3"));
		ErrorResponse serverError = ErrorResponse.of(e -> e
				.error(err -> err.type("exception").reason("service unavailable"))
				.status(503));
		when(openSearchClient.bulk(argThat((BulkRequest req) -> req != null)))
				.thenReturn(firstResponse)
				.thenReturn(single1Ok)
				.thenThrow(new OpenSearchException(serverError))
				.thenReturn(single3Ok);

		// call under test
		long indexed = manager.bulkIndex("search-index-syn1",
				Arrays.asList(bulkOp("1"), bulkOp("2"), bulkOp("3")));

		assertEquals(3L, indexed);
		ArgumentCaptor<BulkRequest> captor = ArgumentCaptor.forClass(BulkRequest.class);
		verify(openSearchClient, times(4)).bulk(captor.capture());
		List<BulkRequest> requests = captor.getAllValues();
		// attempt 1 (batch of 3) → attempt 2 per-doc: submits doc 1 (ok) then doc 3 (envelope 503 aborts)
		// → attempt 3 per-doc: must only carry doc 3 forward, not doc 1 again.
		assertEquals(3, requests.get(0).operations().size(), "batch attempt");
		assertEquals(1, requests.get(1).operations().size(), "per-doc: doc 1 ok");
		assertEquals(1, requests.get(2).operations().size(), "per-doc: doc 3 envelope 503");
		assertEquals(1, requests.get(3).operations().size(),
				"retry after envelope failure must only resubmit the unprocessed doc, not the succeeded one");
		assertEquals("3", requests.get(3).operations().get(0).index().id(),
				"succeeded doc 1 must not be resubmitted");
	}

	@Test
	public void testBulkIndexPerDocumentModePermanentFailureStopsRetries() throws Exception {
		// Partial 429 triggers per-doc mode. On the per-doc retry, doc 1's single op comes back 400
		// (permanent), so the whole bulkIndex call fails without further retries.
		BulkResponse firstResponse = bulkResponseOf(
				failedItem("1", 429, "circuit_breaking_exception", "rate limited"),
				okItem("2"));
		BulkResponse single1Permanent = bulkResponseOf(
				failedItem("1", 400, "mapper_parsing_exception", "failed to parse field [geneName]"));
		when(openSearchClient.bulk(argThat((BulkRequest req) -> req != null)))
				.thenReturn(firstResponse)
				.thenReturn(single1Permanent);

		// call under test
		RuntimeException ex = assertThrows(RuntimeException.class,
				() -> manager.bulkIndex("search-index-syn1",
						Arrays.asList(bulkOp("1"), bulkOp("2"))));
		assertFalse(ex instanceof RecoverableMessageException, ex.getClass().getName());
		verify(openSearchClient, times(2)).bulk(argThat((BulkRequest req) -> req != null));
	}

	// --- search(): offset / limit validation ---

	/**
	 * Build a minimal valid SearchQuery: an opaque {@code match_all} clause is required by
	 * executeSearch's up-front gate; the offset/limit tests then mutate offset or limit and
	 * exercise the validation branches.
	 */
	private static SearchQuery matchAllQuery() {
		Map<String, Object> queryDsl = new HashMap<>();
		queryDsl.put("match_all", Collections.emptyMap());
		return new SearchQuery()
				.setQuery(queryDsl)
				.setOffset(0L).setLimit(10L);
	}

	@Test
	public void testSearchWithNegativeOffsetThrows() throws IOException {
		SearchQuery query = matchAllQuery().setOffset(-1L);

		// call under test
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				() -> manager.search("search-index-syn1", query,
						Collections.emptyList(), EnumSet.of(SearchQueryPart.HITS)));

		assertTrue(ex.getMessage().contains("offset"), ex.getMessage());
		verify(openSearchClient, org.mockito.Mockito.never()).search(any(SearchRequest.class), eq(Map.class));
	}

	@Test
	public void testSearchWithOffsetAboveIntMaxThrows() throws IOException {
		SearchQuery query = matchAllQuery().setOffset((long) Integer.MAX_VALUE + 1L);

		// call under test
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				() -> manager.search("search-index-syn1", query,
						Collections.emptyList(), EnumSet.of(SearchQueryPart.HITS)));

		assertTrue(ex.getMessage().contains("offset"), ex.getMessage());
		verify(openSearchClient, org.mockito.Mockito.never()).search(any(SearchRequest.class), eq(Map.class));
	}

	@Test
	public void testSearchWithNegativeLimitThrows() throws IOException {
		SearchQuery query = matchAllQuery().setLimit(-1L);

		// call under test
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				() -> manager.search("search-index-syn1", query,
						Collections.emptyList(), EnumSet.of(SearchQueryPart.HITS)));

		assertTrue(ex.getMessage().contains("limit"), ex.getMessage());
		verify(openSearchClient, org.mockito.Mockito.never()).search(any(SearchRequest.class), eq(Map.class));
	}

	@Test
	public void testSearchWithLimitAboveMaxClampsToMaxLimit() throws IOException {
		// Limit above MAX_LIMIT must clamp (not throw) so the existing relaxed contract is
		// preserved — the new requirement() check only rejects negative limits.
		when(openSearchClient.search(argThat((SearchRequest req) -> req != null), eq(Map.class)))
				.thenReturn(emptySearchResponse());
		SearchQuery query = matchAllQuery().setLimit(10_000L);

		// call under test
		manager.search("search-index-syn1", query,
				Collections.emptyList(), EnumSet.of(SearchQueryPart.HITS));

		ArgumentCaptor<SearchRequest> captor = ArgumentCaptor.forClass(SearchRequest.class);
		verify(openSearchClient).search(captor.capture(), eq(Map.class));
		// MAX_LIMIT is 100 in OpenSearchManagerImpl; assert against the clamped value on the wire.
		assertEquals(Integer.valueOf(100), captor.getValue().size());
	}

	// ===================== branch coverage: buildProperty per ColumnType =====================

	/**
	 * {@code buildProperty}'s if-chain dispatches by column-type category (text / link /
	 * keyword / long / double / boolean / json / fallback). Every {@link ColumnType} value
	 * must yield a non-null {@link org.opensearch.client.opensearch._types.mapping.Property}
	 * — the parameterized run also pins the dispatch against future ColumnType additions.
	 */
	@ParameterizedTest
	@EnumSource(ColumnType.class)
	public void testBuildPropertyForEveryColumnType(ColumnType type) {
		// hasDefaultSearch=false and qname=null hit the simplest branch for each category.
		org.opensearch.client.opensearch._types.mapping.Property property =
				manager.buildProperty(type, null, false);
		assertNotNull(property, "every column type must yield a Property");
	}

	@Test
	public void testBuildPropertyTextWithBoundQname() {
		// Bound qname forces buildTextProperty's "explicit search_analyzer" path so the
		// search-time analyzer doesn't fall through to the index-wide default.
		assertNotNull(manager.buildProperty(ColumnType.STRING, "biomed-pubs", false));
	}

	@Test
	public void testBuildPropertyTextWithDefaultSearchEnabled() {
		// hasDefaultSearch=true exercises the asymmetric-analyzer branch where the bound
		// search analyzer key differs from the index analyzer key.
		assertNotNull(manager.buildProperty(ColumnType.STRING, "biomed-pubs", true));
	}

	// ===================== branch coverage: isConcurrentDeleteError null edges =====================

	@Test
	public void testIsConcurrentDeleteErrorWithNullReason() {
		// Defensive: error.reason() == null returns false instead of NPE.
		ErrorResponse response = new ErrorResponse.Builder()
				.status(500)
				.error(new ErrorCause.Builder().type("internal_error").build())
				.build();
		assertFalse(OpenSearchManagerImpl.isConcurrentDeleteError(new OpenSearchException(response)));
	}

	// ===================== branch coverage: isRetryableItemStatus boundaries =====================

	@Test
	public void testIsRetryableItemStatusUpperBoundary() {
		// MAX_SERVER_ERROR is 599; 600 falls outside the retryable range.
		assertTrue(OpenSearchManagerImpl.isRetryableItemStatus(599));
		assertFalse(OpenSearchManagerImpl.isRetryableItemStatus(600));
	}

	@Test
	public void testIsRetryableItemStatusLowerBoundary() {
		// 499 is below the 5xx retry range; 500 is the lower edge.
		assertFalse(OpenSearchManagerImpl.isRetryableItemStatus(499));
		assertTrue(OpenSearchManagerImpl.isRetryableItemStatus(500));
	}

	@Test
	public void testIsRetryableItemStatusZero() {
		// 0 isn't a real HTTP status; the bulk path handles 0 separately, so this returns false.
		assertFalse(OpenSearchManagerImpl.isRetryableItemStatus(0));
	}

	// ===================== branch coverage: describeError / appendErrorCauseDetail =====================

	@Test
	public void testDescribeErrorWithRootCauseEntries() {
		// rootCause iteration walks two entries with first/non-first separator branches.
		ErrorCause root1 = new ErrorCause.Builder().type("rc1").reason("r1").build();
		ErrorCause root2 = new ErrorCause.Builder().type("rc2").reason("r2").build();
		ErrorCause cause = new ErrorCause.Builder()
				.type("primary").reason("primary reason")
				.rootCause(Arrays.asList(root1, root2)).build();

		String desc = OpenSearchManagerImpl.describeError(cause);

		assertTrue(desc.contains("rootCause="));
		assertTrue(desc.contains("rc1: r1"));
		assertTrue(desc.contains(", rc2: r2"));
	}

	@Test
	public void testDescribeErrorWithMetadataPresent() {
		// metadata-present branch in appendErrorCauseDetail.
		ErrorCause cause = new ErrorCause.Builder()
				.type("t").reason("r")
				.metadata(Collections.singletonMap("k", JsonData.of("v")))
				.build();

		String desc = OpenSearchManagerImpl.describeError(cause);

		assertTrue(desc.contains("metadata="), "metadata branch must render: " + desc);
	}

	@Test
	public void testDescribeErrorWithStackTrace() {
		// stackTrace-present branch in appendErrorCauseDetail.
		ErrorCause cause = new ErrorCause.Builder()
				.type("t").reason("r")
				.stackTrace("at com.example.Foo.bar(Foo.java:10)")
				.build();

		String desc = OpenSearchManagerImpl.describeError(cause);

		assertTrue(desc.contains("stackTrace="), "stackTrace branch must render: " + desc);
	}

	@Test
	public void testDescribeErrorChainsMultipleCausedByLinks() {
		// caused-by walk loop runs through the chain, attaching " caused by " between links.
		ErrorCause innerInner = new ErrorCause.Builder().type("i2").reason("r2").build();
		ErrorCause inner = new ErrorCause.Builder()
				.type("i1").reason("r1").causedBy(innerInner).build();
		ErrorCause outer = new ErrorCause.Builder()
				.type("outer").reason("outerReason").causedBy(inner).build();

		String desc = OpenSearchManagerImpl.describeError(outer);

		assertTrue(desc.contains("outer: outerReason"));
		assertTrue(desc.contains("i1: r1"));
		assertTrue(desc.contains("i2: r2"));
		// Each caused-by link appears once.
		int causedByCount = 0;
		int idx = 0;
		while ((idx = desc.indexOf(" caused by ", idx)) != -1) {
			causedByCount++;
			idx += " caused by ".length();
		}
		assertEquals(2, causedByCount, "two caused-by links should join three errors: " + desc);
	}

	// ===================== branch coverage: describeBulkItemFailure =====================

	// ===================== branch coverage: autocomplete limit clamp =====================

	@Test
	public void testAutocompleteWithNullLimitClampsToMax() throws Exception {
		// limit == null branch clamps to AUTOCOMPLETE_MAX_LIMIT (8). Caller picks the prefix-
		// style clause (here multi_match with type=bool_prefix); the manager only clamps size.
		when(openSearchClient.search(argThat((SearchRequest req) -> req != null), eq(Map.class)))
				.thenReturn(emptySearchResponse());
		SearchQuery query = matchAllQuery().setLimit(null);

		manager.autocomplete("search-index-syn1", query,
				Collections.emptyList(), EnumSet.of(SearchQueryPart.HITS));

		ArgumentCaptor<SearchRequest> captor = ArgumentCaptor.forClass(SearchRequest.class);
		verify(openSearchClient).search(captor.capture(), eq(Map.class));
		assertEquals(Integer.valueOf(8), captor.getValue().size(),
				"null limit must clamp to AUTOCOMPLETE_MAX_LIMIT");
	}

	@Test
	public void testAutocompleteWithLimitAboveMaxClampsToMax() throws Exception {
		// limit > AUTOCOMPLETE_MAX_LIMIT branch — same clamp.
		when(openSearchClient.search(argThat((SearchRequest req) -> req != null), eq(Map.class)))
				.thenReturn(emptySearchResponse());
		SearchQuery query = matchAllQuery().setLimit(50L);

		manager.autocomplete("search-index-syn1", query,
				Collections.emptyList(), EnumSet.of(SearchQueryPart.HITS));

		ArgumentCaptor<SearchRequest> captor = ArgumentCaptor.forClass(SearchRequest.class);
		verify(openSearchClient).search(captor.capture(), eq(Map.class));
		assertEquals(Integer.valueOf(8), captor.getValue().size(),
				"limit above AUTOCOMPLETE_MAX_LIMIT must clamp");
	}

	@Test
	public void testAutocompleteWithLimitWithinRangePassesThrough() throws Exception {
		// limit <= AUTOCOMPLETE_MAX_LIMIT — no clamp.
		when(openSearchClient.search(argThat((SearchRequest req) -> req != null), eq(Map.class)))
				.thenReturn(emptySearchResponse());
		SearchQuery query = matchAllQuery().setLimit(5L);

		manager.autocomplete("search-index-syn1", query,
				Collections.emptyList(), EnumSet.of(SearchQueryPart.HITS));

		ArgumentCaptor<SearchRequest> captor = ArgumentCaptor.forClass(SearchRequest.class);
		verify(openSearchClient).search(captor.capture(), eq(Map.class));
		assertEquals(Integer.valueOf(5), captor.getValue().size());
	}

	@Test
	public void testDescribeBulkItemFailureWithNoShardFailuresOmitsSection() {
		// shards==null OR shards.failures empty → no "shardFailures=" section.
		ErrorCause err = new ErrorCause.Builder().type("indexing_error").reason("bad doc").build();
		BulkResponseItem item = new BulkResponseItem.Builder()
				.id("doc-42")
				.operationType(org.opensearch.client.opensearch.core.bulk.OperationType.Index)
				.status(400)
				.error(err)
				.index("test-index")
				.build();

		String desc = OpenSearchManagerImpl.describeBulkItemFailure(item);

		assertTrue(desc.contains("doc doc-42"));
		assertTrue(desc.contains("status=400"));
		assertTrue(desc.contains("indexing_error"));
		assertFalse(desc.contains("shardFailures="),
				"no shardFailures section when shards.failures is empty: " + desc);
	}

	// ===================== opaque-DSL handlers =====================

	@Test
	public void testBuildOpaqueQueryWithAllowedClauseRewritesNameToId() {
		// Caller-supplied match clause references the column by name; the manager rewrites
		// the field reference to the column id before deserializing into the typed Query.
		Map<String, String> nameToId = Map.of("title", "100");

		// call under test
		org.opensearch.client.opensearch._types.query_dsl.Query q = manager.buildOpaqueQuery(
				"{\"match\":{\"title\":\"amyloid\"}}", nameToId);

		assertTrue(q.isMatch());
		assertEquals("100", q.match().field());
	}

	@Test
	public void testBuildOpaqueQueryWithDisallowedClauseRejected() {
		// `script` is not allowlisted — must be rejected with HTTP 400 before reaching AOSS.
		// call under test
		assertThrows(IllegalArgumentException.class,
				() -> manager.buildOpaqueQuery("{\"script\":{\"script\":\"doc['x'].value\"}}",
						Collections.emptyMap()));
	}

	@Test
	public void testBuildOpaqueAggregationsWithAllowedAggsRewritesNameToId() {
		Map<String, String> nameToId = Map.of("year", "300");

		// call under test
		Map<String, org.opensearch.client.opensearch._types.aggregations.Aggregation> aggs =
				manager.buildOpaqueAggregations("{\"by_year\":{\"terms\":{\"field\":\"year\"}}}",
						nameToId);

		assertNotNull(aggs.get("by_year"));
		assertEquals("300", aggs.get("by_year").terms().field());
	}

	@Test
	public void testBuildOpaqueAggregationsWithDisallowedAggsRejected() {
		// scripted_metric is not allowlisted.
		// call under test
		assertThrows(IllegalArgumentException.class,
				() -> manager.buildOpaqueAggregations(
						"{\"x\":{\"scripted_metric\":{\"init_script\":\"\"}}}",
						Collections.emptyMap()));
	}

	@Test
	public void testBuildOpaqueSuggestWithAllowedSuggesterRewritesNameToId() {
		Map<String, String> nameToId = Map.of("title", "100");

		// call under test
		org.opensearch.client.opensearch.core.search.Suggester suggester = manager.buildOpaqueSuggest(
				"{\"did_you_mean\":{\"text\":\"amiloid\",\"term\":{\"field\":\"title\"}}}",
				nameToId);

		assertNotNull(suggester);
		assertTrue(suggester.suggesters().containsKey("did_you_mean"));
	}

	@Test
	public void testBuildOpaqueSuggestWithDisallowedSuggesterRejected() {
		// `completion` is allowlisted but `phrase` carrying a script is not.
		// call under test
		assertThrows(IllegalArgumentException.class,
				() -> manager.buildOpaqueSuggest(
						"{\"x\":{\"phrase\":{\"field\":\"title\",\"collate\":{\"script\":\"x\"}}}}",
						Collections.emptyMap()));
	}
}
