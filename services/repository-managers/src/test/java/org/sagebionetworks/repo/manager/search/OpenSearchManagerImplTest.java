package org.sagebionetworks.repo.manager.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.junit.jupiter.MockitoExtension;
import org.opensearch.client.opensearch._types.ErrorCause;
import org.opensearch.client.opensearch._types.ErrorResponse;
import org.opensearch.client.opensearch._types.OpenSearchException;
import org.sagebionetworks.repo.model.search.table.AnalyzerComponent;
import org.sagebionetworks.repo.model.search.table.ColumnAnalyzerOverride;
import org.sagebionetworks.repo.model.search.table.ColumnAnalyzerOverrideEntry;
import org.sagebionetworks.repo.model.search.table.TextAnalyzer;
import org.sagebionetworks.repo.model.search.table.TextAnalyzerSettings;
import org.sagebionetworks.repo.model.table.ColumnType;

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
		OpenSearchManagerImpl manager = new OpenSearchManagerImpl(null);
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
		OpenSearchManagerImpl manager = new OpenSearchManagerImpl(null);

		// call under test — no override → default wins
		String result = manager.resolveEffectiveAnalyzerQname(
				"col-1", ColumnType.STRING, "org-sagebionetworks-STANDARD",
				Collections.emptyMap(), Collections.emptyMap(), false);

		assertEquals("org-sagebionetworks-STANDARD", result);
	}

	@Test
	public void testResolveEffectiveAnalyzerQnameUsesColumnTypeDefault() {
		OpenSearchManagerImpl manager = new OpenSearchManagerImpl(null);
		Map<Long, String> idToQname = new HashMap<>();
		idToQname.put(TextAnalyzerBootstrapper.SCIENTIFIC_ID, "org.sagebionetworks-SCIENTIFIC");

		// call under test — no override, no default → falls back to ColumnType platform default
		String result = manager.resolveEffectiveAnalyzerQname(
				"col-1", ColumnType.STRING, null, Collections.emptyMap(), idToQname, false);

		assertEquals("org.sagebionetworks-SCIENTIFIC", result);
	}

	@Test
	public void testResolveEffectiveAnalyzerQnameSearchSideUsesSearchOverride() {
		OpenSearchManagerImpl manager = new OpenSearchManagerImpl(null);
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
		OpenSearchManagerImpl manager = new OpenSearchManagerImpl(null);
		// call under test
		Map<String, ColumnAnalyzerOverrideEntry> result = manager.buildOverrideMap(null, Collections.emptyMap());

		assertTrue(result.isEmpty());
	}

	@Test
	public void testBuildOverrideMapTranslatesNameToId() {
		OpenSearchManagerImpl manager = new OpenSearchManagerImpl(null);
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
		OpenSearchManagerImpl manager = new OpenSearchManagerImpl(null);
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
		OpenSearchManagerImpl manager = new OpenSearchManagerImpl(null);
		// call under test
		assertEquals("title", manager.stripBoost("title^3"));
	}

	@Test
	public void testStripBoostWithoutCaretReturnsAsIs() {
		OpenSearchManagerImpl manager = new OpenSearchManagerImpl(null);
		// call under test
		assertEquals("title", manager.stripBoost("title"));
	}

	// --- toLong ---

	@Test
	public void testToLongWithLongReturnsLong() {
		OpenSearchManagerImpl manager = new OpenSearchManagerImpl(null);
		// call under test
		assertEquals(Long.valueOf(42), manager.toLong(42L));
	}

	@Test
	public void testToLongWithIntegerReturnsLong() {
		OpenSearchManagerImpl manager = new OpenSearchManagerImpl(null);
		// call under test
		assertEquals(Long.valueOf(42), manager.toLong(42));
	}

	@Test
	public void testToLongWithNumericStringReturnsLong() {
		OpenSearchManagerImpl manager = new OpenSearchManagerImpl(null);
		// call under test
		assertEquals(Long.valueOf(42), manager.toLong("42"));
	}

	@Test
	public void testToLongWithNonNumericStringReturnsNull() {
		OpenSearchManagerImpl manager = new OpenSearchManagerImpl(null);
		// call under test — bad input is swallowed and surfaces as null; callers handle missing rowId.
		assertNull(manager.toLong("not-a-number"));
	}

	// --- validateAnalyzerSettings input gates (no AOSS round-trip) ---

	@Test
	public void testValidateAnalyzerSettingsWithNullSettingsThrows() {
		OpenSearchManagerImpl manager = new OpenSearchManagerImpl(null);
		// call under test
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
				() -> manager.validateAnalyzerSettings(null));
		assertTrue(e.getMessage().contains("settings"));
	}

	@Test
	public void testValidateAnalyzerSettingsWithNullTokenizerThrows() {
		OpenSearchManagerImpl manager = new OpenSearchManagerImpl(null);
		// call under test
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
				() -> manager.validateAnalyzerSettings(new TextAnalyzerSettings()));
		assertTrue(e.getMessage().contains("tokenizer"));
	}

	@Test
	public void testValidateAnalyzerSettingsRejectsFilePathInTokenizerDefinition() {
		OpenSearchManagerImpl manager = new OpenSearchManagerImpl(null);
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
		OpenSearchManagerImpl manager = new OpenSearchManagerImpl(null);
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
		OpenSearchManagerImpl manager = new OpenSearchManagerImpl(null);
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
}
