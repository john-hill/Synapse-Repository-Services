package org.sagebionetworks.grid.db;

import java.util.Optional;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.sagebionetworks.repo.model.grid.patch.LogicalTimestamp;
import org.sagebionetworks.repo.model.grid.patch.Patch;
import org.sagebionetworks.util.ValidateArgument;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true, propagation = Propagation.REQUIRED, isolation = Isolation.READ_COMMITTED)
public class GridIndexManagerImpl implements GridIndexManager {

	private static final Logger log = LogManager.getLogger(GridIndexManagerImpl.class);

	private final GridIndexDao dao;
	private final OperationDispatcher operationDispatcher;

	public GridIndexManagerImpl(GridIndexDao dao, OperationDispatcher operationDispatcher) {
		super();
		this.dao = dao;
		this.operationDispatcher = operationDispatcher;
	}

	@Transactional(readOnly = false)
	@Override
	public void applyPatch(String sessionId, Long replicaId, Patch patch) {
		ValidateArgument.required(patch, "patch");
		ValidateArgument.required(patch.getOperations(), "patch.operations");

		dao.createReplicaIfNotExists(sessionId, replicaId);

		Optional<LogicalTimestamp> opClock = dao.getClock(sessionId, replicaId, patch.getPatchId().getReplicaId());
		if (opClock.isPresent() && patch.getPatchId().getSequenceNumber() <= opClock.get().getSequenceNumber()) {
			log.info("Patch: {}.{} has already been applied to session: {} replica: {}",
					patch.getPatchId().getReplicaId(), patch.getPatchId().getSequenceNumber(), sessionId, replicaId);
			return;
		}

		operationDispatcher.processAll(sessionId, replicaId, patch.getOperations());
		
		dao.setClock(sessionId, replicaId, LogicalTimestamp.newIncrement(patch.getPatchId(), patch.getSpan()));
	}

}
