package org.sagebionetworks.grid.db.handler;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.sagebionetworks.grid.db.GridIndexDao;
import org.sagebionetworks.repo.model.grid.patch.LogicalTimestamp;
import org.sagebionetworks.repo.model.grid.patch.operation.InsertArray;

@ExtendWith(MockitoExtension.class)
public class InsertArrayHandlerTest {

	@Mock
	private GridIndexDao mockDao;

	@InjectMocks
	private InsertArrayHandler handler;

	private String sessionId;
	private Long replicaId;
	private List<LogicalTimestamp> ids;
	private List<InsertArray> batch;

	@BeforeEach
	public void before() {
		sessionId = "sessionOne";
		replicaId = 123L;
		ids = createIds(7);
		batch = List.of(
				// one
				new InsertArray().setArrayId(ids.get(0))
						.setOperationId(new LogicalTimestamp().setReplicaId(99L).setSequenceNumber(1L))
						.setReferenceId(ids.get(0)).setElementIds(List.of(ids.get(1), ids.get(2))),
				// two
				new InsertArray().setArrayId(ids.get(3))
						.setOperationId(new LogicalTimestamp().setReplicaId(101L).setSequenceNumber(1L))
						.setReferenceId(ids.get(4)).setElementIds(List.of(ids.get(5), ids.get(6))));

	}

	@Test
	public void testHandleBatch() {

		// call under test
		handler.handleBatch(sessionId, replicaId, batch);
	}

	/**
	 * Helper to create a set of unique ids.
	 * 
	 * @param count
	 * @return
	 */
	public List<LogicalTimestamp> createIds(int count) {
		List<LogicalTimestamp> ids = new ArrayList<>();
		for (long i = 0; i < count * 2; i += 2) {
			ids.add(new LogicalTimestamp().setReplicaId(i).setSequenceNumber(i + 1L));
		}
		return ids;
	}

}
