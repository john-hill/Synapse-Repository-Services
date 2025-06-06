package org.sagebionetworks.repo.model.grid.patch;

import java.util.List;
import java.util.Objects;

import org.sagebionetworks.repo.model.grid.patch.operation.Operation;

public class Patch {

	private LogicalTimestamp patchId;
	private String metadta;
	private List<Operation> operations;

	public LogicalTimestamp getPatchId() {
		return patchId;
	}

	public Patch setPatchId(LogicalTimestamp patchId) {
		this.patchId = patchId;
		return this;
	}

	public String getMetadta() {
		return metadta;
	}

	public Patch setMetadta(String metadta) {
		this.metadta = metadta;
		return this;
	}

	public List<Operation> getOperations() {
		return operations;
	}

	public Patch setOperations(List<Operation> operations) {
		this.operations = operations;
		return this;
	}

	@Override
	public int hashCode() {
		return Objects.hash(metadta, operations, patchId);
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (getClass() != obj.getClass())
			return false;
		Patch other = (Patch) obj;
		return Objects.equals(metadta, other.metadta) && Objects.equals(operations, other.operations)
				&& Objects.equals(patchId, other.patchId);
	}

	@Override
	public String toString() {
		return "Patch [patchId=" + patchId + ", metadta=" + metadta + ", operations=" + operations + "]";
	}

}
