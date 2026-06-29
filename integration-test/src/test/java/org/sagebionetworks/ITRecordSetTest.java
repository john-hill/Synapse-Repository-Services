package org.sagebionetworks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.sagebionetworks.client.AsynchJobType;
import org.sagebionetworks.client.SynapseAdminClient;
import org.sagebionetworks.client.SynapseClient;
import org.sagebionetworks.client.exceptions.SynapseException;
import org.sagebionetworks.client.exceptions.SynapseNotFoundException;
import org.sagebionetworks.repo.model.Project;
import org.sagebionetworks.repo.model.RecordSet;
import org.sagebionetworks.repo.model.entity.BindSchemaToEntityRequest;
import org.sagebionetworks.repo.model.file.FileHandle;
import org.sagebionetworks.repo.model.file.FileHandleAssociateType;
import org.sagebionetworks.repo.model.file.FileHandleAssociation;
import org.sagebionetworks.repo.model.schema.CreateOrganizationRequest;
import org.sagebionetworks.repo.model.schema.CreateSchemaRequest;
import org.sagebionetworks.repo.model.schema.CreateSchemaResponse;
import org.sagebionetworks.repo.model.schema.JsonSchema;
import org.sagebionetworks.repo.model.schema.Organization;
import org.sagebionetworks.repo.model.schema.Type;
import org.sagebionetworks.repo.model.table.Query;
import org.sagebionetworks.repo.model.table.QueryOptions;
import org.sagebionetworks.repo.model.table.Row;

@ExtendWith(ITTestExtension.class)
public class ITRecordSetTest {

	private SynapseAdminClient adminSynapse;
	private SynapseClient synapse;
	private Project project;
	private File csvFile;
	private FileHandle csvFileHandle;
	private RecordSet recordSet;

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

}
