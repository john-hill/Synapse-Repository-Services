package org.sagebionetworks.repo.service.search;

import org.sagebionetworks.repo.model.search.table.SearchIndexStatus;

public interface SearchIndexStatusService {

	/**
	 * Get the build status of a SearchIndex's OpenSearch index.
	 *
	 * @param userId The user requesting the status
	 * @param searchIndexId The ID of the SearchIndex entity
	 * @return The current build status
	 */
	SearchIndexStatus getSearchIndexStatus(Long userId, String searchIndexId);
}
