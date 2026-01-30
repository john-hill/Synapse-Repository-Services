package org.sagebionetworks.repo.manager.grid.synch;

import org.sagebionetworks.repo.model.UserInfo;
import org.sagebionetworks.repo.model.dao.asynch.AsyncJobProgressCallback;
import org.sagebionetworks.repo.model.dbo.grid.GridSource;
import org.sagebionetworks.repo.model.grid.GridSession;

public interface SourceHandlerProvdier {

	/**
	 * Create a new handler for this provided source.
	 * 
	 * @param gridSource
	 * @return
	 */
	SourceHandler createNewProvider(AsyncJobProgressCallback callback, UserInfo user, GridSession session,
			GridSource gridSource);


}
