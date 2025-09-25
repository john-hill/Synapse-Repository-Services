package org.sagebionetworks.repo.manager.grid.internal.replica.merge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;

import org.json.JSONArray;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.sagebionetworks.repo.manager.EntityManager;
import org.sagebionetworks.repo.manager.file.BucketObjectReader;
import org.sagebionetworks.repo.manager.file.BucketObjectReaderProvider;
import org.sagebionetworks.repo.manager.file.FileHandleManager;
import org.sagebionetworks.repo.manager.grid.GridManager;
import org.sagebionetworks.repo.manager.grid.internal.replica.model.Column;
import org.sagebionetworks.repo.manager.grid.internal.replica.model.GridHeader;
import org.sagebionetworks.repo.manager.grid.internal.replica.model.RowData;
import org.sagebionetworks.repo.manager.grid.internal.replica.model.RowObject;
import org.sagebionetworks.repo.manager.grid.internal.replica.model.RowView;
import org.sagebionetworks.repo.manager.grid.internal.replica.view.GridReplicaViewManager;
import org.sagebionetworks.repo.model.FileEntity;
import org.sagebionetworks.repo.model.RecordSet;
import org.sagebionetworks.repo.model.UserInfo;
import org.sagebionetworks.repo.model.dao.asynch.AsyncJobProgressCallback;
import org.sagebionetworks.repo.model.file.CloudProviderFileHandleInterface;
import org.sagebionetworks.repo.model.file.FileHandle;
import org.sagebionetworks.repo.model.file.S3FileHandle;
import org.sagebionetworks.repo.model.grid.EventSource;
import org.sagebionetworks.repo.model.grid.GridConnectionInfo;
import org.sagebionetworks.repo.model.grid.GridCsvImportRequest;
import org.sagebionetworks.repo.model.grid.GridCsvImportResponse;
import org.sagebionetworks.repo.model.grid.GridSession;
import org.sagebionetworks.repo.model.grid.patch.LogicalTimestamp;
import org.sagebionetworks.repo.model.table.ColumnModel;
import org.sagebionetworks.repo.model.table.ColumnType;
import org.sagebionetworks.repo.model.table.CsvTableDescriptor;
import org.sagebionetworks.workers.util.aws.message.RecoverableMessageException;

@ExtendWith(MockitoExtension.class)
public class GridCsvImporterImplTest {
	
	@Mock
	private GridManager mockGridManager;
	@Mock
	private GridReplicaViewManager mockGridViewManager;
	@Mock
	private EntityManager mockEntityManager;
	@Mock
	private FileHandleManager mockFileHandleManager;
	@Mock
	private BucketObjectReaderProvider mockFileReaderProvider;
	@Mock
	private GridCsvImportDao mockImportDao;
	@Mock
	private JoinedRowChangePublisher mockChangePublisher;
	
	@InjectMocks
	private GridCsvImporterImpl importer;

	private UserInfo user;
	private Long replicaId = 456L;
	private String sessionId = "sessionId";
	private CsvTableDescriptor descriptor;
	private GridCsvImportRequest request;
	private GridSession session;
	private GridConnectionInfo connectionInfo;
	private GridHeader gridHeader;
	private List<String> upsertKey;
	private RecordSet recordSet;
	private CloudProviderFileHandleInterface fileHandle;
	private String csvContent;
	private List<RowView> gridRows;
	private List<JoinedRow> joinedRows;
	private GridCsvImportResponse response;
	
	@Mock
	private AsyncJobProgressCallback mockCallback;
	@Mock
	private BucketObjectReader mockObjReader;
	
	@Captor
	private ArgumentCaptor<DataStream> streamCaptor;
	
	@Captor
	private ArgumentCaptor<Iterator<JoinedRow>> joinedRowCaptor;
	
	@BeforeEach
	public void before() {
		user = new UserInfo(false, 123L);
		
		descriptor = new CsvTableDescriptor().setIsFirstLineHeader(true);
		
		request = new GridCsvImportRequest()
			.setSessionId(sessionId)
			.setFileHandleId("123")
			.setCsvDescriptor(descriptor)
			.setSchema( List.of(
				new ColumnModel().setName("a").setColumnType(ColumnType.INTEGER),
				new ColumnModel().setName("b").setColumnType(ColumnType.STRING),
				new ColumnModel().setName("c").setColumnType(ColumnType.BOOLEAN)
			));
		
		session = new GridSession()
			.setSessionId(sessionId)
			.setSourceEntityId("syn123");
		connectionInfo = new GridConnectionInfo()
			.setSessionId(sessionId)
			.setReplicaId(replicaId);
		gridHeader = new GridHeader().setOrderedColumns(List.of(
			new Column().setName("a").setVectorIndex(0),
			new Column().setName("b").setVectorIndex(1),
			new Column().setName("c").setVectorIndex(2)
		));
		
		upsertKey = List.of("a");
		recordSet = new RecordSet().setUpsertKey(upsertKey);
		
		fileHandle = new S3FileHandle()
			.setBucketName("bucket")
			.setKey("key");
		
		csvContent = 
			"a,b,c" + System.lineSeparator() +
			"0,0,false" + System.lineSeparator() +
			"1,1,false" + System.lineSeparator() + 
			"2,2,false" + System.lineSeparator();
		
		gridRows = List.of(
			new RowView().setRowObject(new RowObject().setData(new RowData().setCells(
				new JSONArray("[0,1,true]")
			))),
			new RowView().setRowObject(new RowObject().setData(new RowData().setCells(
				new JSONArray("[2,3,true]")
			)))
		);
		
		joinedRows = List.of(
			new JoinedRow(new JSONArray(new Object[] {0, 0, false}), new LogicalTimestamp().setReplicaId(replicaId).setSequenceNumber(1L)),
			new JoinedRow(new JSONArray(new Object[] {1, 1, false}), null),
			new JoinedRow(new JSONArray(new Object[] {2, 2, false}), new LogicalTimestamp().setReplicaId(replicaId).setSequenceNumber(2L))
		);
		
		response = new GridCsvImportResponse()
			.setSessionId(sessionId)
			.setTotalCount(3L)
			.setUpdatedCount(2L)
			.setCreatedCount(1L);
	}
	
	@Test
	public void testImportCsv() {
		setupFullMocks();
				
		// Call under test
		GridCsvImportResponse result = importer.importCsv(user, request, mockCallback);
		
		assertEquals(response, result);
		
		ColumnMapping[] expectedMapping = new ColumnMapping[] {
			new ColumnMapping("a", ColumnType.INTEGER, 0, 0, true),
			new ColumnMapping("b", ColumnType.STRING, 1, 1, false),
			new ColumnMapping("c", ColumnType.BOOLEAN, 2, 2, false)
		};
		
		verifyImportSequence(expectedMapping);
	}
	
	@Test
	public void testImportCsvWithDifferentHeaderOrder() {
		csvContent = 
			"b,a,c" + System.lineSeparator() +
			"0,0,false" + System.lineSeparator() +
			"1,1,false" + System.lineSeparator() + 
			"2,2,false" + System.lineSeparator();
		
		setupFullMocks();
				
		// Call under test
		GridCsvImportResponse result = importer.importCsv(user, request, mockCallback);
		
		assertEquals(response, result);
		
		ColumnMapping[] expectedMapping = new ColumnMapping[] {
			new ColumnMapping("a", ColumnType.INTEGER, 1, 0, true),
			new ColumnMapping("b", ColumnType.STRING, 0, 1, false),
			new ColumnMapping("c", ColumnType.BOOLEAN, 2, 2, false)
		};
		
		verifyImportSequence(expectedMapping);
	}
	
	@Test
	public void testImportCsvWithNoCsvHeader() {
		descriptor.setIsFirstLineHeader(false);
		
		csvContent =
			"0,0,false" + System.lineSeparator() +
			"1,1,false" + System.lineSeparator() + 
			"2,2,false" + System.lineSeparator();
		
		setupFullMocks();
				
		// Call under test
		GridCsvImportResponse result = importer.importCsv(user, request, mockCallback);
		
		assertEquals(response, result);
		
		ColumnMapping[] expectedMapping = new ColumnMapping[] {
			new ColumnMapping("a", ColumnType.INTEGER, 0, 0, true),
			new ColumnMapping("b", ColumnType.STRING, 1, 1, false),
			new ColumnMapping("c", ColumnType.BOOLEAN, 2, 2, false)
		};
		
		verifyImportSequence(expectedMapping);
	}
	
	@Test
	public void testImportCsvWithDifferentGridOrder() {
		
		gridHeader = new GridHeader().setOrderedColumns(List.of(
			new Column().setName("b").setVectorIndex(0),
			new Column().setName("a").setVectorIndex(1),
			new Column().setName("c").setVectorIndex(2)
		));
		
		setupFullMocks();
				
		// Call under test
		GridCsvImportResponse result = importer.importCsv(user, request, mockCallback);
		
		assertEquals(response, result);
		
		ColumnMapping[] expectedMapping = new ColumnMapping[] {
			new ColumnMapping("a", ColumnType.INTEGER, 0, 1, true),
			new ColumnMapping("b", ColumnType.STRING, 1, 0, false),
			new ColumnMapping("c", ColumnType.BOOLEAN, 2, 2, false)
		};
		
		verifyImportSequence(expectedMapping);
	}
	
	@Test
	public void testImportCsvWithDifferentUpsertKeyOrder() {
		recordSet.setUpsertKey(List.of("b", "a"));
		
		setupFullMocks();
				
		// Call under test
		GridCsvImportResponse result = importer.importCsv(user, request, mockCallback);
		
		assertEquals(response, result);
		
		ColumnMapping[] expectedMapping = new ColumnMapping[] {
			new ColumnMapping("b", ColumnType.STRING, 1, 1, true),
			new ColumnMapping("a", ColumnType.INTEGER, 0, 0, true),
			new ColumnMapping("c", ColumnType.BOOLEAN, 2, 2, false)
		};
		
		verifyImportSequence(expectedMapping);
	}
	
	@Test
	public void testImportCsvWithNonExistingGridColumn() {
		csvContent = 
			"a,b,c,d" + System.lineSeparator() +
			"0,0,false,0.1" + System.lineSeparator() +
			"1,1,false,0.2" + System.lineSeparator() + 
			"2,2,false,0.3" + System.lineSeparator();
		
		request.setSchema(List.of(
			new ColumnModel().setName("a").setColumnType(ColumnType.INTEGER),
			new ColumnModel().setName("b").setColumnType(ColumnType.STRING),
			new ColumnModel().setName("c").setColumnType(ColumnType.BOOLEAN),
			// Extra column not in the grid
			new ColumnModel().setName("d").setColumnType(ColumnType.DOUBLE)
		));		
		
		setupFullMocks();
				
		// Call under test
		GridCsvImportResponse result = importer.importCsv(user, request, mockCallback);
		
		assertEquals(response, result);
		
		ColumnMapping[] expectedMapping = new ColumnMapping[] {
			new ColumnMapping("a", ColumnType.INTEGER, 0, 0, true),
			new ColumnMapping("b", ColumnType.STRING, 1, 1, false),
			new ColumnMapping("c", ColumnType.BOOLEAN, 2, 2, false)
		};
		
		verifyImportSequence(expectedMapping);
	}
	
	@Test
	public void testImportCsvWithNoConnectionInfo() {
				
		when(mockGridManager.getGridSession(user, sessionId)).thenReturn(session);
		when(mockGridManager.getSingletonConnection(sessionId, EventSource.INTERNAL)).thenReturn(Optional.empty());
		
		assertEquals("No internal connection found for session: sessionId", assertThrows(RecoverableMessageException.class, () -> {	
			// Call under test
			importer.importCsv(user, request, mockCallback);
		}).getMessage());
		
		verifyNoMoreInteractions(mockImportDao);
	}
	
	@Test
	public void testImportCsvWithNoValidationConnectionInfo() {
				
		when(mockGridManager.getGridSession(user, sessionId)).thenReturn(session);
		when(mockGridManager.getSingletonConnection(sessionId, EventSource.INTERNAL)).thenReturn(Optional.of(connectionInfo));
		when(mockGridViewManager.readHeader(sessionId, replicaId)).thenReturn(Optional.of(gridHeader));
		when(mockGridManager.getSingletonConnection(sessionId, EventSource.VALIDATION)).thenReturn(Optional.empty());
		
		assertEquals("No internal connection found for session: sessionId", assertThrows(RecoverableMessageException.class, () -> {	
			// Call under test
			importer.importCsv(user, request, mockCallback);
		}).getMessage());
		
		verifyNoMoreInteractions(mockImportDao);
	}
	
	@Test
	public void testImportCsvWithNoGridHeader() {
				
		when(mockGridManager.getGridSession(user, sessionId)).thenReturn(session);
		when(mockGridManager.getSingletonConnection(sessionId, EventSource.INTERNAL)).thenReturn(Optional.of(connectionInfo));
		when(mockGridViewManager.readHeader(sessionId, replicaId)).thenReturn(Optional.empty());
		
		assertEquals("Grid header has not yet been instantiated for sessionId: sessionId", assertThrows(RecoverableMessageException.class, () -> {	
			// Call under test
			importer.importCsv(user, request, mockCallback);
		}).getMessage());
		
		verifyNoMoreInteractions(mockImportDao);
	}
	
	@Test
	public void testImportCsvWithUnsupportedSourceEntity() {
				
		when(mockGridManager.getGridSession(user, sessionId)).thenReturn(session);
		when(mockGridManager.getSingletonConnection(sessionId, EventSource.INTERNAL)).thenReturn(Optional.of(connectionInfo));
		when(mockGridManager.getSingletonConnection(sessionId, EventSource.VALIDATION)).thenReturn(Optional.of(connectionInfo));
		when(mockGridViewManager.readHeader(sessionId, replicaId)).thenReturn(Optional.of(gridHeader));
		when(mockEntityManager.getEntity(user, session.getSourceEntityId())).thenReturn(Mockito.mock(FileEntity.class));
		
		assertEquals("Unsupported grid session: only a grid created from a record set is supported.", assertThrows(IllegalArgumentException.class, () -> {	
			// Call under test
			importer.importCsv(user, request, mockCallback);
		}).getMessage());
		
		verifyNoMoreInteractions(mockImportDao);
	}
	
	@Test
	public void testImportCsvWithUnsupportedFile() {
				
		when(mockGridManager.getGridSession(user, sessionId)).thenReturn(session);
		when(mockGridManager.getSingletonConnection(sessionId, EventSource.INTERNAL)).thenReturn(Optional.of(connectionInfo));
		when(mockGridManager.getSingletonConnection(sessionId, EventSource.VALIDATION)).thenReturn(Optional.of(connectionInfo));
		when(mockGridViewManager.readHeader(sessionId, replicaId)).thenReturn(Optional.of(gridHeader));
		when(mockEntityManager.getEntity(user, session.getSourceEntityId())).thenReturn(recordSet);
		when(mockFileHandleManager.getRawFileHandle(user, request.getFileHandleId())).thenReturn(Mockito.mock(FileHandle.class));
		
		assertEquals("Only S3 and Google Cloud Storage files that Synapse can access are supported.", assertThrows(IllegalArgumentException.class, () -> {	
			// Call under test
			importer.importCsv(user, request, mockCallback);
		}).getMessage());
		
		verifyNoMoreInteractions(mockImportDao);
	}
	
	@Test
	public void testImportCsvWithEmptyCsv() {
		csvContent = "a,b,c" + System.lineSeparator();
		
		when(mockGridManager.getGridSession(user, sessionId)).thenReturn(session);
		when(mockGridManager.getSingletonConnection(sessionId, EventSource.INTERNAL)).thenReturn(Optional.of(connectionInfo));
		when(mockGridManager.getSingletonConnection(sessionId, EventSource.VALIDATION)).thenReturn(Optional.of(connectionInfo));
		when(mockGridViewManager.readHeader(sessionId, replicaId)).thenReturn(Optional.of(gridHeader));
		when(mockEntityManager.getEntity(user, session.getSourceEntityId())).thenReturn(recordSet);
		when(mockFileHandleManager.getRawFileHandle(user, request.getFileHandleId())).thenReturn(fileHandle);
		when(mockFileReaderProvider.getBucketObjectReader(fileHandle.getClass())).thenReturn(mockObjReader);
		when(mockObjReader.openStream(fileHandle.getBucketName(), fileHandle.getKey())).thenReturn(
			new ByteArrayInputStream(csvContent.getBytes(StandardCharsets.UTF_8))
		);
		
		assertEquals("The CSV file cannot be empty.", assertThrows(IllegalArgumentException.class, () -> {	
			// Call under test
			importer.importCsv(user, request, mockCallback);
		}).getMessage());
		
		verifyNoMoreInteractions(mockImportDao);
	}
	
	@Test
	public void testImportCsvWithMismatchingSchemaSize() {
		request.setSchema(List.of(
			new ColumnModel().setName("a").setColumnType(ColumnType.INTEGER),
			new ColumnModel().setName("b").setColumnType(ColumnType.STRING)
			// Missing column "c"
		));
		
		when(mockGridManager.getGridSession(user, sessionId)).thenReturn(session);
		when(mockGridManager.getSingletonConnection(sessionId, EventSource.INTERNAL)).thenReturn(Optional.of(connectionInfo));
		when(mockGridManager.getSingletonConnection(sessionId, EventSource.VALIDATION)).thenReturn(Optional.of(connectionInfo));
		when(mockGridViewManager.readHeader(sessionId, replicaId)).thenReturn(Optional.of(gridHeader));
		when(mockEntityManager.getEntity(user, session.getSourceEntityId())).thenReturn(recordSet);
		when(mockFileHandleManager.getRawFileHandle(user, request.getFileHandleId())).thenReturn(fileHandle);
		when(mockFileReaderProvider.getBucketObjectReader(fileHandle.getClass())).thenReturn(mockObjReader);
		when(mockObjReader.openStream(fileHandle.getBucketName(), fileHandle.getKey())).thenReturn(
			new ByteArrayInputStream(csvContent.getBytes(StandardCharsets.UTF_8))
		);
		
		assertEquals("The CSV header does not match the schema size.", assertThrows(IllegalArgumentException.class, () -> {	
			// Call under test
			importer.importCsv(user, request, mockCallback);
		}).getMessage());
		
		verifyNoMoreInteractions(mockImportDao);
	}

	@Test
	public void testImportCsvWithMismatchingSchemaColumns() {
		request.setSchema(List.of(
			new ColumnModel().setName("a").setColumnType(ColumnType.INTEGER),
			new ColumnModel().setName("b").setColumnType(ColumnType.STRING),
			// Different column "d" instead of "c"
			new ColumnModel().setName("d").setColumnType(ColumnType.STRING)
		));
		
		when(mockGridManager.getGridSession(user, sessionId)).thenReturn(session);
		when(mockGridManager.getSingletonConnection(sessionId, EventSource.INTERNAL)).thenReturn(Optional.of(connectionInfo));
		when(mockGridManager.getSingletonConnection(sessionId, EventSource.VALIDATION)).thenReturn(Optional.of(connectionInfo));
		when(mockGridViewManager.readHeader(sessionId, replicaId)).thenReturn(Optional.of(gridHeader));
		when(mockEntityManager.getEntity(user, session.getSourceEntityId())).thenReturn(recordSet);
		when(mockFileHandleManager.getRawFileHandle(user, request.getFileHandleId())).thenReturn(fileHandle);
		when(mockFileReaderProvider.getBucketObjectReader(fileHandle.getClass())).thenReturn(mockObjReader);
		when(mockObjReader.openStream(fileHandle.getBucketName(), fileHandle.getKey())).thenReturn(
			new ByteArrayInputStream(csvContent.getBytes(StandardCharsets.UTF_8))
		);
		
		assertEquals("The CSV header column \"c\" does not exist in the schema.", assertThrows(IllegalArgumentException.class, () -> {	
			// Call under test
			importer.importCsv(user, request, mockCallback);
		}).getMessage());
		
		verifyNoMoreInteractions(mockImportDao);
	}
	
	@Test
	public void testImportCsvWithUpsertKeyNotInCsvSchema() {
		recordSet.setUpsertKey(List.of("d"));
		
		when(mockGridManager.getGridSession(user, sessionId)).thenReturn(session);
		when(mockGridManager.getSingletonConnection(sessionId, EventSource.INTERNAL)).thenReturn(Optional.of(connectionInfo));
		when(mockGridManager.getSingletonConnection(sessionId, EventSource.VALIDATION)).thenReturn(Optional.of(connectionInfo));
		when(mockGridViewManager.readHeader(sessionId, replicaId)).thenReturn(Optional.of(gridHeader));
		when(mockEntityManager.getEntity(user, session.getSourceEntityId())).thenReturn(recordSet);
		when(mockFileHandleManager.getRawFileHandle(user, request.getFileHandleId())).thenReturn(fileHandle);
		when(mockFileReaderProvider.getBucketObjectReader(fileHandle.getClass())).thenReturn(mockObjReader);
		when(mockObjReader.openStream(fileHandle.getBucketName(), fileHandle.getKey())).thenReturn(
			new ByteArrayInputStream(csvContent.getBytes(StandardCharsets.UTF_8))
		);
		
		assertEquals("The upsert key column \"d\" does not exist in the CSV schema.", assertThrows(IllegalArgumentException.class, () -> {	
			// Call under test
			importer.importCsv(user, request, mockCallback);
		}).getMessage());
		
		verifyNoMoreInteractions(mockImportDao);
	}
	
	@Test
	public void testImportCsvWithUpsertKeyNotInGridSchema() {
		gridHeader = new GridHeader().setOrderedColumns(List.of(
			new Column().setName("b").setVectorIndex(0),
			new Column().setName("c").setVectorIndex(1),
			new Column().setName("d").setVectorIndex(2)
		));
		
		when(mockGridManager.getGridSession(user, sessionId)).thenReturn(session);
		when(mockGridManager.getSingletonConnection(sessionId, EventSource.INTERNAL)).thenReturn(Optional.of(connectionInfo));
		when(mockGridManager.getSingletonConnection(sessionId, EventSource.VALIDATION)).thenReturn(Optional.of(connectionInfo));
		when(mockGridViewManager.readHeader(sessionId, replicaId)).thenReturn(Optional.of(gridHeader));
		when(mockEntityManager.getEntity(user, session.getSourceEntityId())).thenReturn(recordSet);
		when(mockFileHandleManager.getRawFileHandle(user, request.getFileHandleId())).thenReturn(fileHandle);
		when(mockFileReaderProvider.getBucketObjectReader(fileHandle.getClass())).thenReturn(mockObjReader);
		when(mockObjReader.openStream(fileHandle.getBucketName(), fileHandle.getKey())).thenReturn(
			new ByteArrayInputStream(csvContent.getBytes(StandardCharsets.UTF_8))
		);
		
		assertEquals("The upsert key column \"a\" does not exist in the grid schema.", assertThrows(IllegalArgumentException.class, () -> {	
			// Call under test
			importer.importCsv(user, request, mockCallback);
		}).getMessage());
		
		verifyNoMoreInteractions(mockImportDao);
	}
	
	private void setupFullMocks() {
		when(mockGridManager.getGridSession(user, sessionId)).thenReturn(session);
		when(mockGridManager.getSingletonConnection(sessionId, EventSource.INTERNAL)).thenReturn(Optional.of(connectionInfo));
		when(mockGridManager.getSingletonConnection(sessionId, EventSource.VALIDATION)).thenReturn(Optional.of(connectionInfo));
		when(mockGridViewManager.readHeader(sessionId, replicaId)).thenReturn(Optional.of(gridHeader));
		when(mockEntityManager.getEntity(user, session.getSourceEntityId())).thenReturn(recordSet);
		when(mockFileHandleManager.getRawFileHandle(user, request.getFileHandleId())).thenReturn(fileHandle);
		when(mockFileReaderProvider.getBucketObjectReader(fileHandle.getClass())).thenReturn(mockObjReader);
		when(mockObjReader.openStream(fileHandle.getBucketName(), fileHandle.getKey())).thenReturn(
			new ByteArrayInputStream(csvContent.getBytes(StandardCharsets.UTF_8))
		);
		when(mockGridViewManager.getQueryIterator(gridHeader, Collections.emptyList())).thenReturn(gridRows.iterator());
		when(mockImportDao.getJoinedTempTableIterator(any())).thenReturn(joinedRows.iterator());
		when(mockChangePublisher.processJoinedRows(eq(gridHeader), eq(connectionInfo), any(), any())).thenReturn(response);
	}

	private void verifyImportSequence(ColumnMapping[] expectedMapping) {
		verify(mockImportDao).streamToCsvTempTable(streamCaptor.capture(), eq(expectedMapping));
		
		assertTrue(streamCaptor.getValue() instanceof CsvDataStream);

		verify(mockImportDao).streamToGridTempTable(streamCaptor.capture(), eq(expectedMapping));

		assertTrue(streamCaptor.getValue() instanceof GridDataStream);
		
		verify(mockImportDao).getJoinedTempTableIterator(expectedMapping);
	
		verify(mockChangePublisher).processJoinedRows(eq(gridHeader), eq(connectionInfo), joinedRowCaptor.capture(), eq(expectedMapping));
		
		List<JoinedRow> capturedJoinedRows = new ArrayList<>();
		
		joinedRowCaptor.getValue().forEachRemaining(capturedJoinedRows::add);

		assertEquals(joinedRows, capturedJoinedRows);
	}

}
