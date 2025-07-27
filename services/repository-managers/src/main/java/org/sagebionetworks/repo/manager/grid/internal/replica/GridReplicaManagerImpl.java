package org.sagebionetworks.repo.manager.grid.internal.replica;

import java.util.List;
import java.util.Random;

import org.sagebionetworks.grid.db.GridIndexManager;
import org.sagebionetworks.grid.db.MessageChain;
import org.sagebionetworks.repo.manager.grid.response.InternalReplicaToHubEventPublisher;
import org.sagebionetworks.repo.model.grid.EventContext;
import org.sagebionetworks.repo.model.grid.EventSource;
import org.sagebionetworks.repo.model.grid.EventType;
import org.sagebionetworks.repo.model.grid.GridConnectionInfo;
import org.sagebionetworks.repo.model.grid.message.JsonRxMessage;
import org.sagebionetworks.repo.model.grid.message.JsonRxMessageType;
import org.sagebionetworks.repo.model.grid.patch.LogicalTimestamp;
import org.sagebionetworks.repo.model.grid.patch.Patch;
import org.sagebionetworks.repo.model.grid.patch.compact.LogicalTimestampCompactSerializable;
import org.sagebionetworks.util.progress.ProgressCallback;
import org.sagebionetworks.workers.util.semaphore.WriteLockRequest;
import org.sagebionetworks.workers.util.semaphore.WriteReadSemaphore;
import org.springframework.stereotype.Component;

@Component
public class GridReplicaManagerImpl implements GridReplicaManager {

	private final GridIndexManager gridIndexManager;
	private final WriteReadSemaphore writeReadSemaphore;
	private final InternalReplicaToHubEventPublisher publisher;

	public GridReplicaManagerImpl(GridIndexManager gridIndexManager, WriteReadSemaphore writeReadSemaphore,
			InternalReplicaToHubEventPublisher publisher, Random random) {
		this.gridIndexManager = gridIndexManager;
		this.writeReadSemaphore = writeReadSemaphore;
		this.publisher = publisher;
	}

	void synchronizeClock(ProgressCallback callback, GridConnectionInfo connection) {
		String context = "startSychronizeClock-" + connection.getConnectionId();
		writeReadSemaphore.tryRunWithWriteLock(new WriteLockRequest(callback, context, connection.getConnectionId()),
				(p) -> {
					MessageChain chain = gridIndexManager.startMessageChain(connection.getSessionId(),
							connection.getReplicaId(), SYNCHRONIZE_CLOCK);
					List<LogicalTimestamp> clock = gridIndexManager.getClock(connection.getSessionId(),
							connection.getReplicaId());
					sendClockMessage(chain.getId(), connection.getConnectionId(), clock);
					return null;
				});
	}

	void sendClockMessage(Integer methodId, String connectionId, List<LogicalTimestamp> clock) {
		publisher.publishEvent(new EventContext(EventType.MESSAGE, EventSource.INTERNAL, connectionId),
				new JsonRxMessage(JsonRxMessageType.RequestData).setMethod(SYNCHRONIZE_CLOCK).setId(methodId)
						.setBody(LogicalTimestampCompactSerializable.serializeClock(clock)));
	}

	@Override
	public void onResponseComplete(GridConnectionInfo connection, Integer methodId) {
		gridIndexManager.completeMessageChain(connection.getSessionId(), connection.getReplicaId(), methodId);
	}

	@Override
	public void onApplyPatch(ProgressCallback callback, GridConnectionInfo connection, Integer messageId, Patch patch) {
		String context = "onApplyPatch-" + connection.getConnectionId();
		writeReadSemaphore.tryRunWithWriteLock(new WriteLockRequest(callback, context, connection.getConnectionId()),
				(p) -> {
					gridIndexManager.applyPatch(connection.getSessionId(), connection.getReplicaId(), patch);
					List<LogicalTimestamp> clock = gridIndexManager.getClock(connection.getSessionId(),
							connection.getReplicaId());
					sendClockMessage(messageId, connection.getConnectionId(), clock);
					return null;
				});
	}

	@Override
	public void onConnected(ProgressCallback callback, GridConnectionInfo connection) {
		synchronizeClock(callback, connection);
	}

	@Override
	public void onNewPatch(ProgressCallback callback, GridConnectionInfo connection) {
		synchronizeClock(callback, connection);
	}
}
