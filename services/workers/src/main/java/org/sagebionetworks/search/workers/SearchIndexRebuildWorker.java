package org.sagebionetworks.search.workers;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.sagebionetworks.repo.manager.search.SearchIndexLifecycleManager;
import org.sagebionetworks.repo.model.search.table.SearchIndexRebuildMessage;
import org.sagebionetworks.repo.model.table.TableFailedException;
import org.sagebionetworks.util.progress.ProgressCallback;
import org.sagebionetworks.worker.TypedMessageDrivenRunner;
import org.sagebionetworks.workers.util.aws.message.RecoverableMessageException;
import org.springframework.stereotype.Service;

import com.amazonaws.services.sqs.model.Message;

/**
 * Hop-2 of the source-availability rebuild. Consumes a {@link SearchIndexRebuildMessage} (one per
 * dependent SearchIndex) and asks the lifecycle manager to rebuild it if it is still waiting for its
 * source. {@code TableUnavailableException} is handled inside the manager (recorded as
 * WAITING_FOR_SOURCE and consumed); lock contention is handled inside the manager via
 * consume-and-republish, so it does not surface here as a recoverable retry.
 */
@Service
public class SearchIndexRebuildWorker implements TypedMessageDrivenRunner<SearchIndexRebuildMessage> {

	private static final Logger LOG = LogManager.getLogger(SearchIndexRebuildWorker.class);

	private final SearchIndexLifecycleManager searchIndexLifecycleManager;

	public SearchIndexRebuildWorker(SearchIndexLifecycleManager searchIndexLifecycleManager) {
		this.searchIndexLifecycleManager = searchIndexLifecycleManager;
	}

	@Override
	public Class<SearchIndexRebuildMessage> getObjectClass() {
		return SearchIndexRebuildMessage.class;
	}

	@Override
	public void run(ProgressCallback progressCallback, Message message, SearchIndexRebuildMessage event)
			throws RecoverableMessageException, Exception {
		String entityId = event.getObjectId();
		try {
			searchIndexLifecycleManager.rebuildIfStale(progressCallback, entityId);
		} catch (RecoverableMessageException e) {
			LOG.warn("Recoverable exception rebuilding search index {}: {}", entityId, e.getMessage());
			throw e;
		} catch (TableFailedException e) {
			LOG.error("Source table failed rebuilding search index {}: {}", entityId, e.getMessage());
		}
	}

}
