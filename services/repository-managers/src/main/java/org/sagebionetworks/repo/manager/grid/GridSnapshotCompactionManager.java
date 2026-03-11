package org.sagebionetworks.repo.manager.grid;

import org.sagebionetworks.util.progress.ProgressCallback;

public interface GridSnapshotCompactionManager {

	/**
	 * Scan for sessions needing compaction and create snapshots.
	 *
	 * @param callback Progress callback to refresh semaphore lock
	 * @return The number of sessions compacted in this run.
	 */
	int compactSessions(ProgressCallback callback);
}
