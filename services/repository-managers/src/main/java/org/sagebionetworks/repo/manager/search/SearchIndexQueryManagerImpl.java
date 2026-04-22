package org.sagebionetworks.repo.manager.search;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.sagebionetworks.repo.manager.EntityManager;
import org.sagebionetworks.repo.manager.UserManager;
import org.sagebionetworks.repo.manager.entity.EntityAuthorizationManager;
import org.sagebionetworks.repo.manager.table.TableManagerSupport;
import org.sagebionetworks.repo.model.ACCESS_TYPE;
import org.sagebionetworks.repo.model.AuthorizationConstants;
import org.sagebionetworks.repo.model.UserInfo;
import org.sagebionetworks.repo.model.dbo.search.ColumnAnalyzerOverrideDao;
import org.sagebionetworks.repo.model.dbo.search.SynonymSetDao;
import org.sagebionetworks.repo.model.dbo.search.TextAnalyzerDao;
import org.sagebionetworks.repo.model.entity.IdAndVersion;
import org.sagebionetworks.repo.model.jdo.KeyFactory;
import org.sagebionetworks.repo.model.search.FacetRequest;
import org.sagebionetworks.repo.model.search.KeyRange;
import org.sagebionetworks.repo.model.search.KeyValues;
import org.sagebionetworks.repo.model.search.SearchFieldValue;
import org.sagebionetworks.repo.model.search.SearchHit;
import org.sagebionetworks.repo.model.search.SortField;
import org.sagebionetworks.repo.model.table.ColumnModel;
import org.sagebionetworks.repo.model.table.ColumnType;
import org.sagebionetworks.repo.model.table.SelectColumn;
import org.sagebionetworks.repo.model.table.FacetColumnResult;
import org.sagebionetworks.repo.model.search.table.ColumnAnalyzerOverride;
import org.sagebionetworks.repo.model.search.table.ColumnAnalyzerOverrideEntry;
import org.sagebionetworks.repo.model.search.table.SearchConfiguration;
import org.sagebionetworks.repo.model.search.table.SearchIndex;
import org.sagebionetworks.repo.model.search.table.SearchIndexState;
import org.sagebionetworks.repo.model.search.table.SearchIndexStatus;
import org.sagebionetworks.repo.model.search.SearchQuery;
import org.sagebionetworks.repo.model.search.SearchQueryType;
import org.sagebionetworks.repo.model.search.SearchQueryResults;
import org.sagebionetworks.repo.model.search.table.TextAnalyzer;
import org.sagebionetworks.table.cluster.ConnectionFactory;
import org.sagebionetworks.table.cluster.QueryTranslator;
import org.sagebionetworks.table.query.model.SqlContext;
import org.sagebionetworks.table.cluster.description.IndexDescription;
import org.sagebionetworks.table.cluster.search.SearchIndexStatusDao;
import org.sagebionetworks.table.cluster.utils.TableModelUtils;
import org.sagebionetworks.util.ValidateArgument;
import org.springframework.stereotype.Service;

@Service
public class SearchIndexQueryManagerImpl implements SearchIndexQueryManager {

	private static final String INDEX_PREFIX = "search-index-";

	private final EntityManager entityManager;
	private final EntityAuthorizationManager entityAuthorizationManager;
	private final ConnectionFactory connectionFactory;
	private final OpenSearchManager openSearchManager;
	private final SearchConfigurationResolver searchConfigurationResolver;
	private final UserManager userManager;
	private final TableManagerSupport tableManagerSupport;
	private final ColumnAnalyzerOverrideDao columnAnalyzerOverrideDao;
	private final SynonymSetDao synonymSetDao;
	private final TextAnalyzerDao textAnalyzerDao;

	public SearchIndexQueryManagerImpl(EntityManager entityManager,
			EntityAuthorizationManager entityAuthorizationManager,
			ConnectionFactory connectionFactory,
			OpenSearchManager openSearchManager,
			SearchConfigurationResolver searchConfigurationResolver,
			UserManager userManager,
			TableManagerSupport tableManagerSupport,
			ColumnAnalyzerOverrideDao columnAnalyzerOverrideDao,
			SynonymSetDao synonymSetDao,
			TextAnalyzerDao textAnalyzerDao) {
		this.entityManager = entityManager;
		this.entityAuthorizationManager = entityAuthorizationManager;
		this.connectionFactory = connectionFactory;
		this.openSearchManager = openSearchManager;
		this.searchConfigurationResolver = searchConfigurationResolver;
		this.userManager = userManager;
		this.tableManagerSupport = tableManagerSupport;
		this.columnAnalyzerOverrideDao = columnAnalyzerOverrideDao;
		this.synonymSetDao = synonymSetDao;
		this.textAnalyzerDao = textAnalyzerDao;
	}

	@Override
	public SearchQueryResults search(UserInfo user, String searchIndexId, SearchQuery query) {
		return executeQuery(user, searchIndexId, query);
	}

	@Override
	public SearchQueryResults autocomplete(UserInfo user, String searchIndexId, SearchQuery query) {
		ValidateArgument.required(query, "query");
		return executeQuery(user, searchIndexId, query, true);
	}

	private SearchQueryResults executeQuery(UserInfo user, String searchIndexId, SearchQuery query) {
		return executeQuery(user, searchIndexId, query, false);
	}

	private SearchQueryResults executeQuery(UserInfo user, String searchIndexId, SearchQuery query, boolean isAutocomplete) {
		ValidateArgument.required(user, "user");
		ValidateArgument.required(searchIndexId, "searchIndexId");
		ValidateArgument.required(query, "query");

		SearchIndex searchIndex = entityManager.getEntity(user, searchIndexId, SearchIndex.class);
		entityAuthorizationManager.hasAccess(user, searchIndexId, ACCESS_TYPE.READ)
				.checkAuthorizationOrElseThrow();

		String definingSQL = searchIndex.getDefiningSQL();
		List<IdAndVersion> sourceTableIds = TableModelUtils.getSourceTableIds(definingSQL);
		IdAndVersion sourceEntityId = sourceTableIds.get(0);
		entityAuthorizationManager.hasAccess(user, sourceEntityId.toString(), ACCESS_TYPE.READ)
				.checkAuthorizationOrElseThrow();

		checkIndexStatus(searchIndexId);
		Optional<SearchConfiguration> configOpt = searchConfigurationResolver.resolve(
				user, searchIndex.getSearchConfigurationId(), searchIndex.getParentId());
		SearchConfiguration config = configOpt.orElse(null);

		List<ColumnModel> columns = getSchemaOfDefiningSQL(definingSQL, sourceEntityId);

		// Build name↔ID translation maps
		Map<String, String> nameToId = columns.stream()
				.collect(Collectors.toMap(ColumnModel::getName, ColumnModel::getId, (a, b) -> a));
		Map<String, String> idToName = columns.stream()
				.collect(Collectors.toMap(ColumnModel::getId, ColumnModel::getName, (a, b) -> a));

		// Auto-populate queryFields for autocomplete with all text and link columns
		if (isAutocomplete && (query.getQueryFields() == null || query.getQueryFields().isEmpty())) {
			query.setQueryFields(getSearchableColumnNames(columns));
		}

		// Translate user-facing column names to IDs before sending to OpenSearch
		translateQueryNamesToIds(query, nameToId);

		// Load overrides and analyzers needed for field routing
		List<ColumnAnalyzerOverride> overrides = loadColumnAnalyzerOverrides(config);
		Map<String, TextAnalyzer> analyzers = collectAndLoadAnalyzers(config, overrides, columns);
		String defaultAnalyzer = config != null ? config.getDefaultAnalyzer() : null;

		SearchQueryResults results;
		if (isAutocomplete) {
			results = openSearchManager.autocomplete(getIndexName(searchIndexId), query, columns, defaultAnalyzer, overrides, analyzers);
		} else {
			results = openSearchManager.search(getIndexName(searchIndexId), query, columns, defaultAnalyzer, overrides, analyzers);
		}

		// Translate column IDs back to names in the response
		translateResultIdsToNames(results, idToName);

		return results;
	}

	/**
	 * Returns the names of all text and link columns that are searchable for autocomplete.
	 */
	private List<String> getSearchableColumnNames(List<ColumnModel> columns) {
		List<String> names = new ArrayList<>();
		for (ColumnModel column : columns) {
			if (ColumnTypeToOpenSearchMapping.isTextType(column.getColumnType())
					|| ColumnTypeToOpenSearchMapping.isLinkType(column.getColumnType())) {
				names.add(column.getName());
			}
		}
		return names;
	}

	private List<ColumnModel> getSchemaOfDefiningSQL(String definingSQL, IdAndVersion sourceEntityId) {
		IndexDescription indexDescription = tableManagerSupport.getIndexDescription(sourceEntityId);
		QueryTranslator translator = QueryTranslator.builder()
				.sql(definingSQL)
				.schemaProvider(tableManagerSupport)
				.sqlContext(SqlContext.query)
				.indexDescription(indexDescription)
				.build();
		List<ColumnModel> schemaOfSelect = translator.getSchemaOfSelect();
		List<SelectColumn> selectColumns = translator.getSelectColumns();
		// getSchemaOfSelect() returns ColumnModel objects without IDs set.
		// Copy IDs from the SelectColumn list which has the original column IDs.
		for (int i = 0; i < schemaOfSelect.size() && i < selectColumns.size(); i++) {
			if (schemaOfSelect.get(i).getId() == null && selectColumns.get(i).getId() != null) {
				schemaOfSelect.get(i).setId(selectColumns.get(i).getId());
			}
		}
		return schemaOfSelect;
	}

	private Map<String, TextAnalyzer> collectAndLoadAnalyzers(SearchConfiguration config,
			List<ColumnAnalyzerOverride> overrides, List<ColumnModel> columns) {
		Set<String> qualifiedNames = new HashSet<>();

		// SCIENTIFIC is always needed for keyword .searchable sub-fields
		qualifiedNames.add(ColumnTypeToOpenSearchMapping.getDefaultAnalyzerQualifiedName(ColumnType.STRING));

		if (overrides != null) {
			for (ColumnAnalyzerOverride cao : overrides) {
				if (cao.getOverrides() != null) {
					for (ColumnAnalyzerOverrideEntry entry : cao.getOverrides()) {
						if (entry.getIndexAnalyzer() != null) {
							qualifiedNames.add(entry.getIndexAnalyzer());
						}
						if (entry.getSearchAnalyzer() != null) {
							qualifiedNames.add(entry.getSearchAnalyzer());
						}
					}
				}
			}
		}

		if (config != null && config.getDefaultAnalyzer() != null) {
			qualifiedNames.add(config.getDefaultAnalyzer());
		}

		for (ColumnModel column : columns) {
			qualifiedNames.add(ColumnTypeToOpenSearchMapping.getDefaultAnalyzerQualifiedName(column.getColumnType()));
		}

		return new HashMap<>(textAnalyzerDao.getByQualifiedNames(new ArrayList<>(qualifiedNames)));
	}

	private List<ColumnAnalyzerOverride> loadColumnAnalyzerOverrides(SearchConfiguration config) {
		if (config == null || config.getColumnAnalyzerOverrides() == null
				|| config.getColumnAnalyzerOverrides().isEmpty()) {
			return Collections.emptyList();
		}
		return new ArrayList<>(columnAnalyzerOverrideDao.getByQualifiedNames(
				config.getColumnAnalyzerOverrides()).values());
	}

	private void checkIndexStatus(String searchIndexId) {
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

	private String getIndexName(String entityId) {
		return INDEX_PREFIX + entityId;
	}

	/**
	 * Translates all user-facing column name references in a SearchQuery to column IDs.
	 * Handles boost syntax in queryFields (e.g., "name^3" becomes "id^3").
	 */
	private void translateQueryNamesToIds(SearchQuery query, Map<String, String> nameToId) {
		if (query.getQueryFields() != null) {
			query.setQueryFields(query.getQueryFields().stream()
					.map(field -> translateFieldWithBoost(field, nameToId))
					.collect(Collectors.toList()));
		}

		translateKeyed(query.getTermsFilters(), KeyValues::getKey, KeyValues::setKey, nameToId);
		translateKeyed(query.getRangeFilters(), KeyRange::getKey, KeyRange::setKey, nameToId);
		translateKeyed(query.getFacetRequests(), FacetRequest::getColumnName, FacetRequest::setColumnName, nameToId);

		query.setExistsFilters(translateNames(query.getExistsFilters(), nameToId));
		query.setNotExistsFilters(translateNames(query.getNotExistsFilters(), nameToId));
		query.setReturnFields(translateNames(query.getReturnFields(), nameToId));

		if (query.getSort() != null) {
			for (SortField sf : query.getSort()) {
				if (!"_score".equals(sf.getColumnName())) {
					String id = nameToId.get(sf.getColumnName());
					if (id != null) {
						sf.setColumnName(id);
					}
				}
			}
		}
	}

	private String translateFieldWithBoost(String field, Map<String, String> nameToId) {
		int boostIdx = field.indexOf('^');
		if (boostIdx >= 0) {
			String name = field.substring(0, boostIdx);
			String boost = field.substring(boostIdx);
			String id = nameToId.get(name);
			return (id != null ? id : name) + boost;
		}
		String id = nameToId.get(field);
		return id != null ? id : field;
	}

	private <T> void translateKeyed(List<T> items, Function<T, String> getKey,
			BiConsumer<T, String> setKey, Map<String, String> nameToId) {
		if (items == null) {
			return;
		}
		for (T item : items) {
			String id = nameToId.get(getKey.apply(item));
			if (id != null) {
				setKey.accept(item, id);
			}
		}
	}

	private List<String> translateNames(List<String> names, Map<String, String> nameToId) {
		if (names == null) {
			return null;
		}
		return names.stream()
				.map(name -> nameToId.getOrDefault(name, name))
				.collect(Collectors.toList());
	}

	/**
	 * Translates column IDs back to user-facing names in the search results.
	 * Handles field keys, highlight keys (stripping .searchable suffix), and facet column names.
	 */
	private void translateResultIdsToNames(SearchQueryResults results, Map<String, String> idToName) {
		if (results.getHits() != null) {
			for (SearchHit hit : results.getHits()) {
				translateHitIdsToNames(hit, idToName);
			}
		}
		translateKeyed(results.getFacets(), FacetColumnResult::getColumnName, FacetColumnResult::setColumnName, idToName);
	}

	private void translateHitIdsToNames(SearchHit hit, Map<String, String> idToName) {
		translateKeyed(hit.getFields(), SearchFieldValue::getName, SearchFieldValue::setName, idToName);
		if (hit.getHighlights() != null) {
			for (SearchFieldValue hv : hit.getHighlights()) {
				String key = hv.getName();
				if (key.endsWith(".searchable")) {
					key = key.substring(0, key.length() - ".searchable".length());
				}
				String name = idToName.get(key);
				hv.setName(name != null ? name : key);
			}
		}
	}
}
