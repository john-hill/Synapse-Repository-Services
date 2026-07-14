package org.sagebionetworks.grid.workers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import org.apache.http.entity.ContentType;
import org.java_websocket.WebSocket;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.sagebionetworks.AsynchronousJobWorkerHelper;
import org.sagebionetworks.repo.manager.UserManager;
import org.sagebionetworks.repo.manager.file.FileHandleManager;
import org.sagebionetworks.repo.manager.grid.GridManager;
import org.sagebionetworks.repo.manager.grid.internal.replica.model.Column;
import org.sagebionetworks.repo.manager.grid.internal.replica.model.GridHeader;
import org.sagebionetworks.repo.manager.grid.internal.replica.model.RowView;
import org.sagebionetworks.repo.manager.grid.internal.replica.view.GridReplicaViewManager;
import org.sagebionetworks.repo.manager.schema.JsonSchemaManager;
import org.sagebionetworks.repo.model.AuthorizationConstants.BOOTSTRAP_PRINCIPAL;
import org.sagebionetworks.repo.model.Project;
import org.sagebionetworks.repo.model.RecordSet;
import org.sagebionetworks.repo.model.UserInfo;
import org.sagebionetworks.repo.model.entity.BindSchemaToEntityRequest;
import org.sagebionetworks.repo.model.file.S3FileHandle;
import org.sagebionetworks.repo.model.grid.CreateGridPresignedUrlRequest;
import org.sagebionetworks.repo.model.grid.CreateGridRequest;
import org.sagebionetworks.repo.model.grid.CreateGridResponse;
import org.sagebionetworks.repo.model.grid.CreateReplicaRequest;
import org.sagebionetworks.repo.model.grid.GridConnectionInfo;
import org.sagebionetworks.repo.model.grid.GridConstants;
import org.sagebionetworks.repo.model.grid.GridReplica;
import org.sagebionetworks.repo.model.grid.GridSession;
import org.sagebionetworks.repo.model.grid.SyncType;
import org.sagebionetworks.repo.model.grid.SynchronizeGridRequest;
import org.sagebionetworks.repo.model.grid.message.JsonRxMessage;
import org.sagebionetworks.repo.model.grid.message.JsonRxMessageType;
import org.sagebionetworks.repo.model.grid.node.ConstantNode;
import org.sagebionetworks.repo.model.grid.patch.ConType;
import org.sagebionetworks.repo.model.grid.patch.ConValue;
import org.sagebionetworks.repo.model.grid.patch.LogicalTimestamp;
import org.sagebionetworks.repo.model.grid.patch.Patch;
import org.sagebionetworks.repo.model.grid.patch.compact.PatchCompactSerializable;
import org.sagebionetworks.repo.model.grid.patch.operation.builder.Operations;
import org.sagebionetworks.repo.model.schema.CreateSchemaRequest;
import org.sagebionetworks.repo.model.schema.JsonSchema;
import org.sagebionetworks.repo.model.schema.Organization;
import org.sagebionetworks.repo.model.schema.Type;
import org.sagebionetworks.repo.service.EntityService;
import org.sagebionetworks.repo.service.GridService;
import org.sagebionetworks.util.Pair;
import org.sagebionetworks.util.TimeUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;

/**
 * Autowired integration test for synchronizing a RecordSet-sourced grid (PULL
 * and PULL_PUSH). Exercises the full async pipeline: create a RecordSet + grid,
 * advance the RecordSet to a new revision that adds a column, changes a row, and
 * adds a row, then synchronize and verify the grid reconciles columns and merges
 * rows. PULL_PUSH additionally writes the grid back as a new RecordSet version.
 * The grid is read through the internal replica's view (no user replica/websocket
 * needed, since this scenario has no in-grid user edits).
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(locations = { "classpath:test-context.xml" })
public class GridRecordSetSynchronizationIntegrationTest {

	public static final long MAX_WAIT_MS = 1000L * 60 * 2;

	@Autowired
	private EntityService entityService;
	@Autowired
	private UserManager userManager;
	@Autowired
	private FileHandleManager fileHandleManager;
	@Autowired
	private AsynchronousJobWorkerHelper asynchronousJobWorkerHelper;
	@Autowired
	private GridReplicaViewManager gridViewManager;
	@Autowired
	private GridManager gridManager;
	@Autowired
	private GridService gridService;
	@Autowired
	private JsonSchemaManager jsonSchemaManager;

	private UserInfo admin;
	private Project project;

	@BeforeEach
	public void before() {
		admin = userManager.getUserInfo(BOOTSTRAP_PRINCIPAL.THE_ADMIN_USER.getPrincipalId());
		project = entityService.createEntity(admin.getId(), new Project().setName(UUID.randomUUID().toString()), null);
	}

	@AfterEach
	public void after() {
		if (project != null) {
			entityService.deleteEntity(admin.getId(), project.getId());
		}
	}

	@Test
	public void testSynchronizeRecordSetPull() throws Exception {
		// v1: columns a (key) and b.
		RecordSet recordSet = createRecordSet(uploadCsv("a,b\n1,one\n2,two\n"));
		long v1 = recordSet.getVersionNumber();

		// Create a grid from the RecordSet and wait for the internal replica to populate.
		GridSession session = asynchronousJobWorkerHelper.assertJobResponse(admin,
				new CreateGridRequest().setOwnerPrincipalId(admin.getId().toString())
						.setRecordSetId(recordSet.getId()),
				(CreateGridResponse response) -> {
					assertNotNull(response.getGridSession());
				}, MAX_WAIT_MS).getResponse().getGridSession();
		assertNotNull(session);
		assertEquals(recordSet.getId(), session.getSourceEntityId());
		// the source version is captured at creation
		assertEquals(v1, session.getSourceEntityVersionNumber());

		GridConnectionInfo internalCon = asynchronousJobWorkerHelper.getInternalGridConnection(session.getSessionId(),
				MAX_WAIT_MS);

		// wait for the initial two rows
		waitForRows(session.getSessionId(), internalCon.getReplicaId(), Map.of(
				"1", Map.of("b", "one"),
				"2", Map.of("b", "two")));

		// v2: add column c, change row 1's b, add row 3.
		recordSet.setDataFileHandleId(uploadCsv("a,b,c\n1,one-changed,x1\n2,two,x2\n3,three,x3\n"));
		recordSet.setVersionLabel(null);
		recordSet = entityService.updateEntity(admin.getId(), recordSet, true, null);
		long v2 = recordSet.getVersionNumber();

		// call under test — PULL (explicit; does not write back to the RecordSet)
		asynchronousJobWorkerHelper.assertJobResponse(admin,
				new SynchronizeGridRequest().setGridSessionId(session.getSessionId()).setSyncType(SyncType.PULL),
				(r) -> {
				}, MAX_WAIT_MS);

		// the grid reconciles the new column "c" and merges all source rows (no user
		// edits, so every source value is pulled)
		waitForRows(session.getSessionId(), internalCon.getReplicaId(), Map.of(
				"1", Map.of("b", "one-changed", "c", "x1"),
				"2", Map.of("b", "two", "c", "x2"),
				"3", Map.of("b", "three", "c", "x3")));

		// the session's baseline version advances to the pulled revision
		GridSession synced = gridManager.getGridSession(admin, session.getSessionId());
		assertEquals(v2, synced.getSourceEntityVersionNumber());
	}

	/**
	 * A row with an incomplete upsertKey (blank "a") is imported into the grid when
	 * it appears in a newer revision, and is NOT re-imported on a subsequent PULL
	 * against the same revision
	 */
	@Test
	public void testSynchronizeRecordSetPullWithKeylessSourceRow() throws Exception {
		// v1: two complete-key rows.
		RecordSet recordSet = createRecordSet(uploadCsv("a,b\n1,one\n2,two\n"));

		GridSession session = asynchronousJobWorkerHelper.assertJobResponse(admin,
				new CreateGridRequest().setOwnerPrincipalId(admin.getId().toString())
						.setRecordSetId(recordSet.getId()),
				(CreateGridResponse response) -> assertNotNull(response.getGridSession()), MAX_WAIT_MS).getResponse().getGridSession();
		assertNotNull(session);

		GridConnectionInfo internalCon = asynchronousJobWorkerHelper.getInternalGridConnection(session.getSessionId(),
				MAX_WAIT_MS);
		waitForRowCount(session.getSessionId(), internalCon.getReplicaId(), 2);

		// v2 (newer): adds a row with a blank "a" (incomplete upsertKey).
		recordSet.setDataFileHandleId(uploadCsv("a,b\n1,one\n2,two\n,keyless\n"));
		recordSet.setVersionLabel(null);
		recordSet = entityService.updateEntity(admin.getId(), recordSet, true, null);

		// call under test — PULL imports the keyless row as a new grid row.
		asynchronousJobWorkerHelper.assertJobResponse(admin,
				new SynchronizeGridRequest().setGridSessionId(session.getSessionId()).setSyncType(SyncType.PULL),
				(r) -> {
				}, MAX_WAIT_MS);

		// 2 complete-key rows + 1 imported keyless row.
		waitForRowCount(session.getSessionId(), internalCon.getReplicaId(), 3);

		// call under test — a redundant PULL against the same (now-synced) revision must
		// NOT re-import the keyless row.
		asynchronousJobWorkerHelper.assertJobResponse(admin,
				new SynchronizeGridRequest().setGridSessionId(session.getSessionId()).setSyncType(SyncType.PULL),
				(r) -> {
				}, MAX_WAIT_MS);

		// still 3 rows — no duplication.
		waitForRowCount(session.getSessionId(), internalCon.getReplicaId(), 3);
	}

	@Test
	public void testSynchronizeRecordSetPullPush() throws Exception {
		// v1: columns a (key) and b.
		RecordSet recordSet = createRecordSet(uploadCsv("a,b\n1,one\n2,two\n"));

		// Create a grid from the RecordSet and wait for the internal replica to populate.
		GridSession session = asynchronousJobWorkerHelper.assertJobResponse(admin,
				new CreateGridRequest().setOwnerPrincipalId(admin.getId().toString())
						.setRecordSetId(recordSet.getId()),
				(CreateGridResponse response) -> {
					assertNotNull(response.getGridSession());
				}, MAX_WAIT_MS).getResponse().getGridSession();
		assertNotNull(session);

		GridConnectionInfo internalCon = asynchronousJobWorkerHelper.getInternalGridConnection(session.getSessionId(),
				MAX_WAIT_MS);
		waitForRows(session.getSessionId(), internalCon.getReplicaId(), Map.of(
				"1", Map.of("b", "one"),
				"2", Map.of("b", "two")));

		// v2: add column c, change row 1's b, add row 3.
		recordSet.setDataFileHandleId(uploadCsv("a,b,c\n1,one-changed,x1\n2,two,x2\n3,three,x3\n"));
		recordSet.setVersionLabel(null);
		recordSet = entityService.updateEntity(admin.getId(), recordSet, true, null);
		long v2 = recordSet.getVersionNumber();

		// call under test — PULL_PUSH pulls v2 into the grid and writes the grid back as
		// a new RecordSet version, built synchronously during the merge.
		asynchronousJobWorkerHelper.assertJobResponse(admin,
				new SynchronizeGridRequest().setGridSessionId(session.getSessionId()).setSyncType(SyncType.PULL_PUSH),
				(r) -> {
				}, MAX_WAIT_MS);

		// the grid reconciles the new column and merges all source rows
		waitForRows(session.getSessionId(), internalCon.getReplicaId(), Map.of(
				"1", Map.of("b", "one-changed", "c", "x1"),
				"2", Map.of("b", "two", "c", "x2"),
				"3", Map.of("b", "three", "c", "x3")));

		// the push created a new RecordSet version (> v2) and the session's baseline now
		// points at that pushed version
		RecordSet pushed = entityService.getEntity(admin.getId(), recordSet.getId(), RecordSet.class);
		assertTrue(pushed.getVersionNumber() > v2, "PULL_PUSH should create a new RecordSet version");
		GridSession synced = gridManager.getGridSession(admin, session.getSessionId());
		assertEquals(pushed.getVersionNumber(), synced.getSourceEntityVersionNumber());
	}

	/**
	 * A PULL must not re-attribute the cells a user changed. The merge re-publishes a
	 * conflicting row under the service replica, which would flip the user's edits to
	 * "system" attribution and cause a subsequent PULL to revert them. With the
	 * preserve-user-attribution fix, a user-divergent cell keeps its user-owned CRDT
	 * node across repeated PULLs (so the edit survives), while a user edit that
	 * coincides with the source value is normalized to the service replica.
	 */
	@Test
	public void testSynchronizeRecordSetPullPreservesUserAttribution() throws Exception {
		// v1: key column a, plus b and c.
		RecordSet recordSet = createRecordSet(uploadCsv("a,b,c\n1,one,red\n2,two,blue\n"));

		GridSession session = asynchronousJobWorkerHelper.assertJobResponse(admin,
				new CreateGridRequest().setOwnerPrincipalId(admin.getId().toString())
						.setRecordSetId(recordSet.getId()),
				(CreateGridResponse response) -> {
					assertNotNull(response.getGridSession());
				}, MAX_WAIT_MS).getResponse().getGridSession();
		assertNotNull(session);

		GridConnectionInfo internalCon = asynchronousJobWorkerHelper.getInternalGridConnection(session.getSessionId(),
				MAX_WAIT_MS);
		waitForRows(session.getSessionId(), internalCon.getReplicaId(), Map.of(
				"1", Map.of("b", "one", "c", "red"),
				"2", Map.of("b", "two", "c", "blue")));

		// A user edits row "1": sets c to a value that diverges from the source ("green"
		// vs "red"), and sets b to a value that coincides with the source ("one").
		GridReplica userReplica = gridManager
				.createReplica(admin, new CreateReplicaRequest().setGridSessionId(session.getSessionId())).getReplica();
		applyUserCellEdits(session.getSessionId(), internalCon.getReplicaId(), userReplica.getReplicaId(), "1",
				Map.of("b", new ConValue(ConType.STRING, "one"), "c", new ConValue(ConType.STRING, "green")));

		// the user's edits are visible in the grid before syncing
		waitForRows(session.getSessionId(), internalCon.getReplicaId(), Map.of(
				"1", Map.of("b", "one", "c", "green"),
				"2", Map.of("b", "two", "c", "blue")));

		// PULL twice against the same (unchanged) source revision
		for (int i = 0; i < 2; i++) {
			asynchronousJobWorkerHelper.assertJobResponse(admin,
					new SynchronizeGridRequest().setGridSessionId(session.getSessionId()).setSyncType(SyncType.PULL),
					(r) -> {
					}, MAX_WAIT_MS);
		}

		// The user's divergent edit to c survives the double PULL; b stays "one".
		waitForRows(session.getSessionId(), internalCon.getReplicaId(), Map.of(
				"1", Map.of("b", "one", "c", "green"),
				"2", Map.of("b", "two", "c", "blue")));

		// Attribution: the divergent cell (c) keeps its user-owned node; the coincidental
		// cell (b) was normalized to the service replica. The cells' rendered VALUES
		// already matched before the double-PULL (waitForRows above can't detect an
		// attribution-only change), so poll for attribution directly rather than
		// reading the header once immediately after the value-based wait.
		waitForCellAttribution(session.getSessionId(), internalCon.getReplicaId(), "1", "c", true);
		waitForCellAttribution(session.getSessionId(), internalCon.getReplicaId(), "1", "b", false);
	}

	/**
	 * A schema change picked up during PULL synchronization (the RecordSet's
	 * bound schema is re-bound to a new $id) must re-validate EVERY row in the
	 * grid, including rows whose underlying data never changes across the
	 * resync. This is the behavior that distinguishes
	 * {@code GridReplicaValidationManager#validateSchemaChange} from the normal
	 * data-change-triggered validation path, which opts out of revalidating rows
	 * whose data hasn't changed.
	 */
	@Test
	public void testSynchronizeRecordSetSchemaChangeRevalidatesAllRows() throws Exception {
		Organization org = asynchronousJobWorkerHelper.getOrCreateOrganization(admin.getId(),
				"org" + UUID.randomUUID().toString().replace("-", ""));

		String schema1Id = createSchema(org, "schemaVOne", List.of("a", "b"));

		// v1: columns a (key) and b. Bind the v1 schema to the RecordSet *before*
		// creating the grid, so the grid is created already bound to it.
		RecordSet recordSet = createRecordSet(uploadCsv("a,b\n1,one\n2,two\n"));
		entityService.bindSchemaToEntity(admin.getId(),
				new BindSchemaToEntityRequest().setEntityId(recordSet.getId()).setSchema$id(schema1Id));

		GridSession session = asynchronousJobWorkerHelper.assertJobResponse(admin,
				new CreateGridRequest().setOwnerPrincipalId(admin.getId().toString())
						.setRecordSetId(recordSet.getId()),
				(CreateGridResponse response) -> {
					assertNotNull(response.getGridSession());
				}, MAX_WAIT_MS).getResponse().getGridSession();
		assertNotNull(session);
		assertEquals(schema1Id, session.getGridJsonSchema$Id());

		GridConnectionInfo internalCon = asynchronousJobWorkerHelper.getInternalGridConnection(session.getSessionId(),
				MAX_WAIT_MS);
		waitForRows(session.getSessionId(), internalCon.getReplicaId(), Map.of(
				"1", Map.of("b", "one"),
				"2", Map.of("b", "two")));

		// wait for the initial validation pass (triggered by the grid's creation) to
		// populate a validation result/constant for every row.
		Map<String, LogicalTimestamp> baseline = waitForValidationConstantIds(session.getSessionId(),
				internalCon.getReplicaId(), Set.of("1", "2"));

		// Re-bind a new schema version to the RecordSet. Neither row's data changes at all.
		String schema2Id = createSchema(org, "schemaVTwo", List.of("a", "b"));
		entityService.bindSchemaToEntity(admin.getId(),
				new BindSchemaToEntityRequest().setEntityId(recordSet.getId()).setSchema$id(schema2Id));

		// call under test (indirectly) — PULL synchronization observes the
		// RecordSet's newly-bound schema and calls
		// GridManagerImpl#updateSessionSchemaId, which now invalidates the
		// session's existing validation results.
		asynchronousJobWorkerHelper.assertJobResponse(admin,
				new SynchronizeGridRequest().setGridSessionId(session.getSessionId()).setSyncType(SyncType.PULL),
				(r) -> {
				}, MAX_WAIT_MS);

		GridSession resynced = gridManager.getGridSession(admin, session.getSessionId());
		assertEquals(schema2Id, resynced.getGridJsonSchema$Id());

		// Every row — including row "2", whose data is identical under both schema
		// versions — must be re-validated: its validation constant must advance
		// beyond the baseline captured before the schema change.
		waitForValidationConstantIdsAdvanced(session.getSessionId(), internalCon.getReplicaId(), baseline);
	}

	/**
	 * Apply a user-attributed cell edit to a single grid row (identified by its "a"
	 * key value) over a websocket connection, so the resulting CRDT nodes are owned
	 * by the given user replica.
	 */
	private void applyUserCellEdits(String sessionId, Long internalReplicaId, Long userReplicaId, String rowKey,
			Map<String, ConValue> edits) throws Exception {
		String url = gridService.createPresignedUrl(admin.getId(),
				new CreateGridPresignedUrlRequest().setGridSessionId(sessionId).setReplicaId(userReplicaId))
				.getPresignedUrl();
		BlockingQueue<String> incomingMessages = new LinkedBlockingQueue<>();
		WebSocket websocket = asynchronousJobWorkerHelper.createConnection(url, incomingMessages);

		GridHeader header = gridViewManager.readHeader(sessionId, internalReplicaId).get();
		RowView row = getRowByKey(header, rowKey);
		LogicalTimestamp rowVectorId = row.getRowObject().getData().getVectorId();

		Patch patch = new Patch()
				.setPatchId(new LogicalTimestamp().setReplicaId(userReplicaId).setSequenceNumber(101L));
		Map<Integer, LogicalTimestamp> vectorMap = new HashMap<>();
		for (Map.Entry<String, ConValue> edit : edits.entrySet()) {
			int vectorIndex = columnVectorIndex(header, edit.getKey());
			vectorMap.put(vectorIndex, patch.addNewOperation(Operations.newConstant().setValue(edit.getValue())));
		}
		patch.addNewOperation(Operations.insertVector().setVectorId(rowVectorId).setMap(vectorMap));

		JsonRxMessage message = new JsonRxMessage(JsonRxMessageType.RequestData).setId(102).setMethod("patch")
				.setBody(PatchCompactSerializable.serialize(patch));
		websocket.send(message.toJson());
		asynchronousJobWorkerHelper.waitForMessage((a) -> a.optInt(0) == 5 && a.optInt(1) == message.getId().get(),
				incomingMessages);
	}

	private int columnVectorIndex(GridHeader header, String columnName) {
		return header.getOrderedColumns().stream().filter(c -> columnName.equals(c.getName())).findFirst()
				.map(Column::getVectorIndex).orElseThrow(() -> new IllegalStateException("No column: " + columnName));
	}

	/** The CRDT cell node for a column in a row, located by the column's vector index. */
	private ConstantNode cellNode(GridHeader header, RowView row, String columnName) {
		return row.getRowObject().getData().getNodes().get(columnVectorIndex(header, columnName));
	}

	/** Find the (current) row whose "a" key column equals the given value. */
	private RowView getRowByKey(GridHeader header, String key) {
		for (RowView row : gridViewManager.querySinglePage(header, 100L, 0L)) {
			var doc = row.getRowObject().getData().getRowJsonDocument();
			if (doc.has("a") && key.equals(String.valueOf(doc.get("a")))) {
				return row;
			}
		}
		throw new IllegalStateException("No row with key: " + key);
	}

	/**
	 * Wait until a cell's CRDT node attribution (user-owned vs service-owned)
	 * matches {@code expectedIsUserReplica}. A cell's attribution can change
	 * without its rendered value changing, so {@link #waitForRows} alone cannot
	 * detect this — callers that assert on attribution must poll separately
	 * rather than reading the header once immediately after a value-based wait.
	 */
	private void waitForCellAttribution(String sessionId, Long replicaId, String rowKey, String columnName,
			boolean expectedIsUserReplica) throws Exception {
		TimeUtils.waitFor(MAX_WAIT_MS, 1000L, () -> {
			Optional<GridHeader> header = gridViewManager.readHeader(sessionId, replicaId);
			if (header.isEmpty()) {
				return Pair.create(false, null);
			}
			RowView row;
			try {
				row = getRowByKey(header.get(), rowKey);
			} catch (IllegalStateException e) {
				// row not (yet) visible
				return Pair.create(false, null);
			}
			boolean isUserReplica = GridConstants
					.isUserReplica(cellNode(header.get(), row, columnName).getId().getReplicaId());
			return Pair.create(isUserReplica == expectedIsUserReplica, null);
		});
	}

	/**
	 * Wait until the internal replica's rows match the expected set, keyed by the
	 * "a" column value. Each expected entry maps column name → expected value;
	 * comparison is tolerant of JSON number/string representation.
	 */
	private void waitForRows(String sessionId, Long replicaId, Map<String, Map<String, String>> expectedByKey)
			throws Exception {
		TimeUtils.waitFor(MAX_WAIT_MS, 1000L, () -> {
			Optional<GridHeader> header = gridViewManager.readHeader(sessionId, replicaId);
			if (header.isEmpty()) {
				return Pair.create(false, null);
			}
			List<RowView> rows = gridViewManager.querySinglePage(header.get(), 100L, 0L);
			Map<String, Map<String, String>> actualByKey = new HashMap<>();
			for (RowView row : rows) {
				var doc = row.getRowObject().getData().getRowJsonDocument();
				String key = doc.has("a") ? String.valueOf(doc.get("a")) : null;
				if (key == null) {
					continue;
				}
				Map<String, String> cells = new HashMap<>();
				for (String name : expectedByKey.getOrDefault(key, Map.of()).keySet()) {
					cells.put(name, doc.has(name) ? String.valueOf(doc.get(name)) : null);
				}
				actualByKey.put(key, cells);
			}
			return Pair.create(expectedByKey.equals(actualByKey), null);
		});
	}

	/**
	 * Wait until the internal replica reports exactly {@code expectedCount} rows.
	 * Used to assert keyless rows (which have no "a" key) are imported and not
	 * duplicated, where keying by "a" is not possible.
	 */
	private void waitForRowCount(String sessionId, Long replicaId, int expectedCount) throws Exception {
		TimeUtils.waitFor(MAX_WAIT_MS, 1000L, () -> {
			Optional<GridHeader> header = gridViewManager.readHeader(sessionId, replicaId);
			if (header.isEmpty()) {
				return Pair.create(false, null);
			}
			List<RowView> rows = gridViewManager.querySinglePage(header.get(), 100L, 0L);
			return Pair.create(rows.size() == expectedCount, null);
		});
	}


	private String createSchema(Organization org, String name, List<String> propertyNames) {
		Map<String, JsonSchema> properties = new HashMap<>();
		for (String propertyName : propertyNames) {
			properties.put(propertyName, new JsonSchema().setType(Type.string));
		}
		JsonSchema schema = new JsonSchema().set$id(org.getName() + "-" + name).setProperties(properties);
		return jsonSchemaManager.createJsonSchema(admin, new CreateSchemaRequest().setSchema(schema))
				.getNewVersionInfo().get$id();
	}

	/**
	 * Wait until every row keyed by {@code keys} (via column "a") has a
	 * validation result, and return each row's validation constant id — the CRDT
	 * logical timestamp identifying the current validation result, which
	 * advances on every revalidation regardless of whether the validation
	 * content itself changed.
	 */
	private Map<String, LogicalTimestamp> waitForValidationConstantIds(String sessionId, Long replicaId,
			Set<String> keys) throws Exception {
		return TimeUtils.waitFor(MAX_WAIT_MS, 1000L, () -> {
			Optional<GridHeader> header = gridViewManager.readHeader(sessionId, replicaId);
			if (header.isEmpty()) {
				return Pair.create(false, null);
			}
			Map<String, LogicalTimestamp> found = new HashMap<>();
			for (RowView row : gridViewManager.querySinglePage(header.get(), 100L, 0L)) {
				var doc = row.getRowObject().getData().getRowJsonDocument();
				if (!doc.has("a")) {
					continue;
				}
				String key = String.valueOf(doc.get("a"));
				if (!keys.contains(key)) {
					continue;
				}
				LogicalTimestamp constantId = row.getRowMetadata() == null
						|| row.getRowMetadata().getRowValidation() == null ? null
								: row.getRowMetadata().getRowValidation().getConstantId();
				if (constantId == null) {
					return Pair.create(false, null);
				}
				found.put(key, constantId);
			}
			boolean done = found.keySet().containsAll(keys);
			return Pair.create(done, done ? found : null);
		});
	}

	/**
	 * Wait until every row in {@code baseline} has a validation constant id
	 * strictly greater than its baseline value — i.e. it has been re-validated
	 * since the baseline was captured.
	 */
	private void waitForValidationConstantIdsAdvanced(String sessionId, Long replicaId,
			Map<String, LogicalTimestamp> baseline) throws Exception {
		TimeUtils.waitFor(MAX_WAIT_MS, 1000L, () -> {
			Optional<GridHeader> header = gridViewManager.readHeader(sessionId, replicaId);
			if (header.isEmpty()) {
				return Pair.create(false, null);
			}
			Map<String, LogicalTimestamp> current = new HashMap<>();
			for (RowView row : gridViewManager.querySinglePage(header.get(), 100L, 0L)) {
				var doc = row.getRowObject().getData().getRowJsonDocument();
				if (!doc.has("a")) {
					continue;
				}
				String key = String.valueOf(doc.get("a"));
				if (!baseline.containsKey(key)) {
					continue;
				}
				LogicalTimestamp constantId = row.getRowMetadata() == null
						|| row.getRowMetadata().getRowValidation() == null ? null
								: row.getRowMetadata().getRowValidation().getConstantId();
				current.put(key, constantId);
			}
			for (Map.Entry<String, LogicalTimestamp> entry : baseline.entrySet()) {
				LogicalTimestamp newId = current.get(entry.getKey());
				if (newId == null || newId.compareTo(entry.getValue()) <= 0) {
					return Pair.create(false, null);
				}
			}
			return Pair.create(true, null);
		});
	}

	private String uploadCsv(String content) throws Exception {
		S3FileHandle fileHandle = fileHandleManager.createFileFromByteArray(admin.getId().toString(), new Date(),
				content.getBytes(StandardCharsets.UTF_8), "recordset.csv", ContentType.create("text/csv"), null);
		return fileHandle.getId();
	}

	private RecordSet createRecordSet(String dataFileHandleId) {
		RecordSet rs = new RecordSet().setName(UUID.randomUUID().toString()).setUpsertKey(List.of("a"));
		rs.setParentId(project.getId());
		rs.setDataFileHandleId(dataFileHandleId);
		return entityService.createEntity(admin.getId(), rs, null);
	}
}
