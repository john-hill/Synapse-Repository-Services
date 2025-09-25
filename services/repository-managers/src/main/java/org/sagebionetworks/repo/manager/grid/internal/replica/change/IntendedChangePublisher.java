package org.sagebionetworks.repo.manager.grid.internal.replica.change;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.sagebionetworks.repo.model.grid.GridConnectionInfo;

/**
 * Allows to automatically batch {@link IntendedChange}s into {@link IntendedChangeSet}s of optimal size.
 */
public class IntendedChangePublisher implements AutoCloseable {
	
	private static int getChangeSize(IntendedChange change) {
		return change.toJson().toString().getBytes(StandardCharsets.UTF_8).length + CHANGE_OVERHEAD_BYTES;
	}

	// 1MB is the max size, See
	// https://docs.aws.amazon.com/AWSSimpleQueueService/latest/SQSDeveloperGuide/quotas-messages.html
	// Set it to 768KB so that we do not have to worry about the overhead of the message wrapper.
	private static final int MAX_CHANGE_SET_SIZE = (1024 * 1024) - 256; // 768KB
	private static final int CHANGE_OVERHEAD_BYTES = 4; // Overhead for JSON array commas and brackets.

	private final GridConnectionInfo connInfo;
	private final Long maxClockSeq;
	private final PatchBuilderPublisher publisher;
	
	private List<IntendedChange> currentChanges;
	private IntendedChangeSet currentChangeSet;
	private int currentSizeBytes;

	public IntendedChangePublisher(GridConnectionInfo connInfo, Long maxClockSeq, PatchBuilderPublisher publisher) {
		this.connInfo = connInfo;
		this.maxClockSeq = maxClockSeq;
		this.publisher = publisher;
		this.resetCurrentChangeSet();
	}

	public void publish(IntendedChange change) {
		
		int changeSize = getChangeSize(change);

		if (currentSizeBytes + changeSize > MAX_CHANGE_SET_SIZE) {
			if (currentChanges.isEmpty()) {
				// A single change is too big to fit in a change set.
				throw new IllegalArgumentException("A single change cannot be larger than " + MAX_CHANGE_SET_SIZE + " bytes.");
			}
			// publish the current set
			publisher.sendChangesToPatchBuilder(currentChangeSet);
			// start a new set
			resetCurrentChangeSet();
		}

		currentChanges.add(change);
		currentSizeBytes += changeSize;
	}

	@Override
	public void close() throws Exception {
		if (currentChanges.isEmpty()) {
			return;
		}
		publisher.sendChangesToPatchBuilder(currentChangeSet);
	}

	private void resetCurrentChangeSet() {
		this.currentChanges = new ArrayList<>();
		this.currentChangeSet = new IntendedChangeSet()
			.setSessionId(connInfo.getSessionId())
			.setConnectionId(connInfo.getConnectionId())
			.setReplicaId(connInfo.getReplicaId())
			.setClockSequenceMaximum(maxClockSeq)
			.setChanges(currentChanges);
		this.currentSizeBytes = 0;
	}

}
