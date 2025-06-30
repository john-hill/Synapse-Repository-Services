package org.sagebionetworks.grid.db;

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
@Transactional(readOnly = true, propagation = Propagation.REQUIRED, isolation = Isolation.READ_COMMITTED, transactionManager = "gridTransactionManager")
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

		if (isPatchAlreadyApplied(sessionId, replicaId, patch.getPatchId())) {
			log.info("Patch: {}.{} has already been applied to session: {} replica: {}",
					patch.getPatchId().getReplicaId(), patch.getPatchId().getSequenceNumber(), sessionId, replicaId);
			return;
		}

		// Operations are batched and processed by type.
		operationDispatcher.processAll(sessionId, replicaId, patch.getOperations());

		dao.setClock(sessionId, replicaId, LogicalTimestamp.newIncrement(patch.getPatchId(), patch.getSpan()));
	}

	boolean isPatchAlreadyApplied(String sessionId, Long replicaId, LogicalTimestamp patchId) {
		return dao.getClock(sessionId, replicaId, patchId.getReplicaId())
				.map(clock -> patchId.getSequenceNumber() <= clock.getSequenceNumber()).orElse(false);
	}

}
