package org.sagebionetworks.repo.service.metadata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.EnumSource.Mode;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.sagebionetworks.repo.manager.table.TableManagerSupport;
import org.sagebionetworks.repo.model.EntityHeader;
import org.sagebionetworks.repo.model.NodeDAO;
import org.sagebionetworks.repo.model.Folder;
import org.sagebionetworks.repo.model.ObjectType;
import org.sagebionetworks.repo.model.Project;
import org.sagebionetworks.repo.model.RecordSet;
import org.sagebionetworks.repo.model.UserInfo;
import org.sagebionetworks.repo.model.dbo.schema.EntitySchemaValidationResultDao;
import org.sagebionetworks.repo.model.entity.IdAndVersion;
import org.sagebionetworks.repo.model.jdo.KeyFactory;
import org.sagebionetworks.repo.model.message.ChangeType;
import org.sagebionetworks.repo.model.message.MessageToSend;
import org.sagebionetworks.repo.model.message.TransactionalMessenger;
import org.sagebionetworks.repo.model.schema.ValidationSummaryStatistics;
import org.sagebionetworks.repo.model.table.CsvTableDescriptor;

@ExtendWith(MockitoExtension.class)
public class RecordSetMetadataProviderTest {

	@Mock
	private FileEntityMetadataProvider mockFileEntityMetadataProvider;

	@Mock
	private EntitySchemaValidationResultDao mockValidationResultDao;

	@Mock
	private TableManagerSupport mockTableManagerSupport;

	@Mock
	private TransactionalMessenger mockTransactionalMessenger;

	@Mock
	private NodeDAO mockNodeDao;

	@InjectMocks
	private RecordSetMetadataProvider recordSetMetadataProvider;
	
	@Mock
	private ValidationSummaryStatistics mockValidationStats;
	
	private RecordSet recordSet;
	private UserInfo userInfo;
	private List<EntityHeader> path;
	

	@BeforeEach
	public void before() {

		recordSet = new RecordSet();
		recordSet.setId("syn123");
		recordSet.setVersionNumber(3L);
		recordSet.setDataFileHandleId("456");
		recordSet.setParentId("syn234567");
		recordSet.setUpsertKey(List.of("a", "b"));

		userInfo = new UserInfo(false, 55L);

		path = List.of(
			new EntityHeader().setId("syn123456").setName("project").setType(Project.class.getName()),
			new EntityHeader().setId("syn234567").setName("folder").setType(Folder.class.getName())
		);
		
		
	}
	
	@ParameterizedTest
	@EnumSource(value = EventType.class, mode = Mode.INCLUDE, names = {"CREATE", "UPDATE"})
	public void testValidateEntity(EventType eventType) throws Exception {
		
		EntityEvent event = new EntityEvent(eventType, path, userInfo);
		
		// Call under test
		recordSetMetadataProvider.validateEntity(recordSet, event);
		
		verify(mockFileEntityMetadataProvider).validateEntity(recordSet, event);
	}
	
	@ParameterizedTest
	@EnumSource(value = EventType.class, mode = Mode.INCLUDE, names = {"CREATE", "UPDATE"})
	public void testValidateEntityWithValidationSummary(EventType eventType) throws Exception {
		
		EntityEvent event = new EntityEvent(eventType, path, userInfo);
		
		recordSet.setValidationSummary(mockValidationStats);
		
		// Call under test
		recordSetMetadataProvider.validateEntity(recordSet, event);
		
		verify(mockFileEntityMetadataProvider).validateEntity(recordSet, event);
		
		assertNull(recordSet.getValidationSummary());
	}
	
	@ParameterizedTest
	@EnumSource(value = EventType.class, mode = Mode.INCLUDE, names = {"CREATE", "UPDATE"})
	public void testValidateEntityWithNoUpsertKey(EventType eventType) throws Exception {
		recordSet.setUpsertKey(null);
		
		EntityEvent event = new EntityEvent(eventType, path, userInfo);
		
		assertEquals("The upsertKey is required and must not be empty.", 
			assertThrows(IllegalArgumentException.class, () -> {
				// Call under test
				recordSetMetadataProvider.validateEntity(recordSet, event);
			}).getMessage()
		);
		
		recordSet.setUpsertKey(Collections.emptyList());
		
		assertEquals("The upsertKey is required and must not be empty.", 
			assertThrows(IllegalArgumentException.class, () -> {
				// Call under test
				recordSetMetadataProvider.validateEntity(recordSet, event);
			}).getMessage()
		);
		
		verifyZeroInteractions(mockFileEntityMetadataProvider);
	}
	
	@ParameterizedTest
	@EnumSource(value = EventType.class, mode = Mode.INCLUDE, names = {"CREATE", "UPDATE"})
	public void testValidateEntityWithCsvDescriptorAndIsFirstLineHeaderMissingOrFalse(EventType eventType) throws Exception {
		recordSet.setCsvDescriptor(new CsvTableDescriptor().setIsFirstLineHeader(false));
		
		EntityEvent event = new EntityEvent(eventType, path, userInfo);
		
		assertEquals("The csvDescriptor.isFirstLineHeader must be true.", 
			assertThrows(IllegalArgumentException.class, () -> {
				// Call under test
				recordSetMetadataProvider.validateEntity(recordSet, event);
			}).getMessage()
		);
		
		recordSet.setCsvDescriptor(new CsvTableDescriptor().setIsFirstLineHeader(null));
		
		assertEquals("The csvDescriptor.isFirstLineHeader must be true.", 
			assertThrows(IllegalArgumentException.class, () -> {
				// Call under test
				recordSetMetadataProvider.validateEntity(recordSet, event);
			}).getMessage()
		);
		
		verifyZeroInteractions(mockFileEntityMetadataProvider);
	}
	
	@Test
	public void testEntityCreated() {
		// Call under test
		recordSetMetadataProvider.entityCreated(userInfo, recordSet);

		verify(mockFileEntityMetadataProvider).entityCreated(userInfo, recordSet);
		// Status/trigger is keyed at the unversioned IdAndVersion so unversioned
		// queries find the index status. The worker resolves the current revision
		// from NodeDAO and builds the per-version index table under that key.
		verify(mockTableManagerSupport).setTableToProcessingAndTriggerUpdate(
				IdAndVersion.newBuilder().setId(123L).build());
	}

	@ParameterizedTest
	@ValueSource(booleans = {true, false})
	public void testEntityUpdated(boolean wasNewVersionCreated) {
		// Call under test
		recordSetMetadataProvider.entityUpdated(userInfo, recordSet, wasNewVersionCreated);

		verify(mockFileEntityMetadataProvider).entityUpdated(userInfo, recordSet, wasNewVersionCreated);
		verify(mockTableManagerSupport).setTableToProcessingAndTriggerUpdate(
				IdAndVersion.newBuilder().setId(123L).build());
	}

	@Test
	public void testEntityDeleted() {
		// Call under test
		recordSetMetadataProvider.entityDeleted("syn123");

		ArgumentCaptor<MessageToSend> captor = ArgumentCaptor.forClass(MessageToSend.class);
		verify(mockTransactionalMessenger).sendMessageAfterCommit(captor.capture());
		MessageToSend sent = captor.getValue();
		assertEquals("syn123", sent.getObjectId());
		assertEquals(ObjectType.RECORDSET, sent.getObjectType());
		assertEquals(ChangeType.DELETE, sent.getChangeType());
		assertNull(sent.getObjectVersion());
	}
	
	@ParameterizedTest
	@EnumSource(value = EventType.class)
	public void testAddTypeSpecificMetadata(EventType eventType) throws Exception {
		when(mockValidationResultDao.getRecordSetValidationSummaryStatistics(KeyFactory.stringToKey(recordSet.getId()), recordSet.getVersionNumber())).thenReturn(
			Optional.of(mockValidationStats)
		);
		
		// Call under test
		recordSetMetadataProvider.addTypeSpecificMetadata(recordSet, userInfo, eventType);
	
		assertEquals(mockValidationStats, recordSet.getValidationSummary());
	}
	
	@ParameterizedTest
	@EnumSource(value = EventType.class)
	public void testAddTypeSpecificMetadataWithNoValidationStats(EventType eventType) throws Exception {
		when(mockValidationResultDao.getRecordSetValidationSummaryStatistics(KeyFactory.stringToKey(recordSet.getId()), recordSet.getVersionNumber())).thenReturn(
			Optional.empty()
		);
		
		// Call under test
		recordSetMetadataProvider.addTypeSpecificMetadata(recordSet, userInfo, eventType);
	
		assertNull(recordSet.getValidationSummary());
	}
}
