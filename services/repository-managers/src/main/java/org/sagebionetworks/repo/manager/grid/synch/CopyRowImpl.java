package org.sagebionetworks.repo.manager.grid.synch;

import java.util.Map;
import java.util.Optional;

import org.sagebionetworks.repo.manager.grid.internal.replica.model.SynapseRow;
import org.sagebionetworks.repo.model.grid.patch.ConValue;
import org.sagebionetworks.repo.model.grid.patch.LogicalTimestamp;

public class CopyRowImpl implements CopyRow {

	private final SynapseRow synapseRow;
	private final Map<String, ConValue> data;
    private final Map<String, LogicalTimestamp> cellTimestamps;
	private final LogicalTimestamp rgaNodeId;

	public CopyRowImpl(SynapseRow synapseRow, Map<String, ConValue> data,
			LogicalTimestamp rgaNodeId,  Map<String, LogicalTimestamp> cellTimestamps) {
		super();
		this.synapseRow = synapseRow;
		this.data = data;
		this.rgaNodeId = rgaNodeId;
        this.cellTimestamps = cellTimestamps;
	}

	@Override
	public Map<String, ConValue> getData() {
		return data;
	}


	@Override
	public LogicalTimestamp getArrNodeId() {
		return rgaNodeId;
	}

	@Override
	public Optional<SynapseRow> getSynapseRow() {
		return Optional.ofNullable(synapseRow);
	}

	@Override
	public Map<String, LogicalTimestamp> getCellTimestamps() {
		return cellTimestamps;
	}

}
