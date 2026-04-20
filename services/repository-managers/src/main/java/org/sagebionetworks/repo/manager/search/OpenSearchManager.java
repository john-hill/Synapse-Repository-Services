package org.sagebionetworks.repo.manager.search;

import java.util.List;

import org.opensearch.client.opensearch.core.bulk.BulkOperation;
import org.sagebionetworks.repo.model.search.SearchQuery;
import org.sagebionetworks.repo.model.search.SearchQueryResults;
import org.sagebionetworks.repo.model.search.table.TextAnalyzerSettings;

/**
 * Manager interface for OpenSearch Serverless (AOSS) operations.
 * Manages index lifecycle (create, delete) and query execution (search, autocomplete).
 * Configuration data is provided via {@link SearchIndexContextProvider} which supports
 * lazy loading to avoid unnecessary DB lookups for simple operations.
 */
public interface OpenSearchManager {

	/**
	 * Create an OpenSearch index with the given schema and configuration.
	 *
	 * @param indexName The OpenSearch index name
	 * @param context   Provider for columns, analyzers, synonym sets, and overrides
	 * @return The JSON representation of the CreateIndexRequest sent to OpenSearch
	 */
	String createIndex(String indexName, SearchIndexContextProvider context);

	/**
	 * Delete an OpenSearch index. No-op if the index does not exist.
	 *
	 * @param indexName The OpenSearch index name
	 */
	void deleteIndex(String indexName);

	/**
	 * Bulk index a batch of documents into the OpenSearch index.
	 *
	 * @param indexName   The OpenSearch index name
	 * @param operations  List of bulk operations to execute
	 * @return The number of documents successfully indexed
	 */
	long bulkIndex(String indexName, List<BulkOperation> operations);

	/**
	 * Execute a search query against the OpenSearch index.
	 *
	 * @param indexName The OpenSearch index name
	 * @param query     The search query
	 * @param context   Provider for columns, analyzers, and overrides
	 * @return The search results
	 */
	SearchQueryResults search(String indexName, SearchQuery query, SearchIndexContextProvider context);

	/**
	 * Execute an autocomplete query. Forces PREFIX query type (mapped to multi_match with bool_prefix)
	 * and caps size at 8.
	 *
	 * @param indexName The OpenSearch index name
	 * @param query     The search query (queryType will be overridden to PREFIX)
	 * @param context   Provider for columns, analyzers, and overrides
	 * @return The autocomplete results
	 */
	SearchQueryResults autocomplete(String indexName, SearchQuery query, SearchIndexContextProvider context);

	/**
	 * Validate analyzer settings by invoking the AOSS _analyze API.
	 *
	 * @param settings The TextAnalyzerSettings to validate
	 * @throws IllegalArgumentException if the analyzer configuration is rejected by OpenSearch
	 * @throws IllegalStateException if the OpenSearch service is unreachable
	 */
	void validateAnalyzerSettings(TextAnalyzerSettings settings);
}
