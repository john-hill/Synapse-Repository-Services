package org.sagebionetworks.repo.manager.limits;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.EnumSource.Mode;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.sagebionetworks.repo.model.AuthorizationConstants.BOOTSTRAP_PRINCIPAL;
import org.sagebionetworks.repo.model.EntityType;
import org.sagebionetworks.repo.model.NodeDAO;
import org.sagebionetworks.repo.model.ObjectType;
import org.sagebionetworks.repo.model.UserInfo;
import org.sagebionetworks.repo.model.dbo.limits.ProjectStorageLimitsDao;
import org.sagebionetworks.repo.model.limits.ProjectStorageData;
import org.sagebionetworks.repo.model.limits.ProjectStorageEvent;
import org.sagebionetworks.repo.model.limits.ProjectStorageLimitsBackfillRequest;
import org.sagebionetworks.repo.model.limits.ProjectStorageLocationLimit;
import org.sagebionetworks.repo.model.limits.ProjectStorageLocationUsage;
import org.sagebionetworks.repo.model.limits.ProjectStorageUsage;
import org.sagebionetworks.repo.model.message.TransactionalMessenger;
import org.sagebionetworks.table.cluster.TableIndexDAO;
import org.sagebionetworks.util.Clock;
import org.sagebionetworks.util.Pair;

@ExtendWith(MockitoExtension.class)
public class ProjectStorageLimitsManagerTest {

	@Mock
	private ProjectStorageLimitsDao mockDao;
	
	@Mock
	private TableIndexDAO mockReplicationDao;
	
	@Mock
	private NodeDAO mockNodeDao;
	
	@Mock
	private TransactionalMessenger mockMessenger;
	
	@Mock
	private Clock mockClock;
	
	@InjectMocks
	private ProjectStorageLimitManager manager;

	@Test
	public void testRefreshProjectStorageData() {
		Long projectId = 123L;
		
		Date now = Date.from(Instant.now());
		
		ProjectStorageData data = new ProjectStorageData().setProjectId(projectId);
		
		when(mockClock.now()).thenReturn(now);
		when(mockDao.isStorageDataModifiedOnAfter(projectId, now.toInstant().minus(Duration.ofMinutes(2)))).thenReturn(false);
		when(mockReplicationDao.computeProjectStorageData(projectId)).thenReturn(data);
		
		// Call under test
		manager.refreshProjectStorageData(projectId);
		
		verify(mockDao).setStorageData(List.of(data));
	}
	
	@Test
	public void testRefreshProjectStorageDataWithUptodate() {
		Long projectId = 123L;
		
		Date now = Date.from(Instant.now());
		
		when(mockClock.now()).thenReturn(now);
		when(mockDao.isStorageDataModifiedOnAfter(projectId, now.toInstant().minus(Duration.ofMinutes(2)))).thenReturn(true);
		
		// Call under test
		manager.refreshProjectStorageData(projectId);
		
		verifyNoMoreInteractions(mockDao);
	}
	
	@Test
	public void getProjectStorageUsageWithNoStorageData() {
		String projectId = "syn123";
		Long projectIdLong = 123L;
		
		when(mockNodeDao.getNodeTypeById(projectId)).thenReturn(EntityType.project);
		when(mockDao.getStorageLocationLimits(projectIdLong)).thenReturn(List.of(
			new ProjectStorageLocationLimit().setStorageLocationId("1").setMaxAllowedFileBytes(1024L),
			new ProjectStorageLocationLimit().setStorageLocationId("2").setMaxAllowedFileBytes(2048L)
		));
		
		when(mockDao.getStorageData(projectIdLong)).thenReturn(Optional.empty());
		
		ProjectStorageUsage expected = new ProjectStorageUsage()
			.setProjectId(projectId)
			.setLocations(List.of(
				new ProjectStorageLocationUsage().setStorageLocationId("1").setMaxAllowedFileBytes(1024L).setIsOverLimit(false).setSumFileBytes(0L),
				new ProjectStorageLocationUsage().setStorageLocationId("2").setMaxAllowedFileBytes(2048L).setIsOverLimit(false).setSumFileBytes(0L)				
			));
		
		// Call under test
		assertEquals(expected, manager.gerProjectStorageUsage(projectId));
		
		// Emulates the call to the notification timer, this clears the internal cache
		manager.sendProjectStorageNotifications();
		
		verify(mockMessenger).publishMessageAfterCommit(new ProjectStorageEvent()
			.setObjectType(ObjectType.PROJECT_STORAGE_EVENT)
			.setObjectId(projectIdLong.toString())
			.setProjectId(projectIdLong)
		);
		
		verifyNoMoreInteractions(mockDao, mockNodeDao, mockReplicationDao, mockClock, mockMessenger);
	}
	
	@Test
	public void getProjectStorageUsageWithStorageData() {
		String projectId = "syn123";
		Long projectIdLong = 123L;
		
		when(mockNodeDao.getNodeTypeById(projectId)).thenReturn(EntityType.project);
		when(mockDao.getStorageLocationLimits(projectIdLong)).thenReturn(List.of(
			new ProjectStorageLocationLimit().setStorageLocationId("1").setMaxAllowedFileBytes(1024L),
			new ProjectStorageLocationLimit().setStorageLocationId("2").setMaxAllowedFileBytes(2048L)
		));
		
		when(mockDao.getStorageData(projectIdLong)).thenReturn(Optional.of(new ProjectStorageData()
			.setStorageLocationData(Map.of("1", 512L, "2", 4096L, "3", 2024L))
		));
		
		ProjectStorageUsage expected = new ProjectStorageUsage()
			.setProjectId(projectId)
			.setLocations(List.of(
				new ProjectStorageLocationUsage().setStorageLocationId("1").setMaxAllowedFileBytes(1024L).setIsOverLimit(false).setSumFileBytes(512L),
				new ProjectStorageLocationUsage().setStorageLocationId("2").setMaxAllowedFileBytes(2048L).setIsOverLimit(true).setSumFileBytes(4096L)
			));
		
		// Call under test
		assertEquals(expected, manager.gerProjectStorageUsage(projectId));
		
		// Emulates the call to the notification timer, this clears the internal cache
		manager.sendProjectStorageNotifications();
		
		verify(mockMessenger).publishMessageAfterCommit(new ProjectStorageEvent()
			.setObjectType(ObjectType.PROJECT_STORAGE_EVENT)
			.setObjectId(projectIdLong.toString())
			.setProjectId(projectIdLong)
		);
		
		verifyNoMoreInteractions(mockDao, mockNodeDao, mockReplicationDao, mockClock, mockMessenger);
	}
	
	@Test
	public void getProjectStorageUsageWithNoProjectId() {		
		assertEquals("The projectId is required and must not be the empty string.", assertThrows(IllegalArgumentException.class, () -> {			
			// Call under test
			manager.gerProjectStorageUsage(null);
		}).getMessage());
		
		assertEquals("The projectId is required and must not be a blank string.", assertThrows(IllegalArgumentException.class, () -> {			
			// Call under test
			manager.gerProjectStorageUsage(" ");
		}).getMessage());
		
		verifyZeroInteractions(mockDao, mockNodeDao, mockReplicationDao, mockClock, mockMessenger);
	}
	
	@ParameterizedTest
	@EnumSource(value = EntityType.class, mode = Mode.EXCLUDE, names = "project")
	public void getProjectStorageUsageWithWrongType(EntityType type) {
		String projectId = "123";
		
		when(mockNodeDao.getNodeTypeById(projectId)).thenReturn(type);
		
		assertEquals("The entity with the given id is not a project.", assertThrows(IllegalArgumentException.class, () -> {			
			// Call under test
			manager.gerProjectStorageUsage(projectId);
		}).getMessage());
		
		verifyZeroInteractions(mockDao, mockNodeDao, mockReplicationDao, mockClock, mockMessenger);
	}
	
	@Test
	public void testSendProjectStorageNotification() {
		when(mockNodeDao.getNodeTypeById(any())).thenReturn(EntityType.project);
		when(mockDao.getStorageLocationLimits(any())).thenReturn(Collections.emptyList());
		when(mockDao.getStorageData(any())).thenReturn(Optional.empty());
		
		// Call under test
		manager.sendProjectStorageNotifications();
		
		verifyZeroInteractions(mockMessenger);
		
		manager.gerProjectStorageUsage("123");
		manager.gerProjectStorageUsage("123");
		manager.gerProjectStorageUsage("456");
		
		// Call under test
		manager.sendProjectStorageNotifications();
		
		verify(mockMessenger).publishMessageAfterCommit(new ProjectStorageEvent()
			.setObjectType(ObjectType.PROJECT_STORAGE_EVENT)
			.setObjectId("123")
			.setProjectId(123L)
		);
		
		verify(mockMessenger).publishMessageAfterCommit(new ProjectStorageEvent()
			.setObjectType(ObjectType.PROJECT_STORAGE_EVENT)
			.setObjectId("456")
			.setProjectId(456L)
		);
		
		verifyNoMoreInteractions(mockMessenger);
	}
	
	@Test
	public void testSetDefaultProjectStorageLimit() {
		String projectId = "123";
		String storageLocationId = "2";
		
		when(mockNodeDao.getNodeTypeById(projectId)).thenReturn(EntityType.project);
		when(mockDao.getStorageLocationLimit(123L, 2L)).thenReturn(Optional.empty());
		
		// Call under test
		manager.setDefaultProjectStorageLimit(projectId, storageLocationId);
		
		verify(mockDao).setStorageLocationLimit(BOOTSTRAP_PRINCIPAL.THE_ADMIN_USER.getPrincipalId(), new ProjectStorageLocationLimit()
			.setProjectId(projectId)
			.setStorageLocationId(storageLocationId)
			.setMaxAllowedFileBytes(null)
		);
		
		verifyNoMoreInteractions(mockDao);
	}
	
	@Test
	public void testSetDefaultProjectStorageLimitWithAlreadyExists() {
		String projectId = "123";
		String storageLocationId = "2";
		
		when(mockNodeDao.getNodeTypeById(projectId)).thenReturn(EntityType.project);
		when(mockDao.getStorageLocationLimit(123L, 2L)).thenReturn(Optional.of(new ProjectStorageLocationLimit()));
		
		// Call under test
		manager.setDefaultProjectStorageLimit(projectId, storageLocationId);

		verifyNoMoreInteractions(mockDao);
	}
	
	@Test
	public void testSetDefaultProjectStorageLimitWithDefaultStorageLocation() {
		String projectId = "123";
		String storageLocationId = ProjectStorageLimitManager.DEFAULT_STORAGE_LOCATION_ID;
		
		when(mockNodeDao.getNodeTypeById(projectId)).thenReturn(EntityType.project);
		when(mockDao.getStorageLocationLimit(123L, 1L)).thenReturn(Optional.empty());
		
		// Call under test
		manager.setDefaultProjectStorageLimit(projectId, storageLocationId);
		
		verify(mockDao).setStorageLocationLimit(BOOTSTRAP_PRINCIPAL.THE_ADMIN_USER.getPrincipalId(), new ProjectStorageLocationLimit()
			.setProjectId(projectId)
			.setStorageLocationId(storageLocationId)
			.setMaxAllowedFileBytes(ProjectStorageLimitManager.DEFAULT_STORAGE_LOCATION_MAX_BYTES)
		);
		
		verifyNoMoreInteractions(mockDao);
	}
	
	@Test
	public void testSetDefaultProjectStorageLimitWithNoProjectId() {
		String storageLocationId = "1";
		
		assertEquals("The projectId is required and must not be the empty string.", assertThrows(IllegalArgumentException.class, () -> {			
			// Call under test
			manager.setDefaultProjectStorageLimit(null, storageLocationId);
		}).getMessage());
		
		verifyZeroInteractions(mockDao, mockNodeDao);
	}
	
	@Test
	public void testSetDefaultProjectStorageLimitWithNoStorageLocationId() {
		assertEquals("The storage location id is required.", assertThrows(IllegalArgumentException.class, () -> {			
			// Call under test
			manager.setDefaultProjectStorageLimit("123", null);
		}).getMessage());
		
		verifyZeroInteractions(mockDao, mockNodeDao);
	}
	
	@ParameterizedTest
	@EnumSource(value = EntityType.class, mode = Mode.EXCLUDE, names = "project")
	public void testSetDefaultProjectStorageLimitWithWrongEntityType(EntityType entityType) {
		String projectId = "123";
		String storageLocationId = ProjectStorageLimitManager.DEFAULT_STORAGE_LOCATION_ID;
		
		when(mockNodeDao.getNodeTypeById(projectId)).thenReturn(entityType);
		
		assertEquals("The entity with the given id is not a project.", assertThrows(IllegalArgumentException.class, () -> {			
			// Call under test
			manager.setDefaultProjectStorageLimit("123", storageLocationId);
		}).getMessage());
		
		verifyNoMoreInteractions(mockDao, mockNodeDao);
	}
	
	@Test
	public void testBackfillProjectLimits() {
		UserInfo user = new UserInfo(true, 123L);
		
		
		when(mockDao.getProjectIdsBatch(3, 0)).thenReturn(List.of(1L, 2L, 3L));
		when(mockDao.getProjectIdsBatch(3, 3)).thenReturn(List.of(4L));
		
		when(mockReplicationDao.getProjectStorageLocations(List.of(1L, 2L, 3L))).thenReturn(List.of(
			Pair.create(1L, 1L), Pair.create(2L, 2L), Pair.create(2L, 3L)
		));
		
		when(mockReplicationDao.getProjectStorageLocations(List.of(4L))).thenReturn(Collections.emptyList());
		
		when(mockDao.getMissingLimits(Set.of(Pair.create(1L, 1L), Pair.create(2L, 1L), Pair.create(2L, 2L), Pair.create(2L, 3L), Pair.create(3L, 1L))))
			.thenReturn(Set.of(Pair.create(1L, 1L), Pair.create(2L, 3L)));
		
		when(mockDao.getMissingLimits(Set.of(Pair.create(4L, 1L))))
			.thenReturn(Collections.emptySet());
				
		// Call under test
		manager.backfillProjectLimits(user, new ProjectStorageLimitsBackfillRequest().setBatchSize(3L));
		
		verify(mockDao).setNullLimitBatch(123L, Set.of(Pair.create(1L, 1L), Pair.create(2L, 3L)));
		
		verifyNoMoreInteractions(mockDao);
	}
	
}
