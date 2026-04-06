package org.sagebionetworks.repo.manager.grid;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import org.sagebionetworks.repo.model.dbo.grid.GridDao;
import org.sagebionetworks.repo.model.grid.EventSource;
import org.sagebionetworks.repo.model.grid.GridConnectionInfo;

import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlResponse;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

@ExtendWith(MockitoExtension.class)
public class GridSnapshotCompactionManagerImplTest {

	@Mock
	private GridDao mockGridDao;
	@Mock
	private StackConfiguration mockStackConfig;
	@Mock
	private SqsClient mockSqsClient;

	private GridSnapshotCompactionManagerImpl manager;

	private String sessionId;
	private String connectionId;

	private static final String QUEUE_URL = "https://sqs.us-east-1.amazonaws.com/123456789/dev-test-GRID_INTERNAL_EVENT.fifo";

	@BeforeEach
	public void before() {
		sessionId = "session-123";
		connectionId = "con-abc";

		when(mockStackConfig.getQueueName(GridSnapshotCompactionManagerImpl.INTERNAL_EVENT_QUEUE_NAME))
				.thenReturn("dev-test-GRID_INTERNAL_EVENT.fifo");
		when(mockSqsClient.getQueueUrl(any(GetQueueUrlRequest.class)))
				.thenReturn(GetQueueUrlResponse.builder().queueUrl(QUEUE_URL).build());

		manager = new GridSnapshotCompactionManagerImpl(mockGridDao, mockStackConfig, mockSqsClient);
	}

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
	public void testScanAndPublishWithOneSession() {
		when(mockStackConfig.getGridSnapshotMaxAgeDays()).thenReturn(30);
		when(mockStackConfig.getGridSnapshotMaxPatchCount()).thenReturn(1000);
		when(mockStackConfig.getGridSnapshotCompactionBatchSize()).thenReturn(10);
		when(mockGridDao.listSessionsNeedingCompaction(Duration.ofDays(30), 1000, 10))
				.thenReturn(List.of(sessionId));

		GridConnectionInfo connection = new GridConnectionInfo().setSessionId(sessionId)
				.setConnectionId(connectionId).setSource(EventSource.INTERNAL);
		when(mockGridDao.getSingletonConnection(sessionId, EventSource.INTERNAL))
				.thenReturn(Optional.of(connection));

		// call under test
		List<String> result = manager.scanAndPublishSessionsNeedingCompaction();

		assertEquals(List.of(sessionId), result);

		ArgumentCaptor<SendMessageRequest> captor = ArgumentCaptor.forClass(SendMessageRequest.class);
		verify(mockSqsClient).sendMessage(captor.capture());
		SendMessageRequest request = captor.getValue();
		assertEquals(QUEUE_URL, request.queueUrl());
		assertEquals(GridSnapshotCompactionManagerImpl.NEW_SNAPSHOT_NOTIFICATION, request.messageBody());
		assertEquals(connectionId, request.messageGroupId());
		assertEquals(connectionId,
				request.messageAttributes().get("ConnectionId").stringValue());
	}

	@Test
	public void testScanAndPublishWithMultipleSessions() {
		String sessionId2 = "session-456";
		String connectionId2 = "con-def";

		when(mockStackConfig.getGridSnapshotMaxAgeDays()).thenReturn(30);
		when(mockStackConfig.getGridSnapshotMaxPatchCount()).thenReturn(1000);
		when(mockStackConfig.getGridSnapshotCompactionBatchSize()).thenReturn(10);
		when(mockGridDao.listSessionsNeedingCompaction(Duration.ofDays(30), 1000, 10))
				.thenReturn(List.of(sessionId, sessionId2));

		when(mockGridDao.getSingletonConnection(sessionId, EventSource.INTERNAL))
				.thenReturn(Optional.of(new GridConnectionInfo().setSessionId(sessionId)
						.setConnectionId(connectionId).setSource(EventSource.INTERNAL)));
		when(mockGridDao.getSingletonConnection(sessionId2, EventSource.INTERNAL))
				.thenReturn(Optional.of(new GridConnectionInfo().setSessionId(sessionId2)
						.setConnectionId(connectionId2).setSource(EventSource.INTERNAL)));

		// call under test
		List<String> result = manager.scanAndPublishSessionsNeedingCompaction();

		assertEquals(List.of(sessionId, sessionId2), result);

		ArgumentCaptor<SendMessageRequest> captor = ArgumentCaptor.forClass(SendMessageRequest.class);
		verify(mockSqsClient, times(2)).sendMessage(captor.capture());
		List<SendMessageRequest> requests = captor.getAllValues();
		assertEquals(connectionId, requests.get(0).messageGroupId());
		assertEquals(connectionId2, requests.get(1).messageGroupId());
	}

	@Test
	public void testScanAndPublishSkipsSessionWithNoInternalConnection() {
		when(mockStackConfig.getGridSnapshotMaxAgeDays()).thenReturn(30);
		when(mockStackConfig.getGridSnapshotMaxPatchCount()).thenReturn(1000);
		when(mockStackConfig.getGridSnapshotCompactionBatchSize()).thenReturn(10);
		when(mockGridDao.listSessionsNeedingCompaction(Duration.ofDays(30), 1000, 10))
				.thenReturn(List.of(sessionId));

		when(mockGridDao.getSingletonConnection(sessionId, EventSource.INTERNAL))
				.thenReturn(Optional.empty());

		// call under test
		List<String> result = manager.scanAndPublishSessionsNeedingCompaction();

		assertEquals(List.of(sessionId), result);
		verify(mockSqsClient, never()).sendMessage(any(SendMessageRequest.class));
	}
}
