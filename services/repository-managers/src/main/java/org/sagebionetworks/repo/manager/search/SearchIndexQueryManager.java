package org.sagebionetworks.repo.manager.search;

import org.sagebionetworks.repo.model.UserInfo;
import org.sagebionetworks.repo.model.search.SearchQuery;
import org.sagebionetworks.repo.model.search.SearchQueryResults;

/**
 * Manager that encapsulates the shared authorization, configuration resolution,
 * and query execution logic for search operations.
 * Used by both the async SearchQueryWorker (full search) and the synchronous
 * autocomplete endpoint in SearchIndexQueryServiceImpl.
 */
public interface SearchIndexQueryManager {

	/**
	 * Execute a full search query against a SearchIndex's OpenSearch index.
	 * Performs authorization checks on both the SearchIndex entity and its source entity,
	 * verifies the index status, resolves configuration, and delegates to the OpenSearch manager.
	 *
	 * @param user The user performing the search
	 * @param searchIndexId The ID of the SearchIndex entity to query
	 * @param query The search query
	 * @return The search results
	 */
	SearchQueryResults search(UserInfo user, String searchIndexId, SearchQuery query);

	/**
	 * Execute a synchronous autocomplete query against a SearchIndex's OpenSearch index.
	 * Similar to search, but forces queryType to PREFIX and caps result size to 8.
	 *
	 * @param user The user performing the autocomplete
	 * @param searchIndexId The ID of the SearchIndex entity to query
	 * @param query The search query
	 * @return The autocomplete results
	 */
	SearchQueryResults autocomplete(UserInfo user, String searchIndexId, SearchQuery query);
}
