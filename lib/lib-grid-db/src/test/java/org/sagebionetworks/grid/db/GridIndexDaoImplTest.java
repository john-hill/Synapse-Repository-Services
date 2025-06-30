package org.sagebionetworks.grid.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.sagebionetworks.repo.model.grid.GridUtils;
import org.sagebionetworks.repo.model.grid.node.ConstantNode;
import org.sagebionetworks.repo.model.grid.node.IndexNode;
import org.sagebionetworks.repo.model.grid.node.IndexType;
import org.sagebionetworks.repo.model.grid.patch.LogicalTimestamp;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(locations = { "classpath:grid-db-test-context.xml" })
public class GridIndexDaoImplTest {

	@Autowired
	private GridIndexDao gridIndexDao;

	private String sessionIdOne;
	private Long replicaIdOne;

	private String sessionIdTwo;
	private Long replicaIdTwo;

	private List<LogicalTimestamp> ids;

	@BeforeEach
	public void before() {
		gridIndexDao.truncateAll();
		sessionIdOne = GridUtils.gridSessionIdAsString(99L);
		replicaIdOne = 28L;
		sessionIdTwo = GridUtils.gridSessionIdAsString(101L);
		replicaIdTwo = 29L;

		ids = List.of(new LogicalTimestamp().setReplicaId(1L).setSequenceNumber(2L),
				new LogicalTimestamp().setReplicaId(3L).setSequenceNumber(4L),
				new LogicalTimestamp().setReplicaId(5L).setSequenceNumber(6L),
				new LogicalTimestamp().setReplicaId(7L).setSequenceNumber(8L),
				new LogicalTimestamp().setReplicaId(9L).setSequenceNumber(10L),
				new LogicalTimestamp().setReplicaId(11L).setSequenceNumber(12L),
				new LogicalTimestamp().setReplicaId(13L).setSequenceNumber(14L));
	}

	@AfterEach
	public void after() {
		gridIndexDao.truncateAll();
	}

	@Test
	public void testCreateReplica() {

		Optional<Timestamp> createdOn = gridIndexDao.getReplciaCreatedOn(sessionIdOne, replicaIdOne);
		assertEquals(Optional.empty(), createdOn);

		// call under test
		gridIndexDao.createReplicaIfNotExists(sessionIdOne, replicaIdOne);
		gridIndexDao.createReplicaIfNotExists(sessionIdOne, replicaIdOne + 1L);
		gridIndexDao.createReplicaIfNotExists(sessionIdOne, replicaIdOne);
		gridIndexDao.createReplicaIfNotExists(sessionIdOne, replicaIdOne + 1L);

		createdOn = gridIndexDao.getReplciaCreatedOn(sessionIdOne, replicaIdOne);
		assertNotNull(createdOn);
		assertTrue(createdOn.isPresent());

		createdOn = gridIndexDao.getReplciaCreatedOn(sessionIdOne, replicaIdOne + 1);
		assertNotNull(createdOn);
		assertTrue(createdOn.isPresent());

		// call under test
		gridIndexDao.deleteReplica(sessionIdOne, replicaIdOne);

		createdOn = gridIndexDao.getReplciaCreatedOn(sessionIdOne, replicaIdOne);
		assertEquals(Optional.empty(), createdOn);

		createdOn = gridIndexDao.getReplciaCreatedOn(sessionIdOne, replicaIdOne + 1);
		assertNotNull(createdOn);
		assertTrue(createdOn.isPresent());

	}

	@ParameterizedTest
	@EnumSource(value = IndexType.class)
	public void testSaveIndex(IndexType type) {
		gridIndexDao.createReplicaIfNotExists(sessionIdOne, replicaIdOne);
		gridIndexDao.createReplicaIfNotExists(sessionIdTwo, replicaIdTwo);
		List<LogicalTimestamp> ids = List.of(new LogicalTimestamp().setReplicaId(1L).setSequenceNumber(2L),
				new LogicalTimestamp().setReplicaId(3L).setSequenceNumber(4L));

		List<LogicalTimestamp> ids2 = List.of(new LogicalTimestamp().setReplicaId(5L).setSequenceNumber(6L),
				new LogicalTimestamp().setReplicaId(7L).setSequenceNumber(8L));

		// call under test
		gridIndexDao.saveIndex(sessionIdOne, replicaIdOne, type, ids);
		gridIndexDao.saveIndex(sessionIdTwo, replicaIdTwo, type, ids2);

		// call under test
		List<IndexNode> results = gridIndexDao.getIndices(sessionIdOne, replicaIdOne, ids);
		assertNotNull(results);
		List<IndexNode> expected = List.of(new IndexNode().setId(ids.get(0)).setType(type),
				new IndexNode().setId(ids.get(1)).setType(type));
		assertEquals(expected, results);

		// call under test
		results = gridIndexDao.getIndices(sessionIdTwo, replicaIdTwo, ids2);
		assertNotNull(results);
		expected = List.of(new IndexNode().setId(ids2.get(0)).setType(type),
				new IndexNode().setId(ids2.get(1)).setType(type));
		assertEquals(expected, results);
	}

	@Test
	public void testSaveAndGetConstantEachObjectType() {
		gridIndexDao.createReplicaIfNotExists(sessionIdOne, replicaIdOne);

		List<ConstantNode> constants = List.of(new ConstantNode().setId(ids.get(0)).setValue(true),
				new ConstantNode().setId(ids.get(1)).setValue(101),
				new ConstantNode().setId(ids.get(2)).setValue(505555555555555555L),
				new ConstantNode().setId(ids.get(3)).setValue(3.14),
				new ConstantNode().setId(ids.get(4)).setValue("Hello World"),
				new ConstantNode().setId(ids.get(5)).setValue("[1,2,3]"),
				new ConstantNode().setId(ids.get(6)).setValue("{\"key\":99}"));

		gridIndexDao.saveIndex(sessionIdOne, replicaIdOne, IndexType.con, ids);
		// call under test
		gridIndexDao.saveNewConstants(sessionIdOne, replicaIdOne, constants);
		// call under test
		List<ConstantNode> results = gridIndexDao.getConstants(sessionIdOne, replicaIdOne, ids);
		assertEquals(constants, results);
	}

	@Test
	public void testSaveAndGetConstantWithMultipleSessionIds() {
		gridIndexDao.createReplicaIfNotExists(sessionIdOne, replicaIdOne);
		gridIndexDao.createReplicaIfNotExists(sessionIdTwo, replicaIdTwo);

		List<ConstantNode> constants1 = List.of(new ConstantNode().setId(ids.get(0)).setValue("Hello from session 1"));
		List<ConstantNode> constants2 = List.of(new ConstantNode().setId(ids.get(1)).setValue("Hello from session 2"));

		gridIndexDao.saveIndex(sessionIdOne, replicaIdOne, IndexType.con, ids);
		gridIndexDao.saveNewConstants(sessionIdOne, replicaIdOne, constants1);

		gridIndexDao.saveIndex(sessionIdTwo, replicaIdTwo, IndexType.con, ids);
		gridIndexDao.saveNewConstants(sessionIdTwo, replicaIdTwo, constants2);

		List<ConstantNode> results1 = gridIndexDao.getConstants(sessionIdOne, replicaIdOne, ids);
		assertEquals(constants1, results1);

		List<ConstantNode> results2 = gridIndexDao.getConstants(sessionIdTwo, replicaIdTwo, ids);
		assertEquals(constants2, results2);
	}

}
