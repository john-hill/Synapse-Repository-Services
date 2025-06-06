package org.sagebionetworks.repo.manager.opensearch;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.opensearch.client.opensearch._types.ErrorCause;
import org.opensearch.client.opensearch._types.Result;
import org.opensearch.client.opensearch.core.BulkRequest;
import org.opensearch.client.opensearch.core.BulkResponse;
import org.opensearch.client.opensearch.core.bulk.BulkResponseItem;
import org.opensearch.client.opensearch.core.bulk.OperationType;
import org.sagebionetworks.repo.manager.search.ChangeMessageToSearchDocumentTranslator;
import org.sagebionetworks.repo.model.message.ChangeMessage;
import org.sagebionetworks.repo.model.search.Document;
import org.sagebionetworks.search.SearchConstants;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;

import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class SearchManagerTest {

    @Mock
    ChangeMessageToSearchDocumentTranslator mockTranslator;
    @Captor
    ArgumentCaptor<BulkRequest> bulkRequestArgumentCaptor = ArgumentCaptor.forClass(BulkRequest.class);
    Document doc1;
    @InjectMocks
    private SearchManagerImpl mockSearchManager;
    @Mock
    private SearchDao mockSearchDao;

    @BeforeEach
    public void before() {
        doc1 = new Document();
        doc1.setId("syn1");
    }


    @Test
    public void testDocumentChangeMessages() throws IOException {
        when(mockTranslator.generateSearchDocumentIfNecessary(any(ChangeMessage.class))).thenReturn(doc1);
        BulkResponseItem item = new BulkResponseItem.Builder()
                .index(SearchConstants.OPEN_SEARCH_INDEX_NAME)
                .id(doc1.getId())
                .status(201)
                .result(String.valueOf(Result.Created))
                .operationType(OperationType.Create)
                .build();
        when(mockSearchDao.sendDocuments(any())).thenReturn(new BulkResponse.Builder()
                .items(List.of(item)).errors(false).took(1L).build());

        //call under test
        mockSearchManager.documentChangeMessages(List.of(new ChangeMessage()));
        verify(mockSearchDao, times(1)).sendDocuments(bulkRequestArgumentCaptor.capture());
        BulkRequest request = bulkRequestArgumentCaptor.getValue();
        assertEquals(1, request.operations().size());
        assertEquals(SearchConstants.OPEN_SEARCH_INDEX_NAME, request.operations().get(0).index().index());
        assertEquals(request.operations().get(0).index().document(), doc1);
    }

    @Test
    public void testDocumentChangeMessagesWithoutDocument() throws IOException {
        when(mockTranslator.generateSearchDocumentIfNecessary(any(ChangeMessage.class))).thenReturn(null);
        BulkResponseItem item = new BulkResponseItem.Builder()
                .index(SearchConstants.OPEN_SEARCH_INDEX_NAME)
                .id(doc1.getId())
                .status(201)
                .result(String.valueOf(Result.Created))
                .operationType(OperationType.Create)
                .error(new ErrorCause.Builder()
                        .type("mapper_parsing_exception")
                        .reason("failed to parse field [name] of type [text]")
                        .build())
                .build();
        when(mockSearchDao.sendDocuments(any())).thenReturn(new BulkResponse.Builder()
                .items(List.of(item)).errors(true).took(1L).build());

        //call under test
        mockSearchManager.documentChangeMessages(List.of(new ChangeMessage()));
        verify(mockSearchDao, times(1)).sendDocuments(bulkRequestArgumentCaptor.capture());
        BulkRequest request = bulkRequestArgumentCaptor.getValue();
        assertEquals(0, request.operations().size());

    }

    @Test
    public void testDoesDocumentExist() throws IOException {
        when(mockSearchDao.doesDocumentExists(any())).thenReturn(true);

        //call under test
        mockSearchManager.doesDocumentExist(doc1.getId());
        verify(mockSearchDao, times(1)).doesDocumentExists(doc1.getId());
    }
}
