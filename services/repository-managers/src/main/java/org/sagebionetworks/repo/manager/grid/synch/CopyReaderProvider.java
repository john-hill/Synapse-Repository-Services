package org.sagebionetworks.repo.manager.grid.synch;

import org.sagebionetworks.repo.model.grid.GridSession;

public interface CopyReaderProvider {

	CopyReader createCopyReader(GridSession session);
}
