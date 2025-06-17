package org.sagebionetworks.repo.manager.search.oss;


import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.ErrorCause;
import org.opensearch.client.opensearch._types.ErrorResponse;
import org.opensearch.client.opensearch._types.OpenSearchException;
import org.opensearch.client.opensearch._types.Result;
import org.opensearch.client.opensearch._types.ShardStatistics;
import org.opensearch.client.opensearch.core.BulkRequest;
import org.opensearch.client.opensearch.core.BulkResponse;
import org.opensearch.client.opensearch.core.SearchRequest;
import org.opensearch.client.opensearch.core.SearchResponse;
import org.opensearch.client.opensearch.core.bulk.BulkResponseItem;
import org.opensearch.client.opensearch.core.bulk.DeleteOperation;
import org.opensearch.client.opensearch.core.bulk.OperationType;
import org.opensearch.client.opensearch.core.search.Hit;
import org.sagebionetworks.LoggerProvider;
import org.sagebionetworks.repo.model.message.ChangeMessage;
import org.sagebionetworks.repo.model.search.Document;
import org.sagebionetworks.repo.model.search.DocumentFields;
import org.sagebionetworks.repo.model.search.DocumentTypeNames;
import org.sagebionetworks.search.SearchConstants;
import org.sagebionetworks.workers.util.aws.message.RecoverableMessageException;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class SearchManagerImplTest {
    @Captor
    ArgumentCaptor<BulkRequest> bulkRequestArgumentCaptor = ArgumentCaptor.forClass(BulkRequest.class);
    @Captor
    ArgumentCaptor<SearchRequest> searchRequestArgumentCaptor;
    @Mock
    ChangeMessageToOpenSearchDocumentTranslator mockTranslator;
    @Mock
    Logger mockLog;
    @Mock
    LoggerProvider mockLoggerProvider;
    @Mock
    OpenSearchIndexInitializer mockOpenSearchIndexInitializer;
    Document document;
    @Mock
    private OpenSearchClient mockSearchClient;
    private SearchManagerImpl mockSearchManager;

    @BeforeEach
    public void before() {
        when(mockLoggerProvider.getLogger(anyString())).thenReturn(mockLog);
        document = new Document();
        document.setType(DocumentTypeNames.add);
        document.setId("syn1");
        document.setFields(new DocumentFields().setName("test").setEtag("etag"));
        mockSearchManager = new SearchManagerImpl(mockLoggerProvider, mockTranslator, mockOpenSearchIndexInitializer, mockSearchClient);
    }


    @Test
    public void testADDDocumentChangeMessages() throws IOException {
        when(mockTranslator.generateSearchDocumentIfNecessary(any(ChangeMessage.class))).thenReturn(document);
        BulkResponseItem item = new BulkResponseItem.Builder()
                .index(SearchConstants.OPEN_SEARCH_INDEX_NAME)
                .id(document.getId())
                .status(201)
                .result(String.valueOf(Result.Created))
                .operationType(OperationType.Create)
                .build();
        when(mockSearchClient.bulk(any(BulkRequest.class))).thenReturn(new BulkResponse.Builder()
                .items(List.of(item)).errors(false).took(1L).build());

        //call under test
        mockSearchManager.documentChangeMessages(List.of(new ChangeMessage()));
        verify(mockSearchClient).bulk(bulkRequestArgumentCaptor.capture());
        BulkRequest request = bulkRequestArgumentCaptor.getValue();
        assertEquals(1, request.operations().size());
        assertEquals(SearchConstants.OPEN_SEARCH_INDEX_NAME, request.operations().get(0).index().index());
        assertEquals(request.operations().get(0).index().document(), document.getFields());
    }

    @Test
    public void testDeleteDocumentChangeMessages() throws IOException {
        document.setType(DocumentTypeNames.delete);

        when(mockTranslator.generateSearchDocumentIfNecessary(any(ChangeMessage.class)))
                .thenReturn(document);
        BulkResponseItem item = new BulkResponseItem.Builder()
                .index(SearchConstants.OPEN_SEARCH_INDEX_NAME)
                .id(document.getId())
                .status(404)
                .operationType(OperationType.Delete)
                .build();

        when(mockSearchClient.bulk(any(BulkRequest.class))).thenReturn(new BulkResponse.Builder()
                .items(List.of(item)).errors(false).took(1L).build());

        //call under test
        mockSearchManager.documentChangeMessages(List.of(new ChangeMessage()));
        verify(mockSearchClient).bulk(bulkRequestArgumentCaptor.capture());
        BulkRequest request = bulkRequestArgumentCaptor.getValue();
        assertEquals(1, request.operations().size());
        assertEquals(DeleteOperation.class, request.operations().get(0).delete().getClass());
        assertEquals(SearchConstants.OPEN_SEARCH_INDEX_NAME, request.operations().get(0).delete().index());
    }


    @Test
    public void testDocumentChangeMessagesErrorInResponse() throws IOException {
        document.setType(DocumentTypeNames.delete);

        when(mockTranslator.generateSearchDocumentIfNecessary(any(ChangeMessage.class)))
                .thenReturn(document);
        ErrorCause errorCause = ErrorCause.of(er -> er.reason("reason").type("type"));
        BulkResponseItem itemAdd = new BulkResponseItem.Builder()
                .index(SearchConstants.OPEN_SEARCH_INDEX_NAME)
                .id(document.getId())
                .status(201)
                .operationType(OperationType.Index)
                .build();
        BulkResponseItem itemDel = new BulkResponseItem.Builder()
                .index(SearchConstants.OPEN_SEARCH_INDEX_NAME)
                .id(document.getId())
                .status(403)
                .error(errorCause)
                .operationType(OperationType.Delete)
                .build();

        when(mockSearchClient.bulk(any(BulkRequest.class))).thenReturn(new BulkResponse.Builder()
                .items(List.of(itemAdd, itemDel)).errors(false).took(1L).build());

        assertThrows(RecoverableMessageException.class, () -> {
            //call under test
            mockSearchManager.documentChangeMessages(List.of(new ChangeMessage(), new ChangeMessage()));
            verify(mockLog).error("Document {} has error {} with reason {}.",
                    document.getId(), errorCause.type(), errorCause.type());
        });
    }

    @Test
    public void testDocumentChangeMessagesErrorWithOpenSearchException() throws IOException {
        document.setType(DocumentTypeNames.delete);

        when(mockTranslator.generateSearchDocumentIfNecessary(any(ChangeMessage.class)))
                .thenReturn(document);

        when(mockSearchClient.bulk(any(BulkRequest.class))).thenThrow(new OpenSearchException(
                ErrorResponse.of(er -> er.error(ErrorCause.of(er1 -> er1.reason("reason").type("type"))))));

        assertThrows(RecoverableMessageException.class, () -> {
            //call under test
            mockSearchManager.documentChangeMessages(List.of(new ChangeMessage(), new ChangeMessage()));
            verify(mockLog).error("OpenSearch error type with reason reason.");
        });
    }

    @Test
    public void testDocumentChangeMessagesErrorWithIOException() throws IOException {
        document.setType(DocumentTypeNames.delete);

        when(mockTranslator.generateSearchDocumentIfNecessary(any(ChangeMessage.class)))
                .thenReturn(document);

        when(mockSearchClient.bulk(any(BulkRequest.class))).thenThrow(new IOException("exception"));

        assertThrows(RecoverableMessageException.class, () -> {
            //call under test
            mockSearchManager.documentChangeMessages(List.of(new ChangeMessage(), new ChangeMessage()));
            verify(mockLog).error("IOException exception.");
        });
    }

    @Test
    public void testDoesDocumentExist() throws IOException {
        when(mockSearchClient.search(any(SearchRequest.class), eq(DocumentFields.class))).thenReturn(SearchResponse.searchResponseOf(sr -> sr
                .hits(h -> h.hits(List.of(Hit.of(hit -> hit.id(document.getId()).source(document.getFields())
                        .index(SearchConstants.OPEN_SEARCH_INDEX_NAME))))).took(1).timedOut(false)
                .shards(ShardStatistics.of(sh -> sh.successful(1).failed(0).total(1)))));

        //call under test
        mockSearchManager.doesDocumentExist(document.getId(), document.getFields().getEtag());
        verify(mockSearchClient).search(searchRequestArgumentCaptor.capture(), eq(DocumentFields.class));

        SearchRequest capturedRequest = searchRequestArgumentCaptor.getValue();
        assertEquals(SearchConstants.OPEN_SEARCH_INDEX_NAME, capturedRequest.index().get(0));
    }

    @Test
    public void testDoesDocumentExistWithNoDocument() throws IOException {
        when(mockSearchClient.search(any(SearchRequest.class), eq(DocumentFields.class))).thenReturn(SearchResponse.searchResponseOf(sr -> sr
                .hits(h -> h.hits(List.of(Hit.of(hit -> hit.index(SearchConstants.OPEN_SEARCH_INDEX_NAME)
                        .id(null).source(null))))).took(1).timedOut(false)
                .shards(ShardStatistics.of(sh -> sh.successful(1).failed(0).total(1)))));

        //call under test
        mockSearchManager.doesDocumentExist(document.getId(), document.getFields().getEtag());
        verify(mockSearchClient).search(searchRequestArgumentCaptor.capture(), eq(DocumentFields.class));

        SearchRequest capturedRequest = searchRequestArgumentCaptor.getValue();
        assertEquals(SearchConstants.OPEN_SEARCH_INDEX_NAME, capturedRequest.index().get(0));
    }

    @Test
    public void testDoesDocumentExistWithOpenSearchException() throws IOException {
        when(mockSearchClient.search(any(SearchRequest.class), eq(DocumentFields.class))).thenThrow(new OpenSearchException(
                ErrorResponse.of(er -> er.error(ErrorCause.of(ec -> ec.reason("reason").type("type"))))));

        assertThrows(OpenSearchException.class, () -> {
            //call under test
            mockSearchManager.doesDocumentExist(document.getId(), document.getFields().getEtag());
            verify(mockLog).error("Error Request failed: [type] reason occurred while searching document.");

        });
    }

    @Test
    public void testDoesDocumentExistWithIOException() throws IOException {
        when(mockSearchClient.search(any(SearchRequest.class), eq(DocumentFields.class))).thenThrow(new IOException("IO"));

        assertThrows(IOException.class, () -> {
            //call under test
            mockSearchManager.doesDocumentExist(document.getId(), document.getFields().getEtag());
            verify(mockLog).error("Error IO occurred while searching document.");
        });
    }
}
