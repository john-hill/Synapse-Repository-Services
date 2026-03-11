package org.sagebionetworks.repo.manager.grid;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.sagebionetworks.StackConfiguration;
import org.sagebionetworks.grid.db.GridIndexManager;
import org.sagebionetworks.repo.model.dbo.grid.GridDao;
import org.sagebionetworks.repo.model.grid.ClockTable;
import org.sagebionetworks.repo.model.grid.EventSource;
import org.sagebionetworks.repo.model.grid.GridConnectionInfo;
import org.sagebionetworks.repo.model.grid.patch.LogicalTimestamp;
import org.sagebionetworks.repo.manager.grid.internal.replica.change.GridReplicaPatchBuilderManager;
import org.sagebionetworks.util.FileProvider;
import org.sagebionetworks.util.ValidateArgument;
import org.springframework.stereotype.Service;

import com.amazonaws.services.sqs.AmazonSQS;
import com.amazonaws.services.sqs.model.SendMessageRequest;

@Service
public class GridSnapshotCompactionManagerImpl implements GridSnapshotCompactionManager {

	static final String COMPACTION_QUEUE_NAME = "GRID_SNAPSHOT_COMPACTION";

	private static final Logger log = LogManager.getLogger(GridSnapshotCompactionManagerImpl.class);

	private final GridDao gridDao;
	private final GridIndexManager gridIndexManager;
	private final GridReplicaPatchBuilderManager patchBuilderManager;
	private final SnapshotStore snapshotStore;
	private final StackConfiguration stackConfiguration;
	private final FileProvider fileProvider;
	private final AmazonSQS sqsClient;
	private final String sqsQueueUrl;

	public GridSnapshotCompactionManagerImpl(GridDao gridDao, GridIndexManager gridIndexManager,
			GridReplicaPatchBuilderManager patchBuilderManager, SnapshotStore snapshotStore,
			StackConfiguration stackConfiguration, FileProvider fileProvider, AmazonSQS sqsClient) {
		this.gridDao = gridDao;
		this.gridIndexManager = gridIndexManager;
		this.patchBuilderManager = patchBuilderManager;
		this.snapshotStore = snapshotStore;
		this.stackConfiguration = stackConfiguration;
		this.fileProvider = fileProvider;
		this.sqsClient = sqsClient;
		this.sqsQueueUrl = sqsClient.getQueueUrl(stackConfiguration.getQueueName(COMPACTION_QUEUE_NAME)).getQueueUrl();
	}

	@Override
	public List<String> scanAndPublishSessionsNeedingCompaction() {
		Duration maxAge = Duration.ofDays(stackConfiguration.getGridSnapshotMaxAgeDays());
		int maxPatchCount = stackConfiguration.getGridSnapshotMaxPatchCount();
		int batchSize = stackConfiguration.getGridSnapshotCompactionBatchSize();

		List<String> sessionIds = gridDao.listSessionsNeedingCompaction(maxAge, maxPatchCount, batchSize);

		if (sessionIds.isEmpty()) {
			log.debug("No grid sessions need snapshot compaction.");
			return Collections.emptyList();
		}

		log.info("Found {} grid session(s) needing snapshot compaction. Publishing to queue.", sessionIds.size());

		for (String sessionId : sessionIds) {
			sqsClient.sendMessage(new SendMessageRequest()
					.withQueueUrl(sqsQueueUrl)
					.withMessageBody(sessionId));
		}

		return sessionIds;
	}

	@Override
	public boolean compactSession(String sessionId) {
		ValidateArgument.required(sessionId, "sessionId");

		// Get the INTERNAL connection
		Optional<GridConnectionInfo> internalConnection = gridDao.getSingletonConnection(sessionId,
				EventSource.INTERNAL);
		if (!internalConnection.isPresent()) {
			log.debug("Session {} has no INTERNAL connection. Skipping.", sessionId);
			return false;
		}

		GridConnectionInfo connection = internalConnection.get();
		Long replicaId = connection.getReplicaId();
		Long createdByUserId = connection.getCreatedBy();

		// Check if the replica is fully synchronized
		Optional<LogicalTimestamp> currentClock = patchBuilderManager.getCurrentClockIfAllPatchesApplied(sessionId,
				replicaId);
		if (!currentClock.isPresent()) {
			log.debug("Session {} replica {} is not fully synchronized. Skipping.", sessionId, replicaId);
			return false;
		}

		// Create a temp file for the snapshot
		File tempFile = null;
		try {
			tempFile = fileProvider.createTempFile("grid-snapshot-", ".cbor");

			// Export the snapshot
			ClockTable clockTable = gridIndexManager.exportSnapshot(sessionId, replicaId, tempFile.toPath());

			// Upload to S3 and record in DB
			snapshotStore.saveSnapshot(sessionId, clockTable, createdByUserId, tempFile);

			log.info("Successfully compacted session {} with {} clock entries.", sessionId,
					clockTable.getClocks().size());
			return true;
		} catch (IOException e) {
			throw new RuntimeException("Failed to create temp file for snapshot export", e);
		} finally {
			// Clean up the temp file
			if (tempFile != null) {
				tempFile.delete();
			}
		}
	}
}
