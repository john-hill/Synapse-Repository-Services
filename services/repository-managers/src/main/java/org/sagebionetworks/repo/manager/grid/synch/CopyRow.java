package org.sagebionetworks.repo.manager.grid.synch;

import java.util.Map;
import java.util.Optional;

import org.sagebionetworks.repo.manager.grid.internal.replica.model.SynapseRow;
import org.sagebionetworks.repo.model.grid.patch.ConValue;
import org.sagebionetworks.repo.model.grid.patch.LogicalTimestamp;

public interface CopyRow {

	Optional<SynapseRow> getSynapseRow();

	Map<String, ConValue> getData();

	Map<String, LogicalTimestamp> getCellTimestamps();

	LogicalTimestamp getArrNodeId();
	
}
