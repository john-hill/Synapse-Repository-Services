package org.sagebionetworks.repo.manager.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.ErrorCause;
import org.opensearch.client.opensearch._types.ErrorResponse;
import org.opensearch.client.opensearch._types.OpenSearchException;
import org.opensearch.client.opensearch._types.aggregations.LongTermsBucketKey;
import org.sagebionetworks.repo.model.search.table.ColumnAnalyzerOverride;
import org.sagebionetworks.repo.model.search.table.ColumnAnalyzerOverrideEntry;
import org.sagebionetworks.repo.model.table.ColumnModel;
import org.sagebionetworks.repo.model.table.ColumnType;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Unit tests for the pure-logic helpers in {@link OpenSearchManagerImpl}: qname-to-AOSS-key
 * translation, the per-analyzer chain-reference rewrite that namespaces owned components by
 * their owning analyzer's qualified name, error/failure formatting, retry-status
 * classification, response value coercion, and per-column analyzer resolution. AOSS-backed
 * flows (createIndex, search, bulk) are covered by integration tests against a live cluster.
 */
@ExtendWith(MockitoExtension.class)
public class OpenSearchManagerImplTest {

	@Mock
	private OpenSearchClient openSearchClient;

	@InjectMocks
	private OpenSearchManagerImpl manager;

	private static final ObjectMapper MAPPER = new ObjectMapper();

	// ---- toAossKey ----

	@Test
	public void testToAossKeyReplacesDotsWithUnderscores() {
		// AOSS rejects '.' inside settings keys (it treats them as JSON-path separators), so
		// the qname-to-AOSS-key translation folds dots to underscores at the wire boundary.
		assertEquals("org_sagebionetworks-SCIENTIFIC",
				OpenSearchManagerImpl.toAossKey("org.sagebionetworks-SCIENTIFIC"));
	}

	@Test
	public void testToAossKeyWithoutDotsIsUnchanged() {
		assertEquals("biomed-medical_terms",
				OpenSearchManagerImpl.toAossKey("biomed-medical_terms"));
	}

	@Test
	public void testToAossKeyWithNullReturnsNull() {
		assertNull(OpenSearchManagerImpl.toAossKey(null));
	}

	// ---- rewriteAnalyzerEntry ----

	@Test
	public void testRewriteAnalyzerEntryRewritesOwnedReferences() throws Exception {
		// An analyzer entry with a tokenizer reference, char_filter chain, and filter
		// chain mixing owned and built-in names. Owned names get namespaced; built-ins pass
		// through unchanged.
		JsonNode entry = MAPPER.readTree("{"
				+ "\"type\":\"custom\","
				+ "\"tokenizer\":\"std\","
				+ "\"char_filter\":[\"strip_html\",\"icu_normalizer\"],"
				+ "\"filter\":[\"lowercase\",\"my_syn\",\"english_stop\"]"
				+ "}");
		Set<String> ownedCharFilters = new HashSet<>();
		ownedCharFilters.add("strip_html");
		Set<String> ownedTokenizers = new HashSet<>();
		ownedTokenizers.add("std");
		Set<String> ownedFilters = new HashSet<>();
		ownedFilters.add("my_syn");
		ownedFilters.add("english_stop");

		// call under test
		JsonNode rewritten = OpenSearchManagerImpl.rewriteAnalyzerEntry(entry,
				"biomed-publications", ownedCharFilters, ownedFilters, ownedTokenizers);

		// Owned tokenizer reference is namespaced.
		assertEquals("biomed-publications__std", rewritten.get("tokenizer").asText());
		// char_filter chain: owned is namespaced, built-in passes through.
		assertEquals("biomed-publications__strip_html", rewritten.at("/char_filter/0").asText());
		assertEquals("icu_normalizer", rewritten.at("/char_filter/1").asText());
		// filter chain: built-in passes through; both owned filters are namespaced.
		assertEquals("lowercase", rewritten.at("/filter/0").asText());
		assertEquals("biomed-publications__my_syn", rewritten.at("/filter/1").asText());
		assertEquals("biomed-publications__english_stop", rewritten.at("/filter/2").asText());
	}

	@Test
	public void testRewriteAnalyzerEntryDoesNotMutateInput() throws Exception {
		JsonNode entry = MAPPER.readTree("{\"type\":\"custom\",\"tokenizer\":\"std\","
				+ "\"filter\":[\"my_filter\"]}");
		Set<String> ownedFilters = new HashSet<>();
		ownedFilters.add("my_filter");
		Set<String> ownedTokenizers = new HashSet<>();
		ownedTokenizers.add("std");

		// call under test
		OpenSearchManagerImpl.rewriteAnalyzerEntry(entry, "org-X",
				new HashSet<>(), ownedFilters, ownedTokenizers);

		// Original tree is untouched — still has the un-namespaced names.
		assertEquals("std", entry.get("tokenizer").asText());
		assertEquals("my_filter", entry.at("/filter/0").asText());
	}

	@Test
	public void testRewriteAnalyzerEntryWithBuiltInsOnlyIsIdempotent() throws Exception {
		JsonNode entry = MAPPER.readTree("{\"type\":\"custom\",\"tokenizer\":\"standard\","
				+ "\"filter\":[\"lowercase\",\"english_stop\"]}");

		// call under test — no owned names anywhere; all references should pass through.
		JsonNode rewritten = OpenSearchManagerImpl.rewriteAnalyzerEntry(entry, "org-X",
				new HashSet<>(), new HashSet<>(), new HashSet<>());

		assertEquals("standard", rewritten.get("tokenizer").asText());
		assertEquals("lowercase", rewritten.at("/filter/0").asText());
		assertEquals("english_stop", rewritten.at("/filter/1").asText());
	}

	@Test
	public void testRewriteAnalyzerEntryWithoutChainsIsAcceptedUnchanged() throws Exception {
		// A built-in analyzer entry that doesn't include tokenizer/filter/char_filter (e.g.
		// type:"keyword") should round-trip unchanged.
		JsonNode entry = MAPPER.readTree("{\"type\":\"keyword\"}");

		// call under test
		JsonNode rewritten = OpenSearchManagerImpl.rewriteAnalyzerEntry(entry, "org-X",
				new HashSet<>(), new HashSet<>(), new HashSet<>());

		assertEquals("keyword", rewritten.get("type").asText());
	}

	// ---- isConcurrentDeleteError ----

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

	// ---- isRetryableItemStatus ----

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

	// ---- describeError ----

	@Test
	public void testDescribeErrorWithNullReturnsPlaceholder() {
		assertEquals("?", OpenSearchManagerImpl.describeError(null));
	}

	@Test
	public void testDescribeErrorIncludesTypeAndReason() {
		ErrorCause c = ErrorCause.of(b -> b.type("validation_exception").reason("bad input"));
		assertEquals("validation_exception: bad input", OpenSearchManagerImpl.describeError(c));
	}

	@Test
	public void testDescribeErrorChainsCausedBy() {
		// AOSS frequently buries the real cause inside caused_by; the helper must walk the chain.
		ErrorCause inner = ErrorCause.of(b -> b.type("inner_t").reason("inner_r"));
		ErrorCause outer = ErrorCause.of(b -> b.type("outer_t").reason("outer_r").causedBy(inner));
		String desc = OpenSearchManagerImpl.describeError(outer);
		assertEquals("outer_t: outer_r caused by inner_t: inner_r", desc);
	}

	// ---- buildPermanentFailureMessage ----

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

	// ---- convertFieldValue ----

	@Test
	public void testConvertFieldValueWithNullReturnsNull() {
		assertNull(OpenSearchManagerImpl.convertFieldValue(null));
	}

	@Test
	public void testConvertFieldValueWithStringReturnsRaw() {
		// Strings must NOT be re-quoted — clients expect the raw value.
		assertEquals("hello world",
				OpenSearchManagerImpl.convertFieldValue("hello world"));
	}

	@Test
	public void testConvertFieldValueWithNumberReturnsToString() {
		assertEquals("42", OpenSearchManagerImpl.convertFieldValue(42L));
		assertEquals("3.14", OpenSearchManagerImpl.convertFieldValue(3.14));
	}

	@Test
	public void testConvertFieldValueWithListReturnsCanonicalJsonArray() {
		String result = OpenSearchManagerImpl.convertFieldValue(Arrays.asList("a", "b", "c"));
		assertEquals("[\"a\",\"b\",\"c\"]", result);
	}

	@Test
	public void testConvertFieldValueWithMapReturnsCanonicalJsonObject() {
		Map<String, Object> map = new LinkedHashMap<>();
		map.put("k1", "v1");
		map.put("k2", 2);
		String result = OpenSearchManagerImpl.convertFieldValue(map);
		assertEquals("{\"k1\":\"v1\",\"k2\":2}", result);
	}

	// ---- longBucketKeyToString ----

	@Test
	public void testLongBucketKeyToStringPrefersKeyAsStringWhenPresent() {
		// keyAsString is populated for boolean/date fields with an implicit format and should
		// be used verbatim — that's what makes booleans render as "true"/"false" rather than
		// "1"/"0".
		LongTermsBucketKey signed = LongTermsBucketKey.of(b -> b.signed(1L));
		assertEquals("true", OpenSearchManagerImpl.longBucketKeyToString("true", signed));
	}

	@Test
	public void testLongBucketKeyToStringFallsBackToSignedKey() {
		// keyAsString is null for plain LONG fields — fall back to the typed key.
		LongTermsBucketKey signed = LongTermsBucketKey.of(b -> b.signed(42L));
		assertEquals("42", OpenSearchManagerImpl.longBucketKeyToString(null, signed));
	}

	// ---- buildOverrideMap ----

	@Test
	public void testBuildOverrideMapWithNullReturnsEmpty() {
		Map<String, ColumnAnalyzerOverrideEntry> map = manager.buildOverrideMap(null, new HashMap<>());
		assertTrue(map.isEmpty());
	}

	@Test
	public void testBuildOverrideMapKeysByColumnId() {
		// The override resource carries column NAMES; the impl translates them to column IDs
		// using the supplied nameToId map so the index-build code can key off the same IDs it
		// uses for the field mapping.
		Map<String, String> nameToId = new HashMap<>();
		nameToId.put("disease", "111");
		nameToId.put("name", "222");
		ColumnAnalyzerOverrideEntry diseaseEntry = new ColumnAnalyzerOverrideEntry()
				.setColumnName("disease")
				.setAnalyzer("biomed-medical");
		ColumnAnalyzerOverrideEntry nameEntry = new ColumnAnalyzerOverrideEntry()
				.setColumnName("name")
				.setAnalyzer("biomed-strict");
		ColumnAnalyzerOverride bundle = new ColumnAnalyzerOverride()
				.setOverrides(Arrays.asList(diseaseEntry, nameEntry));

		// call under test
		Map<String, ColumnAnalyzerOverrideEntry> map = manager.buildOverrideMap(
				Collections.singletonList(bundle), nameToId);

		assertEquals(2, map.size());
		assertEquals(diseaseEntry, map.get("111"));
		assertEquals(nameEntry, map.get("222"));
	}

	@Test
	public void testBuildOverrideMapFirstMatchWins() {
		// Two override bundles list the same column — the first bundle's entry should win so
		// the SearchConfiguration's listed order of overrides is preserved.
		Map<String, String> nameToId = new HashMap<>();
		nameToId.put("disease", "111");
		ColumnAnalyzerOverrideEntry first = new ColumnAnalyzerOverrideEntry()
				.setColumnName("disease")
				.setAnalyzer("first");
		ColumnAnalyzerOverrideEntry second = new ColumnAnalyzerOverrideEntry()
				.setColumnName("disease")
				.setAnalyzer("second");
		ColumnAnalyzerOverride a = new ColumnAnalyzerOverride()
				.setOverrides(Collections.singletonList(first));
		ColumnAnalyzerOverride b = new ColumnAnalyzerOverride()
				.setOverrides(Collections.singletonList(second));

		// call under test
		Map<String, ColumnAnalyzerOverrideEntry> map = manager.buildOverrideMap(
				Arrays.asList(a, b), nameToId);

		assertEquals(first, map.get("111"));
	}

	@Test
	public void testBuildOverrideMapSkipsUnknownColumnNames() {
		// Override entries whose columnName is not in the index's schema are silently dropped
		// — one override resource can apply to multiple indexes that share some columns.
		Map<String, String> nameToId = new HashMap<>();
		nameToId.put("known", "111");
		ColumnAnalyzerOverride bundle = new ColumnAnalyzerOverride()
				.setOverrides(Arrays.asList(
						new ColumnAnalyzerOverrideEntry().setColumnName("known")
								.setAnalyzer("a"),
						new ColumnAnalyzerOverrideEntry().setColumnName("missing")
								.setAnalyzer("b")));

		// call under test
		Map<String, ColumnAnalyzerOverrideEntry> map = manager.buildOverrideMap(
				Collections.singletonList(bundle), nameToId);

		assertEquals(1, map.size());
		assertTrue(map.containsKey("111"));
	}

	// ---- stripBoost ----

	@Test
	public void testStripBoostRemovesBoostSuffix() {
		assertEquals("name", manager.stripBoost("name^3"));
	}

	@Test
	public void testStripBoostWithoutBoostIsUnchanged() {
		assertEquals("name", manager.stripBoost("name"));
	}

	// ---- toLong ----

	@Test
	public void testToLongWithNumberReturnsValue() {
		assertEquals(Long.valueOf(42L), manager.toLong(42));
		assertEquals(Long.valueOf(42L), manager.toLong(42L));
		assertEquals(Long.valueOf(3L), manager.toLong(3.7));
	}

	@Test
	public void testToLongWithParseableStringReturnsValue() {
		assertEquals(Long.valueOf(99L), manager.toLong("99"));
	}

	@Test
	public void testToLongWithUnparseableReturnsNull() {
		// _row_id is always a long, but defensive: bad source data should null out instead of
		// throwing so the rest of the response still serializes.
		assertNull(manager.toLong("not a number"));
		assertNull(manager.toLong(null));
	}

	// ---- getFilterFieldName ----

	@Test
	public void testGetFilterFieldNameAddsKeywordSubFieldForTextTypes() {
		// Text and link columns route filter / sort / aggregation operations through the
		// `.keyword` sub-field for exact match.
		Map<String, ColumnModel> columnMap = new HashMap<>();
		columnMap.put("111", new ColumnModel().setId("111").setColumnType(ColumnType.STRING));

		assertEquals("111.keyword", manager.getFilterFieldName("111", columnMap));
	}

	@Test
	public void testGetFilterFieldNameUsesBareIdForNonTextTypes() {
		Map<String, ColumnModel> columnMap = new HashMap<>();
		columnMap.put("111", new ColumnModel().setId("111").setColumnType(ColumnType.INTEGER));

		assertEquals("111", manager.getFilterFieldName("111", columnMap));
	}

	@Test
	public void testGetFilterFieldNameUnknownColumnPassesThrough() {
		// Defensive: an unknown column id (e.g. system field _row_id) should pass through
		// unmodified rather than throwing.
		assertEquals("_row_id", manager.getFilterFieldName("_row_id", new HashMap<>()));
	}

	// ---- resolveQueryFields ----

	@Test
	public void testResolveQueryFieldsReturnsNullForEmptyInput() {
		// An unset queryFields means "search all fields" — preserve null so the OpenSearch
		// request omits the fields list entirely.
		List<ColumnModel> columns = Collections.singletonList(
				new ColumnModel().setId("111").setName("name").setColumnType(ColumnType.STRING));
		assertNull(manager.resolveQueryFields(null, columns, true));
		assertNull(manager.resolveQueryFields(new ArrayList<>(), columns, true));
	}

	@Test
	public void testResolveQueryFieldsTranslatesNamesToIdsAndPreservesBoost() {
		// The user supplies column NAMES (with optional boost suffix); the OpenSearch request
		// requires column IDs.
		List<ColumnModel> columns = Arrays.asList(
				new ColumnModel().setId("111").setName("name").setColumnType(ColumnType.STRING),
				new ColumnModel().setId("222").setName("description").setColumnType(ColumnType.STRING));

		// call under test (search-side path uses bare id, no .keyword sub-field)
		List<String> resolved = manager.resolveQueryFields(
				Arrays.asList("name^3", "description"), columns, true);

		assertEquals(Arrays.asList("111^3", "222"), resolved);
	}

	@Test
	public void testResolveQueryFieldsForFilterUsesKeywordSubField() {
		List<ColumnModel> columns = Collections.singletonList(
				new ColumnModel().setId("111").setName("name").setColumnType(ColumnType.STRING));

		// call under test — filter side routes text fields through .keyword.
		List<String> resolved = manager.resolveQueryFields(
				Collections.singletonList("name"), columns, false);

		assertEquals(Collections.singletonList("111.keyword"), resolved);
	}
}
