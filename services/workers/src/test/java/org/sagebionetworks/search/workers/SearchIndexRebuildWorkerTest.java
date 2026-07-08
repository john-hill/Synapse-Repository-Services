package org.sagebionetworks.search.workers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.sagebionetworks.repo.manager.search.SearchIndexLifecycleManager;
import org.sagebionetworks.repo.model.search.table.SearchIndexRebuildMessage;
import org.sagebionetworks.repo.model.table.TableFailedException;
import org.sagebionetworks.util.progress.ProgressCallback;
import org.sagebionetworks.workers.util.aws.message.RecoverableMessageException;

import com.amazonaws.services.sqs.model.Message;

@ExtendWith(MockitoExtension.class)
public class SearchIndexRebuildWorkerTest {

	@Mock
	private SearchIndexLifecycleManager mockManager;
	@Mock
	private ProgressCallback mockCallback;
	@Mock
	private Message mockMessage;

	@InjectMocks
	private SearchIndexRebuildWorker worker;

	private SearchIndexRebuildMessage event() {
		return new SearchIndexRebuildMessage().setObjectId("syn111");
	}

	@Test
	public void testRun() throws Exception {
		// call under test
		worker.run(mockCallback, mockMessage, event());

		verify(mockManager).rebuildIfStale(mockCallback, "syn111");
	}

	@Test
	public void testRunRethrowsRecoverable() throws Exception {
		RecoverableMessageException ex = new RecoverableMessageException("retry");
		doThrow(ex).when(mockManager).rebuildIfStale(mockCallback, "syn111");

		RecoverableMessageException result = assertThrows(RecoverableMessageException.class,
				// call under test
				() -> worker.run(mockCallback, mockMessage, event()));

		assertEquals(ex, result);
	}

	@Test
	public void testRunSwallowsTableFailed() throws Exception {
		doThrow(new TableFailedException(new org.sagebionetworks.repo.model.table.TableStatus()))
				.when(mockManager).rebuildIfStale(mockCallback, "syn111");

		// call under test — a permanent source failure is logged and the message dropped (no throw).
		worker.run(mockCallback, mockMessage, event());

		verify(mockManager).rebuildIfStale(mockCallback, "syn111");
	}

}
