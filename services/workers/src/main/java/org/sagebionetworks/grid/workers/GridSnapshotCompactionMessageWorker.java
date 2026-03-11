package org.sagebionetworks.grid.workers;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.sagebionetworks.repo.manager.grid.GridSnapshotCompactionManager;
import org.sagebionetworks.util.progress.ProgressCallback;
import org.sagebionetworks.workers.util.aws.message.MessageDrivenRunner;
import org.sagebionetworks.workers.util.aws.message.RecoverableMessageException;
import org.springframework.stereotype.Service;

import com.amazonaws.services.sqs.model.Message;

/**
 * Message-driven worker that compacts a single grid session. Receives the
 * session ID as the plain-text message body from the compaction SQS queue,
 * published by {@link GridSnapshotCompactionWorker}.
 */
@Service
public class GridSnapshotCompactionMessageWorker implements MessageDrivenRunner {

	private static final Logger log = LogManager.getLogger(GridSnapshotCompactionMessageWorker.class);

	private final GridSnapshotCompactionManager compactionManager;

	public GridSnapshotCompactionMessageWorker(GridSnapshotCompactionManager compactionManager) {
		this.compactionManager = compactionManager;
	}

	@Override
	public void run(ProgressCallback progressCallback, Message message) throws RecoverableMessageException, Exception {
		String sessionId = message.getBody();
		try {
			boolean compacted = compactionManager.compactSession(sessionId);
			if (compacted) {
				log.info("Successfully compacted grid session {}.", sessionId);
			} else {
				log.info("Skipped compaction for grid session {} (not ready or no INTERNAL connection).", sessionId);
			}
		} catch (RecoverableMessageException e) {
			throw e;
		} catch (Exception e) {
			log.error("Failed to compact grid session {}: {}", sessionId, e.getMessage(), e);
		}
	}
}
