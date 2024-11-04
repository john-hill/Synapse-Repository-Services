package org.sagebionetworks;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.sagebionetworks.client.SynapseAdminClient;
import org.sagebionetworks.client.SynapseClient;
import org.sagebionetworks.client.exceptions.SynapseException;
import org.sagebionetworks.repo.model.Project;
import org.sagebionetworks.repo.model.file.UploadDestination;
import org.sagebionetworks.repo.model.file.UploadType;
import org.sagebionetworks.repo.model.limits.ProjectStorageLocationLimit;
import org.sagebionetworks.repo.model.limits.ProjectStorageLocationUsage;
import org.sagebionetworks.repo.model.limits.ProjectStorageUsage;
import org.sagebionetworks.repo.model.project.ExternalObjectStorageLocationSetting;
import org.sagebionetworks.repo.model.project.ProjectSettingsType;
import org.sagebionetworks.repo.model.project.UploadDestinationListSetting;

@ExtendWith(ITTestExtension.class)
public class ITProjectStorageTest {

	private SynapseClient client;
	private SynapseAdminClient adminClient;
	private Long defaultMaxAllowedFileBytes;
	private Project project;
	
	public ITProjectStorageTest(StackConfiguration config, SynapseClient client, SynapseAdminClient adminClient) {
		this.client = client;
		this.adminClient = adminClient;
		this.defaultMaxAllowedFileBytes = config.getDefaultProjectStorageLimit();
	}	
	
	@BeforeEach
	public void before() throws SynapseException {
		project = client.createEntity(new Project().setName(UUID.randomUUID().toString()));
	}
	
	@AfterEach
	public void after() throws SynapseException {
		adminClient.deleteEntityById(project.getId(), true);
	}
	
	@Test
	public void testProjectStorageUsageAndLimits() throws SynapseException {
				
		ProjectStorageLocationUsage expectedDefaultLocationUsage = new ProjectStorageLocationUsage()
			.setStorageLocationId(1L)
			.setSumFileBytes(0L)
			.setMaxAllowedFileBytes(defaultMaxAllowedFileBytes)
			.setIsOverLimit(false);
		
		assertEquals(new ProjectStorageUsage()
			.setProjectId(project.getId())
			.setLocations(List.of(expectedDefaultLocationUsage)), 
			client.getProjectStorageUsage(project.getId())
		);
		
		// The default upload destination now contains the usage as well
		UploadDestination defaultUploadDestination = client.getDefaultUploadDestination(project.getId()); 
		
		assertEquals(project.getId(), defaultUploadDestination.getDestinationProjectId());
		assertEquals(expectedDefaultLocationUsage, defaultUploadDestination.getProjectStorageLocationUsage());
		
		// Remove the limit on the project
		adminClient.setProjectStorageLocationLimit(new ProjectStorageLocationLimit()
			.setProjectId(project.getId())
			.setStorageLocationId(expectedDefaultLocationUsage.getStorageLocationId())
			.setMaxAllowedFileBytes(null)
		);
		
		expectedDefaultLocationUsage.setMaxAllowedFileBytes(null);
		
		assertEquals(new ProjectStorageUsage()
			.setProjectId(project.getId())
			.setLocations(List.of(expectedDefaultLocationUsage)),
			client.getProjectStorageUsage(project.getId())
		);
		
		// Add a storage location to the project
		Long externalStorageLocationId = client.createStorageLocationSetting(new ExternalObjectStorageLocationSetting()
			.setBucket("some-bucket")
			.setEndpointUrl("https://someurl.com")
			.setUploadType(UploadType.S3)).getStorageLocationId();
		
		client.createProjectSetting(new UploadDestinationListSetting()
			.setProjectId(project.getId())
			.setSettingsType(ProjectSettingsType.upload)
			.setLocations(List.of(externalStorageLocationId))
		);
		
		ProjectStorageLocationUsage expectedExternalLocationUsage = new ProjectStorageLocationUsage()
			.setStorageLocationId(externalStorageLocationId)
			.setSumFileBytes(0L)
			.setMaxAllowedFileBytes(null)
			.setIsOverLimit(false);
		
		assertEquals(new ProjectStorageUsage()
			.setProjectId(project.getId())
			.setLocations(List.of(
				expectedDefaultLocationUsage,
				expectedExternalLocationUsage
			)), client.getProjectStorageUsage(project.getId())
		);
		
		// The default upload destination is now the external location and contains the usage as well
		defaultUploadDestination = client.getDefaultUploadDestination(project.getId()); 
		
		assertEquals(project.getId(), defaultUploadDestination.getDestinationProjectId());
		assertEquals(expectedExternalLocationUsage, defaultUploadDestination.getProjectStorageLocationUsage());
		
		// Add a limit on the external storage location
		adminClient.setProjectStorageLocationLimit(new ProjectStorageLocationLimit()
			.setProjectId(project.getId())
			.setStorageLocationId(externalStorageLocationId)
			.setMaxAllowedFileBytes(100L)
		);
		
		expectedExternalLocationUsage.setMaxAllowedFileBytes(100L);
		
		assertEquals(new ProjectStorageUsage()
			.setProjectId(project.getId())
			.setLocations(List.of(
				expectedDefaultLocationUsage,
				expectedExternalLocationUsage
			)), client.getProjectStorageUsage(project.getId())
		);
	}

}
