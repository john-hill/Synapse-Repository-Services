package org.sagebionetworks.repo.service.metadata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.EnumSource.Mode;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.sagebionetworks.StackConfiguration;
import org.sagebionetworks.repo.manager.file.FileEventUtils;
import org.sagebionetworks.repo.manager.limits.ProjectStorageLimitsManager;
import org.sagebionetworks.repo.manager.sts.StsManager;
import org.sagebionetworks.repo.model.EntityHeader;
import org.sagebionetworks.repo.model.FileEntity;
import org.sagebionetworks.repo.model.Folder;
import org.sagebionetworks.repo.model.NodeDAO;
import org.sagebionetworks.repo.model.Project;
import org.sagebionetworks.repo.model.UserInfo;
import org.sagebionetworks.repo.model.dbo.file.FileHandleDao;
import org.sagebionetworks.repo.model.file.FileEvent;
import org.sagebionetworks.repo.model.file.FileEventType;
import org.sagebionetworks.repo.model.file.FileHandleAssociateType;
import org.sagebionetworks.repo.model.message.TransactionalMessenger;
import org.sagebionetworks.repo.service.metadata.EntityEvent;
import org.sagebionetworks.repo.service.metadata.EventType;
import org.sagebionetworks.repo.service.metadata.FileEntityMetadataProvider;

@ExtendWith(MockitoExtension.class)
public class FileEntityMetadataProviderTest {
	private static final String PROJECT_ID = "123";
	private static final String PARENT_ENTITY_ID = "456";
	private static final String FILE_HANDLE_ID = "123456";
	private static final Long FILE_STORAGE_LOCATION_ID = 1L;
	
	private static final String STACK = "stack";
	private static final String INSTANCE = "instance";

	@Mock
	private TransactionalMessenger messenger;

	@Mock
	private StsManager mockStsManager;
	
	@Mock
	private StackConfiguration configuration;

	@Mock
	private ProjectStorageLimitsManager mockStorageLimitsManager;
	
	@Mock
	private NodeDAO mockNodeDao;
	
	@Mock
	private FileHandleDao mockFileDao;
	
	@InjectMocks
	private FileEntityMetadataProvider provider;
	
	@Captor
	private ArgumentCaptor<FileEvent> fileEventCaptor;

	private FileEntity fileEntity;
	private UserInfo userInfo;
	private List<EntityHeader> path;

	@BeforeEach
	public void before() {

		fileEntity = new FileEntity();
		fileEntity.setId("syn789");
		fileEntity.setDataFileHandleId(FILE_HANDLE_ID);
		fileEntity.setParentId(PARENT_ENTITY_ID);

		userInfo = new UserInfo(false, 55L);

		// root
		EntityHeader grandparentHeader = new EntityHeader();
		grandparentHeader.setId(PROJECT_ID);
		grandparentHeader.setName("gp");
		grandparentHeader.setType(Project.class.getName());
		path = new ArrayList<>();
		path.add(grandparentHeader);

		// This is our direct parent header
		EntityHeader parentHeader = new EntityHeader();
		parentHeader.setId(PARENT_ENTITY_ID);
		parentHeader.setName("p");
		parentHeader.setType(Folder.class.getName());
		path.add(parentHeader);
	}

	@ParameterizedTest
	@EnumSource(value = EventType.class, mode = Mode.INCLUDE, names = {"CREATE", "UPDATE", "UPDATE_VERSION", "NEW_VERSION"})
	public void testValidateEntity(EventType eventType) {
		FileEntityMetadataProvider providerSpy = Mockito.spy(provider);
		
		EntityEvent event = new EntityEvent(eventType, path, userInfo);
		
		doNothing().when(providerSpy).validateProjectStorageLocationUsageLimit(fileEntity, event);
		
		// Method under test - Does not throw.
		providerSpy.validateEntity(fileEntity, event);
		
		// Validate that we call the STS validator.
		verify(mockStsManager).validateCanAddFile(userInfo, FILE_HANDLE_ID, PARENT_ENTITY_ID);
	}
	
	@ParameterizedTest
	@EnumSource(value = EventType.class, mode = Mode.INCLUDE, names = {"CREATE", "UPDATE", "UPDATE_VERSION", "NEW_VERSION"})
	public void testValidateEntityWithoutDataFileHandleId(EventType eventType) {
		fileEntity.setDataFileHandleId(null);
		
		assertEquals("FileEntity.dataFileHandleId cannot be null", assertThrows(IllegalArgumentException.class, () -> 
			provider.validateEntity(fileEntity, new EntityEvent(eventType, path, userInfo))
		).getMessage());
	}
	
	@ParameterizedTest
	@EnumSource(value = EventType.class, mode = Mode.INCLUDE, names = {"CREATE", "UPDATE", "UPDATE_VERSION", "NEW_VERSION"})
	public void testValidateEntityWithFileNameOverride(EventType eventType) {
		fileEntity.setFileNameOverride("fileNameOverride");
		
		assertEquals("fileNameOverride field is deprecated and should not be set.", assertThrows(IllegalArgumentException.class, () -> 
			provider.validateEntity(fileEntity, new EntityEvent(eventType, path, userInfo))
		).getMessage());
	}
	
	@ParameterizedTest
	@EnumSource(value = EventType.class, mode = Mode.EXCLUDE, names = {"CREATE", "UPDATE", "UPDATE_VERSION", "NEW_VERSION"})
	public void testValidateEntityWithUnsupportedEvents(EventType eventType) {
				
		// Method under test - Does not throw.
		provider.validateEntity(fileEntity, new EntityEvent(eventType, path, userInfo));

		verifyZeroInteractions(mockStsManager, mockFileDao);
	}
	
	@Test
	public void testValidateProjectStorageLocationUsageLimitWithCreate() {
		when(mockFileDao.getStorageLocationId(Long.valueOf(FILE_HANDLE_ID))).thenReturn(Optional.of(FILE_STORAGE_LOCATION_ID));
		
		// Call under test
		provider.validateProjectStorageLocationUsageLimit(fileEntity, new EntityEvent(EventType.CREATE, path, userInfo));
		
		verify(mockStorageLimitsManager).verifyProjectStorageLocationUsageUnderLimit(PROJECT_ID, FILE_STORAGE_LOCATION_ID);
		
		verifyNoMoreInteractions(mockFileDao, mockNodeDao, mockStorageLimitsManager);
	}
	
	@Test
	public void testValidateProjectStorageLocationUsageLimitWithCreateAndNoStorageLocation() {
		when(mockFileDao.getStorageLocationId(Long.valueOf(FILE_HANDLE_ID))).thenReturn(Optional.empty());
		
		// Call under test
		provider.validateProjectStorageLocationUsageLimit(fileEntity, new EntityEvent(EventType.CREATE, path, userInfo));
				
		verifyNoMoreInteractions(mockFileDao, mockNodeDao, mockStorageLimitsManager);
	}
	
	@Test
	public void testValidateProjectStorageLocationUsageLimitWithUpdate() {
		
		when(mockNodeDao.getFileHandleIdForVersion(fileEntity.getId(), null)).thenReturn("54321");
		when(mockFileDao.getStorageLocationId(Long.valueOf(FILE_HANDLE_ID))).thenReturn(Optional.of(FILE_STORAGE_LOCATION_ID));
		
		// Call under test
		provider.validateProjectStorageLocationUsageLimit(fileEntity, new EntityEvent(EventType.UPDATE, path, userInfo));
		
		verify(mockStorageLimitsManager).verifyProjectStorageLocationUsageUnderLimit(PROJECT_ID, FILE_STORAGE_LOCATION_ID);
				
		verifyNoMoreInteractions(mockFileDao, mockNodeDao, mockStorageLimitsManager);
	}
	
	@Test
	public void testValidateProjectStorageLocationUsageLimitWithUpdateAndSameFileHandle() {
		when(mockNodeDao.getFileHandleIdForVersion(fileEntity.getId(), null)).thenReturn(FILE_HANDLE_ID);
		
		// Call under test
		provider.validateProjectStorageLocationUsageLimit(fileEntity, new EntityEvent(EventType.UPDATE, path, userInfo));
				
		verifyNoMoreInteractions(mockFileDao, mockNodeDao, mockStorageLimitsManager);
	}
	
	@Test
	public void testValidateProjectStorageLocationUsageLimitWithUpdateAndDifferentFileHandleAndNoStorageLocation() {
		
		when(mockNodeDao.getFileHandleIdForVersion(fileEntity.getId(), null)).thenReturn("54321");
		when(mockFileDao.getStorageLocationId(Long.valueOf(FILE_HANDLE_ID))).thenReturn(Optional.empty());
		
		// Call under test
		provider.validateProjectStorageLocationUsageLimit(fileEntity, new EntityEvent(EventType.UPDATE, path, userInfo));
						
		verifyNoMoreInteractions(mockFileDao, mockNodeDao, mockStorageLimitsManager);
	}
	
	@Test
	public void testValidateProjectStorageLocationUsageLimitWithUpdateVersion() {
		fileEntity.setVersionNumber(2L);
		
		when(mockNodeDao.getFileHandleIdForVersion(fileEntity.getId(), 2L)).thenReturn("54321");
		when(mockFileDao.getStorageLocationId(Long.valueOf(FILE_HANDLE_ID))).thenReturn(Optional.of(FILE_STORAGE_LOCATION_ID));
		
		// Call under test
		provider.validateProjectStorageLocationUsageLimit(fileEntity, new EntityEvent(EventType.UPDATE_VERSION, path, userInfo));
		
		verify(mockStorageLimitsManager).verifyProjectStorageLocationUsageUnderLimit(PROJECT_ID, FILE_STORAGE_LOCATION_ID);
				
		verifyNoMoreInteractions(mockFileDao, mockNodeDao, mockStorageLimitsManager);
	}
	
	@Test
	public void testValidateProjectStorageLocationUsageLimitWithUpdateVersionAndSameFileHandle() {
		fileEntity.setVersionNumber(2L);
		
		when(mockNodeDao.getFileHandleIdForVersion(fileEntity.getId(), 2L)).thenReturn(FILE_HANDLE_ID);
		
		// Call under test
		provider.validateProjectStorageLocationUsageLimit(fileEntity, new EntityEvent(EventType.UPDATE_VERSION, path, userInfo));
				
		verifyNoMoreInteractions(mockFileDao, mockNodeDao, mockStorageLimitsManager);
	}
	
	@Test
	public void testValidateProjectStorageLocationUsageLimitWithUpdateVersionAndDifferentFileHandleAndNoStorageLocation() {
		fileEntity.setVersionNumber(2L);
		
		when(mockNodeDao.getFileHandleIdForVersion(fileEntity.getId(), 2L)).thenReturn("54321");
		when(mockFileDao.getStorageLocationId(Long.valueOf(FILE_HANDLE_ID))).thenReturn(Optional.empty());
		
		// Call under test
		provider.validateProjectStorageLocationUsageLimit(fileEntity, new EntityEvent(EventType.UPDATE_VERSION, path, userInfo));
						
		verifyNoMoreInteractions(mockFileDao, mockNodeDao, mockStorageLimitsManager);
	}

	@Test
	public void testEntityCreated() {
		when(configuration.getStack()).thenReturn(STACK);
		when(configuration.getStackInstance()).thenReturn(INSTANCE);
		fileEntity.setDataFileHandleId("1");
		provider.entityCreated(userInfo, fileEntity);
		verify(messenger, times(1)).publishMessageAfterCommit(fileEventCaptor.capture());
		FileEvent actualEvent = fileEventCaptor.getValue();
		assertNotNull(actualEvent.getTimestamp());
		FileEvent expectedEvent = FileEventUtils.buildFileEvent(FileEventType.FILE_UPLOAD, userInfo.getId(),
				fileEntity.getDataFileHandleId(), fileEntity.getId(), FileHandleAssociateType.FileEntity, STACK, INSTANCE);
		expectedEvent.setTimestamp(actualEvent.getTimestamp());
		assertEquals(expectedEvent, actualEvent);
	}

	@Test
	public void testEntityUpdatedWithNewVersion() {
		when(configuration.getStack()).thenReturn(STACK);
		when(configuration.getStackInstance()).thenReturn(INSTANCE);
		fileEntity.setDataFileHandleId("1");
		provider.entityUpdated(userInfo, fileEntity, true);
		verify(messenger, times(1)).publishMessageAfterCommit(fileEventCaptor.capture());
		FileEvent actualEvent = fileEventCaptor.getValue();
		assertNotNull(actualEvent.getTimestamp());
		FileEvent expectedEvent = FileEventUtils.buildFileEvent(FileEventType.FILE_UPLOAD, userInfo.getId(),
				fileEntity.getDataFileHandleId(), fileEntity.getId(), FileHandleAssociateType.FileEntity, STACK, INSTANCE);
		expectedEvent.setTimestamp(actualEvent.getTimestamp());
		assertEquals(expectedEvent, actualEvent);
	}

	@Test
	public void testEntityUpdatedWithoutNewVersion() {
		provider.entityUpdated(userInfo, fileEntity, false);
		verifyZeroInteractions(messenger);
	}
}
