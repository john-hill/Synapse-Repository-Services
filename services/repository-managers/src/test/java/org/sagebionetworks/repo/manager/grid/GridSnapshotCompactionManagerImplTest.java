package org.sagebionetworks.repo.manager.grid;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
import org.sagebionetworks.util.FileProvider;

import com.amazonaws.services.sqs.AmazonSQS;
import com.amazonaws.services.sqs.model.GetQueueUrlResult;
import com.amazonaws.services.sqs.model.SendMessageRequest;

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
	private FileProvider mockFileProvider;
	@Mock
	private AmazonSQS mockSqsClient;

	private GridSnapshotCompactionManagerImpl manager;

	private String sessionId;
	private Long replicaId;
	private Long userId;

	private static final String QUEUE_URL = "https://sqs.us-east-1.amazonaws.com/123456789/dev-test-GRID_SNAPSHOT_COMPACTION";

	@BeforeEach
	public void before() {
		when(mockStackConfig.getQueueName(GridSnapshotCompactionManagerImpl.COMPACTION_QUEUE_NAME))
				.thenReturn("dev-test-GRID_SNAPSHOT_COMPACTION");
		when(mockSqsClient.getQueueUrl("dev-test-GRID_SNAPSHOT_COMPACTION"))
				.thenReturn(new GetQueueUrlResult().withQueueUrl(QUEUE_URL));

		manager = new GridSnapshotCompactionManagerImpl(mockGridDao, mockGridIndexManager, mockPatchBuilderManager,
				mockSnapshotStore, mockStackConfig, mockFileProvider, mockSqsClient);

		sessionId = "session-123";
		replicaId = 456L;
		userId = 789L;
	}

	// --- scanAndPublishSessionsNeedingCompaction tests ---

	@Test
	public void testScanAndPublishWithNoSessionsNeeding() {
		when(mockStackConfig.getGridSnapshotMaxAgeDays()).thenReturn(30);
		when(mockStackConfig.getGridSnapshotMaxPatchCount()).thenReturn(1000);
		when(mockStackConfig.getGridSnapshotCompactionBatchSize()).thenReturn(10);
		when(mockGridDao.listSessionsNeedingCompaction(Duration.ofDays(30), 1000, 10))
				.thenReturn(Collections.emptyList());

		// call under test
		List<String> result = manager.scanAndPublishSessionsNeedingCompaction();

		assertEquals(Collections.emptyList(), result);
		verify(mockSqsClient, never()).sendMessage(any(SendMessageRequest.class));
	}

	@Test
	public void testScanAndPublishWithOneSessions() {
		when(mockStackConfig.getGridSnapshotMaxAgeDays()).thenReturn(30);
		when(mockStackConfig.getGridSnapshotMaxPatchCount()).thenReturn(1000);
		when(mockStackConfig.getGridSnapshotCompactionBatchSize()).thenReturn(10);
		when(mockGridDao.listSessionsNeedingCompaction(Duration.ofDays(30), 1000, 10))
				.thenReturn(List.of(sessionId));

		// call under test
		List<String> result = manager.scanAndPublishSessionsNeedingCompaction();

		assertEquals(List.of(sessionId), result);

		ArgumentCaptor<SendMessageRequest> captor = ArgumentCaptor.forClass(SendMessageRequest.class);
		verify(mockSqsClient).sendMessage(captor.capture());
		SendMessageRequest request = captor.getValue();
		assertEquals(QUEUE_URL, request.getQueueUrl());
		assertEquals(sessionId, request.getMessageBody());
	}

	@Test
	public void testScanAndPublishWithMultipleSessions() {
		String sessionId2 = "session-456";

		when(mockStackConfig.getGridSnapshotMaxAgeDays()).thenReturn(30);
		when(mockStackConfig.getGridSnapshotMaxPatchCount()).thenReturn(1000);
		when(mockStackConfig.getGridSnapshotCompactionBatchSize()).thenReturn(10);
		when(mockGridDao.listSessionsNeedingCompaction(Duration.ofDays(30), 1000, 10))
				.thenReturn(List.of(sessionId, sessionId2));

		// call under test
		List<String> result = manager.scanAndPublishSessionsNeedingCompaction();

		assertEquals(List.of(sessionId, sessionId2), result);

		ArgumentCaptor<SendMessageRequest> captor = ArgumentCaptor.forClass(SendMessageRequest.class);
		verify(mockSqsClient, org.mockito.Mockito.times(2)).sendMessage(captor.capture());
		List<SendMessageRequest> requests = captor.getAllValues();
		assertEquals(sessionId, requests.get(0).getMessageBody());
		assertEquals(sessionId2, requests.get(1).getMessageBody());
	}

	// --- compactSession tests ---

	@Test
	public void testCompactSessionWithNullSessionId() {
		assertThrows(IllegalArgumentException.class, () -> {
			// call under test
			manager.compactSession(null);
		});
	}

	@Test
	public void testCompactSessionWithNoInternalConnection() {
		when(mockGridDao.getSingletonConnection(sessionId, EventSource.INTERNAL))
				.thenReturn(Optional.empty());

		// call under test
		boolean result = manager.compactSession(sessionId);

		assertFalse(result);
		verify(mockGridIndexManager, never()).exportSnapshot(any(), any(), any());
	}

	@Test
	public void testCompactSessionWithUnsynchronizedReplica() {
		GridConnectionInfo connection = new GridConnectionInfo().setSessionId(sessionId).setReplicaId(replicaId)
				.setCreatedBy(userId).setSource(EventSource.INTERNAL);
		when(mockGridDao.getSingletonConnection(sessionId, EventSource.INTERNAL))
				.thenReturn(Optional.of(connection));
		when(mockPatchBuilderManager.getCurrentClockIfAllPatchesApplied(sessionId, replicaId))
				.thenReturn(Optional.empty());

		// call under test
		boolean result = manager.compactSession(sessionId);

		assertFalse(result);
		verify(mockGridIndexManager, never()).exportSnapshot(any(), any(), any());
	}

	@Test
	public void testCompactSessionSuccess() throws IOException {
		GridConnectionInfo connection = new GridConnectionInfo().setSessionId(sessionId).setReplicaId(replicaId)
				.setCreatedBy(userId).setSource(EventSource.INTERNAL);
		when(mockGridDao.getSingletonConnection(sessionId, EventSource.INTERNAL))
				.thenReturn(Optional.of(connection));

		LogicalTimestamp clock = new LogicalTimestamp().setReplicaId(replicaId).setSequenceNumber(100L);
		when(mockPatchBuilderManager.getCurrentClockIfAllPatchesApplied(sessionId, replicaId))
				.thenReturn(Optional.of(clock));

		File tempFile = File.createTempFile("test-", ".cbor");
		tempFile.deleteOnExit();
		when(mockFileProvider.createTempFile(any(), any())).thenReturn(tempFile);

		ClockTable clockTable = new ClockTable(List.of(clock));
		when(mockGridIndexManager.exportSnapshot(eq(sessionId), eq(replicaId), any(Path.class)))
				.thenReturn(clockTable);

		// call under test
		boolean result = manager.compactSession(sessionId);

		assertTrue(result);
		verify(mockGridIndexManager).exportSnapshot(eq(sessionId), eq(replicaId), any(Path.class));
		verify(mockSnapshotStore).saveSnapshot(eq(sessionId), eq(clockTable), eq(userId), any(File.class));
	}

	@Test
	public void testCompactSessionWithExportFailure() throws IOException {
		GridConnectionInfo connection = new GridConnectionInfo().setSessionId(sessionId).setReplicaId(replicaId)
				.setCreatedBy(userId).setSource(EventSource.INTERNAL);
		when(mockGridDao.getSingletonConnection(sessionId, EventSource.INTERNAL))
				.thenReturn(Optional.of(connection));

		LogicalTimestamp clock = new LogicalTimestamp().setReplicaId(replicaId).setSequenceNumber(100L);
		when(mockPatchBuilderManager.getCurrentClockIfAllPatchesApplied(sessionId, replicaId))
				.thenReturn(Optional.of(clock));

		File tempFile = File.createTempFile("test-", ".cbor");
		tempFile.deleteOnExit();
		when(mockFileProvider.createTempFile(any(), any())).thenReturn(tempFile);

		when(mockGridIndexManager.exportSnapshot(eq(sessionId), eq(replicaId), any(Path.class)))
				.thenThrow(new RuntimeException("Export failed"));

		// call under test
		assertThrows(RuntimeException.class, () -> {
			manager.compactSession(sessionId);
		});

		verify(mockSnapshotStore, never()).saveSnapshot(any(), any(), any(), any());
	}

	@Test
	public void testCompactSessionCleansUpTempFile() throws IOException {
		GridConnectionInfo connection = new GridConnectionInfo().setSessionId(sessionId).setReplicaId(replicaId)
				.setCreatedBy(userId).setSource(EventSource.INTERNAL);
		when(mockGridDao.getSingletonConnection(sessionId, EventSource.INTERNAL))
				.thenReturn(Optional.of(connection));

		LogicalTimestamp clock = new LogicalTimestamp().setReplicaId(replicaId).setSequenceNumber(100L);
		when(mockPatchBuilderManager.getCurrentClockIfAllPatchesApplied(sessionId, replicaId))
				.thenReturn(Optional.of(clock));

		File tempFile = File.createTempFile("test-", ".cbor");
		tempFile.deleteOnExit();
		when(mockFileProvider.createTempFile(any(), any())).thenReturn(tempFile);

		ClockTable clockTable = new ClockTable(List.of(clock));
		when(mockGridIndexManager.exportSnapshot(eq(sessionId), eq(replicaId), any(Path.class)))
				.thenReturn(clockTable);

		// call under test
		manager.compactSession(sessionId);

		// The temp file should have been deleted
		assertFalse(tempFile.exists(), "Temp file should be deleted after successful compaction");
	}
}
