package org.sagebionetworks.repo.manager.grid.synch;

import java.util.Iterator;

import org.sagebionetworks.repo.manager.grid.internal.replica.model.GridHeader;
import org.sagebionetworks.repo.model.dbo.grid.GridSource;
import org.sagebionetworks.repo.model.grid.GridConnectionInfo;
import org.sagebionetworks.repo.model.grid.patch.LogicalTimestamp;

public class TestStubCopyReader implements CopyReader {

	@Override
	public void close() throws Exception {
		// TODO Auto-generated method stub
		
	}

	@Override
	public GridSource getGridSource() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public GridHeader getHeader() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public GridConnectionInfo getConnectionInfo() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public Iterator<CopyRow> getRows() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public LogicalTimestamp getLastRgaNodeId() {
		// TODO Auto-generated method stub
		return null;
	}

}
