package org.sagebionetworks.repo.manager.grid;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.sagebionetworks.StackConfiguration;
import org.sagebionetworks.repo.model.dbo.grid.GridDao;
import org.sagebionetworks.repo.model.grid.EventSource;
import org.sagebionetworks.repo.model.grid.GridConnectionInfo;
import org.springframework.stereotype.Service;

import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlRequest;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

@Service
public class GridSessionSnapshotPublisherImpl implements GridSessionSnapshotPublisher {

	static final String INTERNAL_EVENT_QUEUE_NAME = "GRID_INTERNAL_EVENT.fifo";
	static final String NEW_SNAPSHOT_NOTIFICATION = "[8,\"new-snapshot\"]";

	private static final Logger log = LogManager.getLogger(GridSessionSnapshotPublisherImpl.class);

	private final GridDao gridDao;
	private final StackConfiguration stackConfiguration;
	private final SqsClient sqsClient;
	private final String sqsQueueUrl;

	public GridSessionSnapshotPublisherImpl(GridDao gridDao, StackConfiguration stackConfiguration,
	                                        SqsClient sqsClient) {
		this.gridDao = gridDao;
		this.stackConfiguration = stackConfiguration;
		this.sqsClient = sqsClient;
		this.sqsQueueUrl = sqsClient
				.getQueueUrl(GetQueueUrlRequest.builder()
						.queueName(stackConfiguration.getQueueName(INTERNAL_EVENT_QUEUE_NAME)).build())
				.queueUrl();
	}

	@Override
	public List<String> scanAndPublishSessionsNeedingSnapshot() {
		Duration maxAge = Duration.ofDays(stackConfiguration.getGridSnapshotMaxAgeDays());
		int maxPatchCount = stackConfiguration.getGridSnapshotMaxPatchCount();
		int batchSize = stackConfiguration.getGridSessionSnapshotPublisherBatchSize();

		List<String> sessionIds = gridDao.listSessionsNeedingSnapshot(maxAge, maxPatchCount, batchSize);

		if (sessionIds.isEmpty()) {
			log.debug("No grid sessions require a new snapshot.");
			return Collections.emptyList();
		}

		log.info("Found {} grid session(s) needing a new snapshot. Publishing to FIFO queue.", sessionIds.size());

		List<String> sessionsPublished = new ArrayList<>();
		for (String sessionId : sessionIds) {
			Optional<GridConnectionInfo> internalConnection = gridDao.getSingletonConnection(sessionId,
					EventSource.INTERNAL);
			if (!internalConnection.isPresent()) {
				log.debug("Session {} has no INTERNAL connection. Skipping.", sessionId);
				continue;
			}
			String connectionId = internalConnection.get().getConnectionId();
			sqsClient.sendMessage(SendMessageRequest.builder()
					.queueUrl(sqsQueueUrl)
					.messageBody(NEW_SNAPSHOT_NOTIFICATION)
					.messageGroupId(connectionId)
					.messageDeduplicationId(UUID.randomUUID().toString())
					.messageAttributes(Map.of("ConnectionId", MessageAttributeValue.builder()
							.stringValue(connectionId).dataType("String").build()))
					.build());
			sessionsPublished.add(sessionId);
		}

		return sessionsPublished;
	}
}
