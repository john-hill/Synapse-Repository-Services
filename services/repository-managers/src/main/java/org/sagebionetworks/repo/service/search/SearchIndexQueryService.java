package org.sagebionetworks.repo.service.search;

import org.sagebionetworks.repo.model.asynch.AsynchronousJobStatus;
import org.sagebionetworks.repo.model.search.SearchQueryResults;
import org.sagebionetworks.repo.model.search.table.SearchIndexQuery;

public interface SearchIndexQueryService {

	/**
	 * Start an asynchronous search query job.
	 */
	AsynchronousJobStatus startSearchQuery(Long userId, SearchIndexQuery query);

	/**
	 * Get the results of a previously started asynchronous search query.
	 */
	AsynchronousJobStatus getSearchQueryResults(Long userId, String asyncToken) throws Throwable;

	/**
	 * Perform a synchronous autocomplete search query.
	 */
	SearchQueryResults autocomplete(Long userId, SearchIndexQuery query);
}
