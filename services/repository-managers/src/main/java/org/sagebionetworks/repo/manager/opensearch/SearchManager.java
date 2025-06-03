package org.sagebionetworks.repo.manager.opensearch;

import java.util.List;

import org.sagebionetworks.repo.model.message.ChangeMessage;
import org.sagebionetworks.repo.web.TemporarilyUnavailableException;


public interface SearchManager {

    /**
     * Creates a document based on Entity or Wiki changes that occurred in Synapse. Used by SearchIndexWorker.
     * @param changeMessages a batch of ChangeMessages representing changes in Synapse
     */
    void documentChangeMessages(List<ChangeMessage> changeMessages) throws TemporarilyUnavailableException;

    /**
     * Returns whether a document exists for a given Synapse id
     * @param id id of the Synapse entity
     * @return true if a document exists, false otherwise.
     */
    boolean doesDocumentExist(String id);

}
