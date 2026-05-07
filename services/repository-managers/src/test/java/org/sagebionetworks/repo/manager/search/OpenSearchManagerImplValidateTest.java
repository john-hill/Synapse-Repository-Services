package org.sagebionetworks.repo.manager.search;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.Arrays;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.opensearch.client.json.jackson.JacksonJsonpMapper;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.transport.OpenSearchTransport;
import org.opensearch.client.opensearch._types.ErrorResponse;
import org.opensearch.client.opensearch._types.OpenSearchException;
import org.opensearch.client.opensearch.indices.CreateIndexRequest;
import org.opensearch.client.opensearch.indices.CreateIndexResponse;
import org.opensearch.client.opensearch.indices.DeleteIndexRequest;
import org.opensearch.client.opensearch.indices.DeleteIndexResponse;
import org.opensearch.client.opensearch.indices.OpenSearchIndicesClient;
import org.sagebionetworks.repo.model.search.table.TextAnalyzerSettings;

@ExtendWith(MockitoExtension.class)
public class OpenSearchManagerImplValidateTest {

	@Mock
	private OpenSearchClient openSearchClient;
	@Mock
	private OpenSearchIndicesClient indicesClient;
	@Mock
	private CreateIndexResponse createResponse;
	@Mock
	private DeleteIndexResponse deleteResponse;
	@Mock
	private OpenSearchTransport transport;

	private OpenSearchManagerImpl manager;

	@BeforeEach
	void setUp() {
		manager = new OpenSearchManagerImpl(openSearchClient);
	}

	private void setupCreateSuccess() throws IOException {
		when(openSearchClient.indices()).thenReturn(indicesClient);
		when(indicesClient.create(any(CreateIndexRequest.class))).thenReturn(createResponse);
		when(indicesClient.delete(any(DeleteIndexRequest.class))).thenReturn(deleteResponse);
	}

	private void setupCleanupOnly() throws IOException {
		when(openSearchClient.indices()).thenReturn(indicesClient);
		when(indicesClient.delete(any(DeleteIndexRequest.class))).thenReturn(deleteResponse);
	}

	private void setupJsonpMapper() {
		when(openSearchClient._transport()).thenReturn(transport);
		when(transport.jsonpMapper()).thenReturn(new JacksonJsonpMapper());
	}

	@Test
	public void testValidateWithStandardTokenizerSuccess() throws IOException {
		setupCreateSuccess();

		TextAnalyzerSettings settings = new TextAnalyzerSettings();
		settings.setTokenizer("standard");

		// call under test
		assertDoesNotThrow(() -> manager.validateAnalyzerSettings(settings));

		verify(indicesClient, times(1)).create(any(CreateIndexRequest.class));
		verify(indicesClient, times(1)).delete(any(DeleteIndexRequest.class));
	}

	@Test
	public void testValidateWithFiltersSuccess() throws IOException {
		setupCreateSuccess();
		setupJsonpMapper();

		TextAnalyzerSettings settings = new TextAnalyzerSettings();
		settings.setTokenizer("standard");
		settings.setTokenFilters("{\"my_stop\":{\"type\":\"stop\",\"stopwords\":\"_english_\"}}");
		settings.setFilterOrder(Arrays.asList("my_stop", "lowercase"));

		// call under test
		assertDoesNotThrow(() -> manager.validateAnalyzerSettings(settings));

		verify(indicesClient, times(1)).create(any(CreateIndexRequest.class));
		verify(indicesClient, times(1)).delete(any(DeleteIndexRequest.class));
	}

	@Test
	public void testValidateWithCustomTokenizerConfig() throws IOException {
		setupCreateSuccess();
		setupJsonpMapper();

		TextAnalyzerSettings settings = new TextAnalyzerSettings();
		settings.setTokenizerConfig("{\"type\":\"edge_ngram\",\"min_gram\":2,\"max_gram\":20,\"token_chars\":[\"letter\",\"digit\"]}");
		settings.setFilterOrder(Arrays.asList("lowercase"));

		// call under test
		assertDoesNotThrow(() -> manager.validateAnalyzerSettings(settings));

		verify(indicesClient, times(1)).create(any(CreateIndexRequest.class));
		verify(indicesClient, times(1)).delete(any(DeleteIndexRequest.class));
	}

	@Test
	public void testValidateWithCharFilters() throws IOException {
		setupCreateSuccess();
		setupJsonpMapper();

		TextAnalyzerSettings settings = new TextAnalyzerSettings();
		settings.setTokenizer("standard");
		settings.setCharFilters("{\"my_mapping\":{\"type\":\"mapping\",\"mappings\":[\"& => and\"]}}");
		settings.setCharFilterOrder(Arrays.asList("my_mapping"));

		// call under test
		assertDoesNotThrow(() -> manager.validateAnalyzerSettings(settings));

		verify(indicesClient, times(1)).create(any(CreateIndexRequest.class));
		verify(indicesClient, times(1)).delete(any(DeleteIndexRequest.class));
	}

	@Test
	public void testValidateWithMinimalSettings() throws IOException {
		setupCreateSuccess();

		TextAnalyzerSettings settings = new TextAnalyzerSettings();

		// call under test
		assertDoesNotThrow(() -> manager.validateAnalyzerSettings(settings));

		verify(indicesClient, times(1)).create(any(CreateIndexRequest.class));
		verify(indicesClient, times(1)).delete(any(DeleteIndexRequest.class));
	}

	@Test
	public void testValidateThrowsIllegalArgumentOnOpenSearchException() throws IOException {
		when(openSearchClient.indices()).thenReturn(indicesClient);
		ErrorResponse errorResponse = ErrorResponse.of(e -> e
			.error(err -> err.type("illegal_argument_exception").reason("Unknown tokenizer type [foobar]"))
			.status(400));
		when(indicesClient.create(any(CreateIndexRequest.class)))
			.thenThrow(new OpenSearchException(errorResponse));
		when(indicesClient.delete(any(DeleteIndexRequest.class))).thenReturn(deleteResponse);

		TextAnalyzerSettings settings = new TextAnalyzerSettings();
		settings.setTokenizer("foobar");

		// call under test
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
			() -> manager.validateAnalyzerSettings(settings));
		assertTrue(ex.getMessage().contains("Unknown tokenizer type [foobar]"));
		assertTrue(ex.getMessage().contains("Invalid analyzer configuration"));

		verify(indicesClient, times(1)).create(any(CreateIndexRequest.class));
		verify(indicesClient, times(1)).delete(any(DeleteIndexRequest.class));
	}

	@Test
	public void testValidateThrowsIllegalStateOnIOException() throws IOException {
		when(openSearchClient.indices()).thenReturn(indicesClient);
		when(indicesClient.create(any(CreateIndexRequest.class)))
			.thenThrow(new IOException("Connection refused"));
		when(indicesClient.delete(any(DeleteIndexRequest.class))).thenReturn(deleteResponse);

		TextAnalyzerSettings settings = new TextAnalyzerSettings();
		settings.setTokenizer("standard");

		// call under test
		IllegalStateException ex = assertThrows(IllegalStateException.class,
			() -> manager.validateAnalyzerSettings(settings));
		assertTrue(ex.getMessage().contains("temporarily unavailable"));

		verify(indicesClient, times(1)).delete(any(DeleteIndexRequest.class));
	}

	@Test
	public void testValidateSwallowsCleanupException() throws IOException {
		when(openSearchClient.indices()).thenReturn(indicesClient);
		when(indicesClient.create(any(CreateIndexRequest.class))).thenReturn(createResponse);
		ErrorResponse errorResponse = ErrorResponse.of(e -> e
			.error(err -> err.type("authorization_exception").reason("not authorized"))
			.status(403));
		when(indicesClient.delete(any(DeleteIndexRequest.class)))
			.thenThrow(new OpenSearchException(errorResponse));

		TextAnalyzerSettings settings = new TextAnalyzerSettings();
		settings.setTokenizer("standard");

		// call under test
		assertDoesNotThrow(() -> manager.validateAnalyzerSettings(settings));

		verify(indicesClient, times(1)).delete(any(DeleteIndexRequest.class));
	}

	@Test
	public void testValidateThrowsOnMalformedTokenFiltersJson() throws IOException {
		setupCleanupOnly();
		setupJsonpMapper();

		TextAnalyzerSettings settings = new TextAnalyzerSettings();
		settings.setTokenizer("standard");
		settings.setTokenFilters("not valid json");
		settings.setFilterOrder(Arrays.asList("my_filter"));

		// call under test
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
			() -> manager.validateAnalyzerSettings(settings));
		assertTrue(ex.getMessage().contains("Invalid analyzer configuration"));
	}

	@Test
	public void testValidateThrowsOnMalformedCharFiltersJson() throws IOException {
		setupCleanupOnly();
		setupJsonpMapper();

		TextAnalyzerSettings settings = new TextAnalyzerSettings();
		settings.setTokenizer("standard");
		settings.setCharFilters("not valid json");
		settings.setCharFilterOrder(Arrays.asList("my_filter"));

		// call under test
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
			() -> manager.validateAnalyzerSettings(settings));
		assertTrue(ex.getMessage().contains("Invalid analyzer configuration"));
	}

	@Test
	public void testValidateThrowsOnMalformedTokenizerConfig() throws IOException {
		setupCleanupOnly();
		setupJsonpMapper();

		TextAnalyzerSettings settings = new TextAnalyzerSettings();
		settings.setTokenizerConfig("not valid json");

		// call under test
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
			() -> manager.validateAnalyzerSettings(settings));
		assertTrue(ex.getMessage().contains("Invalid analyzer configuration"));
	}

	@Test
	public void testValidateThrowsOnNullSettings() {
		// call under test
		assertThrows(IllegalArgumentException.class,
			() -> manager.validateAnalyzerSettings(null));
	}

	@Test
	public void testValidateWithSynonymAwareSettings() throws IOException {
		setupCreateSuccess();

		TextAnalyzerSettings settings = new TextAnalyzerSettings();
		settings.setTokenizer("standard");
		settings.setSynonymAware(true);

		// call under test
		assertDoesNotThrow(() -> manager.validateAnalyzerSettings(settings));

		ArgumentCaptor<CreateIndexRequest> captor = ArgumentCaptor.forClass(CreateIndexRequest.class);
		verify(indicesClient, times(1)).create(captor.capture());
		CreateIndexRequest captured = captor.getValue();
		assertNotNull(captured.settings(), "settings required");
		assertNotNull(captured.settings().analysis(), "analysis required");
		assertTrue(captured.settings().analysis().filter().containsKey("synapse_synonyms"),
				"synapse_synonyms filter must be registered when synonymAware=true");
		verify(indicesClient, times(1)).delete(any(DeleteIndexRequest.class));
	}

	@Test
	public void testValidateDoesNotRetryOnIndexNotFound() throws IOException {
		when(openSearchClient.indices()).thenReturn(indicesClient);
		when(indicesClient.create(any(CreateIndexRequest.class))).thenReturn(createResponse);
		ErrorResponse notFound = ErrorResponse.of(e -> e
			.error(err -> err.type("index_not_found_exception").reason("no such index"))
			.status(404));
		when(indicesClient.delete(any(DeleteIndexRequest.class)))
			.thenThrow(new OpenSearchException(notFound));

		TextAnalyzerSettings settings = new TextAnalyzerSettings();
		settings.setTokenizer("standard");

		// call under test
		assertDoesNotThrow(() -> manager.validateAnalyzerSettings(settings));

		verify(indicesClient, times(1)).delete(any(DeleteIndexRequest.class));
	}

	@Test
	public void testValidateRetriesOnConcurrentDeleteError() throws IOException {
		when(openSearchClient.indices()).thenReturn(indicesClient);
		when(indicesClient.create(any(CreateIndexRequest.class))).thenReturn(createResponse);
		ErrorResponse concurrentDeletes = ErrorResponse.of(e -> e
			.error(err -> err.type("resource_already_exists_exception")
					.reason("OpenSearchStatusException[concurrent deletes]"))
			.status(409));
		when(indicesClient.delete(any(DeleteIndexRequest.class)))
			.thenThrow(new OpenSearchException(concurrentDeletes))
			.thenReturn(deleteResponse);

		TextAnalyzerSettings settings = new TextAnalyzerSettings();
		settings.setTokenizer("standard");

		// call under test
		assertDoesNotThrow(() -> manager.validateAnalyzerSettings(settings));

		verify(indicesClient, atLeast(2)).delete(any(DeleteIndexRequest.class));
	}

	@Test
	public void testValidateDoesNotRetryOnFatalDeleteError() throws IOException {
		when(openSearchClient.indices()).thenReturn(indicesClient);
		when(indicesClient.create(any(CreateIndexRequest.class))).thenReturn(createResponse);
		ErrorResponse authError = ErrorResponse.of(e -> e
			.error(err -> err.type("authorization_exception").reason("not authorized"))
			.status(403));
		when(indicesClient.delete(any(DeleteIndexRequest.class)))
			.thenThrow(new OpenSearchException(authError));

		TextAnalyzerSettings settings = new TextAnalyzerSettings();
		settings.setTokenizer("standard");

		// call under test
		assertDoesNotThrow(() -> manager.validateAnalyzerSettings(settings));

		verify(indicesClient, times(1)).delete(any(DeleteIndexRequest.class));
	}
}
