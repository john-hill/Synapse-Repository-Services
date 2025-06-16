package org.sagebionetworks.repo.model.grid.patch.operation;

import java.util.Objects;

import org.sagebionetworks.repo.model.grid.patch.LogicalTimestamp;

public class NewBinary implements Operation {

	private LogicalTimestamp id;

	@Override
	public OperationType getType() {
		return OperationType.new_bin;
	}

	@Override
	public LogicalTimestamp getOperationId() {
		return id;
	}

	@Override
	public long span() {
		return 1L;
	}

	public NewBinary setId(LogicalTimestamp id) {
		this.id = id;
		return this;
	}

	@Override
	public int hashCode() {
		return Objects.hash(id);
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (getClass() != obj.getClass())
			return false;
		NewBinary other = (NewBinary) obj;
		return Objects.equals(id, other.id);
	}

	@Override
	public String toString() {
		return "NewBinary [id=" + id + "]";
	}

}
