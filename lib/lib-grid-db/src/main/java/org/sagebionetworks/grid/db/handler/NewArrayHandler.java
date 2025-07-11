package org.sagebionetworks.grid.db.handler;

import java.util.List;
import java.util.stream.Collectors;

import org.sagebionetworks.grid.db.GridIndexDao;
import org.sagebionetworks.grid.db.OperationHandler;
import org.sagebionetworks.repo.model.grid.node.ArrayNode;
import org.sagebionetworks.repo.model.grid.node.IndexType;
import org.sagebionetworks.repo.model.grid.patch.operation.NewArray;
import org.sagebionetworks.repo.model.grid.patch.operation.OperationType;
import org.sagebionetworks.util.ValidateArgument;
import org.springframework.stereotype.Repository;

@Repository
public class NewArrayHandler implements OperationHandler<NewArray> {

	private final GridIndexDao dao;

	public NewArrayHandler(GridIndexDao dao) {
		super();
		this.dao = dao;
	}

	@Override
	public OperationType getOperationType() {
		return OperationType.new_arr;
	}

	@Override
	public void handleBatch(String sessionId, Long replicaId, List<NewArray> batch) {
		ValidateArgument.required(sessionId, "sessionId");
		ValidateArgument.required(replicaId, "replicId");
		ValidateArgument.required(batch, "batch");
		dao.saveIndex(sessionId, replicaId, IndexType.arr,
				batch.stream().map(NewArray::getOperationId).collect(Collectors.toList()));
		dao.saveArrayNode(sessionId, replicaId,
				batch.stream().map(o -> new ArrayNode().setNodeId(o.getOperationId()).setArrayId(o.getOperationId()))
						.collect(Collectors.toList()));
	}

}
