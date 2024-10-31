package org.sagebionetworks.limits.workers;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.sagebionetworks.AsynchronousJobWorkerHelper;
import org.sagebionetworks.StackConfiguration;
import org.sagebionetworks.repo.manager.UserManager;
import org.sagebionetworks.repo.manager.limits.ProjectStorageLimitManager;
import org.sagebionetworks.repo.model.AuthorizationConstants.BOOTSTRAP_PRINCIPAL;
import org.sagebionetworks.repo.model.FileEntity;
import org.sagebionetworks.repo.model.NodeDAO;
import org.sagebionetworks.repo.model.Project;
import org.sagebionetworks.repo.model.UserInfo;
import org.sagebionetworks.repo.model.dbo.limits.ProjectStorageLimitsDao;
import org.sagebionetworks.repo.model.helper.FileHandleObjectHelper;
import org.sagebionetworks.repo.model.jdo.KeyFactory;
import org.sagebionetworks.repo.model.limits.ProjectStorageLocationUsage;
import org.sagebionetworks.repo.model.limits.ProjectStorageUsage;
import org.sagebionetworks.repo.model.table.ReplicationType;
import org.sagebionetworks.repo.service.EntityService;
import org.sagebionetworks.table.cluster.TableIndexDAO;
import org.sagebionetworks.util.Pair;
import org.sagebionetworks.util.TimeUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(locations = { "classpath:test-context.xml" })
public class ProjectStorageDataRefreshWorkerIntegrationTest {
	
	private static final long MAX_WAIT = 60 * 1000 * 2;
	
	@Autowired
	private ProjectStorageLimitsDao storageLimitsDao;
	
	@Autowired
	private ProjectStorageLimitManager manager;

	@Autowired
	private EntityService entityService;
	
	@Autowired
	private FileHandleObjectHelper fileHelper;
	
	@Autowired
	private UserManager userManager;
	
	@Autowired
	private TableIndexDAO replicationDao;
	
	@Autowired
	private NodeDAO nodeDao;
	
	@Autowired
	private AsynchronousJobWorkerHelper asyncHelper;
	
	@Autowired
	private StackConfiguration config;
	
	private UserInfo adminUser;
	
	private Long defaultLocationMaxBytes;
	
	@BeforeEach
	public void before() {
		storageLimitsDao.truncateAll();
		nodeDao.truncateAll();
		fileHelper.truncateAll();
		
		adminUser = userManager.getUserInfo(BOOTSTRAP_PRINCIPAL.THE_ADMIN_USER.getPrincipalId());
		defaultLocationMaxBytes = config.getDefaultProjectStorageLimit();
	}
	
	@AfterEach
	public void after() {
		storageLimitsDao.truncateAll();
		nodeDao.truncateAll();
		fileHelper.truncateAll();
	}

	@Test
	public void testRefreshProjectData() throws Exception {
		String projectId = entityService.createEntity(adminUser.getId(), new Project().setName("TestProject"), null).getId();
		
		String fileOneId = entityService.createEntity(adminUser.getId(), new FileEntity().setName("fileOne").setParentId(projectId)
				.setDataFileHandleId(fileHelper.create(file -> file.setStorageLocationId(1L).setContentSize(1024L)).getId()), null).getId();
		
		String fileTwoId = entityService.createEntity(adminUser.getId(), new FileEntity().setName("fileTwo").setParentId(projectId)
				.setDataFileHandleId(fileHelper.create(file -> file.setStorageLocationId(1L).setContentSize(2048L)).getId()), null).getId();
		
		// Wait for the data to be up-to-date in the replication index
		asyncHelper.waitForEntityReplication(adminUser, projectId, MAX_WAIT);
		asyncHelper.waitForEntityReplication(adminUser, fileOneId, MAX_WAIT);
		asyncHelper.waitForEntityReplication(adminUser, fileTwoId, MAX_WAIT);
				
		TimeUtils.waitFor(MAX_WAIT, 1000, () -> {
			return Pair.create(new ProjectStorageUsage()
				.setProjectId(projectId)
				.setLocations(List.of(new ProjectStorageLocationUsage()
					.setStorageLocationId("1")
					.setSumFileBytes(3072L)
					.setMaxAllowedFileBytes(defaultLocationMaxBytes)
					.setIsOverLimit(false)
				)).equals(manager.getProjectStorageUsage(adminUser, projectId)), null);
		});
		
		entityService.deleteEntity(adminUser.getId(), fileTwoId);
		
		// Wait fot the replication index to update with the delete
		TimeUtils.waitFor(MAX_WAIT, 1000, () -> {
			return Pair.create(replicationDao.getObjectDataForCurrentVersion(ReplicationType.ENTITY, KeyFactory.stringToKey(fileTwoId)) == null, null);
		});
		
		// Invalidates the project storage data cache
		storageLimitsDao.deleteStorageData(KeyFactory.stringToKey(projectId));
		
		TimeUtils.waitFor(MAX_WAIT, 1000, () -> {
			return Pair.create(new ProjectStorageUsage()
				.setProjectId(projectId)
				.setLocations(List.of(new ProjectStorageLocationUsage()
					.setStorageLocationId("1")
					.setMaxAllowedFileBytes(defaultLocationMaxBytes)
					.setSumFileBytes(1024L)
					.setIsOverLimit(false)
				)).equals(manager.getProjectStorageUsage(adminUser, projectId)), null);
		});
		
	}

}
