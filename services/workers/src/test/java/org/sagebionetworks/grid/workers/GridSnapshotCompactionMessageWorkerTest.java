package org.sagebionetworks.grid.workers;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.sagebionetworks.repo.manager.grid.GridSnapshotCompactionManager;
import org.sagebionetworks.util.progress.ProgressCallback;
import org.sagebionetworks.workers.util.aws.message.RecoverableMessageException;

import com.amazonaws.services.sqs.model.Message;

@ExtendWith(MockitoExtension.class)
public class GridSnapshotCompactionMessageWorkerTest {

	@Mock
	private GridSnapshotCompactionManager mockCompactionManager;

	@Mock
	private ProgressCallback mockCallback;

	@InjectMocks
	private GridSnapshotCompactionMessageWorker worker;

	@Test
	public void testRunWithSuccessfulCompaction() throws Exception {
		Message message = new Message().withBody("session-123");
		when(mockCompactionManager.compactSession("session-123")).thenReturn(true);

		// call under test
		worker.run(mockCallback, message);

		verify(mockCompactionManager).compactSession("session-123");
	}

	@Test
	public void testRunWithSkippedCompaction() throws Exception {
		Message message = new Message().withBody("session-123");
		when(mockCompactionManager.compactSession("session-123")).thenReturn(false);

		// call under test
		worker.run(mockCallback, message);

		verify(mockCompactionManager).compactSession("session-123");
	}

	@Test
	public void testRunWithRecoverableException() throws Exception {
		Message message = new Message().withBody("session-123");
		doThrow(new RecoverableMessageException("transient")).when(mockCompactionManager).compactSession("session-123");

		// call under test
		assertThrows(RecoverableMessageException.class, () -> {
			worker.run(mockCallback, message);
		});
	}

	@Test
	public void testRunWithNonRecoverableException() throws Exception {
		Message message = new Message().withBody("session-123");
		doThrow(new RuntimeException("permanent failure")).when(mockCompactionManager).compactSession("session-123");

		// call under test — should not throw (exception is caught and logged)
		worker.run(mockCallback, message);

		verify(mockCompactionManager).compactSession("session-123");
	}
}
