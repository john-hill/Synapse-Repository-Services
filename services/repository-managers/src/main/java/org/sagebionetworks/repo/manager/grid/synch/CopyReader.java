package org.sagebionetworks.repo.manager.grid.synch;

import java.util.Iterator;

import org.sagebionetworks.repo.manager.grid.internal.replica.model.GridHeader;
import org.sagebionetworks.repo.model.dbo.grid.GridSource;
import org.sagebionetworks.repo.model.grid.GridConnectionInfo;
import org.sagebionetworks.repo.model.grid.patch.LogicalTimestamp;

public interface CopyReader extends AutoCloseable {

	GridSource getGridSource();

	GridHeader getHeader();

	GridConnectionInfo getConnectionInfo();

	Iterator<CopyRow> getRows();

	LogicalTimestamp getLastRgaNodeId();
}
