package org.sagebionetworks.search.workers;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.sagebionetworks.repo.manager.search.OpenSearchManager;
import org.sagebionetworks.util.progress.ProgressCallback;

@ExtendWith(MockitoExtension.class)
public class ValidationIndexSweeperWorkerTest {

	@Mock
	private OpenSearchManager openSearchManager;
	@Mock
	private ProgressCallback progressCallback;

	@InjectMocks
	private ValidationIndexSweeperWorker worker;

	@Test
	public void testRunWithNoOrphans() throws Exception {
		when(openSearchManager.listOrphanValidationIndices(ValidationIndexSweeperWorker.ORPHAN_AGE_MS))
				.thenReturn(Collections.emptyList());

		// call under test
		worker.run(progressCallback);

		verify(openSearchManager).listOrphanValidationIndices(ValidationIndexSweeperWorker.ORPHAN_AGE_MS);
		verify(openSearchManager, never()).deleteIndex(any());
		verifyNoMoreInteractions(openSearchManager);
	}

	@Test
	public void testRunWithMultipleOrphans() throws Exception {
		when(openSearchManager.listOrphanValidationIndices(ValidationIndexSweeperWorker.ORPHAN_AGE_MS))
				.thenReturn(Arrays.asList("validation-temp-a", "validation-temp-b"));

		// call under test
		worker.run(progressCallback);

		verify(openSearchManager).deleteIndex("validation-temp-a");
		verify(openSearchManager).deleteIndex("validation-temp-b");
	}

	@Test
	public void testRunWithOneDeleteFailure() throws Exception {
		when(openSearchManager.listOrphanValidationIndices(ValidationIndexSweeperWorker.ORPHAN_AGE_MS))
				.thenReturn(Arrays.asList("validation-temp-a", "validation-temp-b"));
		doThrow(new RuntimeException("AOSS 503")).when(openSearchManager).deleteIndex("validation-temp-a");

		// call under test
		worker.run(progressCallback);

		verify(openSearchManager, times(1)).deleteIndex("validation-temp-a");
		verify(openSearchManager, times(1)).deleteIndex("validation-temp-b");
	}

	@Test
	public void testRunWithListException() throws Exception {
		when(openSearchManager.listOrphanValidationIndices(ValidationIndexSweeperWorker.ORPHAN_AGE_MS))
				.thenThrow(new IOException("AOSS unreachable"));

		// call under test
		assertThrows(IOException.class, () -> worker.run(progressCallback));

		verify(openSearchManager, never()).deleteIndex(any());
	}
}
