package org.sagebionetworks.repo.service.search;

import org.sagebionetworks.repo.model.asynch.AsynchronousJobStatus;
import org.sagebionetworks.repo.model.search.SearchQueryResults;
import org.sagebionetworks.repo.model.search.table.SearchIndexQuery;

public interface SearchIndexQueryService {

	/**
	 * Start an asynchronous search query job.
	 *
	 * @param userId The user performing the search
	 * @param query The search index query (contains searchIndexId and searchQuery)
	 * @return The asynchronous job status containing the job ID
	 */
	AsynchronousJobStatus startSearchQuery(Long userId, SearchIndexQuery query);

	/**
	 * Get the results of a previously started asynchronous search query.
	 *
	 * @param userId The user performing the search
	 * @param asyncToken The asynchronous job token
	 * @return The asynchronous job status
	 * @throws Throwable if the job failed or is not ready
	 */
	AsynchronousJobStatus getSearchQueryResults(Long userId, String asyncToken) throws Throwable;

	/**
	 * Perform a synchronous autocomplete search query.
	 *
	 * @param userId The user performing the search
	 * @param query The search index query (contains searchIndexId and searchQuery)
	 * @return The search results
	 */
	SearchQueryResults autocomplete(Long userId, SearchIndexQuery query);
}
