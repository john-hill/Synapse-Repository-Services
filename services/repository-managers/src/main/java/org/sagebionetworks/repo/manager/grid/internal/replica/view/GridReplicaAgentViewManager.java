package org.sagebionetworks.repo.manager.grid.internal.replica.view;

import org.sagebionetworks.repo.model.UserInfo;
import org.sagebionetworks.repo.model.grid.sql.GridQueryRequest;
import org.sagebionetworks.repo.model.grid.sql.GridQueryResponse;

public interface GridReplicaAgentViewManager {

	/**
	 * Run query against the grid.
	 * 
	 * @param user
	 * @param request
	 * @return
	 */
	GridQueryResponse queryGrid(UserInfo user, GridQueryRequest request);

}
