package org.sagebionetworks.repo.manager.grid;

import org.sagebionetworks.repo.model.UserInfo;
import org.sagebionetworks.repo.model.grid.CreateGridPresignedUrlRequest;
import org.sagebionetworks.repo.model.grid.CreateGridPresignedUrlResponse;
import org.sagebionetworks.repo.model.grid.CreateGridRequest;
import org.sagebionetworks.repo.model.grid.CreateGridResponse;
import org.sagebionetworks.repo.model.grid.CreateReplicaRequest;
import org.sagebionetworks.repo.model.grid.CreateReplicaResponse;
import org.sagebionetworks.repo.model.grid.EventSource;
import org.sagebionetworks.repo.model.grid.GridReplica;
import org.sagebionetworks.repo.model.grid.GridSession;

public interface GridManager {

	/**
	 * Create a new grid session.
	 * 
	 * @param user
	 * @param request
	 * @return
	 */
	CreateGridResponse createGrid(UserInfo user, CreateGridRequest request);

	/**
	 * Get information about a grid session.
	 * 
	 * @param user
	 * @param gridSessionId
	 * @return
	 */
	GridSession getGridSession(UserInfo user, String gridSessionId);

	/**
	 * Create a new replica from from a request.
	 * 
	 * @param user
	 * @param request
	 * @return
	 */
	CreateReplicaResponse createReplica(UserInfo user, CreateReplicaRequest request);

	/**
	 * Create replica from an internal source.
	 * 
	 * @param user
	 * @param gridSessionId
	 * @param isAgent
	 * @param source
	 * @return
	 */
	CreateReplicaResponse createReplica(UserInfo user, String gridSessionId, boolean isAgent, EventSource source);

	/**
	 * 
	 * Get the identified replica.
	 * 
	 * @param user
	 * @param sessionId
	 * @param repicaId
	 * @return
	 */
	GridReplica getReplica(UserInfo user, String sessionId, Long repicaId);

	/**
	 * Create new presigned URL to establish a websocket connection to the grid.
	 * 
	 * @param user
	 * @param request
	 * @return
	 */
	CreateGridPresignedUrlResponse createWebsocketPresignedUrl(UserInfo user, CreateGridPresignedUrlRequest request);

}
