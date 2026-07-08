package org.sagebionetworks.search.workers;

import org.sagebionetworks.repo.manager.search.SearchIndexLifecycleManager;
import org.sagebionetworks.repo.model.ObjectType;
import org.sagebionetworks.repo.model.entity.IdAndVersion;
import org.sagebionetworks.repo.model.jdo.KeyFactory;
import org.sagebionetworks.repo.model.table.TableState;
import org.sagebionetworks.repo.model.table.TableStatusChangeEvent;
import org.sagebionetworks.util.progress.ProgressCallback;
import org.sagebionetworks.worker.TypedMessageDrivenRunner;
import org.sagebionetworks.workers.util.aws.message.RecoverableMessageException;
import org.springframework.stereotype.Service;

import com.amazonaws.services.sqs.model.Message;

/**
 * Hop-1 of the source-availability rebuild. Listens for table status changes; when a source
 * table/view becomes AVAILABLE, enqueues a rebuild request for every SearchIndex that depends on it
 * and is waiting for the source. Mirrors {@link org.sagebionetworks.table.worker.MaterializedViewSourceUpdateWorker}.
 */
@Service
public class SearchIndexSourceUpdateWorker implements TypedMessageDrivenRunner<TableStatusChangeEvent> {

	private final SearchIndexLifecycleManager searchIndexLifecycleManager;

	public SearchIndexSourceUpdateWorker(SearchIndexLifecycleManager searchIndexLifecycleManager) {
		this.searchIndexLifecycleManager = searchIndexLifecycleManager;
	}

	@Override
	public Class<TableStatusChangeEvent> getObjectClass() {
		return TableStatusChangeEvent.class;
	}

	@Override
	public void run(ProgressCallback progressCallback, Message message, TableStatusChangeEvent event)
			throws RecoverableMessageException, Exception {
		if (ObjectType.TABLE_STATUS_EVENT != event.getObjectType()) {
			throw new IllegalStateException("Unsupported object type: expected "
					+ ObjectType.TABLE_STATUS_EVENT.name() + ", got " + event.getObjectType());
		}
		// Refresh dependent search indexes only when the source became available.
		if (event.getState() == TableState.AVAILABLE) {
			final IdAndVersion sourceTableId = KeyFactory.idAndVersion(event.getObjectId(), event.getObjectVersion());
			searchIndexLifecycleManager.refreshDependentSearchIndexes(sourceTableId);
		}
	}

}
