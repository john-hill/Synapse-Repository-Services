package org.sagebionetworks.repo.service.search;

import org.sagebionetworks.repo.model.asynch.AsynchronousJobStatus;
import org.sagebionetworks.repo.model.table.search.SearchQuery;
import org.sagebionetworks.repo.model.table.search.SearchResults;

public interface SearchIndexQueryService {

	/**
	 * Start an asynchronous search query job.
	 *
	 * @param userId The user performing the search
	 * @param query The search query
	 * @return The asynchronous job status containing the job ID
	 */
	AsynchronousJobStatus startSearchQuery(Long userId, SearchQuery query);

	/**
	 * Get the results of a previously started asynchronous search query.
	 *
	 * @param userId The user performing the search
	 * @param asyncToken The asynchronous job token
	 * @return The search results
	 * @throws Throwable if the job failed or is not ready
	 */
	AsynchronousJobStatus getSearchQueryResults(Long userId, String asyncToken) throws Throwable;

	/**
	 * Perform a synchronous autocomplete search query.
	 *
	 * @param userId The user performing the search
	 * @param query The search query
	 * @return The search results
	 */
	SearchResults autocomplete(Long userId, SearchQuery query);
}
