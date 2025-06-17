package org.sagebionetworks.search.workers.sqs.search;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.sagebionetworks.repo.manager.search.oss.SearchManager;
import org.sagebionetworks.repo.model.message.ChangeMessage;
import org.sagebionetworks.repo.model.message.ChangeType;
import org.sagebionetworks.search.oss.worker.SearchIndexWorker;
import org.sagebionetworks.util.progress.ProgressCallback;

import java.util.List;

import static org.mockito.Mockito.verify;


@ExtendWith(MockitoExtension.class)
public class SearchIndexWorkerTest {

    @InjectMocks
    private SearchIndexWorker searchIndexWorker;
    @Mock
    private ProgressCallback mockCallback;
    @Mock
    private SearchManager mockManager;

    @Test
    public void testRun() {
        ChangeMessage message = new ChangeMessage().setChangeNumber(123L).setChangeType(ChangeType.CREATE);

        //call under test
        searchIndexWorker.run(mockCallback, List.of(message));
        verify(mockManager).documentChangeMessages(List.of(message));
    }
}
