package org.sagebionetworks.table.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.File;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.sagebionetworks.repo.manager.file.FileHandleManager;
import org.sagebionetworks.repo.manager.file.LocalFileUploadRequest;
import org.sagebionetworks.repo.manager.table.RowHandlerProvider;
import org.sagebionetworks.repo.manager.table.TableQueryManager;
import org.sagebionetworks.repo.manager.table.query.MainQuery;
import org.sagebionetworks.repo.manager.table.query.QueryTranslations;
import org.sagebionetworks.repo.model.UserInfo;
import org.sagebionetworks.repo.model.dao.asynch.AsyncJobProgressCallback;
import org.sagebionetworks.repo.model.dbo.dao.table.TableExceptionTranslator;
import org.sagebionetworks.repo.model.file.S3FileHandle;
import org.sagebionetworks.repo.model.table.ColumnModel;
import org.sagebionetworks.repo.model.table.DownloadPFBRequest;
import org.sagebionetworks.repo.model.table.DownloadPFBResult;
import org.sagebionetworks.repo.model.table.QueryResult;
import org.sagebionetworks.repo.model.table.QueryResultBundle;
import org.sagebionetworks.repo.model.table.RowSet;
import org.sagebionetworks.repo.model.table.TableFailedException;
import org.sagebionetworks.repo.model.table.TableStatus;
import org.sagebionetworks.repo.model.table.TableUnavailableException;
import org.sagebionetworks.table.cluster.QueryTranslator;
import org.sagebionetworks.table.cluster.avro.RowPFBWriterProvider;
import org.sagebionetworks.util.FileProvider;
import org.sagebionetworks.workers.util.aws.message.RecoverableMessageException;
import org.sagebionetworks.workers.util.semaphore.LockType;
import org.sagebionetworks.workers.util.semaphore.LockUnavilableException;

@ExtendWith(MockitoExtension.class)
public class PFBDownloadWorkerTest {

	@Mock
	private FileProvider mockFileProvider;
	@Mock
	private TableQueryManager mockTableQueryManager;
	@Mock
	private FileHandleManager mockFileHandleManager;
	@Mock
	private RowPFBWriterProvider mockWriterProvider;
	@Mock
	private TableExceptionTranslator mockTableExceptionTranslator;
	@Mock
	private AsyncJobProgressCallback mockCallback;
	@Mock
	private UserInfo mockUser;
	@Mock
	private File mockFile;
	@Mock
	private QueryTranslations mockQueryTranslations;
	@Mock
	private MainQuery mockMainQuery;
	@Mock
	private QueryTranslator mockQueryTranslator;

	@InjectMocks
	private PFBDownloadWorker worker;

	private String jobId;
	private DownloadPFBRequest request;
	private QueryResultBundle queryResultBundle;
	private List<ColumnModel> schema;
	private S3FileHandle fileHandle;
	private String tableId;
	private String fileHandleId;

	@BeforeEach
	public void before() {
		jobId = "123";
		request = new DownloadPFBRequest().setPfbEntityName("entityName").setFileName("test.avro");
		tableId = "syn456";
		queryResultBundle = new QueryResultBundle()
				.setQueryResult(new QueryResult().setQueryResults(new RowSet().setTableId(tableId)));
		schema = List.of(new ColumnModel().setName("foo"));
		fileHandleId = "8888";
		fileHandle = new S3FileHandle().setId(fileHandleId);
	}

	@Test
	public void testRun() throws RecoverableMessageException, Exception {

		when(mockUser.getId()).thenReturn(222L);
		when(mockFileProvider.createTempFile("Job-" + jobId, ".avro")).thenReturn(mockFile);
		when(mockQueryTranslations.getMainQuery()).thenReturn(mockMainQuery);
		when(mockMainQuery.getTranslator()).thenReturn(mockQueryTranslator);
		when(mockQueryTranslator.getSchemaOfSelect()).thenReturn(schema);
		when(mockFileHandleManager.uploadLocalFile(new LocalFileUploadRequest().withUserId("222")
				.withFileToUpload(mockFile).withContentType("application/octet-stream").withFileName("test.avro")))
				.thenReturn(fileHandle);

		doAnswer((InvocationOnMock invocation) -> {
			RowHandlerProvider provider = invocation.getArgument(3);
			provider.getHandler(mockQueryTranslations);
			return queryResultBundle;
		}).when(mockTableQueryManager).runQueryAsStream(any(), any(), any(), any());

		// call under test
		DownloadPFBResult results = worker.run(jobId, mockUser, request, mockCallback);
		DownloadPFBResult expected = new DownloadPFBResult().setResultsFileHandleId(fileHandleId).setTableId(tableId);
		assertEquals(expected, results);
		verify(mockCallback).updateProgress("running query...", 0L, 100L);
		verify(mockCallback).updateProgress("saving results...", 0L, 100L);
		verify(mockFile).delete();
	}

	@Test
	public void testRunWithNullFileName() throws RecoverableMessageException, Exception {
		request.setFileName(null);
		when(mockUser.getId()).thenReturn(222L);
		when(mockFileProvider.createTempFile("Job-" + jobId, ".avro")).thenReturn(mockFile);
		when(mockQueryTranslations.getMainQuery()).thenReturn(mockMainQuery);
		when(mockMainQuery.getTranslator()).thenReturn(mockQueryTranslator);
		when(mockQueryTranslator.getSchemaOfSelect()).thenReturn(schema);
		// A default file name will be used.
		when(mockFileHandleManager.uploadLocalFile(new LocalFileUploadRequest().withUserId("222")
				.withFileToUpload(mockFile).withContentType("application/octet-stream").withFileName("Job-123.avro")))
				.thenReturn(fileHandle);

		doAnswer((InvocationOnMock invocation) -> {
			RowHandlerProvider provider = invocation.getArgument(3);
			provider.getHandler(mockQueryTranslations);
			return queryResultBundle;
		}).when(mockTableQueryManager).runQueryAsStream(any(), any(), any(), any());

		// call under test
		DownloadPFBResult results = worker.run(jobId, mockUser, request, mockCallback);
		DownloadPFBResult expected = new DownloadPFBResult().setResultsFileHandleId(fileHandleId).setTableId(tableId);
		assertEquals(expected, results);
		verify(mockCallback).updateProgress("running query...", 0L, 100L);
		verify(mockCallback).updateProgress("saving results...", 0L, 100L);
		verify(mockFile).delete();
	}
	
	@Test
	public void testRunWithTableUnavailableException() throws RecoverableMessageException, Exception {
		request.setFileName(null);
		when(mockFileProvider.createTempFile("Job-" + jobId, ".avro")).thenReturn(mockFile);
		TableUnavailableException e = new TableUnavailableException(new TableStatus().setTableId(tableId));
		when(mockTableQueryManager.runQueryAsStream(any(), any(), any(), any())).thenThrow(e);

		assertThrows(RecoverableMessageException.class, ()->{
			// call under test
			worker.run(jobId, mockUser, request, mockCallback);
		});
		verify(mockCallback).updateProgress("running query...", 0L, 100L);
		verify(mockCallback).updateProgress("Waiting for the table/view to become available...", 0L, 100L);
		verify(mockFile).delete();
	}
	
	@Test
	public void testRunWithLockUnavilableException() throws RecoverableMessageException, Exception {
		request.setFileName(null);
		when(mockFileProvider.createTempFile("Job-" + jobId, ".avro")).thenReturn(mockFile);
		LockUnavilableException e = new LockUnavilableException(LockType.Read, "key", "value");
		when(mockTableQueryManager.runQueryAsStream(any(), any(), any(), any())).thenThrow(e);

		assertThrows(RecoverableMessageException.class, ()->{
			// call under test
			worker.run(jobId, mockUser, request, mockCallback);
		});
		verify(mockCallback).updateProgress("running query...", 0L, 100L);
		verify(mockCallback).updateProgress("Waiting for the table/view to become available...", 0L, 100L);
		verify(mockFile).delete();
	}
	
	@Test
	public void testRunWithTableFailedException() throws RecoverableMessageException, Exception {
		request.setFileName(null);
		when(mockFileProvider.createTempFile("Job-" + jobId, ".avro")).thenReturn(mockFile);
		TableFailedException e = new TableFailedException(new TableStatus().setTableId(tableId));
		when(mockTableQueryManager.runQueryAsStream(any(), any(), any(), any())).thenThrow(e);

		assertThrows(TableFailedException.class, ()->{
			// call under test
			worker.run(jobId, mockUser, request, mockCallback);
		});
		verify(mockCallback).updateProgress("running query...", 0L, 100L);
		verify(mockFile).delete();
	}
	
	@Test
	public void testRunWithRecoverableMessageException() throws RecoverableMessageException, Exception {
		request.setFileName(null);
		when(mockFileProvider.createTempFile("Job-" + jobId, ".avro")).thenReturn(mockFile);
		RecoverableMessageException e = new RecoverableMessageException();
		when(mockTableQueryManager.runQueryAsStream(any(), any(), any(), any())).thenThrow(e);

		assertThrows(RecoverableMessageException.class, ()->{
			// call under test
			worker.run(jobId, mockUser, request, mockCallback);
		});
		verify(mockCallback).updateProgress("running query...", 0L, 100L);
		verify(mockFile).delete();
	}
	
	@Test
	public void testRunWithSqlException() throws RecoverableMessageException, Exception {
		request.setFileName(null);
		when(mockFileProvider.createTempFile("Job-" + jobId, ".avro")).thenReturn(mockFile);
		RuntimeException e = new RuntimeException();
		when(mockTableQueryManager.runQueryAsStream(any(), any(), any(), any())).thenThrow(e);
		when(mockTableExceptionTranslator.translateException(e)).thenReturn(e);

		RuntimeException e1 = assertThrows(RuntimeException.class, ()->{
			// call under test
			worker.run(jobId, mockUser, request, mockCallback);
		});
		assertEquals(e1, e);
		verify(mockCallback).updateProgress("running query...", 0L, 100L);
		verify(mockFile).delete();
	}
}
