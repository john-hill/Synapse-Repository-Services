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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.sagebionetworks.AsynchronousJobWorkerHelper;
import org.sagebionetworks.repo.manager.UserManager;
import org.sagebionetworks.repo.manager.file.FileHandleManager;
import org.sagebionetworks.repo.manager.file.LocalFileUploadRequest;
import org.sagebionetworks.repo.manager.grid.GridSnapshotCompactionManager;
import org.sagebionetworks.repo.manager.grid.internal.replica.model.GridHeader;
import org.sagebionetworks.repo.manager.grid.internal.replica.view.GridReplicaViewManager;
import org.sagebionetworks.repo.model.AuthorizationConstants.BOOTSTRAP_PRINCIPAL;
import org.sagebionetworks.repo.model.Project;
import org.sagebionetworks.repo.model.RecordSet;
import org.sagebionetworks.repo.model.UserInfo;
import org.sagebionetworks.repo.model.dbo.grid.GridDao;
import org.sagebionetworks.repo.model.file.S3FileHandle;
import org.sagebionetworks.repo.model.grid.CreateGridRequest;
import org.sagebionetworks.repo.model.grid.CreateGridResponse;
import org.sagebionetworks.repo.model.grid.GridSession;
import org.sagebionetworks.repo.model.grid.GridSnapshot;
import org.sagebionetworks.repo.model.table.CsvTableDescriptor;
import org.sagebionetworks.repo.service.EntityService;
import org.sagebionetworks.util.Pair;
import org.sagebionetworks.util.TimeUtils;
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

	private static final long INTERNAL_REPLICA_ID = 66534L;

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
	private GridReplicaViewManager gridViewManager;

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

		// Wait for the INTERNAL replica to be fully synchronized (snapshot applied via async workers)
		waitForInternalReplicaReady(sessionId);

		Optional<GridSnapshot> initialSnapshot = gridDao.getLatestSnapshot(sessionId);
		assertTrue(initialSnapshot.isPresent(), "Initial snapshot should exist after grid session creation");

		// Verify that scanAndPublish does NOT select this session (it's too recent)
		List<String> published = compactionManager.scanAndPublishSessionsNeedingCompaction();
		assertFalse(published.contains(sessionId),
				"A freshly created session should not be selected for compaction");
	}

	@Test
	public void testCompactSessionCreatesNewSnapshotForOldSession() throws Exception {
		GridSession session = createGridSessionWithData();
		String sessionId = session.getSessionId();

		// Wait for the INTERNAL replica to be fully synchronized (snapshot applied via async workers)
		waitForInternalReplicaReady(sessionId);

		Optional<GridSnapshot> initialSnapshot = gridDao.getLatestSnapshot(sessionId);
		assertTrue(initialSnapshot.isPresent(), "Initial snapshot should exist after grid session creation");
		Long initialSnapshotId = initialSnapshot.get().getId();

		// Push the snapshot's CREATED_ON back >30 days to make it eligible for compaction
		Timestamp oldTimestamp = Timestamp.from(Instant.now().minus(31, ChronoUnit.DAYS));
		jdbcTemplate.update("UPDATE GRID_SNAPSHOT SET CREATED_ON = ? WHERE SESSION_ID = ?",
				oldTimestamp, sessionId);

		// Publish the session for compaction via the FIFO queue
		List<String> published = compactionManager.scanAndPublishSessionsNeedingCompaction();
		assertTrue(published.contains(sessionId),
				"The old session should be selected for compaction");

		// Poll until a new snapshot appears (created by the worker processing the FIFO message)
		TimeUtils.waitFor(MAX_WAIT_MS, 1000L, () -> {
			Optional<GridSnapshot> latestSnapshot = gridDao.getLatestSnapshot(sessionId);
			boolean newSnapshotCreated = latestSnapshot.isPresent()
					&& !latestSnapshot.get().getId().equals(initialSnapshotId);
			return Pair.create(newSnapshotCreated, null);
		});

		Optional<GridSnapshot> latestSnapshot = gridDao.getLatestSnapshot(sessionId);
		assertTrue(latestSnapshot.isPresent(), "A new snapshot should exist after compaction");
		assertNotEquals(initialSnapshotId, latestSnapshot.get().getId(),
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
	 * Waits for the INTERNAL replica's snapshot to be applied by the async workers.
	 * The snapshot application is asynchronous (multiple SQS hops after session
	 * creation), so we poll until the replica's grid header is available.
	 */
	private void waitForInternalReplicaReady(String sessionId) throws Exception {
		TimeUtils.waitFor(MAX_WAIT_MS, 1000L, () -> {
			Optional<GridHeader> header = gridViewManager.readHeader(sessionId, INTERNAL_REPLICA_ID);
			return Pair.create(header.isPresent(), null);
		});
	}
}
