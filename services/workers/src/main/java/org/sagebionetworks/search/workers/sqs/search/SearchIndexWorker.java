package org.sagebionetworks.search.workers.sqs.search;

import org.sagebionetworks.asynchronous.workers.changes.BatchChangeMessageDrivenRunner;
import org.sagebionetworks.cloudwatch.WorkerLogger;
import org.sagebionetworks.repo.manager.opensearch.SearchManager;
import org.sagebionetworks.repo.model.message.ChangeMessage;
import org.sagebionetworks.repo.web.TemporarilyUnavailableException;
import org.sagebionetworks.util.progress.ProgressCallback;
import org.sagebionetworks.workers.util.aws.message.RecoverableMessageException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * This worker updates the OpenSearch index based on messages received
 *
 * @author Sandhra
 */
@Service
public class SearchIndexWorker implements BatchChangeMessageDrivenRunner {

    @Autowired
    private WorkerLogger workerLogger;

    @Autowired
    private SearchManager searchManager;


    @Override
    public void run(ProgressCallback progressCallback, List<ChangeMessage> changes) throws RecoverableMessageException {
        try {
            searchManager.documentChangeMessages(changes);
        } catch (TemporarilyUnavailableException e) {
            workerLogger.logWorkerFailure(SearchIndexWorker.class.getName(), e, true);
            throw new RecoverableMessageException();
        } catch (Exception e) {
            workerLogger.logWorkerFailure(SearchIndexWorker.class.getName(), e, false);
            throw e;
        }
    }
}
