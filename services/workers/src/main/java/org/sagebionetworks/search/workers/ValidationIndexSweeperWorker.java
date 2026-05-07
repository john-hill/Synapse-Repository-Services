package org.sagebionetworks.search.workers;

import java.util.List;
import java.util.concurrent.TimeUnit;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.sagebionetworks.repo.manager.search.OpenSearchManager;
import org.sagebionetworks.util.progress.ProgressCallback;
import org.sagebionetworks.util.progress.ProgressingRunner;
import org.springframework.stereotype.Component;

/**
 * Scheduled safety-net for orphan {@code validation-temp-*} AOSS indices.
 * The in-request cleanup in {@link OpenSearchManager#validateAnalyzerSettings}
 * is best-effort; this worker reclaims anything it missed.
 */
@Component
public class ValidationIndexSweeperWorker implements ProgressingRunner {

	private static final Logger log = LogManager.getLogger(ValidationIndexSweeperWorker.class);

	static final long ORPHAN_AGE_MS = TimeUnit.HOURS.toMillis(1);

	private final OpenSearchManager openSearchManager;

	public ValidationIndexSweeperWorker(OpenSearchManager openSearchManager) {
		this.openSearchManager = openSearchManager;
	}

	@Override
	public void run(ProgressCallback progressCallback) throws Exception {
		List<String> orphans = openSearchManager.listOrphanValidationIndices(ORPHAN_AGE_MS);
		if (orphans.isEmpty()) {
			return;
		}
		int deleted = 0;
		for (String indexName : orphans) {
			try {
				openSearchManager.deleteIndex(indexName);
				deleted++;
			} catch (RuntimeException ex) {
				log.warn("Failed to delete orphan validation index {}; will retry next cycle: {}",
						indexName, ex.getMessage());
			}
		}
		log.info("Deleted {} of {} orphan validation indices", deleted, orphans.size());
	}
}
