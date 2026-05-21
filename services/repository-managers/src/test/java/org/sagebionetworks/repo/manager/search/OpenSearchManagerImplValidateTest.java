package org.sagebionetworks.repo.manager.search;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.opensearch.client.json.jackson.JacksonJsonpMapper;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.ErrorResponse;
import org.opensearch.client.opensearch._types.OpenSearchException;
import org.opensearch.client.opensearch.indices.AnalyzeRequest;
import org.opensearch.client.opensearch.indices.AnalyzeResponse;
import org.opensearch.client.opensearch.indices.OpenSearchIndicesClient;
import org.opensearch.client.transport.OpenSearchTransport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Unit tests for {@link OpenSearchManagerImpl#validateAnalyzerSettings(JsonNode)}, the
 * AOSS {@code _analyze} probe that surfaces real OpenSearch-side analyzer errors at
 * TextAnalyzer create/update time. Input is the post-{@code $ref}-resolution settings
 * tree produced by {@link SearchAnalyzerJson#resolveRefs}.
 */
@ExtendWith(MockitoExtension.class)
public class OpenSearchManagerImplValidateTest {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	@Mock
	private OpenSearchClient openSearchClient;
	@Mock
	private OpenSearchIndicesClient indicesClient;
	@Mock
	private AnalyzeResponse analyzeResponse;
	@Mock
	private OpenSearchTransport transport;

	private OpenSearchManagerImpl manager;

	private long originalValidateInitialBackoffMs;

	@BeforeEach
	void setUp() {
		manager = new OpenSearchManagerImpl(openSearchClient);
		// Drop validate retry backoff to 1ms so the IOException-exhaustion path
		// doesn't actually sleep through the exponential schedule on every run.
		originalValidateInitialBackoffMs = OpenSearchManagerImpl.VALIDATE_INITIAL_BACKOFF_MS;
		OpenSearchManagerImpl.VALIDATE_INITIAL_BACKOFF_MS = 1L;
	}

	@AfterEach
	void tearDown() {
		OpenSearchManagerImpl.VALIDATE_INITIAL_BACKOFF_MS = originalValidateInitialBackoffMs;
	}

	private void setupAnalyzeSuccess() throws IOException {
		when(openSearchClient.indices()).thenReturn(indicesClient);
		when(indicesClient.analyze(any(AnalyzeRequest.class))).thenReturn(analyzeResponse);
	}

	private void setupJsonpMapper() {
		when(openSearchClient._transport()).thenReturn(transport);
		when(transport.jsonpMapper()).thenReturn(new JacksonJsonpMapper());
	}

	private static JsonNode parse(String json) {
		try {
			return MAPPER.readTree(json);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	@Test
	public void testValidateWithStandardTokenizerSuccess() throws IOException {
		setupAnalyzeSuccess();

		// Bare built-in tokenizer reference; no inline registry needed.
		JsonNode settings = parse("{\"analyzer\":{\"default\":{\"type\":\"custom\","
				+ "\"tokenizer\":\"standard\"}}}");

		// call under test
		assertDoesNotThrow(() -> manager.validateAnalyzerSettings(settings));
	}

	@Test
	public void testValidateWithInlineFilterRegistrySuccess() throws IOException {
		setupAnalyzeSuccess();
		setupJsonpMapper();

		// my_stop is owned by this analyzer's filter registry — submitted inline. lowercase
		// is a built-in — submitted by name.
		JsonNode settings = parse("{"
				+ "\"filter\":{\"my_stop\":{\"type\":\"stop\",\"stopwords\":\"_english_\"}},"
				+ "\"analyzer\":{\"default\":{\"type\":\"custom\","
				+ "\"tokenizer\":\"standard\","
				+ "\"filter\":[\"my_stop\",\"lowercase\"]}}}");

		// call under test
		assertDoesNotThrow(() -> manager.validateAnalyzerSettings(settings));
	}

	@Test
	public void testValidateWithInlineTokenizerRegistrySuccess() throws IOException {
		setupAnalyzeSuccess();
		setupJsonpMapper();

		// Inline tokenizer definition rather than a built-in — exercises the typed deserialize
		// path through TokenizerDefinition.
		JsonNode settings = parse("{"
				+ "\"tokenizer\":{\"my_ngram\":{\"type\":\"edge_ngram\",\"min_gram\":2,"
				+ "\"max_gram\":20,\"token_chars\":[\"letter\",\"digit\"]}},"
				+ "\"analyzer\":{\"default\":{\"type\":\"custom\","
				+ "\"tokenizer\":\"my_ngram\","
				+ "\"filter\":[\"lowercase\"]}}}");

		// call under test
		assertDoesNotThrow(() -> manager.validateAnalyzerSettings(settings));
	}

	@Test
	public void testValidateWithInlineCharFilterRegistrySuccess() throws IOException {
		setupAnalyzeSuccess();
		setupJsonpMapper();

		JsonNode settings = parse("{"
				+ "\"char_filter\":{\"my_mapping\":{\"type\":\"mapping\",\"mappings\":[\"& => and\"]}},"
				+ "\"analyzer\":{\"default\":{\"type\":\"custom\","
				+ "\"tokenizer\":\"standard\","
				+ "\"char_filter\":[\"my_mapping\"]}}}");

		// call under test
		assertDoesNotThrow(() -> manager.validateAnalyzerSettings(settings));
	}

	@Test
	public void testValidateWithMinimalDefaultEntrySuccess() throws IOException {
		setupAnalyzeSuccess();

		// analyzer.default with no tokenizer/filter/char_filter — defaults to "standard".
		JsonNode settings = parse("{\"analyzer\":{\"default\":{\"type\":\"custom\"}}}");

		// call under test
		assertDoesNotThrow(() -> manager.validateAnalyzerSettings(settings));
	}

	@Test
	public void testValidateThrowsIllegalArgumentOnOpenSearchException() throws IOException {
		when(openSearchClient.indices()).thenReturn(indicesClient);
		ErrorResponse errorResponse = ErrorResponse.of(e -> e
				.error(err -> err.type("illegal_argument_exception").reason("Unknown tokenizer type [foobar]"))
				.status(400));
		when(indicesClient.analyze(any(AnalyzeRequest.class)))
				.thenThrow(new OpenSearchException(errorResponse));

		JsonNode settings = parse("{\"analyzer\":{\"default\":{\"type\":\"custom\","
				+ "\"tokenizer\":\"foobar\"}}}");

		// call under test
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				() -> manager.validateAnalyzerSettings(settings));
		assertTrue(ex.getMessage().contains("Unknown tokenizer type [foobar]"));
		assertTrue(ex.getMessage().contains("Invalid analyzer configuration"));
	}

	@Test
	public void testValidateThrowsIllegalStateOnIOException() throws IOException {
		when(openSearchClient.indices()).thenReturn(indicesClient);
		when(indicesClient.analyze(any(AnalyzeRequest.class)))
				.thenThrow(new IOException("Connection refused"));

		JsonNode settings = parse("{\"analyzer\":{\"default\":{\"type\":\"custom\","
				+ "\"tokenizer\":\"standard\"}}}");

		// call under test
		IllegalStateException ex = assertThrows(IllegalStateException.class,
				() -> manager.validateAnalyzerSettings(settings));
		assertTrue(ex.getMessage().contains("temporarily unavailable"));
	}

	@Test
	public void testValidateThrowsOnNullSettings() {
		// call under test
		assertThrows(IllegalArgumentException.class,
				() -> manager.validateAnalyzerSettings(null));
	}

	@Test
	public void testValidateThrowsWhenAnalyzerDefaultMissing() {
		// Defense-in-depth — TextAnalyzerManagerImpl.validateSettings already enforces this,
		// but the AOSS-facing seam should not silently no-op when its core requirement
		// (an analyzer.default entry) isn't present.
		JsonNode settings = parse("{\"filter\":{\"english_stop\":{\"type\":\"stop\"}}}");

		// call under test
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				() -> manager.validateAnalyzerSettings(settings));
		assertTrue(ex.getMessage().contains("analyzer.default"));
	}

	@Test
	public void testValidateRetriesOnIndexNotFoundThenSucceeds() throws IOException {
		// AOSS occasionally returns transient index_not_found_exception from the cluster-
		// level _analyze endpoint while a system index is being provisioned. The retry must
		// absorb a single hit and the second attempt must succeed.
		when(openSearchClient.indices()).thenReturn(indicesClient);
		ErrorResponse indexNotFound = ErrorResponse.of(e -> e
				.error(err -> err.type("index_not_found_exception").reason("no such index"))
				.status(404));
		when(indicesClient.analyze(any(AnalyzeRequest.class)))
				.thenThrow(new OpenSearchException(indexNotFound))
				.thenReturn(analyzeResponse);

		JsonNode settings = parse("{\"analyzer\":{\"default\":{\"type\":\"custom\","
				+ "\"tokenizer\":\"standard\"}}}");

		// call under test — first attempt throws index_not_found, second succeeds
		assertDoesNotThrow(() -> manager.validateAnalyzerSettings(settings));
		verify(indicesClient, times(2)).analyze(any(AnalyzeRequest.class));
	}

	@Test
	public void testValidateThrowsIllegalStateWhenIndexNotFoundExhaustsRetries() throws IOException {
		// All retries exhausted — must surface as IllegalStateException, not the underlying
		// IllegalArgumentException-shaped index_not_found message that a curator can't act on.
		when(openSearchClient.indices()).thenReturn(indicesClient);
		ErrorResponse indexNotFound = ErrorResponse.of(e -> e
				.error(err -> err.type("index_not_found_exception").reason("no such index"))
				.status(404));
		when(indicesClient.analyze(any(AnalyzeRequest.class)))
				.thenThrow(new OpenSearchException(indexNotFound));

		JsonNode settings = parse("{\"analyzer\":{\"default\":{\"type\":\"custom\","
				+ "\"tokenizer\":\"standard\"}}}");

		// call under test
		IllegalStateException ex = assertThrows(IllegalStateException.class,
				() -> manager.validateAnalyzerSettings(settings));
		assertTrue(ex.getMessage().contains("temporarily unavailable"));
		verify(indicesClient, times(OpenSearchManagerImpl.VALIDATE_MAX_RETRIES))
				.analyze(any(AnalyzeRequest.class));
	}

	@Test
	public void testValidateRunsOneAnalyzePerAnalyzerEntry() throws IOException {
		// The validator must exercise every entry under analyzer.* — a single _analyze call
		// against `default` doesn't cover filters/tokenizers that are only reachable from a
		// sibling entry like `default_search`.
		setupAnalyzeSuccess();

		JsonNode settings = parse("{"
				+ "\"analyzer\":{"
					+ "\"default\":{\"type\":\"custom\",\"tokenizer\":\"standard\"},"
					+ "\"default_search\":{\"type\":\"custom\",\"tokenizer\":\"keyword\"},"
					+ "\"third\":{\"type\":\"custom\",\"tokenizer\":\"whitespace\"}"
				+ "}}");

		// call under test
		assertDoesNotThrow(() -> manager.validateAnalyzerSettings(settings));
		verify(indicesClient, times(3)).analyze(any(AnalyzeRequest.class));
	}

	@Test
	public void testValidateCatchesBadFilterReferencedOnlyFromNonDefaultEntry() throws IOException {
		// `default` is well-formed, but `default_search` references a filter type AOSS
		// doesn't recognize. The first per-entry analyze succeeds; the second must surface
		// AOSS's rejection as a permanent IllegalArgumentException naming the offending entry.
		when(openSearchClient.indices()).thenReturn(indicesClient);
		ErrorResponse goodResponse = ErrorResponse.of(e -> e
				.error(err -> err.type("ok").reason("ok")).status(200));
		ErrorResponse bad = ErrorResponse.of(e -> e
				.error(err -> err.type("illegal_argument_exception")
						.reason("Unknown token filter type [bogus_type]")).status(400));
		// First call (default): succeeds. Second call (default_search): fails.
		when(indicesClient.analyze(any(AnalyzeRequest.class)))
				.thenReturn(analyzeResponse)
				.thenThrow(new OpenSearchException(bad));

		JsonNode settings = parse("{"
				+ "\"analyzer\":{"
					+ "\"default\":{\"type\":\"custom\",\"tokenizer\":\"standard\"},"
					+ "\"default_search\":{\"type\":\"custom\",\"tokenizer\":\"standard\","
						+ "\"filter\":[\"bogus_type\"]}"
				+ "}}");

		// call under test
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				() -> manager.validateAnalyzerSettings(settings));
		assertTrue(ex.getMessage().contains("default_search"),
				"Error must name the offending analyzer entry: " + ex.getMessage());
		assertTrue(ex.getMessage().contains("bogus_type"),
				"Error must surface AOSS's reason: " + ex.getMessage());
	}

	@Test
	public void testValidateThrowsOnMalformedInlineFilter() {
		// Inline filter registry entry that's not a valid TokenFilterDefinition — typed
		// deserialize should fail and surface as IllegalArgumentException with the filter name.
		setupJsonpMapper();
		JsonNode settings = parse("{"
				+ "\"filter\":{\"bad_one\":{\"type\":\"this_filter_does_not_exist\"}},"
				+ "\"analyzer\":{\"default\":{\"type\":\"custom\","
				+ "\"tokenizer\":\"standard\","
				+ "\"filter\":[\"bad_one\"]}}}");

		// call under test
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				() -> manager.validateAnalyzerSettings(settings));
		assertTrue(ex.getMessage().contains("bad_one"),
				"Error must name the offending filter: " + ex.getMessage());
	}
}
