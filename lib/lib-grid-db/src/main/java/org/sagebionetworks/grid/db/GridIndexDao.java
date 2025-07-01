package org.sagebionetworks.grid.db;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

import org.sagebionetworks.repo.model.grid.node.ConstantNode;
import org.sagebionetworks.repo.model.grid.node.IndexNode;
import org.sagebionetworks.repo.model.grid.node.IndexType;
import org.sagebionetworks.repo.model.grid.node.ObjectNode;
import org.sagebionetworks.repo.model.grid.patch.LogicalTimestamp;
import org.sagebionetworks.repo.model.grid.patch.operation.NewVector;

public interface GridIndexDao {

	/**
	 * Create a new Replica, if it does not already exist. This the first step to
	 * start a new replica.
	 * 
	 * @param sessionId
	 * @param replicaId
	 */
	void createReplicaIfNotExists(String sessionId, Long replicaId);

	/**
	 * Get the created on for a grid replica.
	 * 
	 * @param sessionId
	 * @param replicaId
	 * @return
	 */
	Optional<Timestamp> getReplicaCreatedOn(String sessionId, Long replicaId);

	/**
	 * Delete a replica and all of its data.
	 * 
	 * @param sessionId
	 * @param replicaId
	 */
	void deleteReplica(String sessionId, Long replicaId);

	/**
	 * Get the a replica's full clock.
	 * 
	 * @param sessionId
	 * @param replicaId
	 * @return
	 */
	List<LogicalTimestamp> getClock(String sessionId, Long replicaId);

	/**
	 * Get the current sequence number for a clock replica ID if it exists.
	 * 
	 * @param sessionId
	 * @param replicaId
	 * @param clockIdRep
	 * @return {@link Optional#empty()} if there is no clock for the provided
	 *         patchId.
	 */
	Optional<Long> getClockSequenceNumber(String sessionId, Long replicaId, Long clockIdRep);

	/**
	 * Save a batch of Index objects to a replica.
	 * 
	 * @param sessionId
	 * @param replicaId
	 * @param type      The type of objects in the batch.
	 * @param batch     The batch of timestamps to save.
	 */
	void saveIndex(String sessionId, Long replicaId, IndexType type, List<LogicalTimestamp> batch);

	/**
	 * Get a list of indices given their IDs.
	 * 
	 * @param sessionId
	 * @param replicaId
	 * @param ids
	 * @return
	 */
	List<IndexNode> getIndices(String sessionId, Long replicaId, List<LogicalTimestamp> ids);

	/**
	 * Save a batch of {@link ObjectNode} to a replica.
	 * 
	 * @param sessionId
	 * @param replicaId
	 * @param batch
	 */
	void saveNewObjects(String sessionId, Long replicaId, List<ObjectNode> batch);

	/**
	 * Get a batch of {@link ObjectNode} given their Ids.
	 * 
	 * @param sessionId
	 * @param replicaId
	 * @param ids
	 * @return
	 */
	List<ObjectNode> getObjects(String sessionId, Long replicaId, List<LogicalTimestamp> ids);

	/**
	 * Save a batch of {@link NewVector} to a replica.
	 * 
	 * @param sessionId
	 * @param replicaId
	 * @param batch
	 */
	void saveNewVectors(String sessionId, Long replicaId, List<NewVector> batch);

	/**
	 * Save a batch of {@link ConstantNode} to a replica.
	 * 
	 * @param sessionId
	 * @param replicaId
	 * @param batch
	 */
	void saveNewConstants(String sessionId, Long replicaId, List<ConstantNode> batch);

	/**
	 * Get a batch of {@link ConstantNode} given their Ids.
	 * 
	 * @param sessionId
	 * @param replicaId
	 * @param ids
	 * @return
	 */
	List<ConstantNode> getConstants(String sessionId, Long replicaId, List<LogicalTimestamp> ids);

	/**
	 * Set a single clock value for a replica.
	 * 
	 * @param sessionId
	 * @param replicaId
	 * @param clock
	 */
	void setClock(String sessionId, Long replicaId, LogicalTimestamp clock);

	void truncateAll();

}
