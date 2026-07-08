package org.sagebionetworks.search.workers;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.sagebionetworks.repo.manager.search.SearchIndexLifecycleManager;
import org.sagebionetworks.repo.model.ObjectType;
import org.sagebionetworks.repo.model.entity.IdAndVersion;
import org.sagebionetworks.repo.model.table.TableState;
import org.sagebionetworks.repo.model.table.TableStatusChangeEvent;
import org.sagebionetworks.util.progress.ProgressCallback;

import com.amazonaws.services.sqs.model.Message;

@ExtendWith(MockitoExtension.class)
public class SearchIndexSourceUpdateWorkerTest {

	@Mock
	private SearchIndexLifecycleManager mockManager;
	@Mock
	private ProgressCallback mockCallback;
	@Mock
	private TableStatusChangeEvent mockEvent;
	@Mock
	private Message mockMessage;

	@InjectMocks
	private SearchIndexSourceUpdateWorker worker;

	@Test
	public void testRunWithAvailableState() throws Exception {
		when(mockEvent.getObjectType()).thenReturn(ObjectType.TABLE_STATUS_EVENT);
		when(mockEvent.getObjectId()).thenReturn("syn123");
		when(mockEvent.getObjectVersion()).thenReturn(null);
		when(mockEvent.getState()).thenReturn(TableState.AVAILABLE);

		// call under test
		worker.run(mockCallback, mockMessage, mockEvent);

		verify(mockManager).refreshDependentSearchIndexes(IdAndVersion.parse("123"));
	}

	@Test
	public void testRunWithAvailableStateAndVersion() throws Exception {
		when(mockEvent.getObjectType()).thenReturn(ObjectType.TABLE_STATUS_EVENT);
		when(mockEvent.getObjectId()).thenReturn("syn123");
		when(mockEvent.getObjectVersion()).thenReturn(2L);
		when(mockEvent.getState()).thenReturn(TableState.AVAILABLE);

		// call under test
		worker.run(mockCallback, mockMessage, mockEvent);

		verify(mockManager).refreshDependentSearchIndexes(IdAndVersion.parse("123.2"));
	}

	@Test
	public void testRunWithProcessingState() throws Exception {
		when(mockEvent.getObjectType()).thenReturn(ObjectType.TABLE_STATUS_EVENT);
		when(mockEvent.getState()).thenReturn(TableState.PROCESSING);

		// call under test
		worker.run(mockCallback, mockMessage, mockEvent);

		verifyNoMoreInteractions(mockManager);
	}

	@Test
	public void testRunWithProcessingFailedState() throws Exception {
		when(mockEvent.getObjectType()).thenReturn(ObjectType.TABLE_STATUS_EVENT);
		when(mockEvent.getState()).thenReturn(TableState.PROCESSING_FAILED);

		// call under test
		worker.run(mockCallback, mockMessage, mockEvent);

		verifyNoMoreInteractions(mockManager);
	}

	@Test
	public void testRunWithUnsupportedObjectType() throws Exception {
		when(mockEvent.getObjectType()).thenReturn(ObjectType.ENTITY);

		// call under test
		org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
				() -> worker.run(mockCallback, mockMessage, mockEvent));

		verifyNoMoreInteractions(mockManager);
	}

}
