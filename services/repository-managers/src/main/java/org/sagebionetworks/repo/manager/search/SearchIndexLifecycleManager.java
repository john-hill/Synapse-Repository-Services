package org.sagebionetworks.repo.manager.search;

import org.sagebionetworks.repo.model.table.TableFailedException;
import org.sagebionetworks.repo.model.table.TableUnavailableException;
import org.sagebionetworks.util.progress.ProgressCallback;
import org.sagebionetworks.workers.util.aws.message.RecoverableMessageException;
import org.sagebionetworks.workers.util.semaphore.LockUnavilableException;

/**
 * Manager for search index lifecycle operations (create, update, delete).
 * Orchestrates between the OpenSearchManager (data access layer), entity/table managers,
 * configuration resolution, and the SearchIndexStatusDao.
 */
public interface SearchIndexLifecycleManager {

	/**
	 * Handle a create event for a SearchIndex entity. Always deletes any existing AOSS index,
	 * then builds the index from scratch and indexes all data.
	 *
	 * @param progressCallback Progress callback for long-running operations
	 * @param entityId         The SearchIndex entity ID
	 * @param userId           The user who triggered the change
	 */
	void handleCreate(ProgressCallback progressCallback, String entityId, Long userId)
			throws RecoverableMessageException, TableUnavailableException, TableFailedException, LockUnavilableException;

	/**
	 * Handle an update event for a SearchIndex entity. Unconditionally deletes and rebuilds
	 * the AOSS index — simpler and bulletproof for the MVP.
	 *
	 * @param progressCallback Progress callback for long-running operations
	 * @param entityId         The SearchIndex entity ID
	 * @param userId           The user who triggered the change
	 */
	void handleUpdate(ProgressCallback progressCallback, String entityId, Long userId)
			throws RecoverableMessageException, TableUnavailableException, TableFailedException, LockUnavilableException;

	/**
	 * Handle a delete event for a SearchIndex entity. If the index is currently being built
	 * (CREATING), throws RecoverableMessageException to retry later. Otherwise deletes the
	 * AOSS index and the status row.
	 *
	 * @param entityId The SearchIndex entity ID
	 */
	void handleDelete(String entityId) throws RecoverableMessageException;
}
