package org.sagebionetworks.grid.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Timestamp;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.sagebionetworks.repo.model.grid.GridUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(locations = { "classpath:grid-db-test-context.xml" })
public class GridIndexDaoImplTest {

	@Autowired
	private GridIndexDao gridIndexDao;

	private String sessionId;
	private Long replicaId;

	@BeforeEach
	public void before() {
		gridIndexDao.truncateAll();
		sessionId = GridUtils.gridSessionIdAsString(99L);
		replicaId = 28L;
	}

	@AfterEach
	public void after() {
		gridIndexDao.truncateAll();
	}

	@Test
	public void testCreateReplica() {

		Optional<Timestamp> createdOn = gridIndexDao.getReplciaCreatedOn(sessionId, replicaId);
		assertEquals(Optional.empty(), createdOn);

		// call under test
		gridIndexDao.createReplicaIfNotExists(sessionId, replicaId);
		gridIndexDao.createReplicaIfNotExists(sessionId, replicaId + 1L);
		gridIndexDao.createReplicaIfNotExists(sessionId, replicaId);
		gridIndexDao.createReplicaIfNotExists(sessionId, replicaId + 1L);

		createdOn = gridIndexDao.getReplciaCreatedOn(sessionId, replicaId);
		assertNotNull(createdOn);
		assertTrue(createdOn.isPresent());

		createdOn = gridIndexDao.getReplciaCreatedOn(sessionId, replicaId + 1);
		assertNotNull(createdOn);
		assertTrue(createdOn.isPresent());

		// call under test
		gridIndexDao.deleteReplica(sessionId, replicaId);

		createdOn = gridIndexDao.getReplciaCreatedOn(sessionId, replicaId);
		assertEquals(Optional.empty(), createdOn);

		createdOn = gridIndexDao.getReplciaCreatedOn(sessionId, replicaId + 1);
		assertNotNull(createdOn);
		assertTrue(createdOn.isPresent());

	}
}
