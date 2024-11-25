package org.sagebionetworks.repo.model.dbo.limits;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.sagebionetworks.repo.model.AuthorizationConstants.BOOTSTRAP_PRINCIPAL;
import org.sagebionetworks.repo.model.EntityType;
import org.sagebionetworks.repo.model.dbo.DBOBasicDao;
import org.sagebionetworks.repo.model.dbo.SinglePrimaryKeySqlParameterSource;
import org.sagebionetworks.repo.model.helper.FileHandleObjectHelper;
import org.sagebionetworks.repo.model.helper.NodeDaoObjectHelper;
import org.sagebionetworks.repo.model.helper.StorageLocationHelper;
import org.sagebionetworks.repo.model.jdo.KeyFactory;
import org.sagebionetworks.repo.model.limits.ProjectStorageData;
import org.sagebionetworks.repo.model.limits.ProjectStorageLocationLimit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(locations = { "classpath:jdomodels-test-context.xml" })
public class ProjectStorageLimitsDaoImplTest {

	@Autowired
	private ProjectStorageLimitsDaoImpl dao;
	
	@Autowired
	private NodeDaoObjectHelper nodeHelper;
	
	@Autowired
	private StorageLocationHelper locationHelper;
	
	@Autowired
	private FileHandleObjectHelper fileHelper;
	
	@Autowired
	private DBOBasicDao basicDao;
	
	private Long projectOneId;
	private Long projectTwoId;
	private Long projectThreeId;
	
	private Long sLocOneId;
	private Long sLocTwoId;
	private Long sLocThreeId;
	
	@BeforeEach
	public void before() {
		nodeHelper.truncateAll();
		dao.truncateAll();
		fileHelper.truncateAll();
		locationHelper.truncateAll();
		
		projectOneId = KeyFactory.stringToKey(nodeHelper.create(node -> node.setNodeType(EntityType.project)).getId());
		projectTwoId = KeyFactory.stringToKey(nodeHelper.create(node -> node.setNodeType(EntityType.project)).getId());
		projectThreeId = KeyFactory.stringToKey(nodeHelper.create(node -> node.setNodeType(EntityType.project)).getId());
		
		sLocOneId = locationHelper.create(location -> {}).getStorageLocationId();
		sLocTwoId = locationHelper.create(location -> {}).getStorageLocationId();
		sLocThreeId = locationHelper.create(location -> {}).getStorageLocationId();
	}

	@AfterEach
	public void after() {
		dao.truncateAll();
		nodeHelper.truncateAll();
		fileHelper.truncateAll();
		locationHelper.truncateAll();
	}
	
	@Test
	public void testSetAndGetStorageData() throws InterruptedException {
				
		// Call under test
		assertEquals(Optional.empty(), dao.getStorageData(projectOneId));
		assertEquals(Optional.empty(), dao.getStorageData(projectTwoId));
		assertEquals(Optional.empty(), dao.getStorageData(projectThreeId));

		List<ProjectStorageData> projectsData = List.of(new ProjectStorageData()
				.setProjectId(projectOneId)
				.setRuntimeMs(1000L)
				.setStorageLocationData(Map.of(sLocOneId.toString(), 1024L, sLocTwoId.toString(), 3072L)),
			new ProjectStorageData()
				.setProjectId(projectTwoId)
				.setRuntimeMs(1024L)
				.setStorageLocationData(Map.of(sLocTwoId.toString(), 2048L, sLocThreeId.toString(), 4096L)),
			new ProjectStorageData()
				.setProjectId(projectThreeId)
				.setRuntimeMs(2048L)
				.setStorageLocationData(Collections.emptyMap())
		);
		
		// Call under test
		dao.setStorageData(projectsData);
		
		Thread.sleep(1000);
				
		for (ProjectStorageData expectedData : projectsData) {
			ProjectStorageData fetchedProjectData = dao.getStorageData(expectedData.getProjectId()).orElseThrow();
			
			// Etag and modifiedOn are generated
			assertNotNull(fetchedProjectData.getEtag());
			assertNotNull(fetchedProjectData.getModifiedOn());
			
			expectedData.setEtag(fetchedProjectData.getEtag()).setModifiedOn(fetchedProjectData.getModifiedOn());
			assertEquals(expectedData, fetchedProjectData);
			
			// Storing again should update the etag and updatedOn
			dao.setStorageData(List.of(expectedData));
			
			fetchedProjectData = dao.getStorageData(expectedData.getProjectId()).orElseThrow();
			
			assertNotEquals(expectedData.getEtag(), fetchedProjectData.getEtag());
			assertNotEquals(expectedData.getModifiedOn(), fetchedProjectData.getModifiedOn());
			
			dao.deleteStorageData(expectedData.getProjectId());
			
			assertEquals(Optional.empty(), dao.getStorageData(expectedData.getProjectId()));
		}
	}
	
	@Test
	public void testIsStorageDataModifiedOnAfter() {
		Instant instant = Instant.now().minusSeconds(1);
		
		assertFalse(dao.isStorageDataModifiedOnAfter(projectOneId, instant));
		
		dao.setStorageData(List.of(new ProjectStorageData().setProjectId(projectOneId).setRuntimeMs(1024L)));
		
		assertTrue(dao.isStorageDataModifiedOnAfter(projectOneId, instant));
		
		assertFalse(dao.isStorageDataModifiedOnAfter(projectOneId, instant.plusSeconds(60)));
	}
	
	@Test
	public void testGetAndSetStorageLimits() {
		Long userId = BOOTSTRAP_PRINCIPAL.THE_ADMIN_USER.getPrincipalId();
				
		// Call under test
		assertEquals(Collections.emptyList(), dao.getStorageLocationLimits(projectOneId));
		assertEquals(Collections.emptyList(), dao.getStorageLocationLimits(projectTwoId));
				
		// Call under test
		assertEquals(Optional.empty(), dao.getStorageLocationLimit(projectOneId, sLocOneId));
		assertEquals(Optional.empty(), dao.getStorageLocationLimit(projectOneId, sLocOneId));
		
		List<ProjectStorageLocationLimit> limits = List.of(
			new ProjectStorageLocationLimit()
				.setProjectId(KeyFactory.keyToString(projectOneId))
				.setStorageLocationId(sLocOneId)
				.setMaxAllowedFileBytes(1024L),
			new ProjectStorageLocationLimit()
				.setProjectId(KeyFactory.keyToString(projectOneId))
				.setStorageLocationId(sLocTwoId)
				.setMaxAllowedFileBytes(2048L),
			new ProjectStorageLocationLimit()
				.setProjectId(KeyFactory.keyToString(projectTwoId))
				.setStorageLocationId(sLocTwoId)
				.setMaxAllowedFileBytes(3072L),
			new ProjectStorageLocationLimit()
				.setProjectId(KeyFactory.keyToString(projectTwoId))
				.setStorageLocationId(sLocThreeId)
				.setMaxAllowedFileBytes(null)
		);

		limits.forEach(limit -> {

			// Call under test
			ProjectStorageLocationLimit stored = dao.setStorageLocationLimit(userId, limit);
			
			assertEquals(limit, stored);
			
			// Call under test
			assertEquals(Optional.of(limit), dao.getStorageLocationLimit(KeyFactory.stringToKey(limit.getProjectId()), Long.valueOf(limit.getStorageLocationId())));
		});
		
		// Call under test
		assertEquals(Optional.empty(), dao.getStorageLocationLimit(projectTwoId, sLocOneId));
		
		// Call under test
		assertEquals(limits.subList(0, 2), dao.getStorageLocationLimits(projectOneId));
		assertEquals(limits.subList(2, 4), dao.getStorageLocationLimits(projectTwoId));
		
	}
	
	// Test for https://sagebionetworks.jira.com/browse/PLFM-8706
	@Test
	public void testDBOProjectStorageLimitWithNullLimit() {
		Date now = new Date(1731452665000l);
		
		DBOProjectStorageLimit dbo = new DBOProjectStorageLimit()
			.setId(123L)
			.setProjectId(projectOneId)
			.setStorageLocationId(sLocOneId)
			.setCreatedBy(BOOTSTRAP_PRINCIPAL.THE_ADMIN_USER.getPrincipalId())
			.setCreatedOn(now)
			.setEtag(UUID.randomUUID().toString())
			.setModifiedBy(BOOTSTRAP_PRINCIPAL.THE_ADMIN_USER.getPrincipalId())
			.setModifiedOn(now)
			.setMaxBytes(null);
		
		basicDao.createNew(dbo);
		
		assertEquals(Optional.of(dbo), basicDao.getObjectByPrimaryKey(DBOProjectStorageLimit.class, new SinglePrimaryKeySqlParameterSource(123L)));
	}
 }
