package org.sagebionetworks.repo.manager.grid.synch;

import static org.junit.Assert.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.sagebionetworks.repo.manager.grid.GridManager;
import org.sagebionetworks.repo.manager.grid.internal.replica.change.IntendedChangePublisher;
import org.sagebionetworks.repo.manager.grid.internal.replica.change.PatchBuilderPublisher;
import org.sagebionetworks.repo.manager.grid.internal.replica.model.Column;
import org.sagebionetworks.repo.manager.grid.internal.replica.model.GridHeader;
import org.sagebionetworks.repo.manager.grid.synch.core.SynchronizationLogic;
import org.sagebionetworks.repo.manager.grid.synch.core.SyncOutcomeListener;
import org.sagebionetworks.repo.manager.grid.synch.handler.CopyHandler;
import org.sagebionetworks.repo.manager.grid.synch.handler.CopyHandlerProvider;
import org.sagebionetworks.repo.manager.grid.synch.handler.SourceHandler;
import org.sagebionetworks.repo.manager.grid.synch.handler.SourceHandlerProvider;
import org.sagebionetworks.repo.manager.grid.synch.io.RowSourceItemReader;
import org.sagebionetworks.repo.manager.grid.synch.io.RowSourceItemReference;
import org.sagebionetworks.repo.manager.grid.synch.row.RowCopy;
import org.sagebionetworks.repo.manager.grid.synch.row.RowCopyItem;
import org.sagebionetworks.repo.manager.grid.synch.row.RowMerge;
import org.sagebionetworks.repo.manager.grid.synch.row.RowSource;
import org.sagebionetworks.repo.manager.grid.synch.schema.SchemaCopy;
import org.sagebionetworks.repo.manager.grid.synch.schema.SchemaSource;
import org.sagebionetworks.repo.model.UserInfo;
import org.sagebionetworks.repo.model.dao.asynch.AsyncJobProgressCallback;
import org.sagebionetworks.repo.model.dbo.grid.GridSource;
import org.sagebionetworks.repo.model.EntityType;
import org.sagebionetworks.repo.model.grid.GridConnectionInfo;
import org.sagebionetworks.repo.model.grid.GridSession;
import org.sagebionetworks.repo.model.grid.SyncType;
import org.sagebionetworks.repo.model.grid.SynchronizeGridRequest;
import org.sagebionetworks.repo.model.grid.SynchronizeGridResponse;

@ExtendWith(MockitoExtension.class)
public class GridSynchronizationManagerImplTest {

	@Mock
	private GridManager mockGridManager;
	@Mock
	private PatchBuilderPublisher mockPatchBuilderPublisher;
	@Mock
	private SourceHandlerProvider mockSourceHandlerProvdier;
	@Mock
	private CopyHandlerProvider mockCopyHandlerProvider;
	@Mock
	private SynchronizationLogic mockLogic;
	@Mock
	private SynchronizeProvider mockSynchronizeProvider;
	@Mock
	private CopyHandler mockCopyHandler;
	@Mock
	private SourceHandler mockSourceHandler;
	@Mock
	private RowSourceItemReader mockSourceReader;
	@Mock
	private SchemaCopy mockSchemaCopy;
	@Mock
	private SchemaSource mockSchemaSource;
	@Mock
	private RowCopy mockRowCopy;
	@Mock
	private RowSource mockRowSource;
	@Mock
	private RowMerge mockRowMerge;
	@Mock
	private SyncOutcomeListener<RowCopyItem, RowSourceItemReference> mockRowSyncListener;
	@Mock
	private IntendedChangePublisher mockIntendedChangePublisher;
	@Mock
	private AsyncJobProgressCallback mockCallback;
	@Mock
	private UserInfo mockUser;
	@Mock
	private GridHeader mockHeader;
	@Mock
	private GridConnectionInfo mockConnection;

	@Spy
	@InjectMocks
	private GridSynchronizationManagerImpl manager;

	private String gridSessionId;
	private GridSession gridSession;
	private GridSource gridSource;
	private List<Column> finalSchema;
	private SynchronizeGridRequest request;

	@BeforeEach
	public void before() {
		gridSessionId = "123";
		request = new SynchronizeGridRequest().setGridSessionId(gridSessionId);
		gridSession = new GridSession().setSessionId(gridSessionId);
		gridSource = new GridSource(333L, EntityType.entityview);
		finalSchema = List.of(new Column().setName("foo"));
	}

	@Test
	public void testSynchronizeCopyWithSource() throws Exception {
		when(mockGridManager.getGridSession(mockUser, gridSessionId)).thenReturn(gridSession);
		when(mockCopyHandlerProvider.createCopyHandler(gridSession)).thenReturn(mockCopyHandler);
		when(mockCopyHandler.getGridSource()).thenReturn(gridSource);
		when(mockSourceHandlerProvdier.createNewProvider(mockCallback, mockUser, gridSession, gridSource))
				.thenReturn(mockSourceHandler);
		when(mockSourceHandler.getSourceRowReader()).thenReturn(mockSourceReader);
		when(mockCopyHandler.getHeader()).thenReturn(mockHeader);
		when(mockHeader.getOrderedColumns()).thenReturn(List.of());
		when(mockSourceHandler.getEffectiveSchemaColumnNames(List.of())).thenReturn(List.of("foo"));
		when(mockSynchronizeProvider.getSchemaCopy(eq(mockIntendedChangePublisher), any())).thenReturn(mockSchemaCopy);
		when(mockSchemaCopy.getFinalSchema()).thenReturn(finalSchema);
		when(mockSynchronizeProvider.getSchemaSource(eq(mockSourceHandler), eq(List.of("foo"))))
				.thenReturn(mockSchemaSource);
		when(mockSynchronizeProvider.getRowCopy(mockIntendedChangePublisher, finalSchema, mockCopyHandler))
				.thenReturn(mockRowCopy);
		when(mockSynchronizeProvider.getRowSource(mockSourceReader, mockSourceHandler)).thenReturn(mockRowSource);
		when(mockSynchronizeProvider.getRowMerge(mockLogic, mockIntendedChangePublisher, finalSchema, mockCopyHandler, mockSourceHandler,
				false)).thenReturn(mockRowMerge);
		when(mockSynchronizeProvider.getRowSyncOutcomeListener(mockSourceHandler)).thenReturn(mockRowSyncListener);

		Set<Long> benefactorIds = Set.of(111L, 222L);
		when(mockSourceHandler.getErrorMessages()).thenReturn(List.of("errorOne", "errorTwo"));
		when(mockSourceHandler.getBenefactorIds()).thenReturn(benefactorIds);
		when(mockSourceHandler.getSourceVersion()).thenReturn(Optional.of(5L));
		when(mockSourceHandler.getSourceSchema$Id()).thenReturn(Optional.of("my.org-Schema-1.0.0"));
		when(mockSourceHandler.completePush()).thenReturn(Optional.empty());

		doReturn(mockIntendedChangePublisher).when(manager).newIntendedChangePublisher(mockCopyHandler);

		// call under test
		SynchronizeGridResponse response = manager.synchronizeCopyWithSource(mockCallback, mockUser, request);
		assertEquals(new SynchronizeGridResponse().setGridSessionId(request.getGridSessionId())
				.setErrorMessages(List.of("errorOne", "errorTwo")), response);

		// null syncType defaults to PULL_PUSH and is validated against the source
		verify(mockSourceHandler).resolveAndValidateSyncType(SyncType.PULL_PUSH);
		// the source prepares any push artifact keyed to the final schema
		verify(mockSourceHandler).beginPush(mockCallback, finalSchema, SyncType.PULL_PUSH);
		verify(mockLogic).synchronize(eq(mockSchemaCopy), eq(mockSchemaSource), any());
		verify(mockLogic).synchronize(mockRowCopy, mockRowSource, mockRowMerge, mockRowSyncListener);
		// updateSessionBenefactorIds on gridManager handles both DAO update and eviction
		verify(mockGridManager).updateSessionBenefactorIds(gridSessionId, benefactorIds);
		// the synced source revision is recorded as the new baseline version
		verify(mockGridManager).updateSourceEntityVersion(gridSessionId, 5L);
		// the source's bound schema $id is recorded so validation uses the new schema
		verify(mockGridManager).updateSessionSchemaId(gridSessionId, "my.org-Schema-1.0.0");

		verify(mockCopyHandler).close();
		verify(mockSourceHandler).close();
		verify(mockSourceReader).close();
		verify(mockIntendedChangePublisher).close();
		verify(mockSchemaCopy).close();
	}

	@Test
	public void testSynchronizeCopyWithSourcePullIsRejected() throws Exception {
		request.setSyncType(SyncType.PULL);
		String expectedError = "PULL is not supported";
		when(mockGridManager.getGridSession(mockUser, gridSessionId)).thenReturn(gridSession);
		when(mockCopyHandlerProvider.createCopyHandler(gridSession)).thenReturn(mockCopyHandler);
		when(mockCopyHandler.getGridSource()).thenReturn(gridSource);
		when(mockSourceHandlerProvdier.createNewProvider(mockCallback, mockUser, gridSession, gridSource))
				.thenReturn(mockSourceHandler);
		when(mockSourceHandler.getSourceRowReader()).thenReturn(mockSourceReader);
		doThrow(new IllegalArgumentException(expectedError))
				.when(mockSourceHandler).resolveAndValidateSyncType(SyncType.PULL);
		doReturn(mockIntendedChangePublisher).when(manager).newIntendedChangePublisher(mockCopyHandler);

		String message = Assertions.assertThrows(IllegalArgumentException.class, () -> {
			// call under test
			manager.synchronizeCopyWithSource(mockCallback, mockUser, request);
		}).getMessage();
		assertEquals(expectedError, message);

		// No merge should occur and resources are still closed.
		verify(mockCopyHandler).close();
		verify(mockSourceHandler).close();
		verify(mockSourceReader).close();
		verify(mockIntendedChangePublisher).close();
	}

	@Test
	public void testSynchronizeCopyWithSourcePullPushBuildsNewVersion() throws Exception {
		GridSource recordSetSource = new GridSource(444L, EntityType.recordset);
		request.setSyncType(SyncType.PULL_PUSH);

		when(mockGridManager.getGridSession(mockUser, gridSessionId)).thenReturn(gridSession);
		when(mockCopyHandlerProvider.createCopyHandler(gridSession)).thenReturn(mockCopyHandler);
		when(mockCopyHandler.getGridSource()).thenReturn(recordSetSource);
		when(mockSourceHandlerProvdier.createNewProvider(mockCallback, mockUser, gridSession, recordSetSource))
				.thenReturn(mockSourceHandler);
		when(mockSourceHandler.getSourceRowReader()).thenReturn(mockSourceReader);
		when(mockCopyHandler.getHeader()).thenReturn(mockHeader);
		when(mockHeader.getOrderedColumns()).thenReturn(List.of());
		when(mockSourceHandler.getEffectiveSchemaColumnNames(List.of())).thenReturn(List.of("foo"));
		when(mockSynchronizeProvider.getSchemaCopy(eq(mockIntendedChangePublisher), any())).thenReturn(mockSchemaCopy);
		when(mockSchemaCopy.getFinalSchema()).thenReturn(finalSchema);
		when(mockSynchronizeProvider.getSchemaSource(eq(mockSourceHandler), any())).thenReturn(mockSchemaSource);
		when(mockSynchronizeProvider.getRowCopy(mockIntendedChangePublisher, finalSchema, mockCopyHandler))
				.thenReturn(mockRowCopy);
		when(mockSynchronizeProvider.getRowSource(mockSourceReader, mockSourceHandler)).thenReturn(mockRowSource);
		when(mockSynchronizeProvider.getRowMerge(mockLogic, mockIntendedChangePublisher, finalSchema, mockCopyHandler, mockSourceHandler,
				false)).thenReturn(mockRowMerge);
		when(mockSourceHandler.getSourceVersion()).thenReturn(Optional.of(7L));
		when(mockSourceHandler.completePush()).thenReturn(Optional.of(8L));

		doReturn(mockIntendedChangePublisher).when(manager).newIntendedChangePublisher(mockCopyHandler);

		// call under test
		manager.synchronizeCopyWithSource(mockCallback, mockUser, request);

		// the source prepares the push artifact, then flushes it to a new version
		verify(mockSourceHandler).beginPush(mockCallback, finalSchema, SyncType.PULL_PUSH);
		verify(mockSourceHandler).completePush();
		// the new pushed version becomes the synced baseline (overrides the 7L from getSourceVersion)
		verify(mockGridManager).updateSourceEntityVersion(gridSessionId, 8L);
		verify(mockSourceHandler).close();
	}

	@Test
	public void testSynchronizeCopyWithSourceRecordSetPullPreservesUserAttribution() throws Exception {
		GridSource recordSetSource = new GridSource(444L, EntityType.recordset);
		request.setSyncType(SyncType.PULL);

		when(mockGridManager.getGridSession(mockUser, gridSessionId)).thenReturn(gridSession);
		when(mockCopyHandlerProvider.createCopyHandler(gridSession)).thenReturn(mockCopyHandler);
		when(mockCopyHandler.getGridSource()).thenReturn(recordSetSource);
		when(mockSourceHandlerProvdier.createNewProvider(mockCallback, mockUser, gridSession, recordSetSource))
				.thenReturn(mockSourceHandler);
		when(mockSourceHandler.getSourceRowReader()).thenReturn(mockSourceReader);
		when(mockCopyHandler.getHeader()).thenReturn(mockHeader);
		when(mockHeader.getOrderedColumns()).thenReturn(List.of());
		when(mockSourceHandler.getEffectiveSchemaColumnNames(List.of())).thenReturn(List.of("foo"));
		when(mockSynchronizeProvider.getSchemaCopy(eq(mockIntendedChangePublisher), any())).thenReturn(mockSchemaCopy);
		when(mockSchemaCopy.getFinalSchema()).thenReturn(finalSchema);
		when(mockSynchronizeProvider.getSchemaSource(eq(mockSourceHandler), any())).thenReturn(mockSchemaSource);
		when(mockSynchronizeProvider.getRowCopy(mockIntendedChangePublisher, finalSchema, mockCopyHandler))
				.thenReturn(mockRowCopy);
		when(mockSynchronizeProvider.getRowSource(mockSourceReader, mockSourceHandler)).thenReturn(mockRowSource);
		// PULL: the merge must preserve user attribution
		when(mockSynchronizeProvider.getRowMerge(mockLogic, mockIntendedChangePublisher, finalSchema, mockCopyHandler, mockSourceHandler,
				true)).thenReturn(mockRowMerge);
		when(mockSourceHandler.getSourceVersion()).thenReturn(Optional.of(5L));
		when(mockSourceHandler.completePush()).thenReturn(Optional.empty());

		doReturn(mockIntendedChangePublisher).when(manager).newIntendedChangePublisher(mockCopyHandler);

		// call under test
		manager.synchronizeCopyWithSource(mockCallback, mockUser, request);

		// PULL builds the merge with preserveUserAttribution=true
		verify(mockSynchronizeProvider).getRowMerge(mockLogic, mockIntendedChangePublisher, finalSchema, mockCopyHandler, mockSourceHandler,
				true);
		verify(mockSourceHandler).completePush();
		// PULL: no new version is pushed; the synced source revision is still recorded
		verify(mockGridManager).updateSourceEntityVersion(gridSessionId, 5L);
		verify(mockSourceHandler).close();
	}

	@Test
	public void testNewIntendedChangePublisher() {
		when(mockCopyHandler.getHeader()).thenReturn(mockHeader);
		when(mockCopyHandler.getConnectionInfo()).thenReturn(mockConnection);
		// call under test
		IntendedChangePublisher icp = manager.newIntendedChangePublisher(mockCopyHandler);
		assertNotNull(icp);
	}

}
