package org.sagebionetworks.repo.manager.grid.internal.replica;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
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
import org.sagebionetworks.util.progress.ProgressingCallable;
import org.sagebionetworks.workers.util.semaphore.WriteLockRequest;
import org.sagebionetworks.workers.util.semaphore.WriteReadSemaphore;

@ExtendWith(MockitoExtension.class)
public class GridReplicaManagerImplTest {

	@Mock
	private GridIndexManager mockGridIndexManager;
	@Mock
	private WriteReadSemaphore mockWriteReadSemaphore;
	@Mock
	private InternalReplicaToHubEventPublisher mockPublisher;
	@Mock
	private ProgressCallback mockCallback;
	@Captor
	ArgumentCaptor<WriteLockRequest> writeLockRequestCaptor;

	private GridConnectionInfo connection;
	private String sessionId;
	private String connectionId;
	private Long replicaId;
	private Integer methodId;
	private List<LogicalTimestamp> clock;
	private Patch patch;

	@BeforeEach
	public void before() {
		sessionId = "session456";
		connectionId = "con123";
		replicaId = 111L;
		methodId = 444;
		connection = new GridConnectionInfo().setConnectionId(connectionId).setReplicaId(replicaId)
				.setSessionId(sessionId);
		clock = List.of(new LogicalTimestamp().setReplicaId(replicaId).setSequenceNumber(99L));
		patch = new Patch().setPatchId(new LogicalTimestamp().setReplicaId(3L).setSequenceNumber(4L))
				.setOperations(List.of());

	}

	@Spy
	@InjectMocks
	private GridReplicaManagerImpl manager;

	@Test
	public void testOnConnect() {
		doNothing().when(manager).synchronizeClock(mockCallback, connection);
		// call under test
		manager.onConnected(mockCallback, connection);
	}

	@Test
	public void testOnNewPatch() {
		doNothing().when(manager).synchronizeClock(mockCallback, connection);
		// call under test
		manager.onNewPatch(mockCallback, connection);
	}

	@Test
	public void testonResponseComplete() {
		Integer methodId = 44;
		// call under test
		manager.onResponseComplete(connection, methodId);
		verify(mockGridIndexManager).completeMessageChain(sessionId, replicaId, methodId);
	}

	@Test
	public void testSendClockMessage() {
		// call under test
		manager.sendClockMessage(methodId, connectionId, clock);
		verify(mockPublisher).publishEvent(new EventContext(EventType.MESSAGE, EventSource.INTERNAL, connectionId),
				new JsonRxMessage(JsonRxMessageType.RequestData).setMethod(GridReplicaManager.SYNCHRONIZE_CLOCK)
						.setId(methodId).setBody(LogicalTimestampCompactSerializable.serializeClock(clock)));
	}

	@Test
	public void testOnApplyPatch() {
		when(mockCallback.getLockTimeoutSeconds()).thenReturn(2L);
		// Mock the semaphore to execute the lambda immediately
		doAnswer(invocation -> {
			WriteLockRequest request = invocation.getArgument(0);
			ProgressingCallable<Void> function = invocation.getArgument(1);
			return function.call(request.getCallback());
		}).when(mockWriteReadSemaphore).tryRunWithWriteLock(writeLockRequestCaptor.capture(), any());

		// Mock the gridIndexManager calls
		when(mockGridIndexManager.getClock(sessionId, replicaId)).thenReturn(clock);
		doNothing().when(manager).sendClockMessage(methodId, connectionId, clock);

		// call under test
		manager.onApplyPatch(mockCallback, connection, methodId, patch);

		// verify interactions
		verify(mockGridIndexManager).applyPatch(sessionId, replicaId, patch);
		verify(mockGridIndexManager).getClock(sessionId, replicaId);
		verify(manager).sendClockMessage(methodId, connectionId, clock);
		assertEquals("onApplyPatch-con123", writeLockRequestCaptor.getValue().getCallersContext());
	}

	@Test
	public void testSynchronizeClock() {
		when(mockCallback.getLockTimeoutSeconds()).thenReturn(2L);
		doAnswer(invocation -> {
			WriteLockRequest request = invocation.getArgument(0);
			ProgressingCallable<Void> function = invocation.getArgument(1);
			return function.call(request.getCallback());
		}).when(mockWriteReadSemaphore).tryRunWithWriteLock(writeLockRequestCaptor.capture(), any());

		when(mockGridIndexManager.startMessageChain(sessionId, replicaId, GridReplicaManager.SYNCHRONIZE_CLOCK))
				.thenReturn(new MessageChain().setMethod(GridReplicaManager.SYNCHRONIZE_CLOCK).setId(methodId));
		when(mockGridIndexManager.getClock(sessionId, replicaId)).thenReturn(clock);

		// call under test
		manager.synchronizeClock(mockCallback, connection);

		verify(mockWriteReadSemaphore).tryRunWithWriteLock(any(WriteLockRequest.class), any());
		verify(mockGridIndexManager).startMessageChain(sessionId, replicaId, GridReplicaManager.SYNCHRONIZE_CLOCK);
		verify(mockGridIndexManager).getClock(sessionId, replicaId);
		verify(mockPublisher).publishEvent(new EventContext(EventType.MESSAGE, EventSource.INTERNAL, connectionId),
				new JsonRxMessage(JsonRxMessageType.RequestData).setMethod(GridReplicaManager.SYNCHRONIZE_CLOCK)
						.setId(methodId).setBody(LogicalTimestampCompactSerializable.serializeClock(clock)));
		assertEquals("startSychronizeClock-con123", writeLockRequestCaptor.getValue().getCallersContext());
	}

}
