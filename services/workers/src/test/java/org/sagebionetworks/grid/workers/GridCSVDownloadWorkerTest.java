package org.sagebionetworks.grid.workers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.sagebionetworks.repo.manager.file.FileHandleManager;
import org.sagebionetworks.repo.manager.file.LocalFileUploadRequest;
import org.sagebionetworks.repo.manager.grid.internal.replica.export.GridReplicaCsvExporter;
import org.sagebionetworks.repo.model.UserInfo;
import org.sagebionetworks.repo.model.asynch.AsynchronousJobStatus;
import org.sagebionetworks.repo.model.dao.asynch.AsyncJobProgressCallback;
import org.sagebionetworks.repo.model.file.S3FileHandle;
import org.sagebionetworks.repo.model.grid.DownloadFromGridRequest;
import org.sagebionetworks.repo.model.grid.DownloadFromGridResult;
import org.sagebionetworks.table.worker.CSVWriterProvider;
import org.sagebionetworks.util.Clock;
import org.sagebionetworks.workers.util.aws.message.RecoverableMessageException;

import com.amazonaws.services.sqs.model.Message;
import au.com.bytecode.opencsv.CSVWriter;

@ExtendWith(MockitoExtension.class)
public class GridCSVDownloadWorkerTest {
    @Mock
    private GridReplicaCsvExporter mockGridReplicaCsvExporter;
    @Mock
    private FileHandleManager mockFileHandleManager;
    @Mock
    private Clock mockClock;
    @Mock
    private AsyncJobProgressCallback mockJobProgressCallback;
    @Captor
    private ArgumentCaptor<LocalFileUploadRequest> fileUploadCaptor;
    @Mock
    private CSVWriterProvider mockCSVWriterProvider;
    @Mock
    private CSVWriter mockCSVWriter;

    @InjectMocks
    private GridCSVDownloadWorker worker;

    private String sessionId = "some-session-id";
    private String fileHandleId = "8888";

    Long userId;
    UserInfo userInfo;
    DownloadFromGridRequest request;
    AsynchronousJobStatus status;
    String jobId;
    Message message;

    DownloadFromGridResult results;

    @BeforeEach
    public void before() throws Exception {
        userId = 987L;
        userInfo = new UserInfo(false);
        userInfo.setId(userId);

        request = new DownloadFromGridRequest();
        request.setSessionId(sessionId);

        jobId = "1";
        status = new AsynchronousJobStatus();
        status.setJobId(jobId);
        status.setRequestBody(request);
        status.setStartedByUserId(userId);

        message = new Message();
        message.setBody(jobId);

        results = new DownloadFromGridResult();
        results.setSessionId(sessionId);
        results.setResultsFileHandleId(fileHandleId);

        when(mockGridReplicaCsvExporter.exportGridAsCsv(any(), any(), any())).thenReturn(new DownloadFromGridResult().setSessionId(sessionId));
    }

    @Test
    public void testBasicQuery() throws Exception {
        when(mockFileHandleManager.uploadLocalFile(any())).thenReturn(new S3FileHandle().setId(fileHandleId));
        when(mockCSVWriterProvider.createWriter(any(), any())).thenReturn(mockCSVWriter);

        // call under test
        DownloadFromGridResult response = worker.run(jobId, userInfo, request, mockJobProgressCallback);

        assertEquals(results, response);

        verify(mockFileHandleManager).uploadLocalFile(fileUploadCaptor.capture());
        LocalFileUploadRequest request = fileUploadCaptor.getValue();
        assertNotNull(request);
        assertEquals(userInfo.getId().toString(), request.getUserId());
        assertEquals("text/csv", request.getContentType());
        assertEquals(null, request.getFileName());
        verify(mockCSVWriterProvider).createWriter(any(), any());
        verify(mockCSVWriter).close();
    }

    @Test
    public void testBasicQueryWithError() throws Exception {
        when(mockCSVWriterProvider.createWriter(any(), any())).thenReturn(mockCSVWriter);
        doThrow(new IOException("Fake out of disk space error")).when(mockCSVWriter).close();

        String message = assertThrows(RuntimeException.class, ()->{
            // call under test
            worker.run(jobId, userInfo, request, mockJobProgressCallback);
        }).getMessage();
        assertEquals("java.io.IOException: Fake out of disk space error", message);

        verify(mockCSVWriterProvider).createWriter(any(), any());
        verify(mockCSVWriter).close();
    }

    @Test
    public void testRecoverableMessageException() {
        when(mockCSVWriterProvider.createWriter(any(), any())).thenReturn(mockCSVWriter);

        RecoverableMessageException ex = new RecoverableMessageException("recoverable");

        when(mockGridReplicaCsvExporter.exportGridAsCsv(any(),any(), any())).thenThrow(ex);
        assertThrows(RecoverableMessageException.class, () -> {
            // call under test
            worker.run(jobId, userInfo, request, mockJobProgressCallback);
        }, "recoverable");
    }

    @Test
    public void testUnknownException() {
        when(mockCSVWriterProvider.createWriter(any(), any())).thenReturn(mockCSVWriter);

        RuntimeException error = new RuntimeException("Bad stuff happened");
        // table not available
        when(mockGridReplicaCsvExporter.exportGridAsCsv(any(),any(), any())).thenThrow(error);
        assertThrows(RuntimeException.class, () -> {
            // call under test
            worker.run(jobId, userInfo, request, mockJobProgressCallback);
        },"Bad stuff happened");
    }

}