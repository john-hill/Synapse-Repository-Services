package org.sagebionetworks.grid.workers;

import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.sagebionetworks.repo.manager.grid.GridSnapshotCompactionManager;
import org.sagebionetworks.util.progress.ProgressCallback;

@ExtendWith(MockitoExtension.class)
public class GridSnapshotCompactionWorkerTest {

	@Mock
	private GridSnapshotCompactionManager mockCompactionManager;

	@Mock
	private ProgressCallback mockCallback;

	@InjectMocks
	private GridSnapshotCompactionWorker worker;

	@Test
	public void testRun() throws Exception {
		// call under test
		worker.run(mockCallback);

		verify(mockCompactionManager).scanAndPublishSessionsNeedingCompaction();
	}
}
