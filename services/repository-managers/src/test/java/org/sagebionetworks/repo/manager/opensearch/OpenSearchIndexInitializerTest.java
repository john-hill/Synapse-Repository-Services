package org.sagebionetworks.repo.manager.opensearch;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch.indices.CreateIndexRequest;
import org.opensearch.client.opensearch.indices.CreateIndexResponse;
import org.opensearch.client.opensearch.indices.ExistsRequest;
import org.opensearch.client.opensearch.indices.OpenSearchIndicesClient;
import org.opensearch.client.transport.endpoints.BooleanResponse;
import org.sagebionetworks.search.SearchConstants;

import java.io.IOException;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;


@ExtendWith(MockitoExtension.class)
public class OpenSearchIndexInitializerTest {
    ArgumentCaptor<ExistsRequest> captorIndexExists = ArgumentCaptor.forClass(ExistsRequest.class);
    ArgumentCaptor<CreateIndexRequest> captorIndexCreation = ArgumentCaptor.forClass(CreateIndexRequest.class);
    @Mock
    private OpenSearchClient mockClient;
    @Mock
    private OpenSearchIndicesClient mockIndicesClient;
    @InjectMocks
    private OpenSearchIndexInitializer initializer;

    @Test
    public void testIndexCreation() throws IOException {
        when(mockClient.indices()).thenReturn(mockIndicesClient);
        when(mockIndicesClient.exists((ExistsRequest) ArgumentMatchers.any())).thenReturn(new BooleanResponse(false));
        when(mockIndicesClient.create((CreateIndexRequest) ArgumentMatchers.any())).thenReturn(
                new CreateIndexResponse.Builder().index(SearchConstants.OPEN_SEARCH_INDEX_NAME)
                        .acknowledged(true).shardsAcknowledged(true).build());

        //call under test
        initializer.init();

        verify(mockIndicesClient, Mockito.times(1)).exists(captorIndexExists.capture());
        ExistsRequest captured = captorIndexExists.getValue();
        assertEquals(SearchConstants.OPEN_SEARCH_INDEX_NAME, captured.index().get(0));
        verify(mockIndicesClient, Mockito.times(1)).create(captorIndexCreation.capture());
        CreateIndexRequest capturedCreation = captorIndexCreation.getValue();
        assertEquals(SearchConstants.OPEN_SEARCH_INDEX_NAME, capturedCreation.index());
    }

    @Test
    public void testIndexAlreadyExists() throws IOException {
        when(mockClient.indices()).thenReturn(mockIndicesClient);
        when(mockIndicesClient.exists((ExistsRequest) ArgumentMatchers.any())).thenReturn(new BooleanResponse(true));

        //call under test
        initializer.init();

        verify(mockIndicesClient).exists(captorIndexExists.capture());
        ExistsRequest captured = captorIndexExists.getValue();
        assertEquals(SearchConstants.OPEN_SEARCH_INDEX_NAME, captured.index().get(0));
        verify(mockIndicesClient, Mockito.never()).create((CreateIndexRequest) ArgumentMatchers.any());
    }

    @Test
    public void testConcurrentIndexCreationOnlyOneCreates() throws Exception {
        when(mockClient.indices()).thenReturn(mockIndicesClient);
        when(mockIndicesClient.exists((ExistsRequest) ArgumentMatchers.any()))
                .thenReturn(new BooleanResponse(false))  // first thread
                .thenReturn(new BooleanResponse(true)); // second thread sees it exists

        when(mockIndicesClient.create((CreateIndexRequest) ArgumentMatchers.any())).thenReturn(
                new CreateIndexResponse.Builder().index(SearchConstants.OPEN_SEARCH_INDEX_NAME)
                        .acknowledged(true).shardsAcknowledged(true).build());

        ExecutorService executor = Executors.newFixedThreadPool(2);

        Callable<Void> task = () -> {
            //call under test
            initializer.init();
            return null;
        };

        Future<Void> future1 = executor.submit(task);
        Future<Void> future2 = executor.submit(task);

        future1.get();
        future2.get();

        verify(mockIndicesClient, Mockito.times(2)).exists(captorIndexExists.capture());
        verify(mockIndicesClient, Mockito.times(1)).create(captorIndexCreation.capture());

        executor.shutdown();
    }

    @Test
    public void testConcurrentIndexCreationBothTryToCreateIndex() throws Exception {
        when(mockClient.indices()).thenReturn(mockIndicesClient);
        when(mockIndicesClient.exists((ExistsRequest) ArgumentMatchers.any()))
                .thenReturn(new BooleanResponse(false))
                .thenReturn(new BooleanResponse(false));

        when(mockIndicesClient.create((CreateIndexRequest) ArgumentMatchers.any())).thenReturn(
                new CreateIndexResponse.Builder().index(SearchConstants.OPEN_SEARCH_INDEX_NAME)
                        .acknowledged(true).shardsAcknowledged(true).build()).thenThrow(new IOException("failure"));

        ExecutorService executor = Executors.newFixedThreadPool(2);

        Callable<Void> task = () -> {
            //call under test
            initializer.init();
            return null;
        };

        Future<Void> future1 = executor.submit(task);
        Future<Void> future2 = executor.submit(task);

        future1.get();
        future2.get();

        verify(mockIndicesClient, Mockito.times(2)).exists(captorIndexExists.capture());
        verify(mockIndicesClient, Mockito.times(2)).create(captorIndexCreation.capture());

        executor.shutdown();
    }
}




