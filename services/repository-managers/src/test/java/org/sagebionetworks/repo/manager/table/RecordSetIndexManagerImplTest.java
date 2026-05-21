package org.sagebionetworks.repo.manager.table;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.sagebionetworks.LoggerProvider;
import org.sagebionetworks.repo.manager.EntityManager;
import org.sagebionetworks.repo.manager.UserManager;
import org.sagebionetworks.repo.manager.file.CsvFileHandleProvider;
import org.sagebionetworks.repo.manager.file.FileHandleManager;
import org.sagebionetworks.repo.model.AuthorizationConstants.BOOTSTRAP_PRINCIPAL;
import org.sagebionetworks.repo.model.NodeDAO;
import org.sagebionetworks.repo.model.RecordSet;
import org.sagebionetworks.repo.model.UserInfo;
import org.sagebionetworks.repo.model.entity.IdAndVersion;
import org.sagebionetworks.repo.model.file.S3FileHandle;
import org.sagebionetworks.repo.model.semaphore.LockContext;
import org.sagebionetworks.repo.model.table.ColumnModel;
import org.sagebionetworks.repo.model.table.ColumnType;
import org.sagebionetworks.repo.model.table.CsvTableDescriptor;
import org.sagebionetworks.table.cluster.description.IndexDescription;
import org.sagebionetworks.table.cluster.description.RecordSetIndexDescription;
import org.sagebionetworks.util.progress.ProgressCallback;
import org.sagebionetworks.util.progress.ProgressingCallable;

@ExtendWith(MockitoExtension.class)
public class RecordSetIndexManagerImplTest {

	@Mock
	private TableManagerSupport mockTableManagerSupport;
	@Mock
	private TableIndexConnectionFactory mockConnectionFactory;
	@Mock
	private ColumnModelManager mockColumnModelManager;
	@Mock
	private EntityManager mockEntityManager;
	@Mock
	private UserManager mockUserManager;
	@Mock
	private FileHandleManager mockFileHandleManager;
	@Mock
	private CsvFileHandleProvider mockCsvFileHandleProvider;
	@Mock
	private NodeDAO mockNodeDao;
	@Mock
	private LoggerProvider mockLoggerProvider;
	@Mock
	private Logger mockLogger;
	@Mock
	private TableIndexManager mockIndexManager;
	@Mock
	private ProgressCallback mockProgressCallback;
	@Mock
	private UserInfo mockAdminUser;

	@Spy
	@InjectMocks
	private RecordSetIndexManagerImpl manager;

	/** The unversioned entity key used for the exclusive lock + entity-level status. */
	private IdAndVersion entityKey;
	/** The versioned key the factory resolves the entity reference to. */
	private IdAndVersion versionedKey;
	/** A versioned IdAndVersion fed in to prove the manager still operates on entityKey. */
	private IdAndVersion incomingIdAndVersion;
	private RecordSet recordSet;
	private S3FileHandle fileHandle;
	private CsvTableDescriptor csvDescriptor;
	private List<ColumnModel> inferredSchema;
	private List<ColumnModel> persistedSchema;
	private String token;
	private long currentRevision;

	@BeforeEach
	public void before() {
		when(mockLoggerProvider.getLogger(RecordSetIndexManagerImpl.class.getName())).thenReturn(mockLogger);
		manager = Mockito.spy(new RecordSetIndexManagerImpl(mockTableManagerSupport, mockConnectionFactory,
				mockColumnModelManager, mockEntityManager, mockUserManager, mockFileHandleManager,
				mockCsvFileHandleProvider, mockNodeDao, mockLoggerProvider));

		incomingIdAndVersion = IdAndVersion.parse("syn999.2");
		entityKey = IdAndVersion.newBuilder().setId(999L).build();
		versionedKey = IdAndVersion.newBuilder().setId(999L).setVersion(2L).build();
		currentRevision = 2L;
		csvDescriptor = new CsvTableDescriptor().setIsFirstLineHeader(true);
		recordSet = new RecordSet().setDataFileHandleId("9991").setCsvDescriptor(csvDescriptor);
		recordSet.setId("syn999");
		recordSet.setVersionNumber(currentRevision);
		fileHandle = new S3FileHandle();
		fileHandle.setId("9991");
		inferredSchema = Arrays.asList(
				new ColumnModel().setName("a").setColumnType(ColumnType.STRING).setMaximumSize(50L),
				new ColumnModel().setName("b").setColumnType(ColumnType.INTEGER));
		persistedSchema = Arrays.asList(
				new ColumnModel().setId("100").setName("a").setColumnType(ColumnType.STRING).setMaximumSize(50L),
				new ColumnModel().setId("101").setName("b").setColumnType(ColumnType.INTEGER));
		token = "the-token";
	}

	@Test
	public void testCreateOrUpdateRecordSetIndexHappyPath() throws Exception {
		stubLockToRunInline();
		when(mockTableManagerSupport.isIndexWorkRequired(entityKey)).thenReturn(true);
		when(mockTableManagerSupport.startTableProcessing(entityKey)).thenReturn(token);
		String versionedToken = "versioned-token";
		when(mockTableManagerSupport.startTableProcessing(versionedKey)).thenReturn(versionedToken);
		when(mockNodeDao.getCurrentRevisionNumber("999")).thenReturn(currentRevision);
		IndexDescription entityDescription = new RecordSetIndexDescription(entityKey, currentRevision);
		IndexDescription versionedDescription = new RecordSetIndexDescription(versionedKey, currentRevision);
		when(mockUserManager.getUserInfo(BOOTSTRAP_PRINCIPAL.THE_ADMIN_USER.getPrincipalId())).thenReturn(mockAdminUser);
		when(mockEntityManager.getEntityForVersion(mockAdminUser, "999", currentRevision, RecordSet.class))
				.thenReturn(recordSet);
		when(mockFileHandleManager.getRawFileHandleUnchecked("9991")).thenReturn(fileHandle);
		doReturn(inferredSchema).when(manager).inferSchema(fileHandle, csvDescriptor);
		when(mockColumnModelManager.createColumnModels(mockAdminUser, inferredSchema)).thenReturn(persistedSchema);
		when(mockConnectionFactory.connectToTableIndex(entityKey)).thenReturn(mockIndexManager);
		// Both index loads stubbed; they execute against the same TableIndexManager mock.
		doReturn(42L).when(manager).loadRows(eq(mockIndexManager), eq(entityDescription), eq(persistedSchema),
				eq(fileHandle), eq(csvDescriptor), eq(currentRevision));
		doReturn(42L).when(manager).loadRows(eq(mockIndexManager), eq(versionedDescription), eq(persistedSchema),
				eq(fileHandle), eq(csvDescriptor), eq(currentRevision));

		// call under test
		manager.createOrUpdateRecordSetIndex(incomingIdAndVersion, mockProgressCallback);

		// Bindings: default (current alias) + versioned (snapshot history).
		verify(mockColumnModelManager).bindColumnsToDefaultVersionOfObject(Arrays.asList("100", "101"), "999");
		verify(mockColumnModelManager).bindColumnsToVersionOfObject(Arrays.asList("100", "101"), versionedKey);
		// Build the entity-level T{id} index with the current CSV.
		verify(mockIndexManager).resetTableIndex(entityDescription, persistedSchema, false);
		verify(manager).loadRows(mockIndexManager, entityDescription, persistedSchema, fileHandle, csvDescriptor,
				currentRevision);
		verify(mockIndexManager).buildTableIndexIndices(entityDescription, persistedSchema);
		verify(mockIndexManager).setIndexVersion(entityKey, currentRevision);
		// Build the immutable per-version snapshot T{id}_{v}.
		verify(mockIndexManager).resetTableIndex(versionedDescription, persistedSchema, false);
		verify(manager).loadRows(mockIndexManager, versionedDescription, persistedSchema, fileHandle, csvDescriptor,
				currentRevision);
		verify(mockIndexManager).buildTableIndexIndices(versionedDescription, persistedSchema);
		verify(mockIndexManager).setIndexVersion(versionedKey, currentRevision);
		// Both TABLE_STATUS rows flip to AVAILABLE.
		verify(mockTableManagerSupport).attemptToSetTableStatusToAvailable(eq(versionedKey), eq(versionedToken), eq("DEFAULT"));
		verify(mockTableManagerSupport).attemptToSetTableStatusToAvailable(eq(entityKey), eq(token), eq("DEFAULT"));
		verify(mockTableManagerSupport, never()).attemptToSetTableStatusToFailed(any(), any());
	}

	@Test
	public void testCreateOrUpdateRecordSetIndexWithEmptySchemaFails() throws Exception {
		stubLockToRunInline();
		when(mockTableManagerSupport.isIndexWorkRequired(entityKey)).thenReturn(true);
		when(mockTableManagerSupport.startTableProcessing(entityKey)).thenReturn(token);
		when(mockNodeDao.getCurrentRevisionNumber("999")).thenReturn(currentRevision);
		when(mockUserManager.getUserInfo(BOOTSTRAP_PRINCIPAL.THE_ADMIN_USER.getPrincipalId())).thenReturn(mockAdminUser);
		when(mockEntityManager.getEntityForVersion(mockAdminUser, "999", currentRevision, RecordSet.class))
				.thenReturn(recordSet);
		when(mockFileHandleManager.getRawFileHandleUnchecked("9991")).thenReturn(fileHandle);
		doReturn(Collections.emptyList()).when(manager).inferSchema(fileHandle, csvDescriptor);

		// call under test
		assertThrows(RuntimeException.class,
				() -> manager.createOrUpdateRecordSetIndex(incomingIdAndVersion, mockProgressCallback));

		verify(mockTableManagerSupport).attemptToSetTableStatusToFailed(eq(entityKey), any(Exception.class));
		verify(mockTableManagerSupport, never()).attemptToSetTableStatusToAvailable(any(), any(), any());
		verify(mockColumnModelManager, never()).bindColumnsToDefaultVersionOfObject(any(), any());
		verify(mockIndexManager, never()).resetTableIndex(any(IndexDescription.class), any(List.class),
				Mockito.anyBoolean());
	}

	@Test
	public void testCreateOrUpdateRecordSetIndexSkipsWhenNotRequired() throws Exception {
		stubLockToRunInline();
		when(mockTableManagerSupport.isIndexWorkRequired(entityKey)).thenReturn(false);

		// call under test
		manager.createOrUpdateRecordSetIndex(incomingIdAndVersion, mockProgressCallback);

		verify(mockTableManagerSupport, never()).startTableProcessing(any());
		verify(mockTableManagerSupport, never()).attemptToSetTableStatusToAvailable(any(), any(), any());
		verify(mockTableManagerSupport, never()).attemptToSetTableStatusToFailed(any(), any());
		verifyZeroInteractions(mockConnectionFactory, mockEntityManager, mockFileHandleManager, mockColumnModelManager);
	}

	@Test
	public void testDeleteRecordSetIndex() throws Exception {
		when(mockConnectionFactory.connectToTableIndex(entityKey)).thenReturn(mockIndexManager);

		// call under test — delete drops the entity-level stub and unbinds the columns.
		// Per-version snapshot tables T{id}_{v} are intentionally left as unreachable orphans.
		manager.deleteRecordSetIndex(incomingIdAndVersion);

		verify(mockIndexManager).deleteTableIndex(entityKey);
		verify(mockColumnModelManager).unbindAllColumnsAndOwnerFromObject("999");
	}

	@Test
	public void testDeleteRecordSetIndexSurvivesIndexUnavailable() {
		when(mockConnectionFactory.connectToTableIndex(entityKey))
				.thenThrow(new TableIndexConnectionUnavailableException("nope"));

		// call under test — bindings are still cleaned up.
		manager.deleteRecordSetIndex(entityKey);

		verify(mockColumnModelManager).unbindAllColumnsAndOwnerFromObject("999");
	}

	@SuppressWarnings("unchecked")
	private void stubLockToRunInline() throws Exception {
		doAnswer(invocation -> {
			ProgressCallback inner = invocation.getArgument(0);
			ProgressingCallable<Object> callable = invocation.getArgument(3);
			return callable.call(inner);
		}).when(mockTableManagerSupport).tryRunWithTableExclusiveLock(any(ProgressCallback.class),
				any(LockContext.class), eq(entityKey), any(ProgressingCallable.class));
	}
}
