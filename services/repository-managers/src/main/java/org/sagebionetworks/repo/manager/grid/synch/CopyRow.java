package org.sagebionetworks.repo.manager.grid.synch;

import java.util.List;
import java.util.Optional;

import org.sagebionetworks.repo.manager.grid.internal.replica.model.SynapseRow;
import org.sagebionetworks.repo.model.grid.patch.LogicalTimestamp;

public interface CopyRow {

	Optional<SynapseRow> getSynapseRow();

	LogicalTimestamp getRgaNodeId();

	LogicalTimestamp getVectorNodeId();

	List<CopyCell> getCells();

}
