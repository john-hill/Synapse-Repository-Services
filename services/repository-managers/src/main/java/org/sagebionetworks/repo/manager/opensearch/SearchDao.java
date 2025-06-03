package org.sagebionetworks.repo.manager.opensearch;

import org.opensearch.client.opensearch.core.BulkRequest;
import org.opensearch.client.opensearch.core.BulkResponse;

import java.io.IOException;


/**
 * Abstraction for interacting with the open search index.
 *
 * @author ssokhal
 *
 */
public interface SearchDao {

    /**
     * Send documents in bulk to OpenSearch Index.
     * @param bulkRequest
     * @return
     */
    BulkResponse sendDocuments(BulkRequest bulkRequest) throws IOException;

    /**
     * Does a document already exist with the given id.?
     * @param id
     * @return
     */
    boolean doesDocumentExistInSearchIndex(String id);
}
