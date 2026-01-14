package org.sagebionetworks.repo.manager.grid.internal.replica.synch;

import org.sagebionetworks.repo.model.UserInfo;
import org.sagebionetworks.repo.model.grid.GridViewSynchronizationRequest;
import org.sagebionetworks.repo.model.grid.GridViewSynchronizationResponse;

public interface GridToViewSynchronizationManager {

	GridViewSynchronizationResponse synchronize(UserInfo user, GridViewSynchronizationRequest request);

}
