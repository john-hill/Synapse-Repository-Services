package org.sagebionetworks.repo.manager.grid.internal.replica.synch;

import java.util.ArrayList;
import java.util.List;

import org.sagebionetworks.repo.manager.grid.GridManager;
import org.sagebionetworks.repo.manager.grid.internal.replica.change.GridReplicaPatchBuilderManager;
import org.sagebionetworks.repo.manager.grid.internal.replica.change.IntendedChangeSet;
import org.sagebionetworks.repo.manager.grid.internal.replica.change.PatchBuilderPublisher;
import org.sagebionetworks.repo.manager.grid.internal.replica.model.GridHeader;
import org.sagebionetworks.repo.manager.grid.internal.replica.view.GridReplicaViewManager;
import org.sagebionetworks.repo.manager.grid.internal.replica.view.query.QueryElement;
import org.sagebionetworks.repo.manager.table.ColumnModelManager;
import org.sagebionetworks.repo.model.UserInfo;
import org.sagebionetworks.repo.model.grid.EntitySynchronizationStatus;
import org.sagebionetworks.repo.model.grid.EventSource;
import org.sagebionetworks.repo.model.grid.GridConnectionInfo;
import org.sagebionetworks.repo.model.grid.GridSession;
import org.sagebionetworks.repo.model.grid.GridViewSynchronizationRequest;
import org.sagebionetworks.repo.model.grid.GridViewSynchronizationResponse;
import org.sagebionetworks.repo.model.table.ColumnModel;
import org.sagebionetworks.util.ValidateArgument;
import org.sagebionetworks.workers.util.aws.message.RecoverableMessageException;
import org.springframework.stereotype.Service;

@Service
public class GridToViewSynchronizationManagerImpl implements GridToViewSynchronizationManager {

	private final GridReplicaViewManager gridReplicaViewManager;
	private final GridManager gridManager;
	private final PatchBuilderPublisher patchBuilderPublisher;
	private final SynchronizationRowHandler synchronizationRowHandler;
	private final GridReplicaPatchBuilderManager patchBuilderManager;
	private final ColumnModelManager columnModelManager;

	public GridToViewSynchronizationManagerImpl(GridReplicaViewManager gridReplicaViewManager, GridManager gridManager,
			PatchBuilderPublisher patchBuilderPublisher, SynchronizationRowHandler synchronizationRowHandler,
			GridReplicaPatchBuilderManager patchBuilderManager) {
		super();
		this.gridReplicaViewManager = gridReplicaViewManager;
		this.gridManager = gridManager;
		this.patchBuilderPublisher = patchBuilderPublisher;
		this.synchronizationRowHandler = synchronizationRowHandler;
		this.patchBuilderManager = patchBuilderManager;
		this.columnModelManager = null;
	}

	public GridViewSynchronizationResponse synchronize(UserInfo user, GridViewSynchronizationRequest request) {
		ValidateArgument.required(request, "request");
		ValidateArgument.required(request.getSessionId(), "request.sessionId");
		ValidateArgument.required(request.getOperation(), "request.operation");
		GridSession session = gridManager.getGridSession(user, request.getSessionId());

		List<ColumnModel> viewSchema = columnModelManager.getColumnModelsForTable(user, session.getSourceEntityId());

		GridConnectionInfo connection = gridManager.getSingletonConnection(session.getSessionId(), EventSource.INTERNAL)
				.orElseThrow(() -> new RecoverableMessageException(
						"No internal connection found for session: " + session.getSessionId()));

		patchBuilderManager.getCurrentClockIfAllPatchesApplied(connection.getSessionId(), connection.getReplicaId())
				.orElseThrow(() -> new RecoverableMessageException(
						"Current clock could not be retrieved, patches are still being applied to sessionId: "
								+ connection.getSessionId() + ", replicaId: " + connection.getReplicaId()));

		GridHeader header = gridReplicaViewManager.readHeader(connection.getSessionId(), connection.getReplicaId())
				.orElseThrow(() -> new RecoverableMessageException(
						"Grid header has not yet been instantiated for sessionId: " + connection.getSessionId()));

		List<EntitySynchronizationStatus> resultStatus = new ArrayList<>();
		gridReplicaViewManager.getQueryIterator(header, new QueryElement()).forEachRemaining((rowView) -> {
			SynchronizationResult result = synchronizationRowHandler.processRow(rowView, request.getOperation());
			if (!result.getGridChanges().isEmpty()) {
				patchBuilderPublisher.sendChangesToPatchBuilder(new IntendedChangeSet()
						.setChanges(result.getGridChanges()).setClockSequenceMaximum(header.getClockSequenceMaximum())
						.setConnectionId(connection.getConnectionId()).setReplicaId(connection.getReplicaId())
						.setSessionId(connection.getSessionId()));
			}
		});

		return new GridViewSynchronizationResponse().setSessionId(request.getSessionId()).setRowStatus(resultStatus);
	}

}
