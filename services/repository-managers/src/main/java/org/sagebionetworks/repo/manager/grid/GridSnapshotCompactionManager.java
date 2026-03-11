package org.sagebionetworks.repo.manager.grid;

import java.util.List;

public interface GridSnapshotCompactionManager {

	/**
	 * Scan for sessions needing compaction and publish each session ID to the
	 * compaction SQS queue for individual processing.
	 *
	 * @return The list of session IDs that were published for compaction.
	 */
	List<String> scanAndPublishSessionsNeedingCompaction();

	/**
	 * Compact a single grid session by exporting a new snapshot from the INTERNAL
	 * replica's current state, uploading it to S3, and recording it in the main
	 * database.
	 *
	 * @param sessionId The session to compact.
	 * @return true if the session was compacted, false if it was skipped (e.g.,
	 *         no INTERNAL connection, replica not synchronized).
	 */
	boolean compactSession(String sessionId);
}
