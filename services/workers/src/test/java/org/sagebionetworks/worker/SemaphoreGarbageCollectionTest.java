package org.sagebionetworks.worker;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.sagebionetworks.cloudwatch.WorkerLogger;
import org.sagebionetworks.database.semaphore.CountingSemaphore;
import org.sagebionetworks.util.progress.ProgressCallback;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;


@RunWith(MockitoJUnitRunner.class)
public class SemaphoreGarbageCollectionTest {

	@Mock
	CountingSemaphore mockSemaphore;
	@Mock
	WorkerLogger mockWorkerLogger;
	@Mock
	ProgressCallback mockCallback;

	@InjectMocks
	SemaphoreGarbageCollection collection;

	@Test
	public void testCollection() throws Exception {
		long autoIncrementValue = 12345L;
		when(mockSemaphore.getLockKeyAutoIncrement()).thenReturn(autoIncrementValue);

		// call under test
		collection.run(mockCallback);

		verify(mockSemaphore).runGarbageCollection();
		verify(mockSemaphore).getLockKeyAutoIncrement();
		verify(mockWorkerLogger).logWorkerGaugeMetric(
				SemaphoreGarbageCollection.class,
				SemaphoreGarbageCollection.METRIC_AUTO_INCREMENT,
				autoIncrementValue);
	}
}
