package org.sagebionetworks.repo.manager.grid.synch;

import java.io.IOException;

public interface RowHeader {
	
	byte[] getHash();
	
	SynchRow fetchRow() throws IOException;
 
}
