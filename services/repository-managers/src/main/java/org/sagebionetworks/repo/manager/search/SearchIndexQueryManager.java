package org.sagebionetworks.repo.manager.search;

import org.sagebionetworks.repo.model.UserInfo;
import org.sagebionetworks.repo.model.search.SearchQueryResults;
import org.sagebionetworks.repo.model.search.table.SearchIndexQuery;

/**
 * Manager that encapsulates the shared authorization, configuration resolution,
 * and query execution logic for search operations.
 * Used by both the async SearchQueryWorker (full search) and the synchronous
 * autocomplete endpoint in SearchIndexQueryServiceImpl.
 *
 * <p>Both methods accept a {@link SearchIndexQuery} and honor its
 * {@code responseParts} list: when null or empty every part is populated;
 * otherwise only the named parts are. Omitting parts also skips the
 * corresponding work in OpenSearch (aggregations, total-hit tracking, and
 * source retrieval) where applicable. Autocomplete never produces facets.
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
	 * Similar to search, but forces queryType to PREFIX and caps result size to 8.
	 *
	 * @param user    The user performing the autocomplete
	 * @param request The full SearchIndexQuery; responseParts are honored uniformly with
	 *                {@link #search(UserInfo, SearchIndexQuery)}.
	 * @return The autocomplete results
	 */
	SearchQueryResults autocomplete(UserInfo user, SearchIndexQuery request);
}
