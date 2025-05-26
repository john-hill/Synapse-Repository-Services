package org.sagebionetworks.repo.manager.grid;

import org.sagebionetworks.repo.model.UserInfo;

public interface GridManager {

	void sendMessage(String connectionId, String message);

	/**
	 * Generate a presigned URL that can be used to establish a websocket connection
	 * to the provided grid.
	 * 
	 * @param gridSessionId
	 * @param replicaId
	 * @param userer
	 * @return
	 */
	String createWebsocketPresignedUrl(String gridSessionId, int replicaId, UserInfo userer);

}
