package org.sagebionetworks.agent.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.File;
import java.io.FileWriter;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.json.JSONObject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;
import org.junit.jupiter.api.extension.ExtendWith;
import org.sagebionetworks.AsynchronousJobWorkerHelper;
import org.sagebionetworks.grid.db.GridIndexDao;
import org.sagebionetworks.grid.workers.GridIntegrationTestUtils;
import org.sagebionetworks.repo.manager.UserManager;
import org.sagebionetworks.repo.manager.agent.Agent;
import org.sagebionetworks.repo.manager.agent.AgentToolContextKey;
import org.sagebionetworks.repo.manager.agent.specialist.gridupdate.GridUpdateSpecialist;
import org.sagebionetworks.repo.manager.agent.specialist.gridupdate.GridUpdateSpecialistFactory;
import org.sagebionetworks.repo.manager.file.FileHandleManager;
import org.sagebionetworks.repo.manager.file.LocalFileUploadRequest;
import org.sagebionetworks.repo.manager.grid.GridManager;
import org.sagebionetworks.repo.manager.grid.internal.replica.model.GridHeader;
import org.sagebionetworks.repo.manager.grid.internal.replica.model.RowView;
import org.sagebionetworks.repo.manager.grid.internal.replica.view.GridReplicaViewManager;
import org.sagebionetworks.repo.model.AuthorizationConstants.BOOTSTRAP_PRINCIPAL;
import org.sagebionetworks.repo.model.Project;
import org.sagebionetworks.repo.model.RecordSet;
import org.sagebionetworks.repo.model.UserInfo;
import org.sagebionetworks.repo.model.agent.GridAgentSessionContext;
import org.sagebionetworks.repo.model.entity.BindSchemaToEntityRequest;
import org.sagebionetworks.repo.model.file.S3FileHandle;
import org.sagebionetworks.repo.model.grid.CreateGridRequest;
import org.sagebionetworks.repo.model.grid.CreateGridResponse;
import org.sagebionetworks.repo.model.grid.CreateReplicaRequest;
import org.sagebionetworks.repo.model.grid.GridReplica;
import org.sagebionetworks.repo.model.grid.GridSession;
import org.sagebionetworks.repo.model.schema.CreateSchemaRequest;
import org.sagebionetworks.repo.model.schema.CreateSchemaResponse;
import org.sagebionetworks.repo.model.schema.JsonSchema;
import org.sagebionetworks.repo.model.schema.Organization;
import org.sagebionetworks.repo.model.table.CsvTableDescriptor;
import org.sagebionetworks.repo.service.EntityService;
import org.sagebionetworks.util.ClasspathUtil;
import org.sagebionetworks.util.JsonEntityUtils;
import org.sagebionetworks.util.csv.CSVWriterProviderImpl;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import au.com.bytecode.opencsv.CSVWriter;

/**
 * Real-LLM regression test for PLFM-9868: when a {@link GridUpdateSpecialist} turn is truncated at its
 * output-token limit, the tool call it was emitting never executes, yet the model still returns a
 * "success" narration — a false success. The fix has {@code Agent.chat} detect the {@code max_tokens}
 * finish reason and return {@link Agent#TRUNCATED_RESPONSE_MESSAGE} instead of that narration, so the
 * caller learns the batch was too large rather than being told it succeeded.
 * <p>
 * The fixture is deliberately <b>wide and tall</b> — {@link #ROW_COUNT} rows by
 * {@link #TARGET_COLUMN_COUNT} initially-undefined string columns — and the single test asks the
 * specialist to write an explicit value into every target cell of every row in <b>one</b>
 * {@code updateGrid} call. That is the same per-row-by-rowId apply shape seen in the PLFM-9868 agent
 * trace, scaled up so the one call cannot fit in one response (each {@code LiteralSetValue} carries the
 * long {@code org.sagebionetworks.repo.model.grid.update.LiteralSetValue} concreteType discriminator),
 * forcing the truncating turn.
 * <p>
 * The assertion verifies only that the specialist reports the truncation to its caller (returns
 * {@link Agent#TRUNCATED_RESPONSE_MESSAGE}) rather than a false success. How a supervisor recovers from
 * that message — by re-delegating the work in smaller pieces — is a separate concern tested elsewhere;
 * here the test is the supervisor and just receives the report.
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(locations = { "classpath:test-context.xml" })
@TestInstance(Lifecycle.PER_CLASS)
public class GridUpdateSpecialistMaxTokenIntegrationTest {

	private static final long MAX_WAIT_MS = 1000L * 60 * 2;
	private static final long INTERNAL_REPLICA_ID = 66534L;

	private static final String SCHEMA_PATH = "schema/GridUpdateSpecialistMaxToken.json";

	// Sized so a single literal batch over every target cell (ROW_COUNT * TARGET_COLUMN_COUNT
	// assignments, each carrying a verbose concreteType) cannot fit in one response within the
	// specialist's output-token budget, so the apply only completes if the specialist splits it across
	// multiple updateGrid batches.
	private static final int ROW_COUNT = 16;
	private static final int TARGET_COLUMN_COUNT = 12;

	@Autowired
	private GridUpdateSpecialistFactory specialistFactory;
	@Autowired
	private UserManager userManager;
	@Autowired
	private EntityService entityService;
	@Autowired
	private FileHandleManager fileHandleManager;
	@Autowired
	private AsynchronousJobWorkerHelper asynchronousJobWorkerHelper;
	@Autowired
	private GridReplicaViewManager gridReplicaViewManager;
	@Autowired
	private GridManager gridManager;
	@Autowired
	private GridIndexDao gridIndexDao;
	@Autowired
	private GridIntegrationTestUtils gridTestUtils;

	private UserInfo admin;
	private GridSession session;
	private GridAgentSessionContext gridContext;

	@BeforeAll
	public void beforeAll() throws Exception {
		admin = userManager.getUserInfo(BOOTSTRAP_PRINCIPAL.THE_ADMIN_USER.getPrincipalId());
		entityService.truncateAll();
		gridIndexDao.truncateAll();

		// ROW_COUNT rows keyed a1..aN, each with TARGET_COLUMN_COUNT empty target columns (v01..vW). The
		// empty fields load as UNDEFINED so the test can observe each cell transition to its literal value.
		File temp = File.createTempFile("GridUpdateSpecialistMaxTokenIntegrationTest", ".csv");
		CsvTableDescriptor csvDescriptor = new CsvTableDescriptor().setIsFirstLineHeader(true);
		try (CSVWriter writer = new CSVWriterProviderImpl().createWriter(new FileWriter(temp), csvDescriptor)) {
			String[] header = new String[TARGET_COLUMN_COUNT + 1];
			header[0] = "a";
			for (int c = 0; c < TARGET_COLUMN_COUNT; c++) {
				header[c + 1] = column(c);
			}
			writer.writeNext(header);
			for (int r = 0; r < ROW_COUNT; r++) {
				String[] row = new String[TARGET_COLUMN_COUNT + 1];
				row[0] = key(r);
				writer.writeNext(row);
			}
		}

		S3FileHandle fh = fileHandleManager.uploadLocalFile(new LocalFileUploadRequest().withFileToUpload(temp)
				.withContentType("text/csv").withFileName(temp.getName()).withUserId(admin.getId().toString()));
		temp.delete();

		Project project = entityService.createEntity(admin.getId(),
				new Project().setName("GridUpdateSpecialistMaxTokenTest"), null);

		RecordSet recordSet = entityService.createEntity(admin.getId(),
				new RecordSet().setName("update-specialist-max-token-set").setParentId(project.getId())
						.setDataFileHandleId(fh.getId()).setUpsertKey(List.of("a")), null);

		String schema$id = createJsonSchema();
		entityService.bindSchemaToEntity(admin.getId(),
				new BindSchemaToEntityRequest().setEntityId(recordSet.getId()).setSchema$id(schema$id));

		session = asynchronousJobWorkerHelper.assertJobResponse(admin,
				new CreateGridRequest().setRecordSetId(recordSet.getId()), (CreateGridResponse response) -> {
					assertNotNull(response.getGridSession());
				}, MAX_WAIT_MS).getResponse().getGridSession();
		assertNotNull(session);

		gridTestUtils.waitForRowCount(session.getSessionId(), INTERNAL_REPLICA_ID, ROW_COUNT);

		GridReplica usersReplica = gridManager
				.createReplica(admin, new CreateReplicaRequest().setGridSessionId(session.getSessionId())).getReplica();
		// The update tool resolves the agent's write connection from agentsReplicaId; in production
		// GridContextValidatorHandler sets it. Create the agent replica directly here.
		GridReplica agentReplica = gridManager.createAgentReplica(admin, session);

		gridContext = new GridAgentSessionContext().setGridSessionId(session.getSessionId())
				.setUsersReplicaId(usersReplica.getReplicaId()).setAgentsReplicaId(agentReplica.getReplicaId());
	}

	@Test
	public void testLargeSingleBatchApplyTruncates() throws Exception {
		Agent specialist = specialistFactory.create();

		Map<String, String> rowIdByKey = readRowIdsByKey();

		// Build one explicit literal value per (row, column) and an instruction that mandates a SINGLE
		// updateGrid call. This mirrors the supervisor's "Please APPLY ... There are N rows" message from
		// the PLFM-9868 trace, scaled up so the one call cannot fit in one response and the turn ends
		// truncated (finish reason max_tokens) with the tool call never executed.
		StringBuilder instruction = new StringBuilder();
		instruction.append("Please APPLY the following updates to the grid in a SINGLE updateGrid call. Update ")
				.append("each row identified by its exact rowId with the exact values listed. Do not split the ")
				.append("work across multiple calls — put every row in one updateGrid batch. There are ")
				.append(ROW_COUNT).append(" rows to apply.\n");
		for (int r = 0; r < ROW_COUNT; r++) {
			String key = key(r);
			String rowId = rowIdByKey.get(key);
			assertNotNull(rowId, "Missing rowId for fixture key: " + key);

			instruction.append("rowId ").append(rowId).append(":");
			for (int c = 0; c < TARGET_COLUMN_COUNT; c++) {
				String col = column(c);
				instruction.append(' ').append(col).append("=\"").append(value(r, c)).append('"');
				if (c < TARGET_COLUMN_COUNT - 1) {
					instruction.append(',');
				}
			}
			instruction.append('\n');
		}

		// call under test
		String response = specialist.chat(instruction.toString(), toolContext());

		// The oversized single batch truncated the turn at the output-token limit, so instead of the
		// model's misleading "success" narration the specialist reports the truncation to its caller.
		assertEquals(Agent.TRUNCATED_RESPONSE_MESSAGE, response);
	}

	/**
	 * Builds the tool context the caller hands to the specialist: the acting user and the grid session
	 * context that scopes and authorizes the batch update the specialist applies.
	 */
	private ToolContext toolContext() {
		Map<String, Object> context = new HashMap<>();
		AgentToolContextKey.USER_INFO.put(context, admin);
		AgentToolContextKey.GRID_SESSION_CONTEXT.put(context, gridContext);
		return new ToolContext(context);
	}

	/** Map of every fixture row's "a" key to its composite replicaId.sequenceNumber rowId, from the internal view. */
	private Map<String, String> readRowIdsByKey() {
		GridHeader header = gridReplicaViewManager.readHeader(session.getSessionId(), INTERNAL_REPLICA_ID).get();
		Map<String, String> byKey = new LinkedHashMap<>();
		for (RowView row : gridReplicaViewManager.querySinglePage(header, (long) ROW_COUNT + 1, 0L)) {
			JSONObject doc = row.getRowObject().getData().getRowJsonDocument();
			if (doc.has("a")) {
				byKey.put(String.valueOf(doc.get("a")), row.getRowId());
			}
		}
		return byKey;
	}

	/** The "a" key for fixture row index r (0-based): a1..aN. */
	private static String key(int r) {
		return "a" + (r + 1);
	}

	/** The target column name for column index c (0-based): v01..vW. */
	private static String column(int c) {
		return String.format("v%02d", c + 1);
	}

	/** The distinct literal value to write into cell (row r, column c). */
	private static String value(int r, int c) {
		return key(r) + "-" + column(c);
	}

	private String createJsonSchema() throws Exception {
		Organization org = asynchronousJobWorkerHelper.getOrCreateOrganization(admin.getId(),
				"GridUpdateSpecialistMaxTokenIntegrationTest");
		JsonSchema jsonSchema = JsonEntityUtils.fromJsonString(ClasspathUtil.loadFromClasspath(SCHEMA_PATH),
				JsonSchema.class);
		jsonSchema.set$id(org.getName() + "-gridupdatespecialistmaxtoken");
		return asynchronousJobWorkerHelper.assertJobResponse(admin,
				new CreateSchemaRequest().setDryRun(false).setSchema(jsonSchema), (CreateSchemaResponse response) -> {
					assertNotNull(response.getNewVersionInfo());
					assertNotNull(response.getNewVersionInfo().get$id());
				}, MAX_WAIT_MS).getResponse().getNewVersionInfo().get$id();
	}
}
