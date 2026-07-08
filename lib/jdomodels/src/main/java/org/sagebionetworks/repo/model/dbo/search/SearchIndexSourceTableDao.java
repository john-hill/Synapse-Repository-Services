package org.sagebionetworks.repo.model.dbo.search;

import java.util.List;
import java.util.Optional;

import org.sagebionetworks.repo.model.entity.IdAndVersion;

/**
 * Reverse-lookup DAO mapping a source table/view to the SearchIndex that depends on it. A
 * SearchIndex references exactly one source entity, so each SearchIndex owns a single edge
 * row keyed on its node id.
 */
public interface SearchIndexSourceTableDao {

	/**
	 * Records (or replaces) the source table that the given SearchIndex depends on. Idempotent:
	 * re-registering the same SearchIndex overwrites its existing edge and rotates the etag.
	 *
	 * @param searchIndexId The id of the SearchIndex node.
	 * @param sourceTableId The id and (optional) version of the source table/view.
	 */
	void setSourceTable(IdAndVersion searchIndexId, IdAndVersion sourceTableId);

	/**
	 * @param sourceTableId The id and (optional) version of a source table/view.
	 * @return The ids of every SearchIndex that depends on the source table with the given id
	 *         and (optional) version.
	 */
	List<Long> getDependentSearchIndexIds(IdAndVersion sourceTableId);

	/**
	 * Forward lookup of the source table a SearchIndex depends on.
	 *
	 * @param searchIndexId The id of the SearchIndex node.
	 * @return The source table's id and version, or empty if the SearchIndex has no registered edge.
	 */
	Optional<IdAndVersion> getSourceTable(IdAndVersion searchIndexId);

	/**
	 * Removes the edge for the given SearchIndex, if present. The node {@code ON DELETE CASCADE}
	 * normally handles cleanup; this is for explicit removal.
	 *
	 * @param searchIndexId The id of the SearchIndex node.
	 */
	void delete(IdAndVersion searchIndexId);

}
