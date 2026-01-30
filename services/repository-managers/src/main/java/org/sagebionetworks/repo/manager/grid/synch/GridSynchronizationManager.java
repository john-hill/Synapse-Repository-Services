package org.sagebionetworks.repo.manager.grid.synch;

import org.sagebionetworks.repo.model.UserInfo;
import org.sagebionetworks.repo.model.dao.asynch.AsyncJobProgressCallback;
import org.sagebionetworks.repo.model.grid.GridSession;

public interface GridSynchronizationManager {

	void synchronizeCopyWithSource(AsyncJobProgressCallback callback, UserInfo user, GridSession session)
			throws Exception;

}
