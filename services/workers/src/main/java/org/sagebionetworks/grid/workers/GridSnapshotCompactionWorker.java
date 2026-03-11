package org.sagebionetworks.grid.workers;

import org.sagebionetworks.repo.manager.grid.GridSnapshotCompactionManager;
import org.sagebionetworks.util.progress.ProgressCallback;
import org.sagebionetworks.util.progress.ProgressingRunner;
import org.springframework.stereotype.Component;

/**
 * Periodic worker that scans for grid sessions needing snapshot compaction
 * and creates new snapshots from the INTERNAL replica's current CRDT state.
 */
@Component
public class GridSnapshotCompactionWorker implements ProgressingRunner {

	private final GridSnapshotCompactionManager compactionManager;

	public GridSnapshotCompactionWorker(GridSnapshotCompactionManager compactionManager) {
		this.compactionManager = compactionManager;
	}

	@Override
	public void run(ProgressCallback callback) throws Exception {
		compactionManager.compactSessions(callback);
	}
}
