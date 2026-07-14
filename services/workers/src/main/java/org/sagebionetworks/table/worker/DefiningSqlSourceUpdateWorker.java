package org.sagebionetworks.table.worker;

import org.sagebionetworks.repo.manager.search.SearchIndexLifecycleManager;
import org.sagebionetworks.repo.manager.table.MaterializedViewManager;
import org.sagebionetworks.repo.model.ObjectType;
import org.sagebionetworks.repo.model.entity.IdAndVersion;
import org.sagebionetworks.repo.model.jdo.KeyFactory;
import org.sagebionetworks.repo.model.table.TableState;
import org.sagebionetworks.repo.model.table.TableStatusChangeEvent;
import org.sagebionetworks.util.progress.ProgressCallback;
import org.sagebionetworks.worker.TypedMessageDrivenRunner;
import org.sagebionetworks.workers.util.aws.message.RecoverableMessageException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.amazonaws.services.sqs.model.Message;

/**
 * Listens for table status changes. When a source table/view becomes AVAILABLE, triggers a rebuild
 * of every defining-SQL object that depends on it. Each dependent kind reverse-looks-up only its own
 * rows in the shared dependency table (filtered by object type), so materialized views and search
 * indexes are refreshed independently from the same event.
 */
@Service
public class DefiningSqlSourceUpdateWorker implements TypedMessageDrivenRunner<TableStatusChangeEvent> {

	private final MaterializedViewManager materializedViewManager;
	private final SearchIndexLifecycleManager searchIndexLifecycleManager;

	@Autowired
	public DefiningSqlSourceUpdateWorker(MaterializedViewManager materializedViewManager,
			SearchIndexLifecycleManager searchIndexLifecycleManager) {
		this.materializedViewManager = materializedViewManager;
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
		// Refresh dependents only when the source became available.
		if (event.getState() == TableState.AVAILABLE) {
			final IdAndVersion sourceTableId = KeyFactory.idAndVersion(event.getObjectId(), event.getObjectVersion());
			materializedViewManager.refreshDependentMaterializedViews(sourceTableId);
			searchIndexLifecycleManager.refreshDependentSearchIndexes(sourceTableId);
		}
	}

}
