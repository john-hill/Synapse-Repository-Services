package org.sagebionetworks.repo.manager.grid;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
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
import org.sagebionetworks.util.ValidateArgument;
import org.sagebionetworks.util.progress.ProgressCallback;
import org.springframework.stereotype.Service;

@Service
public class GridSnapshotCompactionManagerImpl implements GridSnapshotCompactionManager {

	private static final Logger log = LogManager.getLogger(GridSnapshotCompactionManagerImpl.class);

	private final GridDao gridDao;
	private final GridIndexManager gridIndexManager;
	private final GridReplicaPatchBuilderManager patchBuilderManager;
	private final SnapshotStore snapshotStore;
	private final StackConfiguration stackConfiguration;

	public GridSnapshotCompactionManagerImpl(GridDao gridDao, GridIndexManager gridIndexManager,
			GridReplicaPatchBuilderManager patchBuilderManager, SnapshotStore snapshotStore,
			StackConfiguration stackConfiguration) {
		this.gridDao = gridDao;
		this.gridIndexManager = gridIndexManager;
		this.patchBuilderManager = patchBuilderManager;
		this.snapshotStore = snapshotStore;
		this.stackConfiguration = stackConfiguration;
	}

	@Override
	public int compactSessions(ProgressCallback callback) {
		ValidateArgument.required(callback, "callback");

		Duration maxAge = Duration.ofDays(stackConfiguration.getGridSnapshotMaxAgeDays());
		int maxPatchCount = stackConfiguration.getGridSnapshotMaxPatchCount();
		int batchSize = stackConfiguration.getGridSnapshotCompactionBatchSize();

		List<String> sessionIds = gridDao.listSessionsNeedingCompaction(maxAge, maxPatchCount, batchSize);

		if (sessionIds.isEmpty()) {
			log.debug("No grid sessions need snapshot compaction.");
			return 0;
		}

		log.info("Found {} grid session(s) needing snapshot compaction.", sessionIds.size());

		int compactedCount = 0;
		for (String sessionId : sessionIds) {
			try {
				if (compactSession(sessionId)) {
					compactedCount++;
				}
			} catch (Exception e) {
				log.warn("Failed to compact session: {}. Skipping.", sessionId, e);
			}
		}

		log.info("Compacted {} grid session(s).", compactedCount);
		return compactedCount;
	}

	/**
	 * Attempt to compact a single session.
	 *
	 * @param sessionId
	 * @return true if the session was compacted, false if skipped
	 */
	boolean compactSession(String sessionId) {
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
		Path tempFile = null;
		try {
			tempFile = Files.createTempFile("grid-snapshot-", ".cbor");

			// Export the snapshot
			ClockTable clockTable = gridIndexManager.exportSnapshot(sessionId, replicaId, tempFile);

			// Upload to S3 and record in DB
			snapshotStore.saveSnapshot(sessionId, clockTable, createdByUserId, tempFile.toFile());

			log.info("Successfully compacted session {} with {} clock entries.", sessionId,
					clockTable.getClocks().size());
			return true;
		} catch (IOException e) {
			throw new RuntimeException("Failed to create temp file for snapshot export", e);
		} finally {
			// Clean up the temp file
			if (tempFile != null) {
				try {
					Files.deleteIfExists(tempFile);
				} catch (IOException e) {
					log.warn("Failed to delete temp file: {}", tempFile, e);
				}
			}
		}
	}
}
