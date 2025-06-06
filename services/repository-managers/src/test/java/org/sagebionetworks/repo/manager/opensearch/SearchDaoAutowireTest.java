package org.sagebionetworks.repo.manager.opensearch;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch.core.BulkRequest;
import org.opensearch.client.opensearch.core.BulkResponse;
import org.opensearch.client.opensearch.core.bulk.BulkOperation;
import org.opensearch.client.util.MissingRequiredPropertyException;
import org.sagebionetworks.repo.model.search.Document;
import org.sagebionetworks.repo.model.search.DocumentFields;
import org.sagebionetworks.repo.model.search.DocumentTypeNames;
import org.sagebionetworks.search.SearchConstants;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(locations = {"classpath:test-context.xml"})
public class SearchDaoAutowireTest {

    Document document;
    @Autowired
    private SearchDao searchDao;
    @Autowired
    private OpenSearchClient openSearchClient;

    @BeforeEach
    public void before() {
        document = new Document();
        document.setId("syn4");
        document.setType(DocumentTypeNames.add);
        document.setFields(new DocumentFields().setName("test"));
    }

    @Test
    public void testSendDocuments() throws IOException, InterruptedException {
        BulkRequest request = new BulkRequest.Builder().operations(List.of(BulkOperation.of(op -> op
                .index(idx -> idx
                        .index(SearchConstants.OPEN_SEARCH_INDEX_NAME)
                        .id(document.getId())
                        .document(document))))).build();

        //call under test
        BulkResponse response = searchDao.sendDocuments(request);
        assertFalse(response.errors());
        assertEquals(1, response.items().size());
        assertEquals(document.getId(), response.items().get(0).id());
        Thread.sleep(15000);
        assertTrue(searchDao.doesDocumentExists(document.getId()));
    }

    @Test
    public void testSendDocumentWithNullRequest() {

        assertThrows(IllegalArgumentException.class, () -> {
            //call under test
            searchDao.sendDocuments(null);
        });
    }

    @Test
    public void testSendDocumentWithEmptyRequest() {

        assertThrows(MissingRequiredPropertyException.class, () -> {
            //call under test
            searchDao.sendDocuments(new BulkRequest.Builder().build());
        });
    }

    @Test
    public void testDoesDocumentExistsWithNullId() {

        assertThrows(IllegalArgumentException.class, () -> {
            //call under test
            searchDao.doesDocumentExists(null);
        });
    }
}
