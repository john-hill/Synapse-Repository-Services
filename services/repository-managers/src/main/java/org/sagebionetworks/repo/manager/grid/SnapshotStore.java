package org.sagebionetworks.repo.manager.grid;

import org.sagebionetworks.repo.model.grid.ClockTable;
import org.sagebionetworks.repo.model.grid.patch.LogicalTimestamp;

public interface SnapshotStore {

	/**
	 * Save a snapshot that has already been uploaded to S3.
	 *
	 * @param sessionId The grid session ID
	 * @param clockTable The clock table representing the state of the snapshot
	 * @param s3Key The S3 key where the snapshot file is stored
	 * @param createdByUserId The user who created the snapshot
	 */
	void saveSnapshot(String sessionId, ClockTable clockTable, String s3Key, Long createdByUserId);

}
