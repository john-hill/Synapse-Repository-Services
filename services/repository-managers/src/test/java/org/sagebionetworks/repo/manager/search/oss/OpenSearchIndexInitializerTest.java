package org.sagebionetworks.repo.manager.search.oss;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.ErrorCause;
import org.opensearch.client.opensearch._types.ErrorResponse;
import org.opensearch.client.opensearch._types.OpenSearchException;
import org.opensearch.client.opensearch._types.mapping.TypeMapping;
import org.opensearch.client.opensearch.indices.CreateIndexRequest;
import org.opensearch.client.opensearch.indices.CreateIndexResponse;
import org.opensearch.client.opensearch.indices.ExistsRequest;
import org.opensearch.client.opensearch.indices.OpenSearchIndicesClient;
import org.opensearch.client.transport.endpoints.BooleanResponse;
import org.sagebionetworks.LoggerProvider;
import org.sagebionetworks.search.SearchConstants;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;


@ExtendWith(MockitoExtension.class)
public class OpenSearchIndexInitializerTest {
    ArgumentCaptor<ExistsRequest> captorIndexExists = ArgumentCaptor.forClass(ExistsRequest.class);
    ArgumentCaptor<CreateIndexRequest> captorIndexCreation = ArgumentCaptor.forClass(CreateIndexRequest.class);
    @Mock
    private OpenSearchClient mockOpenSearchClient;
    @Mock
    private OpenSearchIndicesClient mockIndicesClient;
    @Mock
    Logger mockLog;
    @Mock
    LoggerProvider mockLoggerProvider;

    private OpenSearchIndexInitializer initializer;

    @BeforeEach
    public void before() {
        when(mockLoggerProvider.getLogger(anyString())).thenReturn(mockLog);
        initializer = new OpenSearchIndexInitializer(mockLoggerProvider, mockOpenSearchClient);
    }

    @Test
    public void testIndexCreation() throws IOException {
        when(mockOpenSearchClient.indices()).thenReturn(mockIndicesClient);
        when(mockIndicesClient.exists((ExistsRequest) ArgumentMatchers.any())).thenReturn(new BooleanResponse(false));
        when(mockIndicesClient.create((CreateIndexRequest) ArgumentMatchers.any())).thenReturn(
                new CreateIndexResponse.Builder().index(SearchConstants.OPEN_SEARCH_INDEX_NAME)
                        .acknowledged(true).shardsAcknowledged(true).build());

        //call under test
        initializer.init();

        verify(mockIndicesClient).exists(captorIndexExists.capture());
        verify(mockLog).info("Index {} creation completed.", SearchConstants.OPEN_SEARCH_INDEX_NAME);
        ExistsRequest captured = captorIndexExists.getValue();
        assertEquals(SearchConstants.OPEN_SEARCH_INDEX_NAME, captured.index().get(0));
        verify(mockIndicesClient).create(captorIndexCreation.capture());
        CreateIndexRequest capturedCreation = captorIndexCreation.getValue();
        assertEquals(SearchConstants.OPEN_SEARCH_INDEX_NAME, capturedCreation.index());
    }

    @Test
    public void testIndexMapping() throws IOException {
        when(mockOpenSearchClient.indices()).thenReturn(mockIndicesClient);
        when(mockIndicesClient.exists((ExistsRequest) ArgumentMatchers.any())).thenReturn(new BooleanResponse(false));
        when(mockIndicesClient.create((CreateIndexRequest) ArgumentMatchers.any())).thenReturn(
                new CreateIndexResponse.Builder().index(SearchConstants.OPEN_SEARCH_INDEX_NAME)
                        .acknowledged(true).shardsAcknowledged(true).build());

        TypeMapping expectedMapping = TypeMapping.of(t -> t.properties(SearchConstants.FIELD_NAME, p -> p.text(text ->
                        text.analyzer("english")))
                .properties(SearchConstants.FIELD_DESCRIPTION,
                        p -> p.text(text ->
                                text.analyzer("english")))
                .properties(SearchConstants.FIELD_CREATED_ON,
                        p -> p.integer(i -> i))
                .properties(SearchConstants.FIELD_MODIFIED_ON,
                        p -> p.integer(i -> i))
                .properties(SearchConstants.FIELD_NODE_TYPE,
                        p -> p.keyword(k -> k))
                .properties(SearchConstants.FIELD_ETAG,
                        p -> p.keyword(k -> k))
                .properties(SearchConstants.FIELD_PARENT_ID,
                        p -> p.keyword(k -> k))
                .properties(SearchConstants.FIELD_CREATED_BY,
                        p -> p.keyword(k -> k))
                .properties(SearchConstants.FIELD_MODIFIED_BY,
                        p -> p.keyword(k -> k))
                .properties(SearchConstants.FIELD_ACL,
                        p -> p.keyword(k -> k))
                .properties(SearchConstants.FIELD_UPDATE_ACL,
                        p -> p.keyword(k -> k))
                .properties(SearchConstants.FIELD_DIAGNOSIS,
                        p -> p.keyword(k -> k))
                .properties(SearchConstants.FIELD_TISSUE,
                        p -> p.keyword(k -> k))
                .properties(SearchConstants.FIELD_CONSORTIUM,
                        p -> p.keyword(k -> k))
                .properties(SearchConstants.FIELD_ORGAN,
                        p -> p.keyword(k -> k)));

        //call under test
        initializer.init();
        verify(mockIndicesClient).create(captorIndexCreation.capture());
        CreateIndexRequest capturedCreation = captorIndexCreation.getValue();

        ObjectMapper mapper = new ObjectMapper();
        String expectedJson = mapper.writeValueAsString(expectedMapping.properties());
        String actualJson = mapper.writeValueAsString(capturedCreation.mappings().properties());

        assertEquals(expectedJson, actualJson);
    }

    @Test
    public void testNullAcknowledgeIndexCreation() throws IOException {
        when(mockOpenSearchClient.indices()).thenReturn(mockIndicesClient);
        when(mockIndicesClient.exists((ExistsRequest) ArgumentMatchers.any())).thenReturn(new BooleanResponse(false));
        when(mockIndicesClient.create((CreateIndexRequest) ArgumentMatchers.any())).thenReturn(
                new CreateIndexResponse.Builder().index(SearchConstants.OPEN_SEARCH_INDEX_NAME)
                        .acknowledged(null).shardsAcknowledged(true).build());

        //call under test
        initializer.init();

        verify(mockIndicesClient).exists(captorIndexExists.capture());
        ExistsRequest captured = captorIndexExists.getValue();
        assertEquals(SearchConstants.OPEN_SEARCH_INDEX_NAME, captured.index().get(0));
        verify(mockIndicesClient).create(captorIndexCreation.capture());
        CreateIndexRequest capturedCreation = captorIndexCreation.getValue();
        assertEquals(SearchConstants.OPEN_SEARCH_INDEX_NAME, capturedCreation.index());
        verify(mockLog).error("Index {} creation was not acknowledged.", SearchConstants.OPEN_SEARCH_INDEX_NAME);
    }

    @Test
    public void testCreateIndexThrowOpenSearchException() throws IOException {
        when(mockOpenSearchClient.indices()).thenReturn(mockIndicesClient);
        when(mockIndicesClient.exists((ExistsRequest) ArgumentMatchers.any())).thenReturn(new BooleanResponse(false));
        OpenSearchException exception = new OpenSearchException(
                ErrorResponse.of(er -> er.error(ErrorCause.of(er1 -> er1.reason("reason").type("type")))));
        when(mockIndicesClient.create((CreateIndexRequest) ArgumentMatchers.any())).thenThrow(exception);

        //call under test
        initializer.init();

        verify(mockIndicesClient).exists(captorIndexExists.capture());
        ExistsRequest captured = captorIndexExists.getValue();
        assertEquals(SearchConstants.OPEN_SEARCH_INDEX_NAME, captured.index().get(0));
        verify(mockIndicesClient).create(captorIndexCreation.capture());
        CreateIndexRequest capturedCreation = captorIndexCreation.getValue();
        assertEquals(SearchConstants.OPEN_SEARCH_INDEX_NAME, capturedCreation.index());
        verify(mockLog).error("Index {} creation failed {}.", SearchConstants.OPEN_SEARCH_INDEX_NAME, exception.getMessage());
    }
    @Test
    public void testCreateIndexThrowResourceAlreadyExistsException() throws IOException {
        when(mockOpenSearchClient.indices()).thenReturn(mockIndicesClient);
        when(mockIndicesClient.exists((ExistsRequest) ArgumentMatchers.any())).thenReturn(new BooleanResponse(false));
        when(mockIndicesClient.create((CreateIndexRequest) ArgumentMatchers.any())).thenThrow(new OpenSearchException(
                ErrorResponse.of(er -> er.error(ErrorCause.of(er1 -> er1.reason("because index already exists")
                        .type("resource_already_exists_exception"))))));

        //call under test
        initializer.init();

        verify(mockIndicesClient).exists(captorIndexExists.capture());
        ExistsRequest captured = captorIndexExists.getValue();
        assertEquals(SearchConstants.OPEN_SEARCH_INDEX_NAME, captured.index().get(0));
        verify(mockIndicesClient).create(captorIndexCreation.capture());
        CreateIndexRequest capturedCreation = captorIndexCreation.getValue();
        assertEquals(SearchConstants.OPEN_SEARCH_INDEX_NAME, capturedCreation.index());
        verify(mockLog).error("Index {} already exists.",
                SearchConstants.OPEN_SEARCH_INDEX_NAME);
    }

    @Test
    public void testCreateIndexThrowIOException() throws IOException {
        IOException exception = new IOException("IOException");
        when(mockOpenSearchClient.indices()).thenReturn(mockIndicesClient);
        when(mockIndicesClient.exists((ExistsRequest) ArgumentMatchers.any())).thenReturn(new BooleanResponse(false));
        when(mockIndicesClient.create((CreateIndexRequest) ArgumentMatchers.any())).thenThrow(exception);

        //call under test
        initializer.init();

        verify(mockIndicesClient).exists(captorIndexExists.capture());
        ExistsRequest captured = captorIndexExists.getValue();
        assertEquals(SearchConstants.OPEN_SEARCH_INDEX_NAME, captured.index().get(0));
        verify(mockIndicesClient).create(captorIndexCreation.capture());
        CreateIndexRequest capturedCreation = captorIndexCreation.getValue();
        assertEquals(SearchConstants.OPEN_SEARCH_INDEX_NAME, capturedCreation.index());
        verify(mockLog).error("Index {} creation failed {}.",
                SearchConstants.OPEN_SEARCH_INDEX_NAME, exception.getMessage());
    }

    @Test
    public void testIndexAlreadyExists() throws IOException {
        when(mockOpenSearchClient.indices()).thenReturn(mockIndicesClient);
        when(mockIndicesClient.exists((ExistsRequest) ArgumentMatchers.any())).thenReturn(new BooleanResponse(true));

        //call under test
        initializer.init();

        verify(mockIndicesClient).exists(captorIndexExists.capture());
        ExistsRequest captured = captorIndexExists.getValue();
        assertEquals(SearchConstants.OPEN_SEARCH_INDEX_NAME, captured.index().get(0));
        verify(mockIndicesClient, Mockito.never()).create((CreateIndexRequest) ArgumentMatchers.any());
    }
}




