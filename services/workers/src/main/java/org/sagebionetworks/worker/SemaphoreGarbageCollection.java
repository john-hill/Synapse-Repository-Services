package org.sagebionetworks.worker;

import org.sagebionetworks.cloudwatch.WorkerLogger;
import org.sagebionetworks.database.semaphore.CountingSemaphore;
import org.sagebionetworks.util.progress.ProgressCallback;
import org.sagebionetworks.util.progress.ProgressingRunner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class SemaphoreGarbageCollection implements ProgressingRunner {

	static final String METRIC_AUTO_INCREMENT = "SemaphoreLockAutoIncrement";

	@Autowired
	CountingSemaphore countingSemaphore;

	@Autowired
	WorkerLogger workerLogger;

	@Override
	public void run(ProgressCallback progressCallback) throws Exception {
		countingSemaphore.runGarbageCollection();
		long autoIncrement = countingSemaphore.getLockKeyAutoIncrement();
		workerLogger.logWorkerGaugeMetric(SemaphoreGarbageCollection.class, METRIC_AUTO_INCREMENT, autoIncrement);
	}

}
