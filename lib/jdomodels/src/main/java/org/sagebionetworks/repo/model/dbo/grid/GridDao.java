package org.sagebionetworks.repo.model.dbo.grid;

import java.util.Optional;

import org.sagebionetworks.repo.model.grid.EventSource;
import org.sagebionetworks.repo.model.grid.GridReplica;
import org.sagebionetworks.repo.model.grid.GridSession;

public interface GridDao {

	/**
	 * Create a new grid session.
	 * @param userId
	 * @return
	 */
	GridSession createGridSession(Long userId);
	
	/**
	 * Get the user that started the grid session.
	 * @param gridSessionId
	 * @return
	 */
	Long getGridSessionStartedBy(String gridSessionId);

	/**
	 * Get session by ID.
	 * @param gridSessionId
	 * @return
	 */
	GridSession geGridSession(String gridSessionId);

	/**
	 * Create a new replica.
	 * @param userId
	 * @param gridSessionId
	 * @param isAgent
	 * @param source
	 * @return
	 */
	GridReplica createReplica(Long userId, String gridSessionId, boolean isAgent, EventSource source);

	/**
	 * Get information about a grid replica.
	 * @param sessionId
	 * @param replicaId
	 * @return
	 */
	GridReplica getGridReplica(String sessionId, Long replicaId);
	
	/**
	 * Get the replica createdBy of the replica matching the parameters.
	 * @param sessionId
	 * @param replicaId
	 * @param isAgent
	 * @return
	 */
	Long getReplicaCreatedBy(String sessionId, Long replicaId, boolean isAgentReplica);


}
