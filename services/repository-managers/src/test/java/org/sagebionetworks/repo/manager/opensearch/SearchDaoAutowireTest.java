package org.sagebionetworks.repo.manager.opensearch;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.opensearch.client.opensearch.core.BulkRequest;
import org.opensearch.client.opensearch.core.BulkResponse;
import org.opensearch.client.opensearch.core.bulk.BulkOperation;
import org.opensearch.client.util.MissingRequiredPropertyException;
import org.sagebionetworks.repo.model.Project;
import org.sagebionetworks.repo.model.search.Document;
import org.sagebionetworks.repo.model.search.DocumentFields;
import org.sagebionetworks.repo.model.search.DocumentTypeNames;
import org.sagebionetworks.search.SearchConstants;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

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


    @BeforeEach
    public void before() {
        document = new Document();
        document.setId("syn" + UUID.randomUUID());
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

        //once we index the document, it takes some time to became searchable
        Thread.sleep(15000);
        assertTrue(searchDao.doesDocumentExists(document.getId()));

    }

    @Test
    public void testSendDocumentTwiceUpdateDocument() throws IOException, InterruptedException {
        BulkRequest request = new BulkRequest.Builder().operations(List.of(BulkOperation.of(op -> op
                .index(idx -> idx
                        .index(SearchConstants.OPEN_SEARCH_INDEX_NAME)
                        .id(document.getId())
                        .document(document))))).build();

        //call under test. sending document first time.
        BulkResponse response = searchDao.sendDocuments(request);
        assertFalse(response.errors());
        assertEquals(1, response.items().size());
        assertEquals(1, response.items().get(0).version());

        //once we index the document, it takes some time to became searchable
        Thread.sleep(15000);

        //sending document 2nd time.
        BulkResponse response2 = searchDao.sendDocuments(request);
        assertFalse(response.errors());
        assertEquals(1, response2.items().size());
        assertEquals(2, response2.items().get(0).version());
    }


    @Test
    public void testSendDocumentWithWrongMapping() throws IOException {
        Project project = new Project().setId("test" + UUID.randomUUID()).setName("test");
        BulkRequest request = new BulkRequest.Builder().operations(List.of(BulkOperation.of(op -> op
                .index(idx -> idx
                        .index(SearchConstants.OPEN_SEARCH_INDEX_NAME)
                        .id(project.getId())
                        .document(project))))).build();

        //call under test. sending document first time.
        BulkResponse response = searchDao.sendDocuments(request);
        assertTrue(response.errors());
        assertEquals("strict_dynamic_mapping_exception", response.items().get(0).error().type());
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

    @Test
    public void testDoesDocumentWithWrongId() {
        assertFalse(searchDao.doesDocumentExists(UUID.randomUUID().toString()));
    }
}
