package org.sagebionetworks.repo.manager.search;

import java.util.List;

import org.sagebionetworks.repo.model.entity.IdAndVersion;
import org.sagebionetworks.repo.model.table.TableFailedException;
import org.sagebionetworks.repo.model.table.TableUnavailableException;
import org.sagebionetworks.util.progress.ProgressCallback;
import org.sagebionetworks.workers.util.aws.message.RecoverableMessageException;

/**
 * Orchestrates the build / rebuild / delete lifecycle of an AOSS index that backs a
 * {@code SearchIndex} entity. Implementations serialize work per entity via a
 * cluster-wide write lock, resolve the effective {@code SearchConfiguration}, validate
 * referenced analyzer / synonym qnames, build the index, stream rows from the source
 * table as the realm's anonymous user (so {@code addRowLevelFilter} enforces benefactor
 * ACLs and only publicly-visible rows reach AOSS), and persist final state into
 * {@code SEARCH_INDEX_STATUS}. Transient failures propagate so the worker can translate
 * them to {@code RecoverableMessageException}; permanent failures are recorded as
 * FAILED with a truncated error message.
 */
public interface SearchIndexLifecycleManager {

	/**
	 * Handle a create event for a SearchIndex entity. Always deletes any existing AOSS index,
	 * then builds the index from scratch and indexes all data.
	 */
	void handleCreate(ProgressCallback progressCallback, String entityId, Long userId) throws Exception;

	/**
	 * Handle an update event for a SearchIndex entity. Unconditionally deletes and rebuilds
	 * the AOSS index — simpler and bulletproof for the MVP.
	 */
	void handleUpdate(ProgressCallback progressCallback, String entityId, Long userId) throws Exception;

	/**
	 * Handle a delete event for a SearchIndex entity. Acquires the per-entity write lock
	 * to serialize with any concurrent build, then deletes the AOSS index and the status row.
	 */
	void handleDelete(ProgressCallback progressCallback, String entityId) throws Exception;

	/**
	 * Resolve every SELECT-list column in {@code definingSql} — including literals
	 * and aliases not on the source schema — to a persisted {@link
	 * org.sagebionetworks.repo.model.table.ColumnModel} and bind them to the
	 * SearchIndex. Returns the bound column ids in SELECT-list order.
	 */
	List<String> registerSchema(IdAndVersion searchIndexId, String definingSql);
}
