package org.sagebionetworks.repo.manager.search;

import org.sagebionetworks.repo.model.UserInfo;
import org.sagebionetworks.repo.model.search.SearchQueryResults;
import org.sagebionetworks.repo.model.search.table.SearchAutocompleteRequest;
import org.sagebionetworks.repo.model.search.table.SearchIndexQuery;

/**
 * Manager that encapsulates the shared authorization, configuration resolution,
 * and query execution logic for search operations. Used by both the async
 * SearchQueryWorker (full search) and the synchronous autocomplete endpoint in
 * SearchIndexQueryServiceImpl.
 */
public interface SearchIndexQueryManager {

	/**
	 * Execute a full search query against a SearchIndex's OpenSearch index.
	 * Performs authorization checks on both the SearchIndex entity and its source entity,
	 * verifies the index status, resolves configuration, and delegates to the OpenSearch manager.
	 *
	 * @param user    The user performing the search
	 * @param request The full SearchIndexQuery carrying the target index, the structured
	 *                search query, and an optional responseParts list.
	 * @return The search results (parts populated per the request's responseParts)
	 */
	SearchQueryResults search(UserInfo user, SearchIndexQuery request);

	/**
	 * Execute a synchronous autocomplete query against a SearchIndex's OpenSearch index.
	 * The result is always {@code hits}-only and is capped at 8 entries by the
	 * OpenSearch manager.
	 *
	 * @param user    The user performing the autocomplete
	 * @param request The slim autocomplete request: target index, a prefix-flavored DSL
	 *                clause, and optional returnFields.
	 * @return The autocomplete hits (up to 8)
	 */
	SearchQueryResults autocomplete(UserInfo user, SearchAutocompleteRequest request);
}
