package org.sagebionetworks.repo.manager.grid;

import java.util.List;

public interface GridSnapshotCompactionManager {

	/**
	 * Scan for sessions needing compaction and publish a {@code [8,"new-snapshot"]}
	 * notification to the {@code GRID_INTERNAL_EVENT.fifo} queue for each session,
	 * using the session's INTERNAL connection ID as the FIFO message group ID.
	 *
	 * @return The list of session IDs that were published for snapshot export.
	 */
	List<String> scanAndPublishSessionsNeedingCompaction();
}
