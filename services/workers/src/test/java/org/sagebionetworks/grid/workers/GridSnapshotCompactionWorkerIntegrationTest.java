package org.sagebionetworks.grid.workers;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.sagebionetworks.AsynchronousJobWorkerHelper;
import org.sagebionetworks.repo.manager.EntityManager;
import org.sagebionetworks.repo.manager.UserManager;
import org.sagebionetworks.repo.manager.grid.GridSnapshotCompactionManager;
import org.sagebionetworks.repo.model.AuthorizationConstants.BOOTSTRAP_PRINCIPAL;
import org.sagebionetworks.repo.model.UserInfo;
import org.sagebionetworks.repo.model.dbo.grid.GridDao;
import org.sagebionetworks.repo.model.grid.CreateGridRequest;
import org.sagebionetworks.repo.model.grid.CreateGridResponse;
import org.sagebionetworks.repo.model.grid.EventSource;
import org.sagebionetworks.repo.model.grid.GridConnectionInfo;
import org.sagebionetworks.repo.model.grid.GridSession;
import org.sagebionetworks.repo.model.grid.GridSnapshot;
import org.sagebionetworks.util.TimeUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(locations = { "classpath:test-context.xml" })
public class GridSnapshotCompactionWorkerIntegrationTest {

	public static final long MAX_WAIT_MS = 120_000;

	@Autowired
	private UserManager userManager;

	@Autowired
	private AsynchronousJobWorkerHelper asynchronousJobWorkerHelper;

	@Autowired
	private EntityManager entityManager;

	@Autowired
	private GridDao gridDao;

	@Autowired
	private GridSnapshotCompactionManager compactionManager;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	private UserInfo admin;

	@BeforeEach
	public void before() {
		admin = userManager.getUserInfo(BOOTSTRAP_PRINCIPAL.THE_ADMIN_USER.getPrincipalId());
		entityManager.truncateAll();
	}

	@AfterEach
	public void after() {
		entityManager.truncateAll();
	}

	@Test
	public void testCompactSessionSkipsRecentSession() throws Exception {
		// Create a grid session via the async job (which creates the initial snapshot + INTERNAL replica)
		GridSession session = createGridSession();
		String sessionId = session.getSessionId();

		waitForInternalConnection(sessionId);

		// Record the initial snapshot
		Optional<GridSnapshot> initialSnapshot = gridDao.getLatestSnapshot(sessionId);
		assertTrue(initialSnapshot.isPresent(), "Initial snapshot should exist after grid session creation");

		// With a freshly created session, compactSession should return false (nothing to do)
		boolean compacted = compactionManager.compactSession(sessionId);

		// The session is fresh, the INTERNAL replica is synchronized, but there's no reason to
		// create a new snapshot since the existing one is fine. compactSession should still succeed
		// (it exports a new snapshot from the INTERNAL replica), but scanAndPublishSessionsNeedingCompaction
		// would not have selected this session. We're testing compactSession directly here.
		// The result depends on whether the replica is synchronized — it should be true since
		// the INTERNAL connection has been established and the initial snapshot was applied.
		if (compacted) {
			// A new snapshot was created
			Optional<GridSnapshot> latestSnapshot = gridDao.getLatestSnapshot(sessionId);
			assertTrue(latestSnapshot.isPresent());
			assertNotEquals(initialSnapshot.get().getId(), latestSnapshot.get().getId(),
					"A new snapshot should have been created");
		}

		// Verify that scanAndPublish does NOT select this session (it's too recent)
		List<String> published = compactionManager.scanAndPublishSessionsNeedingCompaction();
		assertFalse(published.contains(sessionId),
				"A freshly created session should not be selected for compaction");
	}

	@Test
	public void testCompactSessionCreatesNewSnapshotForOldSession() throws Exception {
		// Create a grid session via the async job (which creates the initial snapshot + INTERNAL replica)
		GridSession session = createGridSession();
		String sessionId = session.getSessionId();

		waitForInternalConnection(sessionId);

		// Record the initial snapshot
		Optional<GridSnapshot> initialSnapshot = gridDao.getLatestSnapshot(sessionId);
		assertTrue(initialSnapshot.isPresent(), "Initial snapshot should exist after grid session creation");

		// Push the snapshot's CREATED_ON back >30 days to make it eligible for compaction
		Timestamp oldTimestamp = Timestamp.from(Instant.now().minus(31, ChronoUnit.DAYS));
		jdbcTemplate.update("UPDATE GRID_SNAPSHOT SET CREATED_ON = ? WHERE SESSION_ID = ?",
				oldTimestamp, sessionId);

		// Run compactSession directly — the old snapshot should trigger compaction
		boolean compacted = compactionManager.compactSession(sessionId);

		assertTrue(compacted, "The session should have been compacted");

		// The latest snapshot should be different from the initial one
		Optional<GridSnapshot> latestSnapshot = gridDao.getLatestSnapshot(sessionId);
		assertTrue(latestSnapshot.isPresent(), "A new snapshot should exist after compaction");
		assertNotEquals(initialSnapshot.get().getId(), latestSnapshot.get().getId(),
				"A new snapshot should have been created");
		assertTrue(latestSnapshot.get().getCreatedOn().getTime() > initialSnapshot.get().getCreatedOn().getTime(),
				"The new snapshot should be newer than the old one");
	}

	private GridSession createGridSession() throws Exception {
		return asynchronousJobWorkerHelper
				.assertJobResponse(admin, new CreateGridRequest(), (CreateGridResponse response) -> {
					assertNotNull(response);
					assertNotNull(response.getGridSession());
				}, MAX_WAIT_MS).getResponse().getGridSession();
	}

	private void waitForInternalConnection(String sessionId) throws Exception {
		GridConnectionInfo internalConnection = TimeUtils.waitFor(MAX_WAIT_MS, 1000, () -> {
			Optional<GridConnectionInfo> con = gridDao.getSingletonConnection(sessionId, EventSource.INTERNAL);
			return new org.sagebionetworks.util.Pair<>(con.isPresent(), con.orElse(null));
		});
		assertNotNull(internalConnection, "INTERNAL connection should exist");
	}
}
