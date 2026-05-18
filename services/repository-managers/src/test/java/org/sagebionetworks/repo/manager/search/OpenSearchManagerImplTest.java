package org.sagebionetworks.repo.manager.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.ErrorCause;
import org.opensearch.client.opensearch._types.ErrorResponse;
import org.opensearch.client.opensearch._types.OpenSearchException;
import org.opensearch.client.opensearch._types.ShardSearchFailure;
import org.opensearch.client.opensearch._types.ShardStatistics;
import org.opensearch.client.opensearch._types.query_dsl.BoolQuery;
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
import org.opensearch.client.opensearch.core.search.HitsMetadata;
import org.opensearch.client.opensearch.core.search.TotalHits;
import org.opensearch.client.opensearch.core.search.TotalHitsRelation;
import org.opensearch.client.opensearch.core.search.TrackHits;
import org.sagebionetworks.repo.model.search.SearchQueryPart;
import org.sagebionetworks.repo.model.search.table.AnalyzerComponent;
import org.sagebionetworks.repo.model.search.table.ColumnAnalyzerOverride;
import org.sagebionetworks.repo.model.search.table.ColumnAnalyzerOverrideEntry;
import org.sagebionetworks.repo.model.search.table.TextAnalyzer;
import org.sagebionetworks.repo.model.search.table.TextAnalyzerSettings;
import org.sagebionetworks.repo.model.table.ColumnType;
import org.sagebionetworks.workers.util.aws.message.RecoverableMessageException;

/**
 * Unit tests for the pure-logic surface of {@link OpenSearchManagerImpl}: filter-chain
 * resolution (including the {@code synapse_synonyms} placeholder expansion), analyzer
 * routing helpers, bulk-failure message building, and input validation that runs before
 * any AOSS round-trip. AOSS-backed flows (create/search/bulk/analyze) are covered by IT
 * tests against a live cluster, per CLAUDE.md's "external-service-backed manager"
 * guidance.
 */
@ExtendWith(MockitoExtension.class)
public class OpenSearchManagerImplTest {

	@Mock
	private OpenSearchClient openSearchClient;

	@InjectMocks
	private OpenSearchManagerImpl manager;

	private long originalBulkInitialBackoffMs;
	private long originalProbeInitialBackoffMs;

	@BeforeEach
	public void setUp() {
		// Drop bulk-index retry backoff to 1ms in tests so retry-exhaustion paths don't
		// actually sleep ~21s per invocation. Restored in @AfterEach.
		originalBulkInitialBackoffMs = OpenSearchManagerImpl.BULK_INDEX_INITIAL_BACKOFF_MS;
		OpenSearchManagerImpl.BULK_INDEX_INITIAL_BACKOFF_MS = 1L;
		originalProbeInitialBackoffMs = OpenSearchManagerImpl.INDEX_WRITABLE_INITIAL_BACKOFF_MS;
		OpenSearchManagerImpl.INDEX_WRITABLE_INITIAL_BACKOFF_MS = 1L;
	}

	@AfterEach
	public void tearDown() {
		OpenSearchManagerImpl.BULK_INDEX_INITIAL_BACKOFF_MS = originalBulkInitialBackoffMs;
		OpenSearchManagerImpl.INDEX_WRITABLE_INITIAL_BACKOFF_MS = originalProbeInitialBackoffMs;
	}

	// --- isRetryableItemStatus ---

	@ParameterizedTest
	@ValueSource(ints = {429, 500, 503, 599})
	public void testIsRetryableItemStatusWithRetryableCodes(int code) {
		// call under test
		assertTrue(OpenSearchManagerImpl.isRetryableItemStatus(code));
	}

	@ParameterizedTest
	@ValueSource(ints = {200, 400, 404, 409, 600})
	public void testIsRetryableItemStatusWithPermanentCodes(int code) {
		// call under test
		assertFalse(OpenSearchManagerImpl.isRetryableItemStatus(code));
	}

	// --- buildPermanentFailureMessage ---

	@Test
	public void testBuildPermanentFailureMessageWithSamplesAppendsThem() {
		String summary = "Bulk index to foo failed: 2 document(s) rejected out of 10";
		// call under test
		String result = OpenSearchManagerImpl.buildPermanentFailureMessage(
				summary, Arrays.asList("row 1: reason A", "row 2: reason B"));

		assertTrue(result.startsWith(summary));
		assertTrue(result.contains("row 1: reason A"));
		assertTrue(result.contains("row 2: reason B"));
	}

	@Test
	public void testBuildPermanentFailureMessageWithEmptySamplesReturnsSummary() {
		String summary = "Bulk index failed";
		// call under test
		assertEquals(summary, OpenSearchManagerImpl.buildPermanentFailureMessage(summary, Collections.emptyList()));
	}

	@Test
	public void testBuildPermanentFailureMessageTruncatesToColumnWidth() {
		String[] samples = new String[200];
		for (int i = 0; i < samples.length; i++) {
			samples[i] = "very long sample failure descriptor " + i + " ".repeat(50);
		}
		// call under test
		String result = OpenSearchManagerImpl.buildPermanentFailureMessage("summary", Arrays.asList(samples));

		assertTrue(result.length() <= OpenSearchManagerImpl.MAX_BULK_ERROR_MESSAGE_CHARS);
		assertTrue(result.endsWith(OpenSearchManagerImpl.TRUNCATION_MARKER));
	}

	// --- buildIdToQualifiedNameMap ---

	@Test
	public void testBuildIdToQualifiedNameMapRoundTrip() {
		Map<String, TextAnalyzer> input = new HashMap<>();
		input.put("org-a", new TextAnalyzer().setId("42"));
		input.put("org-b", new TextAnalyzer().setId("17"));

		// call under test
		Map<Long, String> result = OpenSearchManagerImpl.buildIdToQualifiedNameMap(input);

		assertEquals("org-a", result.get(42L));
		assertEquals("org-b", result.get(17L));
		assertEquals(2, result.size());
	}

	// --- resolveFilterChain (the placeholder mechanism — critical) ---

	@Test
	public void testResolveFilterChainWithNullOrderReturnsEmpty() {
		// call under test
		List<String> result = OpenSearchManagerImpl.resolveFilterChain(
				"org-a", null, Collections.emptySet(), Collections.emptyList());

		assertTrue(result.isEmpty());
	}

	@Test
	public void testResolveFilterChainWithEmptyOrderReturnsEmpty() {
		// call under test
		List<String> result = OpenSearchManagerImpl.resolveFilterChain(
				"org-a", Collections.emptyList(), Collections.emptySet(), Collections.emptyList());

		assertTrue(result.isEmpty());
	}

	@Test
	public void testResolveFilterChainNamespacesOwnedNames() {
		Set<String> owned = new HashSet<>(Arrays.asList("sci_word_delimiter", "english_stemmer"));
		List<String> order = Arrays.asList("sci_word_delimiter", "lowercase", "english_stemmer");

		// call under test
		List<String> result = OpenSearchManagerImpl.resolveFilterChain(
				"org-a", order, owned, Collections.emptyList());

		// Owned names get namespaced with `<qname>__`; built-in 'lowercase' passes through.
		assertEquals(Arrays.asList("org-a__sci_word_delimiter", "lowercase", "org-a__english_stemmer"), result);
	}

	@Test
	public void testResolveFilterChainExpandsSynonymPlaceholderInPlace() {
		List<String> order = Arrays.asList("lowercase", OpenSearchManagerImpl.SYNONYM_PLACEHOLDER, "english_stemmer");
		List<String> synonyms = Arrays.asList("biomed-medical_terms", "biomed-disease_acronyms");

		// call under test
		List<String> result = OpenSearchManagerImpl.resolveFilterChain(
				"org-a", order, Collections.emptySet(), synonyms);

		// Placeholder expands in place, preserving SynonymSet order.
		assertEquals(Arrays.asList("lowercase", "biomed-medical_terms", "biomed-disease_acronyms", "english_stemmer"),
				result);
	}

	@Test
	public void testResolveFilterChainDropsPlaceholderWhenSynonymsEmpty() {
		List<String> order = Arrays.asList("lowercase", OpenSearchManagerImpl.SYNONYM_PLACEHOLDER, "english_stemmer");

		// call under test
		List<String> result = OpenSearchManagerImpl.resolveFilterChain(
				"org-a", order, Collections.emptySet(), Collections.emptyList());

		// No synonyms listed on the SearchConfiguration → placeholder silently dropped.
		assertEquals(Arrays.asList("lowercase", "english_stemmer"), result);
	}

	@Test
	public void testResolveFilterChainMixesAllThreeKinds() {
		Set<String> owned = new HashSet<>(Collections.singletonList("custom_stop"));
		List<String> order = Arrays.asList(
				"custom_stop", "lowercase", OpenSearchManagerImpl.SYNONYM_PLACEHOLDER, "asciifolding");
		List<String> synonyms = Arrays.asList("biomed-medical_terms");

		// call under test
		List<String> result = OpenSearchManagerImpl.resolveFilterChain(
				"org-a", order, owned, synonyms);

		assertEquals(Arrays.asList("org-a__custom_stop", "lowercase", "biomed-medical_terms", "asciifolding"), result);
	}

	// --- resolveOwnedChain (char-filter chain — no placeholder support) ---

	@Test
	public void testResolveOwnedChainWithNullOrderReturnsEmpty() {
		// call under test
		List<String> result = OpenSearchManagerImpl.resolveOwnedChain(
				"org-a", null, Collections.emptySet());

		assertTrue(result.isEmpty());
	}

	@Test
	public void testResolveOwnedChainNamespacesOwnedNamesOnly() {
		Set<String> owned = new HashSet<>(Collections.singletonList("html_strip_custom"));
		List<String> order = Arrays.asList("html_strip_custom", "icu_normalizer");

		// call under test
		List<String> result = OpenSearchManagerImpl.resolveOwnedChain("org-a", order, owned);

		assertEquals(Arrays.asList("org-a__html_strip_custom", "icu_normalizer"), result);
	}

	@Test
	public void testResolveOwnedChainPassesPlaceholderThrough() {
		// Char-filter chains never get synonym injection — 'synapse_synonyms' would pass
		// through as a literal name and fail at index build. That is intended (defensive)
		// behavior: the placeholder belongs in token filter chains only.
		List<String> order = Arrays.asList("lowercase", OpenSearchManagerImpl.SYNONYM_PLACEHOLDER);

		// call under test
		List<String> result = OpenSearchManagerImpl.resolveOwnedChain(
				"org-a", order, Collections.emptySet());

		assertEquals(Arrays.asList("lowercase", OpenSearchManagerImpl.SYNONYM_PLACEHOLDER), result);
	}

	// --- isConcurrentDeleteError ---

	@Test
	public void testIsConcurrentDeleteErrorWithMarkerReturnsTrue() {
		ErrorResponse response = ErrorResponse.of(r -> r.status(400).error(
				ErrorCause.of(b -> b.type("concurrent_delete_error")
						.reason("Failed to acknowledge concurrent deletes for index/foo"))));
		OpenSearchException ex = new OpenSearchException(response);

		// call under test
		assertTrue(OpenSearchManagerImpl.isConcurrentDeleteError(ex));
	}

	@Test
	public void testIsConcurrentDeleteErrorWithoutMarkerReturnsFalse() {
		ErrorResponse response = ErrorResponse.of(r -> r.status(404).error(
				ErrorCause.of(b -> b.type("index_not_found_exception").reason("no such index"))));
		OpenSearchException ex = new OpenSearchException(response);

		// call under test
		assertFalse(OpenSearchManagerImpl.isConcurrentDeleteError(ex));
	}

	// --- convertFieldValue (response-side stringification) ---

	@Test
	public void testConvertFieldValueWithNullReturnsNull() {
		// call under test
		assertNull(OpenSearchManagerImpl.convertFieldValue(null));
	}

	@Test
	public void testConvertFieldValueWithStringReturnsRawString() {
		// call under test — String passes through without quoting so callers don't see
		// double-quoted scalars on simple text columns.
		assertEquals("alpha", OpenSearchManagerImpl.convertFieldValue("alpha"));
	}

	@Test
	public void testConvertFieldValueWithNumberStringifies() {
		// call under test
		assertEquals("42", OpenSearchManagerImpl.convertFieldValue(42L));
	}

	@Test
	public void testConvertFieldValueWithListReturnsJsonArray() {
		// call under test
		String result = OpenSearchManagerImpl.convertFieldValue(Arrays.asList("a", "b"));

		assertEquals("[\"a\",\"b\"]", result);
	}

	@Test
	public void testConvertFieldValueWithMapReturnsJsonObject() {
		Map<String, Object> map = new LinkedHashMap<>();
		map.put("foo", "bar");
		// call under test
		String result = OpenSearchManagerImpl.convertFieldValue(map);

		assertTrue(result.contains("\"foo\""));
		assertTrue(result.contains("\"bar\""));
	}

	// --- resolveEffectiveAnalyzerQname ---

	@Test
	public void testResolveEffectiveAnalyzerQnameOverrideWinsOverDefault() {
		Map<String, ColumnAnalyzerOverrideEntry> overrideMap = new HashMap<>();
		overrideMap.put("col-1", new ColumnAnalyzerOverrideEntry()
				.setColumnName("title")
				.setIndexAnalyzer("biomed-special"));

		// call under test — override is set → it wins
		String result = manager.resolveEffectiveAnalyzerQname(
				"col-1", ColumnType.STRING, "org-sagebionetworks-STANDARD",
				overrideMap, Collections.emptyMap(), /*searchSide*/ false);

		assertEquals("biomed-special", result);
	}

	@Test
	public void testResolveEffectiveAnalyzerQnameFallsThroughToDefault() {

		// call under test — no override → default wins
		String result = manager.resolveEffectiveAnalyzerQname(
				"col-1", ColumnType.STRING, "org-sagebionetworks-STANDARD",
				Collections.emptyMap(), Collections.emptyMap(), false);

		assertEquals("org-sagebionetworks-STANDARD", result);
	}

	@Test
	public void testResolveEffectiveAnalyzerQnameUsesColumnTypeDefault() {
		Map<Long, String> idToQname = new HashMap<>();
		idToQname.put(TextAnalyzerBootstrapper.SCIENTIFIC_ID, "org.sagebionetworks-SCIENTIFIC");

		// call under test — no override, no default → falls back to ColumnType platform default
		String result = manager.resolveEffectiveAnalyzerQname(
				"col-1", ColumnType.STRING, null, Collections.emptyMap(), idToQname, false);

		assertEquals("org.sagebionetworks-SCIENTIFIC", result);
	}

	@Test
	public void testResolveEffectiveAnalyzerQnameSearchSideUsesSearchOverride() {
		Map<String, ColumnAnalyzerOverrideEntry> overrideMap = new HashMap<>();
		overrideMap.put("col-1", new ColumnAnalyzerOverrideEntry()
				.setColumnName("title")
				.setIndexAnalyzer("biomed-index-side")
				.setSearchAnalyzer("biomed-search-side"));

		// call under test — searchSide=true picks the search analyzer
		String result = manager.resolveEffectiveAnalyzerQname(
				"col-1", ColumnType.STRING, "org-default",
				overrideMap, Collections.emptyMap(), /*searchSide*/ true);

		assertEquals("biomed-search-side", result);
	}

	// --- buildOverrideMap ---

	@Test
	public void testBuildOverrideMapWithNullReturnsEmpty() {
		// call under test
		Map<String, ColumnAnalyzerOverrideEntry> result = manager.buildOverrideMap(null, Collections.emptyMap());

		assertTrue(result.isEmpty());
	}

	@Test
	public void testBuildOverrideMapTranslatesNameToId() {
		Map<String, String> nameToId = new HashMap<>();
		nameToId.put("title", "col-1");

		ColumnAnalyzerOverrideEntry entry = new ColumnAnalyzerOverrideEntry()
				.setColumnName("title").setIndexAnalyzer("a");
		ColumnAnalyzerOverride cao = new ColumnAnalyzerOverride()
				.setOverrides(Collections.singletonList(entry));

		// call under test
		Map<String, ColumnAnalyzerOverrideEntry> result = manager.buildOverrideMap(
				Collections.singletonList(cao), nameToId);

		assertEquals(1, result.size());
		assertEquals(entry, result.get("col-1"));
	}

	@Test
	public void testBuildOverrideMapSkipsUnknownColumnNames() {
		// Empty nameToId — column "title" can't translate to an ID and the entry is dropped.
		ColumnAnalyzerOverrideEntry entry = new ColumnAnalyzerOverrideEntry()
				.setColumnName("title").setIndexAnalyzer("a");
		ColumnAnalyzerOverride cao = new ColumnAnalyzerOverride()
				.setOverrides(Collections.singletonList(entry));

		// call under test
		Map<String, ColumnAnalyzerOverrideEntry> result = manager.buildOverrideMap(
				Collections.singletonList(cao), Collections.emptyMap());

		assertTrue(result.isEmpty());
	}

	// --- stripBoost ---

	@Test
	public void testStripBoostRemovesCaret() {
		// call under test
		assertEquals("title", manager.stripBoost("title^3"));
	}

	@Test
	public void testStripBoostWithoutCaretReturnsAsIs() {
		// call under test
		assertEquals("title", manager.stripBoost("title"));
	}

	// --- toLong ---

	@Test
	public void testToLongWithLongReturnsLong() {
		// call under test
		assertEquals(Long.valueOf(42), manager.toLong(42L));
	}

	@Test
	public void testToLongWithIntegerReturnsLong() {
		// call under test
		assertEquals(Long.valueOf(42), manager.toLong(42));
	}

	@Test
	public void testToLongWithNumericStringReturnsLong() {
		// call under test
		assertEquals(Long.valueOf(42), manager.toLong("42"));
	}

	@Test
	public void testToLongWithNonNumericStringReturnsNull() {
		// call under test — bad input is swallowed and surfaces as null; callers handle missing rowId.
		assertNull(manager.toLong("not-a-number"));
	}

	// --- validateAnalyzerSettings input gates (no AOSS round-trip) ---

	@Test
	public void testValidateAnalyzerSettingsWithNullSettingsThrows() {
		// call under test
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
				() -> manager.validateAnalyzerSettings(null));
		assertTrue(e.getMessage().contains("settings"));
	}

	@Test
	public void testValidateAnalyzerSettingsWithNullTokenizerThrows() {
		// call under test
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
				() -> manager.validateAnalyzerSettings(new TextAnalyzerSettings()));
		assertTrue(e.getMessage().contains("tokenizer"));
	}

	@Test
	public void testValidateAnalyzerSettingsRejectsFilePathInTokenizerDefinition() {
		TextAnalyzerSettings settings = new TextAnalyzerSettings()
				.setTokenizer(new AnalyzerComponent().setName("custom")
						.setDefinition("{\"type\":\"hyphenation_decompounder\",\"hyphenation_patterns_path\":\"foo.xml\"}"));

		// call under test — *_path keys are rejected before any AOSS call
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
				() -> manager.validateAnalyzerSettings(settings));

		assertTrue(e.getMessage().contains("hyphenation_patterns_path"));
		assertTrue(e.getMessage().contains("file-based parameters"));
	}

	@Test
	public void testValidateAnalyzerSettingsRejectsFilePathInTokenFilter() {
		TextAnalyzerSettings settings = new TextAnalyzerSettings()
				.setTokenizer(new AnalyzerComponent().setName("standard"))
				.setTokenFilters(Collections.singletonList(new AnalyzerComponent()
						.setName("my_stop")
						.setDefinition("{\"type\":\"stop\",\"stopwords_path\":\"english.txt\"}")));

		// call under test
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
				() -> manager.validateAnalyzerSettings(settings));

		assertTrue(e.getMessage().contains("stopwords_path"));
	}

	@Test
	public void testValidateAnalyzerSettingsRejectsFilePathInCharFilter() {
		TextAnalyzerSettings settings = new TextAnalyzerSettings()
				.setTokenizer(new AnalyzerComponent().setName("standard"))
				.setCharFilters(Collections.singletonList(new AnalyzerComponent()
						.setName("my_mapping")
						.setDefinition("{\"type\":\"mapping\",\"mappings_path\":\"/etc/mappings.txt\"}")));

		// call under test
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
				() -> manager.validateAnalyzerSettings(settings));

		assertTrue(e.getMessage().contains("mappings_path"));
	}

	// --- AnalyzerComponent POJO round-trip (belt and suspenders) ---

	@Test
	public void testAnalyzerComponentRoundTrip() {
		AnalyzerComponent c = new AnalyzerComponent()
				.setName("my_edge_ngram")
				.setDefinition("{\"type\":\"edge_ngram\",\"min_gram\":2,\"max_gram\":20}");

		// call under test
		assertEquals("my_edge_ngram", c.getName());
		assertNotNull(c.getDefinition());
		assertTrue(c.getDefinition().contains("edge_ngram"));
	}

	// --- describeError ---

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

	// --- bulkIndex retry / per-document fallback ---

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
				0, 10, Collections.emptyMap(), null, null,
				Collections.emptyList(), Collections.emptyMap(),
				EnumSet.of(SearchQueryPart.TOTAL_HITS));

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
				0, 10, Collections.emptyMap(), null, null,
				Collections.emptyList(), Collections.emptyMap(),
				EnumSet.of(SearchQueryPart.HITS));

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
	public void testWaitForIndexWritableSentinelCleanupFailureIsSwallowed() throws Exception {
		// Write succeeds, but delete fails. The probe should still return normally — cleanup
		// failures are non-fatal; the sentinel with _row_id = -1 cannot collide with real ids.
		when(openSearchClient.index(argThat((IndexRequest<?> req) -> req != null)))
				.thenReturn(okIndexResponse());
		when(openSearchClient.delete(argThat((DeleteRequest req) -> req != null)))
				.thenThrow(new IOException("cleanup failed"));

		// call under test
		manager.waitForIndexWritable("search-index-syn1");

		verify(openSearchClient, times(1)).index(argThat((IndexRequest<?> req) -> req != null));
		verify(openSearchClient, times(1)).delete(argThat((DeleteRequest req) -> req != null));
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
}
