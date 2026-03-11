package org.sagebionetworks.repo.manager.grid;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.sagebionetworks.StackConfiguration;
import org.sagebionetworks.grid.db.GridIndexManager;
import org.sagebionetworks.repo.model.dbo.grid.GridDao;
import org.sagebionetworks.repo.model.grid.ClockTable;
import org.sagebionetworks.repo.model.grid.EventSource;
import org.sagebionetworks.repo.model.grid.GridConnectionInfo;
import org.sagebionetworks.repo.model.grid.patch.LogicalTimestamp;
import org.sagebionetworks.repo.manager.grid.internal.replica.change.GridReplicaPatchBuilderManager;
import org.sagebionetworks.util.progress.ProgressCallback;

@ExtendWith(MockitoExtension.class)
public class GridSnapshotCompactionManagerImplTest {

	@Mock
	private GridDao mockGridDao;
	@Mock
	private GridIndexManager mockGridIndexManager;
	@Mock
	private GridReplicaPatchBuilderManager mockPatchBuilderManager;
	@Mock
	private SnapshotStore mockSnapshotStore;
	@Mock
	private StackConfiguration mockStackConfig;
	@Mock
	private ProgressCallback mockCallback;

	private GridSnapshotCompactionManagerImpl manager;

	private String sessionId;
	private Long replicaId;
	private Long userId;

	@BeforeEach
	public void before() {
		manager = new GridSnapshotCompactionManagerImpl(mockGridDao, mockGridIndexManager, mockPatchBuilderManager,
				mockSnapshotStore, mockStackConfig);

		sessionId = "session-123";
		replicaId = 456L;
		userId = 789L;

		when(mockStackConfig.getGridSnapshotMaxAgeDays()).thenReturn(30);
		when(mockStackConfig.getGridSnapshotMaxPatchCount()).thenReturn(1000);
		when(mockStackConfig.getGridSnapshotCompactionBatchSize()).thenReturn(10);
	}

	@Test
	public void testCompactSessionsWithNoSessionsNeeding() {
		when(mockGridDao.listSessionsNeedingCompaction(Duration.ofDays(30), 1000, 10))
				.thenReturn(Collections.emptyList());

		// call under test
		int result = manager.compactSessions(mockCallback);

		assertEquals(0, result);
		verifyZeroInteractions(mockGridIndexManager);
		verifyZeroInteractions(mockSnapshotStore);
	}

	@Test
	public void testCompactSessionsWithOneSession() {
		when(mockGridDao.listSessionsNeedingCompaction(Duration.ofDays(30), 1000, 10))
				.thenReturn(List.of(sessionId));

		GridConnectionInfo connection = new GridConnectionInfo().setSessionId(sessionId).setReplicaId(replicaId)
				.setCreatedBy(userId).setSource(EventSource.INTERNAL);
		when(mockGridDao.getSingletonConnection(sessionId, EventSource.INTERNAL))
				.thenReturn(Optional.of(connection));

		LogicalTimestamp clock = new LogicalTimestamp().setReplicaId(replicaId).setSequenceNumber(100L);
		when(mockPatchBuilderManager.getCurrentClockIfAllPatchesApplied(sessionId, replicaId))
				.thenReturn(Optional.of(clock));

		ClockTable clockTable = new ClockTable(List.of(clock));
		when(mockGridIndexManager.exportSnapshot(eq(sessionId), eq(replicaId), any(Path.class)))
				.thenReturn(clockTable);

		// call under test
		int result = manager.compactSessions(mockCallback);

		assertEquals(1, result);
		verify(mockGridIndexManager).exportSnapshot(eq(sessionId), eq(replicaId), any(Path.class));
		verify(mockSnapshotStore).saveSnapshot(eq(sessionId), eq(clockTable), eq(userId), any());
	}

	@Test
	public void testCompactSessionsWithNoInternalConnection() {
		when(mockGridDao.listSessionsNeedingCompaction(Duration.ofDays(30), 1000, 10))
				.thenReturn(List.of(sessionId));
		when(mockGridDao.getSingletonConnection(sessionId, EventSource.INTERNAL))
				.thenReturn(Optional.empty());

		// call under test
		int result = manager.compactSessions(mockCallback);

		assertEquals(0, result);
		verify(mockGridIndexManager, never()).exportSnapshot(any(), any(), any());
	}

	@Test
	public void testCompactSessionsWithUnsynchronizedReplica() {
		when(mockGridDao.listSessionsNeedingCompaction(Duration.ofDays(30), 1000, 10))
				.thenReturn(List.of(sessionId));

		GridConnectionInfo connection = new GridConnectionInfo().setSessionId(sessionId).setReplicaId(replicaId)
				.setCreatedBy(userId).setSource(EventSource.INTERNAL);
		when(mockGridDao.getSingletonConnection(sessionId, EventSource.INTERNAL))
				.thenReturn(Optional.of(connection));
		when(mockPatchBuilderManager.getCurrentClockIfAllPatchesApplied(sessionId, replicaId))
				.thenReturn(Optional.empty());

		// call under test
		int result = manager.compactSessions(mockCallback);

		assertEquals(0, result);
		verify(mockGridIndexManager, never()).exportSnapshot(any(), any(), any());
	}

	@Test
	public void testCompactSessionsWithExportFailure() {
		when(mockGridDao.listSessionsNeedingCompaction(Duration.ofDays(30), 1000, 10))
				.thenReturn(List.of(sessionId));

		GridConnectionInfo connection = new GridConnectionInfo().setSessionId(sessionId).setReplicaId(replicaId)
				.setCreatedBy(userId).setSource(EventSource.INTERNAL);
		when(mockGridDao.getSingletonConnection(sessionId, EventSource.INTERNAL))
				.thenReturn(Optional.of(connection));

		LogicalTimestamp clock = new LogicalTimestamp().setReplicaId(replicaId).setSequenceNumber(100L);
		when(mockPatchBuilderManager.getCurrentClockIfAllPatchesApplied(sessionId, replicaId))
				.thenReturn(Optional.of(clock));
		when(mockGridIndexManager.exportSnapshot(eq(sessionId), eq(replicaId), any(Path.class)))
				.thenThrow(new RuntimeException("Export failed"));

		// call under test -- should not throw, but return 0
		int result = manager.compactSessions(mockCallback);

		assertEquals(0, result);
		verify(mockSnapshotStore, never()).saveSnapshot(any(), any(), any(), any());
	}

	@Test
	public void testCompactSessionsWithMultipleSessions() {
		String sessionId2 = "session-456";
		Long replicaId2 = 789L;

		when(mockGridDao.listSessionsNeedingCompaction(Duration.ofDays(30), 1000, 10))
				.thenReturn(List.of(sessionId, sessionId2));

		// First session compacts successfully
		GridConnectionInfo connection1 = new GridConnectionInfo().setSessionId(sessionId).setReplicaId(replicaId)
				.setCreatedBy(userId).setSource(EventSource.INTERNAL);
		when(mockGridDao.getSingletonConnection(sessionId, EventSource.INTERNAL))
				.thenReturn(Optional.of(connection1));
		when(mockPatchBuilderManager.getCurrentClockIfAllPatchesApplied(sessionId, replicaId))
				.thenReturn(Optional.of(new LogicalTimestamp().setReplicaId(replicaId).setSequenceNumber(100L)));
		ClockTable clockTable1 = new ClockTable(
				List.of(new LogicalTimestamp().setReplicaId(replicaId).setSequenceNumber(100L)));
		when(mockGridIndexManager.exportSnapshot(eq(sessionId), eq(replicaId), any(Path.class)))
				.thenReturn(clockTable1);

		// Second session has no internal connection
		when(mockGridDao.getSingletonConnection(sessionId2, EventSource.INTERNAL))
				.thenReturn(Optional.empty());

		// call under test
		int result = manager.compactSessions(mockCallback);

		assertEquals(1, result);
	}
}
