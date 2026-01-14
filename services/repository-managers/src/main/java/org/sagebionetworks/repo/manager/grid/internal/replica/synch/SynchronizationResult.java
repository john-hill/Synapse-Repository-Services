package org.sagebionetworks.repo.manager.grid.internal.replica.synch;

import java.util.List;
import java.util.Objects;

import org.sagebionetworks.repo.manager.grid.internal.replica.change.IntendedChange;
import org.sagebionetworks.repo.model.grid.EntitySynchronizationStatus;

public class SynchronizationResult {

	private final EntitySynchronizationStatus status;
	private final List<IntendedChange> gridChanges;

	public SynchronizationResult(EntitySynchronizationStatus status, List<IntendedChange> gridChanges) {
		super();
		this.status = status;
		this.gridChanges = gridChanges;
	}

	public EntitySynchronizationStatus getStatus() {
		return status;
	}

	public List<IntendedChange> getGridChanges() {
		return gridChanges;
	}

	@Override
	public int hashCode() {
		return Objects.hash(gridChanges, status);
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (getClass() != obj.getClass())
			return false;
		SynchronizationResult other = (SynchronizationResult) obj;
		return Objects.equals(gridChanges, other.gridChanges) && Objects.equals(status, other.status);
	}

	@Override
	public String toString() {
		return "SynchronizationResult [status=" + status + ", gridChanges=" + gridChanges + "]";
	}

}
