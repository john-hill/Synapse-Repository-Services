package org.sagebionetworks.repo.model.grid.patch.operation;

import java.util.Objects;

import org.sagebionetworks.repo.model.grid.patch.LogicalTimestamp;

public class NewVector implements Operation {

	private LogicalTimestamp id;

	@Override
	public OperationType getType() {
		return OperationType.new_vec;
	}

	@Override
	public LogicalTimestamp getOperationId() {
		return id;
	}

	@Override
	public long span() {
		return 1L;
	}

	public NewVector setId(LogicalTimestamp id) {
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
		NewVector other = (NewVector) obj;
		return Objects.equals(id, other.id);
	}

	@Override
	public String toString() {
		return "NewVector [id=" + id + "]";
	}

}
