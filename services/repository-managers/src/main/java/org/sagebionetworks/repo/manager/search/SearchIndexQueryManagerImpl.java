package org.sagebionetworks.repo.manager.search;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.sagebionetworks.repo.manager.EntityManager;
import org.sagebionetworks.repo.manager.table.TableManagerSupport;
import org.sagebionetworks.repo.model.UserInfo;
import org.sagebionetworks.repo.model.entity.IdAndVersion;
import org.sagebionetworks.repo.model.jdo.KeyFactory;
import org.sagebionetworks.repo.model.search.SearchQuery;
import org.sagebionetworks.repo.model.search.SearchQueryPart;
import org.sagebionetworks.repo.model.search.SearchQueryResults;
import org.sagebionetworks.repo.model.search.table.SearchIndex;
import org.sagebionetworks.repo.model.search.table.SearchIndexQuery;
import org.sagebionetworks.repo.model.search.table.SearchIndexState;
import org.sagebionetworks.repo.model.search.table.SearchIndexStatus;
import org.sagebionetworks.repo.model.table.ColumnModel;
import org.sagebionetworks.repo.model.table.SelectColumn;
import org.sagebionetworks.table.cluster.ConnectionFactory;
import org.sagebionetworks.table.cluster.QueryTranslator;
import org.sagebionetworks.table.cluster.description.IndexDescription;
import org.sagebionetworks.table.cluster.search.SearchIndexStatusDao;
import org.sagebionetworks.table.cluster.utils.TableModelUtils;
import org.sagebionetworks.util.ValidateArgument;
import org.springframework.stereotype.Service;

@Service
public class SearchIndexQueryManagerImpl implements SearchIndexQueryManager {

	private static final String INDEX_PREFIX = "search-index-";

	private final EntityManager entityManager;
	private final ConnectionFactory connectionFactory;
	private final OpenSearchManager openSearchManager;
	private final TableManagerSupport tableManagerSupport;

	public SearchIndexQueryManagerImpl(EntityManager entityManager,
			ConnectionFactory connectionFactory,
			OpenSearchManager openSearchManager,
			TableManagerSupport tableManagerSupport) {
		this.entityManager = entityManager;
		this.connectionFactory = connectionFactory;
		this.openSearchManager = openSearchManager;
		this.tableManagerSupport = tableManagerSupport;
	}

	@Override
	public SearchQueryResults search(UserInfo user, SearchIndexQuery request) {
		return executeQuery(user, request, false);
	}

	@Override
	public SearchQueryResults autocomplete(UserInfo user, SearchIndexQuery request) {
		ValidateArgument.required(request, "request");
		ValidateArgument.required(request.getSearchQuery(), "request.searchQuery");
		return executeQuery(user, request, true);
	}

	SearchQueryResults executeQuery(UserInfo user, SearchIndexQuery request, boolean isAutocomplete) {
		ValidateArgument.required(user, "user");
		ValidateArgument.required(request, "request");
		ValidateArgument.required(request.getSearchIndexId(), "request.searchIndexId");
		ValidateArgument.required(request.getSearchQuery(), "request.searchQuery");

		String searchIndexId = request.getSearchIndexId();
		SearchQuery query = request.getSearchQuery();
		Set<SearchQueryPart> parts = resolveRequestedParts(request.getResponseParts());

		SearchIndex searchIndex = entityManager.getEntity(user, searchIndexId, SearchIndex.class);

		String definingSQL = searchIndex.getDefiningSQL();
		List<IdAndVersion> sourceTableIds = TableModelUtils.getSourceTableIds(definingSQL);
		IdAndVersion sourceEntityId = sourceTableIds.get(0);
		// TODO: Copy-pasted from TableQueryManagerImpl.queryPreflight — READ on the source entity
		// (plus DOWNLOAD if it's a table) applied recursively across the IndexDescription. Remove
		// this duplication when row-level filtering lands and unifies the auth gate between the
		// table-query and search-query paths.
		IndexDescription indexDescription = tableManagerSupport.getIndexDescription(sourceEntityId);
		tableManagerSupport.validateTableReadAccess(user, indexDescription);

		checkIndexStatus(searchIndexId);

		QueryMetadata metadata = buildQueryMetadata(IdAndVersion.parse(searchIndexId));
		List<ColumnModel> columns = metadata.getColumns();

		// Snapshot the user-facing returnFields BEFORE handing the query to OpenSearchManager.
		// The response's selectColumns filter operates on user-facing names, and the
		// OpenSearchManager only sees the user-facing names too — but we capture here so a
		// future change to the manager-side translation can't drift.
		List<String> originalReturnFields = query.getReturnFields() == null
				? null
				: new ArrayList<>(query.getReturnFields());

		// Query-time analysis is baked into the AOSS index at build time, so the manager
		// does not need TextAnalyzer or override metadata here — AOSS routes each field
		// through its own configured search analyzer automatically. OpenSearchManager
		// handles all column-name → column-id translation internally (in the opaque-DSL
		// rewrite step) and rewrites column ids back to column names on the response.
		SearchQueryResults rawResults;
		if (isAutocomplete) {
			rawResults = openSearchManager.autocomplete(getIndexName(searchIndexId), query, columns, parts);
		} else {
			rawResults = openSearchManager.search(getIndexName(searchIndexId), query, columns, parts);
		}

		// Defense-in-depth: gate every opt-in field by the resolved parts even though
		// OpenSearchManager already obeys them. Keeps the response contract crisp regardless
		// of upstream behavior, and makes the per-part wiring obvious to readers.
		SearchQueryResults results = new SearchQueryResults().setOffset(rawResults.getOffset());
		if (parts.contains(SearchQueryPart.HITS)) {
			results.setHits(rawResults.getHits());
			results.setNextSearchAfter(rawResults.getNextSearchAfter());
		}
		if (parts.contains(SearchQueryPart.TOTAL_HITS)) {
			results.setTotalHits(rawResults.getTotalHits());
		}
		// FACETS is the historical opt-in for aggregations; expose the raw aggregationResults
		// JSON string when requested, since the typed FacetColumnResult shape is gone.
		if (parts.contains(SearchQueryPart.FACETS)) {
			results.setAggregationResults(rawResults.getAggregationResults());
		}
		// Suggesters do not have a dedicated SearchQueryPart bit; they're scoped by the
		// caller supplying SearchQuery.suggest. Pass through unconditionally — this matches
		// how aggregationResults flow when the caller doesn't ask for FACETS but did ask
		// for an aggregation explicitly.
		results.setSuggestResults(rawResults.getSuggestResults());
		// SELECT_COLUMNS is a manager-layer addition (not produced by OpenSearch), so set it here.
		if (parts.contains(SearchQueryPart.SELECT_COLUMNS)) {
			results.setSelectColumns(filterSelectColumnsForReturnFields(
					metadata.getSelectColumns(), originalReturnFields));
		}
		return results;
	}

	/**
	 * Resolves the caller's requested response parts. A null or empty set means
	 * "default minimal payload" (just {@link SearchQueryPart#HITS}); otherwise the
	 * input is returned as an {@link EnumSet} for O(1) {@code contains} lookups.
	 *
	 * <p>The default-minimal behavior matches what most callers want — they ask
	 * for hits, and only opt in to extras like total count or facets when needed.
	 * It also keeps the OpenSearch request lean for the common case (no aggregations,
	 * no total-hit tracking).
	 */
	static Set<SearchQueryPart> resolveRequestedParts(Set<SearchQueryPart> requested) {
		if (requested == null || requested.isEmpty()) {
			return EnumSet.of(SearchQueryPart.HITS);
		}
		return EnumSet.copyOf(requested);
	}

	/**
	 * Filter a SELECT-clause {@link SelectColumn} list down to entries whose names
	 * match {@code returnFields}. When {@code returnFields} is null or empty, the
	 * original list is returned unchanged. Unknown names are silently dropped; the
	 * SELECT-clause order is preserved.
	 */
	List<SelectColumn> filterSelectColumnsForReturnFields(List<SelectColumn> selectColumns, List<String> returnFields) {
		if (selectColumns == null) {
			return null;
		}
		if (returnFields == null || returnFields.isEmpty()) {
			return selectColumns;
		}
		Set<String> allowed = new HashSet<>(returnFields);
		return selectColumns.stream()
				.filter(sc -> allowed.contains(sc.getName()))
				.collect(Collectors.toList());
	}

	/**
	 * Loads the bound {@link ColumnModel} list for the SearchIndex and the parallel
	 * {@link SelectColumn} list used by response serialization.
	 */
	QueryMetadata buildQueryMetadata(IdAndVersion searchIndexIdAndVersion) {
		List<ColumnModel> columns = tableManagerSupport.getTableSchema(searchIndexIdAndVersion);
		if (columns == null || columns.isEmpty()) {
			throw new IllegalStateException("SearchIndex " + searchIndexIdAndVersion
					+ " has no bound schema — update the entity to re-register.");
		}
		return new QueryMetadata(columns, TableModelUtils.getSelectColumns(columns));
	}

	void checkIndexStatus(String searchIndexId) {
		SearchIndexStatusDao statusDao = connectionFactory.getSearchIndexStatusDao();
		Optional<SearchIndexStatus> statusOpt = statusDao.getStatus(KeyFactory.stringToKey(searchIndexId));
		if (statusOpt.isEmpty() || statusOpt.get().getState() == SearchIndexState.CREATING) {
			throw new IllegalStateException("Search index is still building. Please try again later.");
		}
		if (statusOpt.get().getState() == SearchIndexState.FAILED) {
			String storedError = statusOpt.get().getErrorMessage();
			String detail = storedError == null || storedError.isBlank()
					? "Delete or update the SearchIndex to trigger a rebuild."
					: storedError;
			throw new IllegalArgumentException(
					"Search index build failed: " + detail
							+ " Delete or update the SearchIndex to trigger a rebuild.");
		}
	}

	String getIndexName(String entityId) {
		return INDEX_PREFIX + entityId;
	}

	/**
	 * Holder for the two parallel column views produced from a single
	 * {@link QueryTranslator} build: the full {@link ColumnModel} list used for
	 * analyzer routing and name/ID translation, and the parallel
	 * {@link SelectColumn} list surfaced in the response.
	 */
	static final class QueryMetadata {
		private final List<ColumnModel> columns;
		private final List<SelectColumn> selectColumns;

		QueryMetadata(List<ColumnModel> columns, List<SelectColumn> selectColumns) {
			this.columns = columns;
			this.selectColumns = selectColumns;
		}

		List<ColumnModel> getColumns() {
			return columns;
		}

		List<SelectColumn> getSelectColumns() {
			return selectColumns;
		}
	}
}
