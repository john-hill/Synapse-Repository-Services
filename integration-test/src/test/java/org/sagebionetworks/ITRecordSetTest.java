package org.sagebionetworks;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.sagebionetworks.client.SynapseAdminClient;
import org.sagebionetworks.client.SynapseClient;
import org.sagebionetworks.client.exceptions.SynapseException;
import org.sagebionetworks.repo.model.Project;
import org.sagebionetworks.repo.model.RecordSet;
import org.sagebionetworks.repo.model.file.FileHandle;
import org.sagebionetworks.repo.model.file.FileHandleAssociateType;
import org.sagebionetworks.repo.model.file.FileHandleAssociation;
import org.sagebionetworks.repo.model.table.MaterializedView;
import org.sagebionetworks.repo.model.table.Query;
import org.sagebionetworks.repo.model.table.QueryOptions;
import org.sagebionetworks.repo.model.table.Row;

@ExtendWith(ITTestExtension.class)
public class ITRecordSetTest {

	private static final long INDEX_TIMEOUT_MS = 1000L * 60 * 5;

	private SynapseAdminClient adminSynapse;
	private SynapseClient synapse;
	private Project project;
	private File csvFile;
	private FileHandle csvFileHandle;
	private RecordSet recordSet;
	private MaterializedView materializedView;
	
	public ITRecordSetTest(SynapseAdminClient adminSynapse, SynapseClient synapse) {
		this.adminSynapse = adminSynapse;
		this.synapse = synapse;
	}
	
	@BeforeEach
	public void before() throws FileNotFoundException, SynapseException, IOException {
		adminSynapse.clearAllLocks();
		// Create a project, this will own the file entity
		project = new Project();
		project = synapse.createEntity(project);
		
		csvFile = new File(ITRecordSetTest.class.getClassLoader().getResource("docs/test.csv").getFile().replaceAll("%20", " "));

		csvFileHandle = synapse.multipartUpload(csvFile, null, false, true);
	}
	
	@AfterEach
	public void after() throws Exception {

		if (materializedView != null) {
			synapse.deleteEntity(materializedView, true);
		}

		if (recordSet != null) {
			synapse.deleteEntity(recordSet, true);
		}

		if (project != null){
			synapse.deleteEntity(project, true);
		}
	}
	
	@Test
	public void testRecordSet() throws SynapseException, IOException {
		recordSet = new RecordSet();
		
		recordSet.setParentId(project.getId());
		recordSet.setName("Record Set");
		recordSet.setUpsertKey(List.of("a", "b"));
		recordSet.setDataFileHandleId(csvFileHandle.getId());
	
		// Call under test
		recordSet = synapse.createEntity(recordSet);

		// Makes sure we can get a presigned URL for the file
		URL url = synapse.getFileURL(new FileHandleAssociation()
			.setAssociateObjectType(FileHandleAssociateType.FileEntity)
			.setAssociateObjectId(recordSet.getId())
			.setFileHandleId(recordSet.getDataFileHandleId())
		);
		
		assertEquals(FileUtils.readFileToString(csvFile, StandardCharsets.UTF_8), IOUtils.toString(url, StandardCharsets.UTF_8));

	}

	@Test
	public void testQueryRecordSet() throws Exception {
		recordSet = createRecordSet(csvFileHandle.getId());

		// call under test — assertions inside the consumer so AsyncJobHelper retries
		// while the worker is still building the index.
		queryAndAssertExpectedRows(recordSet.getId());
	}

	@Test
	public void testQueryRecordSetByExplicitVersion() throws Exception {
		recordSet = createRecordSet(csvFileHandle.getId());

		// call under test — querying with the explicit version hits the immutable T{id}_{v} snapshot.
		queryAndAssertExpectedRows(recordSet.getId() + "." + recordSet.getVersionNumber());
	}

	@Test
	public void testQueryRecordSetThroughMaterializedView() throws Exception {
		recordSet = createRecordSet(csvFileHandle.getId());

		// Wait for the RecordSet index so the MV build sees rows to copy.
		queryAndAssertExpectedRows(recordSet.getId());

		materializedView = new MaterializedView()
				.setDefiningSQL(String.format("select * from %s", recordSet.getId()))
				.setName(UUID.randomUUID().toString())
				.setParentId(project.getId());
		materializedView = synapse.createEntity(materializedView);

		// call under test — same retry posture for the MV build.
		queryAndAssertExpectedRows(materializedView.getId());
	}

	private RecordSet createRecordSet(String dataFileHandleId) throws SynapseException {
		RecordSet rs = new RecordSet();
		rs.setParentId(project.getId());
		rs.setName(UUID.randomUUID().toString());
		rs.setUpsertKey(List.of("a"));
		rs.setDataFileHandleId(dataFileHandleId);
		return synapse.createEntity(rs);
	}

	/**
	 * Queries the given table/MV with retries: docs/test.csv has two data rows
	 * ("1,2,3" and "4,5,6") after the header. Assertions live inside the consumer
	 * so AsyncJobHelper restarts the async query until the worker has built the
	 * index (or the timeout expires).
	 */
	private void queryAndAssertExpectedRows(String tableId) throws Exception {
		Query query = new Query().setSql("select * from " + tableId);
		QueryOptions options = new QueryOptions().withMask((long) SynapseClient.QUERY_PARTMASK);
		AsyncJobHelper.assertQueryBundleResults(
				synapse,
				tableId,
				query,
				options,
				bundle -> {
					List<Row> rows = bundle.getQueryResult().getQueryResults().getRows();
					assertEquals(2, rows.size());
					assertEquals(List.of("1", "2", "3"), rows.get(0).getValues());
					assertEquals(List.of("4", "5", "6"), rows.get(1).getValues());
				},
				INDEX_TIMEOUT_MS,
				AsyncJobHelper.INFINITE_RETRIES);
	}

}
