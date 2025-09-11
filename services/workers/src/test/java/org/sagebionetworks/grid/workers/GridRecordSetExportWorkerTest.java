package org.sagebionetworks.grid.workers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.sagebionetworks.repo.manager.grid.internal.replica.export.GridRecordSetExporter;
import org.sagebionetworks.repo.model.UserInfo;
import org.sagebionetworks.repo.model.dao.asynch.AsyncJobProgressCallback;
import org.sagebionetworks.repo.model.grid.GridRecordSetExportRequest;
import org.sagebionetworks.repo.model.grid.GridRecordSetExportResponse;
import org.sagebionetworks.workers.util.aws.message.RecoverableMessageException;

@ExtendWith(MockitoExtension.class)
public class GridRecordSetExportWorkerTest {
    @Mock
    private GridRecordSetExporter mockGridRecordSetExporter;
    @Mock
    private AsyncJobProgressCallback mockJobProgressCallback;

    @InjectMocks
    private GridRecordSetExportWorker worker;

    private UserInfo userInfo;
    private String jobId;

    private GridRecordSetExportRequest request;
    private GridRecordSetExportResponse results;

    @BeforeEach
    public void before() throws Exception {
        userInfo = new UserInfo(false, 123L);

        request = new GridRecordSetExportRequest();
        
        jobId = "1";

        results = new GridRecordSetExportResponse();
        
    }

    @Test
    public void testRun() throws Exception {
        when(mockGridRecordSetExporter.exportGrid(userInfo, request, mockJobProgressCallback)).thenReturn(results);

        // call under test
        GridRecordSetExportResponse response = worker.run(jobId, userInfo, request, mockJobProgressCallback);

        assertEquals(results, response);
    }

    @Test
    public void testRunWithRecoverableMessageException() throws Exception {
        RecoverableMessageException ex = new RecoverableMessageException("recoverable");

        when(mockGridRecordSetExporter.exportGrid(any(), any(), any())).thenThrow(ex);
       
        assertEquals(ex, assertThrows(RecoverableMessageException.class, () -> {
            // call under test
            worker.run(jobId, userInfo, request, mockJobProgressCallback);
        }, "recoverable"));
    }

    @Test
    public void testRunWithUnknownException() throws Exception {
        RuntimeException ex = new RuntimeException("Bad stuff happened");
        
        when(mockGridRecordSetExporter.exportGrid(any(), any(), any())).thenThrow(ex);
        
        assertEquals(ex, assertThrows(RuntimeException.class, () -> {
            // call under test
            worker.run(jobId, userInfo, request, mockJobProgressCallback);
        }, "Bad stuff happened"));
    }

}