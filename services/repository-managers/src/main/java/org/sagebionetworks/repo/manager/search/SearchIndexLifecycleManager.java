package org.sagebionetworks.repo.manager.search;

import org.sagebionetworks.workers.util.aws.message.RecoverableMessageException;
import org.sagebionetworks.util.progress.ProgressCallback;

/**
 * Manager for search index lifecycle operations (create, update, delete).
 * Orchestrates between the OpenSearchManager (data access layer), entity/table managers,
 * configuration resolution, and the SearchIndexStatusDao.
 */
public interface SearchIndexLifecycleManager {

	/**
	 * Handle a create event for a SearchIndex entity. Deletes any existing AOSS index,
	 * then builds the index from scratch and indexes all data.
	 *
	 * @param progressCallback Progress callback for long-running operations
	 * @param entityId         The SearchIndex entity ID
	 * @param userId           The user who triggered the change
	 */
	void handleCreate(ProgressCallback progressCallback, String entityId, Long userId)
			throws RecoverableMessageException;

	/**
	 * Handle an update event for a SearchIndex entity. Only builds the AOSS index
	 * if one does not already exist (no status row). This is a no-op if the index
	 * has already been created, preventing unnecessary rebuilds.
	 *
	 * @param progressCallback Progress callback for long-running operations
	 * @param entityId         The SearchIndex entity ID
	 * @param userId           The user who triggered the change
	 */
	void handleUpdate(ProgressCallback progressCallback, String entityId, Long userId)
			throws RecoverableMessageException;

	/**
	 * Handle a delete event for a SearchIndex entity. Uses state-driven coordination:
	 * if the index is currently being built (CREATING), throws RecoverableMessageException
	 * to retry later. Only proceeds when the state is ACTIVE, FAILED, or absent.
	 *
	 * @param entityId The SearchIndex entity ID
	 */
	void handleDelete(String entityId) throws RecoverableMessageException;
}
