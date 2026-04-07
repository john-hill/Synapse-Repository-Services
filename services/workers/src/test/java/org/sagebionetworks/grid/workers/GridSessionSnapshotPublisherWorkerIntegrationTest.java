package org.sagebionetworks.grid.workers;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.FileWriter;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.sagebionetworks.AsynchronousJobWorkerHelper;
import org.sagebionetworks.grid.db.GridIndexDao;
import org.sagebionetworks.repo.manager.UserManager;
import org.sagebionetworks.repo.manager.file.FileHandleManager;
import org.sagebionetworks.repo.manager.file.LocalFileUploadRequest;
import org.sagebionetworks.repo.manager.grid.GridSessionSnapshotPublisher;
import org.sagebionetworks.repo.manager.grid.PatchStore;
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
import org.sagebionetworks.repo.model.grid.patch.LogicalTimestamp;
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
public class GridSessionSnapshotPublisherWorkerIntegrationTest {

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
	private GridIndexDao gridIndexDao;

	@Autowired
	private PatchStore patchStore;

	@Autowired
	private GridSessionSnapshotPublisher snapshotPublisher;

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
	public void testScanSkipsRecentSession() throws Exception {
		GridSession session = createGridSessionWithData();
		String sessionId = session.getSessionId();

		// Wait for the INTERNAL replica to be fully synchronized (snapshot applied via async workers)
		waitForInternalReplicaReady(sessionId);

		Optional<GridSnapshot> initialSnapshot = gridDao.getLatestSnapshot(sessionId);
		assertTrue(initialSnapshot.isPresent(), "Initial snapshot should exist after grid session creation");

		// Verify that scanAndPublish does NOT select this session: all patches are covered by the
		// initial snapshot, so there are no uncovered patches to trigger a new snapshot
		List<String> published = snapshotPublisher.scanAndPublishSessionsNeedingSnapshot();
		assertFalse(published.contains(sessionId),
				"A freshly created session with no uncovered patches should not be selected for a new snapshot");
	}

	@Test
	public void testScanCreatesNewSnapshotForSessionWithNoSnapshot() throws Exception {
		GridSession session = createGridSessionWithData();
		String sessionId = session.getSessionId();

		// Wait for the INTERNAL replica to be fully synchronized (snapshot applied via async workers)
		waitForInternalReplicaReady(sessionId);

		Optional<GridSnapshot> initialSnapshot = gridDao.getLatestSnapshot(sessionId);
		assertTrue(initialSnapshot.isPresent(), "Initial snapshot should exist after grid session creation");

		// Add a patch that creates one constant node: [[[replicaId, seq]], [0, "test"]].
		// GridManager.savePatch fires a GRID_SESSION change message which triggers
		// GridSessionIndexWorker → "new-patch" → hub applies the patch.
		LogicalTimestamp patchId = new LogicalTimestamp().setReplicaId(1L).setSequenceNumber(1L);
		patchStore.savePatch(sessionId, patchId, "[[[1,1]],[0,\"test\"]]");

		// Poll until the hub has applied the patch (no more missing patches in GRID_PATCH)
		TimeUtils.waitFor(MAX_WAIT_MS, 1000L, () -> {
			List<LogicalTimestamp> clock = gridIndexDao.getClock(sessionId, INTERNAL_REPLICA_ID);
			boolean applied = gridDao.listMissingPatchInfoForClock(sessionId, clock, 1).isEmpty();
			return Pair.create(applied, null);
		});

		// Delete the snapshot so the applied patch becomes uncovered, making the session eligible
		jdbcTemplate.update("DELETE FROM GRID_SNAPSHOT WHERE SESSION_ID = ?", sessionId);

		// With no snapshot and an uncovered applied patch, the session should be eligible for a new snapshot
		List<String> published = snapshotPublisher.scanAndPublishSessionsNeedingSnapshot();
		assertTrue(published.contains(sessionId),
				"A session with uncovered patches and no snapshot should be eligible for a new snapshot");

		// Poll until a new snapshot appears (created by the worker processing the FIFO message)
		TimeUtils.waitFor(MAX_WAIT_MS, 1000L, () -> {
			Optional<GridSnapshot> latestSnapshot = gridDao.getLatestSnapshot(sessionId);
			return Pair.create(latestSnapshot.isPresent(), null);
		});

		Optional<GridSnapshot> latestSnapshot = gridDao.getLatestSnapshot(sessionId);
		assertTrue(latestSnapshot.isPresent(), "A new snapshot should exist");
		assertTrue(latestSnapshot.get().getCreatedOn().getTime() > initialSnapshot.get().getCreatedOn().getTime(),
				"The new snapshot should not be newer than the deleted one");

		published = snapshotPublisher.scanAndPublishSessionsNeedingSnapshot();
		assertFalse(published.contains(sessionId),
				"A session with a snapshot and no uncovered patches should not be selected for another snapshot");
	}

	/**
	 * Creates a grid session backed by a RecordSet so that the async job produces
	 * an initial snapshot and INTERNAL replica. An empty CreateGridRequest would use
	 * the EmptyCreateGridHandler which creates neither.
	 */
	private GridSession createGridSessionWithData() throws Exception {
		File csvFile = File.createTempFile("snapshot-publisher-test", ".csv");
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
					new Project().setName("GridSessionSnapshotPublisherWorkerIntegrationTest_createGridSessionWithData"), null);

			RecordSet recordSet = entityService.createEntity(admin.getId(), new RecordSet()
					.setName("snapshotRecordSet")
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
