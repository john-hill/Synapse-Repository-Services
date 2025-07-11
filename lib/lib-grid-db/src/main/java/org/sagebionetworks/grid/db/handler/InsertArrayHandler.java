package org.sagebionetworks.grid.db.handler;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.sagebionetworks.grid.db.GridIndexDao;
import org.sagebionetworks.grid.db.OperationHandler;
import org.sagebionetworks.repo.model.grid.node.ArrayNode;
import org.sagebionetworks.repo.model.grid.patch.LogicalTimestamp;
import org.sagebionetworks.repo.model.grid.patch.operation.InsertArray;
import org.sagebionetworks.repo.model.grid.patch.operation.OperationType;

public class InsertArrayHandler implements OperationHandler<InsertArray> {

	private final GridIndexDao gridDao;

	public InsertArrayHandler(GridIndexDao gridDao) {
		super();
		this.gridDao = gridDao;
	}

	@Override
	public OperationType getOperationType() {
		return OperationType.ins_arr;
	}

	@Override
	public void handleBatch(String sessionId, Long replicaId, List<InsertArray> batch) {
		/*
		 * An InsertArray can contain more than one elementId. This is an optimization
		 * that allows multiple elements to be inserted into the array at the same
		 * position. However, if we later need to insert a value between two of these
		 * elements, then we would need to first split the ArrayNode into multiple nodes
		 * before executing the new insert. To avoid the complexity of dynamically
		 * splitting nodes, we automatically do the split the elements into their own
		 * nodes at the start.
		 */
		List<ArrayNode> flatBatch = batch.stream().flatMap(insert -> IntStream.range(0, insert.getElementIds().size())
				.mapToObj(i -> new ArrayNode().setNodeId(LogicalTimestamp.newIncrement(insert.getOperationId(), i))
						.setArrayId(insert.getArrayId()).setReferenceNodeId(insert.getReferenceId())
						.setDataId(insert.getElementIds().get(i))))
				.collect(Collectors.toList());

		Map<LogicalTimestamp, ArrayNode> current = gridDao
				.getArrays(sessionId, replicaId,
						flatBatch.stream().map(ArrayNode::getNodeId).collect(Collectors.toList()))
				.stream().collect(Collectors.toMap(ArrayNode::getNodeId, Function.identity()));

		List<ArrayNode> toChange = new ArrayList<>();
		flatBatch.forEach(i -> {
			/*
			 * 1. Insertion cursor is set to the position before all elements in the RGA
			 * node.
			 */
			LogicalTimestamp cursor = i.getArrayId();
			/*
			 * 2. If the rga.ref is not equal to the rga.node, then the cursor is moved to
			 * the position right after the element with the rga.ref ID.
			 */
			if (i.getReferenceNodeId().compareTo(i.getArrayId()) != 0) {
				cursor = i.getReferenceNodeId();
			}
			
			Optional<ArrayNode> atCursor = gridDao.getRgaAtPosition(sessionId, replicaId, i.getArrayId(), cursor);
			if(atCursor.isPresent()) {
				if(atCursor.get().getDataId().compareTo(i.getDataId()) < 1) {
					// move to next
				}
			}

			ArrayNode cur = current.get(i.getNodeId());
			if (cur == null) {
				throw new IllegalArgumentException("Cannot update an array that does not exist: " + i.getNodeId());
			}
			if (cur.attemptInsert(i)) {
				toChange.add(cur);
			}
		});
		gridDao.saveArrayNode(sessionId, replicaId, toChange);

	}

}
