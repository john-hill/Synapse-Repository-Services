package org.sagebionetworks.repo.manager.grid.synch;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.sagebionetworks.repo.manager.grid.internal.replica.model.SynapseRow;
import org.sagebionetworks.repo.model.grid.patch.LogicalTimestamp;

public class CopyRowImpl implements CopyRow {

	private SynapseRow synapseRow;
	private LogicalTimestamp rgaNodeId;
	private LogicalTimestamp vectorNodeId;
	private List<CopyCell> cells;

	@Override
	public LogicalTimestamp getRgaNodeId() {
		return rgaNodeId;
	}

	@Override
	public Optional<SynapseRow> getSynapseRow() {
		return Optional.ofNullable(synapseRow);
	}

	@Override
	public LogicalTimestamp getVectorNodeId() {
		return vectorNodeId;
	}

	public CopyRowImpl setRgaNodeId(LogicalTimestamp rgaNodeId) {
		this.rgaNodeId = rgaNodeId;
		return this;
	}

	public List<CopyCell> getCells() {
		return cells;
	}

	public CopyRowImpl setCells(List<CopyCell> cells) {
		this.cells = cells;
		return this;
	}

	public CopyRowImpl setSynapseRow(SynapseRow synapseRow) {
		this.synapseRow = synapseRow;
		return this;
	}

	public CopyRowImpl setVectorNodeId(LogicalTimestamp vectorNodeId) {
		this.vectorNodeId = vectorNodeId;
		return this;
	}

	@Override
	public int hashCode() {
		return Objects.hash(cells, rgaNodeId, synapseRow, vectorNodeId);
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (getClass() != obj.getClass())
			return false;
		CopyRowImpl other = (CopyRowImpl) obj;
		return Objects.equals(cells, other.cells) && Objects.equals(rgaNodeId, other.rgaNodeId)
				&& Objects.equals(synapseRow, other.synapseRow) && Objects.equals(vectorNodeId, other.vectorNodeId);
	}

	@Override
	public String toString() {
		return "CopyRowImpl [synapseRow=" + synapseRow + ", rgaNodeId=" + rgaNodeId + ", vectorNodeId=" + vectorNodeId
				+ ", cells=" + cells + "]";
	}

}
