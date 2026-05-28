package org.sagebionetworks.repo.manager.search;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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
import org.sagebionetworks.repo.model.search.table.SearchAutocompleteRequest;
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
		ValidateArgument.required(user, "user");
		ValidateArgument.required(request, "request");
		ValidateArgument.required(request.getSearchIndexId(), "request.searchIndexId");
		ValidateArgument.required(request.getSearchQuery(), "request.searchQuery");

		String searchIndexId = request.getSearchIndexId();
		SearchQuery body = request.getSearchQuery();
		Set<SearchQueryPart> parts = resolveRequestedParts(request.getResponseParts());

		preflightAndCheckIndex(user, searchIndexId);
		QueryMetadata metadata = buildQueryMetadata(IdAndVersion.parse(searchIndexId));

		List<String> originalReturnFields = parts.contains(SearchQueryPart.SELECT_COLUMNS)
				? extractSourceIncludes(body)
				: null;

		SearchQueryResults rawResults = openSearchManager.search(
				getIndexName(searchIndexId), body, metadata.getColumns(), parts);

		return shapeResults(rawResults, parts, metadata, originalReturnFields);
	}

	@Override
	public SearchQueryResults autocomplete(UserInfo user, SearchAutocompleteRequest request) {
		ValidateArgument.required(user, "user");
		ValidateArgument.required(request, "request");
		ValidateArgument.required(request.getSearchIndexId(), "request.searchIndexId");
		ValidateArgument.required(request.getBody(), "request.body");

		String searchIndexId = request.getSearchIndexId();
		preflightAndCheckIndex(user, searchIndexId);
		QueryMetadata metadata = buildQueryMetadata(IdAndVersion.parse(searchIndexId));

		Set<SearchQueryPart> parts = EnumSet.of(SearchQueryPart.HITS);
		SearchQueryResults rawResults = openSearchManager.autocomplete(
				getIndexName(searchIndexId), request.getBody(), metadata.getColumns(), parts);

		return new SearchQueryResults()
				.setOffset(rawResults.getOffset())
				.setHits(rawResults.getHits());
	}

	private void preflightAndCheckIndex(UserInfo user, String searchIndexId) {
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
	}

	private SearchQueryResults shapeResults(SearchQueryResults rawResults, Set<SearchQueryPart> parts,
			QueryMetadata metadata, List<String> originalReturnFields) {
		SearchQueryResults results = new SearchQueryResults().setOffset(rawResults.getOffset());
		if (parts.contains(SearchQueryPart.HITS)) {
			results.setHits(rawResults.getHits());
			results.setNextSearchAfter(rawResults.getNextSearchAfter());
		}
		if (parts.contains(SearchQueryPart.TOTAL_HITS)) {
			results.setTotalHits(rawResults.getTotalHits());
		}
		// Aggregations and suggesters are scoped by the caller supplying body.aggregations /
		// body.suggest, not a SearchQueryPart bit. Raw fields are null when not requested.
		results.setAggregationResults(rawResults.getAggregationResults());
		results.setSuggestResults(rawResults.getSuggestResults());
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
	 */
	static Set<SearchQueryPart> resolveRequestedParts(Set<SearchQueryPart> requested) {
		if (requested == null || requested.isEmpty()) {
			return EnumSet.of(SearchQueryPart.HITS);
		}
		return EnumSet.copyOf(requested);
	}

	/**
	 * Returns the caller-supplied {@code _source.includes} (or the {@code _source} array
	 * shorthand) as a list of column names. Returns null when no source filter is supplied,
	 * when {@code _source} is a boolean, or when there are no includes.
	 */
	static List<String> extractSourceIncludes(SearchQuery body) {
		Object source = body == null ? null : body.get_source();
		if (!(source instanceof List) && !(source instanceof Map)) {
			return null;
		}
		Object includes = source instanceof List ? source : ((Map<?, ?>) source).get("includes");
		if (!(includes instanceof List)) {
			return null;
		}
		List<String> names = new ArrayList<>();
		for (Object element : (List<?>) includes) {
			if (element instanceof String) {
				names.add((String) element);
			}
		}
		return names.isEmpty() ? null : names;
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
