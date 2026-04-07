package org.sagebionetworks.grid.workers;

import org.sagebionetworks.repo.manager.grid.GridSessionSnapshotPublisher;
import org.sagebionetworks.util.progress.ProgressCallback;
import org.sagebionetworks.util.progress.ProgressingRunner;
import org.springframework.stereotype.Component;

/**
 * Periodic worker that scans for grid sessions that need a new snapshot and
 * publishes {@code [8,"new-snapshot"]} notifications to the
 * {@code GRID_INTERNAL_EVENT.fifo} queue for each session.
 */
@Component
public class GridSessionSnapshotPublisherWorker implements ProgressingRunner {

	private final GridSessionSnapshotPublisher snapshotPublisher;

	public GridSessionSnapshotPublisherWorker(GridSessionSnapshotPublisher snapshotPublisher) {
		this.snapshotPublisher = snapshotPublisher;
	}

	@Override
	public void run(ProgressCallback callback) throws Exception {
		snapshotPublisher.scanAndPublishSessionsNeedingSnapshot();
	}
}
