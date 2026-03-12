package org.sagebionetworks.grid.workers;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.FileWriter;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.sagebionetworks.AsynchronousJobWorkerHelper;
import org.sagebionetworks.repo.manager.UserManager;
import org.sagebionetworks.repo.manager.file.FileHandleManager;
import org.sagebionetworks.repo.manager.file.LocalFileUploadRequest;
import org.sagebionetworks.repo.manager.grid.GridSnapshotCompactionManager;
import org.sagebionetworks.repo.model.AuthorizationConstants.BOOTSTRAP_PRINCIPAL;
import org.sagebionetworks.repo.model.Project;
import org.sagebionetworks.repo.model.RecordSet;
import org.sagebionetworks.repo.model.UserInfo;
import org.sagebionetworks.repo.model.dbo.grid.GridDao;
import org.sagebionetworks.repo.model.file.S3FileHandle;
import org.sagebionetworks.repo.model.grid.CreateGridRequest;
import org.sagebionetworks.repo.model.grid.CreateGridResponse;
import org.sagebionetworks.repo.model.grid.EventSource;
import org.sagebionetworks.repo.model.grid.GridConnectionInfo;
import org.sagebionetworks.repo.model.grid.GridSession;
import org.sagebionetworks.repo.model.grid.GridSnapshot;
import org.sagebionetworks.repo.model.table.CsvTableDescriptor;
import org.sagebionetworks.repo.service.EntityService;
import org.sagebionetworks.util.csv.CSVWriterProviderImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import au.com.bytecode.opencsv.CSVWriter;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(locations = { "classpath:test-context.xml" })
public class GridSnapshotCompactionWorkerIntegrationTest {

	public static final long MAX_WAIT_MS = 120_000;

	@Autowired
	private UserManager userManager;

	@Autowired
	private AsynchronousJobWorkerHelper asynchronousJobWorkerHelper;

	@Autowired
	private EntityService entityService;

	@Autowired
	private FileHandleManager fileHandleManager;

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
		entityService.truncateAll();
	}

	@AfterEach
	public void after() {
		entityService.truncateAll();
	}

	@Test
	public void testCompactSessionSkipsRecentSession() throws Exception {
		GridSession session = createGridSessionWithData();
		String sessionId = session.getSessionId();

		// Workaround for PLFM-9488: the INTERNAL connection may not be auto-created
		createInternalConnectionIfMissing(sessionId);

		Optional<GridSnapshot> initialSnapshot = gridDao.getLatestSnapshot(sessionId);
		assertTrue(initialSnapshot.isPresent(), "Initial snapshot should exist after grid session creation");

		boolean compacted = compactionManager.compactSession(sessionId);

		if (compacted) {
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
		GridSession session = createGridSessionWithData();
		String sessionId = session.getSessionId();

		// Workaround for PLFM-9488: the INTERNAL connection may not be auto-created
		createInternalConnectionIfMissing(sessionId);

		Optional<GridSnapshot> initialSnapshot = gridDao.getLatestSnapshot(sessionId);
		assertTrue(initialSnapshot.isPresent(), "Initial snapshot should exist after grid session creation");

		// Push the snapshot's CREATED_ON back >30 days to make it eligible for compaction
		Timestamp oldTimestamp = Timestamp.from(Instant.now().minus(31, ChronoUnit.DAYS));
		jdbcTemplate.update("UPDATE GRID_SNAPSHOT SET CREATED_ON = ? WHERE SESSION_ID = ?",
				oldTimestamp, sessionId);

		boolean compacted = compactionManager.compactSession(sessionId);

		assertTrue(compacted, "The session should have been compacted");

		Optional<GridSnapshot> latestSnapshot = gridDao.getLatestSnapshot(sessionId);
		assertTrue(latestSnapshot.isPresent(), "A new snapshot should exist after compaction");
		assertNotEquals(initialSnapshot.get().getId(), latestSnapshot.get().getId(),
				"A new snapshot should have been created");
		assertTrue(latestSnapshot.get().getCreatedOn().getTime() > initialSnapshot.get().getCreatedOn().getTime(),
				"The new snapshot should be newer than the old one");
	}

	/**
	 * Creates a grid session backed by a RecordSet so that the async job produces
	 * an initial snapshot and INTERNAL replica. An empty CreateGridRequest would use
	 * the EmptyCreateGridHandler which creates neither.
	 */
	private GridSession createGridSessionWithData() throws Exception {
		File csvFile = File.createTempFile("compaction-test", ".csv");
		try {
			CsvTableDescriptor descriptor = new CsvTableDescriptor().setIsFirstLineHeader(true);
			try (CSVWriter writer = new CSVWriterProviderImpl().createWriter(new FileWriter(csvFile), descriptor)) {
				writer.writeNext(new String[] { "id", "value" });
				writer.writeNext(new String[] { "1", "a" });
			}

			S3FileHandle fileHandle = fileHandleManager.uploadLocalFile(new LocalFileUploadRequest()
					.withFileToUpload(csvFile)
					.withContentType("text/csv")
					.withFileName(csvFile.getName())
					.withUserId(admin.getId().toString()));

			Project project = entityService.createEntity(admin.getId(),
					new Project().setName("CompactionTest"), null);

			RecordSet recordSet = entityService.createEntity(admin.getId(), new RecordSet()
					.setName("compactionRecordSet")
					.setParentId(project.getId())
					.setDataFileHandleId(fileHandle.getId())
					.setUpsertKey(List.of("id")), null);

			return asynchronousJobWorkerHelper.assertJobResponse(admin,
					new CreateGridRequest().setRecordSetId(recordSet.getId()),
					(CreateGridResponse response) -> {
						assertNotNull(response);
						assertNotNull(response.getGridSession());
					}, MAX_WAIT_MS).getResponse().getGridSession();
		} finally {
			csvFile.delete();
		}
	}

	/**
	 * Creates the INTERNAL connection for the given session if it does not already
	 * exist. Due to PLFM-9488 the connection may not be established by the async
	 * event. This method looks up the replica and creates the connection directly.
	 */
	private void createInternalConnectionIfMissing(String sessionId) {
		Optional<GridConnectionInfo> existing = gridDao.getSingletonConnection(sessionId, EventSource.INTERNAL);
		if (existing.isPresent()) {
			return;
		}
		Long replicaId = jdbcTemplate.queryForObject(
				"SELECT REPLICA_ID FROM GRID_REPLICA WHERE SESSION_ID = ? ORDER BY REPLICA_ID DESC LIMIT 1",
				Long.class, sessionId);
		assertNotNull(replicaId, "A replica should exist after grid session creation");

		gridDao.createConnection(new GridConnectionInfo()
				.setConnectionId(UUID.randomUUID().toString())
				.setSessionId(sessionId)
				.setReplicaId(replicaId)
				.setCreatedBy(admin.getId())
				.setSource(EventSource.INTERNAL));
	}
}
