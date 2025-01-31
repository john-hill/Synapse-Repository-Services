package org.sagebionetworks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.File;
import java.net.URISyntaxException;
import java.net.URL;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.sagebionetworks.client.SynapseAdminClient;
import org.sagebionetworks.client.SynapseClient;
import org.sagebionetworks.client.exceptions.SynapseBadRequestException;
import org.sagebionetworks.client.exceptions.SynapseException;
import org.sagebionetworks.repo.model.ErrorResponseCode;
import org.sagebionetworks.repo.model.FileEntity;
import org.sagebionetworks.repo.model.Project;
import org.sagebionetworks.repo.model.file.CloudProviderFileHandleInterface;
import org.sagebionetworks.repo.model.file.UploadDestination;
import org.sagebionetworks.repo.model.file.UploadType;
import org.sagebionetworks.repo.model.jdo.KeyFactory;
import org.sagebionetworks.repo.model.limits.ProjectStorageLocationLimit;
import org.sagebionetworks.repo.model.limits.ProjectStorageLocationUsage;
import org.sagebionetworks.repo.model.limits.ProjectStorageUsage;
import org.sagebionetworks.repo.model.project.ExternalObjectStorageLocationSetting;
import org.sagebionetworks.repo.model.project.ProjectSettingsType;
import org.sagebionetworks.repo.model.project.UploadDestinationListSetting;
import org.sagebionetworks.util.Pair;
import org.sagebionetworks.util.TimeUtils;
import org.sagebionetworks.warehouse.WarehouseTestHelper;

@ExtendWith(ITTestExtension.class)
public class ITProjectStorageTest {

	private SynapseClient client;
	private SynapseAdminClient adminClient;
	private Long defaultMaxAllowedFileBytes;
	private Project project;
	private FileEntity fileEntity;
	private WarehouseTestHelper warehouseHelper;
	
	public ITProjectStorageTest(StackConfiguration config, SynapseClient client, SynapseAdminClient adminClient, WarehouseTestHelper warehouseHelper) {
		this.client = client;
		this.adminClient = adminClient;
		this.defaultMaxAllowedFileBytes = config.getDefaultProjectStorageLimit();
		this.warehouseHelper = warehouseHelper;
	}	
	
	@BeforeEach
	public void before() throws SynapseException {
		project = client.createEntity(new Project().setName(UUID.randomUUID().toString()));
	}
	
	@AfterEach
	public void after() throws SynapseException {
		adminClient.deleteEntityById(fileEntity.getId(), true);
		adminClient.deleteEntityById(project.getId(), true);
	}
	
	@Test
	public void testProjectStorageUsageAndLimits() throws Exception {
				
		ProjectStorageLocationUsage expectedDefaultLocationUsage = new ProjectStorageLocationUsage()
			.setStorageLocationId(1L)
			.setSumFileBytes(0L)
			.setMaxAllowedFileBytes(defaultMaxAllowedFileBytes)
			.setIsOverLimit(false);
		
		ProjectStorageUsage expectedStorageUsage = new ProjectStorageUsage()
			.setProjectId(project.getId())
			.setLocations(List.of(expectedDefaultLocationUsage)); 
		
		assertEquals(expectedStorageUsage, client.getProjectStorageUsage(project.getId()));
		
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
		
		assertEquals(expectedStorageUsage, client.getProjectStorageUsage(project.getId()));
		
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
		
		expectedStorageUsage.setLocations(List.of(
			expectedDefaultLocationUsage,
			expectedExternalLocationUsage
		));
		
		assertEquals(expectedStorageUsage, client.getProjectStorageUsage(project.getId()));
		
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
		
		assertEquals(expectedStorageUsage, client.getProjectStorageUsage(project.getId()));
		
		// Now set a low limit on the default storage location and upload a file
		adminClient.setProjectStorageLocationLimit(new ProjectStorageLocationLimit()
			.setProjectId(project.getId())
			.setStorageLocationId(expectedDefaultLocationUsage.getStorageLocationId())
			.setMaxAllowedFileBytes(100L)
		);
		
		CloudProviderFileHandleInterface fileHandle = client.multipartUpload(getTestFile(), null, false, true);
		
		fileEntity = client.createEntity(new FileEntity()
			.setParentId(project.getId())
			.setName(UUID.randomUUID().toString())
			.setDataFileHandleId(fileHandle.getId())
		);
		
		expectedDefaultLocationUsage
			.setMaxAllowedFileBytes(100L)
			.setSumFileBytes(fileHandle.getContentSize())
			.setIsOverLimit(true);
				
		TimeUtils.waitFor(180_000, 1000, () -> {
			return Pair.create(expectedStorageUsage.equals(client.getProjectStorageUsage(project.getId())), null);
		});
		
		// Now that the limit is exceeded creating another file is not allowed
		
		SynapseBadRequestException ex = assertThrows(SynapseBadRequestException.class, () -> {
			client.createEntity(new FileEntity()
				.setParentId(project.getId())
				.setName(UUID.randomUUID().toString())
				.setDataFileHandleId(fileHandle.getId())
			);
		});

		assertEquals(ErrorResponseCode.PROJECT_STORAGE_LIMIT_EXCEEDED, ex.getErrorResponseCode());
		assertEquals("The project storage usage exceeds the limit for the storage location (Project: " + project.getId() + ", Storage Location: 1, Usage: "
			+ fileHandle.getContentSize() + " Bytes, Limit: 100 Bytes).", ex.getMessage());
				
		// Updates the project to trigger a node snapshot
		project = client.putEntity(project.setName(UUID.randomUUID().toString()));
		
		Instant now = Instant.now();
		
		String query = String.format(
			"select count(*) from nodesnapshots"
			+ " where snapshot_date %s"
			+ " and id = %s"
			+ " and any_match(project_storage_usage.locations, l -> l.storageLocationId = %s and l.isOverLimit = true)"
			+ " and any_match(project_storage_usage.locations, l -> l.storageLocationId = %s and l.isOverLimit = false)",
			warehouseHelper.toDateStringBetweenPlusAndMinusThirtySeconds(now),
			KeyFactory.stringToKey(project.getId()),
			expectedDefaultLocationUsage.getStorageLocationId().toString(),
			externalStorageLocationId.toString()
		);
		
		warehouseHelper.assertWarehouseQuery(query);
		
		// Sleeping gives the snapshot worker a chance to take the snapshots before the test suite deletes the project.
		Thread.sleep(10_000);
	}
	
	private File getTestFile() {
		URL fileUrl = ITProjectStorageTest.class.getClassLoader().getResource("images/LittleImage.png");
		try {
			return new File(fileUrl.toURI());
		} catch (URISyntaxException e) {
			throw new RuntimeException(e);
		}
	}
	
	

}
