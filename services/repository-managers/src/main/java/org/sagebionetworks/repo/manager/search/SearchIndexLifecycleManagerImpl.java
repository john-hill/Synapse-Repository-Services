package org.sagebionetworks.repo.manager.search;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.opensearch.client.opensearch.core.bulk.BulkOperation;
import org.sagebionetworks.repo.manager.EntityManager;
import org.sagebionetworks.repo.manager.UserManager;
import org.sagebionetworks.repo.manager.table.TableManagerSupport;
import org.sagebionetworks.repo.manager.table.TableQueryManager;
import org.sagebionetworks.repo.model.AuthorizationConstants;
import org.sagebionetworks.repo.model.UserInfo;
import org.sagebionetworks.repo.model.dbo.search.ColumnAnalyzerOverrideDao;
import org.sagebionetworks.repo.model.dbo.search.SynonymSetDao;
import org.sagebionetworks.repo.model.dbo.search.TextAnalyzerDao;
import org.sagebionetworks.repo.model.UnauthorizedException;
import org.sagebionetworks.repo.model.entity.IdAndVersion;
import org.sagebionetworks.repo.model.jdo.KeyFactory;
import org.sagebionetworks.repo.model.search.table.ColumnAnalyzerOverride;
import org.sagebionetworks.repo.model.search.table.ColumnAnalyzerOverrideEntry;
import org.sagebionetworks.repo.model.search.table.SearchConfiguration;
import org.sagebionetworks.repo.model.search.table.SearchIndex;
import org.sagebionetworks.repo.model.search.table.SearchIndexState;
import org.sagebionetworks.repo.model.search.table.SynonymSet;
import org.sagebionetworks.repo.model.search.table.TextAnalyzer;
import org.sagebionetworks.repo.model.table.ColumnModel;
import org.sagebionetworks.repo.model.table.ColumnType;
import org.sagebionetworks.repo.model.table.Row;
import org.sagebionetworks.repo.model.table.SelectColumn;
import org.sagebionetworks.repo.model.table.TableState;
import org.sagebionetworks.repo.model.table.TableStatus;
import org.sagebionetworks.table.cluster.ConnectionFactory;
import org.sagebionetworks.table.cluster.search.SearchIndexStatusDao;
import org.sagebionetworks.table.cluster.utils.TableModelUtils;
import org.sagebionetworks.repo.manager.table.query.QueryTranslations;
import org.sagebionetworks.util.ValidateArgument;
import org.sagebionetworks.util.progress.ProgressCallback;
import org.sagebionetworks.workers.util.aws.message.RecoverableMessageException;
import org.springframework.stereotype.Service;

import org.sagebionetworks.repo.model.ACCESS_TYPE;
import org.sagebionetworks.repo.model.table.Query;
import org.sagebionetworks.repo.model.table.QueryOptions;
import org.sagebionetworks.repo.model.table.QueryResultBundle;
import org.sagebionetworks.repo.model.dao.table.RowHandler;

import java.io.IOException;

@Service
public class SearchIndexLifecycleManagerImpl implements SearchIndexLifecycleManager {

	private static final Logger LOG = LogManager.getLogger(SearchIndexLifecycleManagerImpl.class);
	private static final String INDEX_PREFIX = "search-index-";
	private static final int MAX_ERROR_MESSAGE_LENGTH = 3000;
	private static final int BATCH_SIZE = 1000;
	private static final long MAX_ROWS = 500_000L;

	private final ConnectionFactory connectionFactory;
	private final OpenSearchManager openSearchManager;
	private final SearchConfigurationResolver searchConfigurationResolver;
	private final TableManagerSupport tableManagerSupport;
	private final TableQueryManager tableQueryManager;
	private final UserManager userManager;
	private final EntityManager entityManager;
	private final SynonymSetDao synonymSetDao;
	private final ColumnAnalyzerOverrideDao columnAnalyzerOverrideDao;
	private final TextAnalyzerDao textAnalyzerDao;

	public SearchIndexLifecycleManagerImpl(ConnectionFactory connectionFactory,
			OpenSearchManager openSearchManager,
			SearchConfigurationResolver searchConfigurationResolver,
			TableManagerSupport tableManagerSupport,
			TableQueryManager tableQueryManager, UserManager userManager,
			EntityManager entityManager,
			SynonymSetDao synonymSetDao, ColumnAnalyzerOverrideDao columnAnalyzerOverrideDao,
			TextAnalyzerDao textAnalyzerDao) {
		this.connectionFactory = connectionFactory;
		this.openSearchManager = openSearchManager;
		this.searchConfigurationResolver = searchConfigurationResolver;
		this.tableManagerSupport = tableManagerSupport;
		this.tableQueryManager = tableQueryManager;
		this.userManager = userManager;
		this.entityManager = entityManager;
		this.synonymSetDao = synonymSetDao;
		this.columnAnalyzerOverrideDao = columnAnalyzerOverrideDao;
		this.textAnalyzerDao = textAnalyzerDao;
	}

	@Override
	public void handleCreate(ProgressCallback progressCallback, String entityId, Long userId)
			throws RecoverableMessageException {
		buildIndex(progressCallback, entityId, userId, true);
	}

	@Override
	public void handleUpdate(ProgressCallback progressCallback, String entityId, Long userId)
			throws RecoverableMessageException {
		Long searchIndexId = KeyFactory.stringToKey(entityId);
		SearchIndexStatusDao statusDao = connectionFactory.getSearchIndexStatusDao();
		// Only build if the index does not already exist
		if (statusDao.exists(searchIndexId)) {
			return;
		}
		buildIndex(progressCallback, entityId, userId, false);
	}

	private void buildIndex(ProgressCallback progressCallback, String entityId, Long userId,
			boolean deleteExistingFirst) throws RecoverableMessageException {
		ValidateArgument.required(entityId, "entityId");
		ValidateArgument.required(userId, "userId");
		Long searchIndexId = KeyFactory.stringToKey(entityId);
		SearchIndexStatusDao statusDao = connectionFactory.getSearchIndexStatusDao();
		try {
			UserInfo user = userManager.getUserInfo(userId);
			SearchIndex searchIndex = entityManager.getEntity(user, entityId, SearchIndex.class);

			String definingSQL = searchIndex.getDefiningSQL();

			checkSourceTablesReady(definingSQL);

			statusDao.createOrUpdate(searchIndexId, SearchIndexState.CREATING, null, null);

			Optional<SearchConfiguration> configOpt = searchConfigurationResolver.resolve(
					user, searchIndex.getSearchConfigurationId(), searchIndex.getParentId());
			SearchConfiguration config = configOpt.orElse(null);

			List<ColumnAnalyzerOverride> overrides = loadColumnAnalyzerOverrides(config);
			List<SynonymSet> synonymSets = loadSynonymSets(config);

			// Query data as an unprivileged authenticated user to enforce
			// row-level ACL filtering. Uses the change message user's identity
			// but restricts groups to only PUBLIC_GROUP and AUTHENTICATED_USERS_GROUP,
			// stripping any user-specific team memberships or ACL grants.
			// This ensures only data visible to all authenticated users gets indexed,
			// while still allowing table DOWNLOAD access (which anonymous lacks).
			UserInfo authenticatedUser = new UserInfo(false);
			authenticatedUser.setId(userId);
			Set<Long> restrictedGroups = new HashSet<>();
			restrictedGroups.add(userId);
			restrictedGroups.add(AuthorizationConstants.BOOTSTRAP_PRINCIPAL.PUBLIC_GROUP.getPrincipalId());
			restrictedGroups.add(AuthorizationConstants.BOOTSTRAP_PRINCIPAL.AUTHENTICATED_USERS_GROUP.getPrincipalId());
			authenticatedUser.setGroups(restrictedGroups);
			Query query = new Query();
			query.setSql(definingSQL);

			QueryOptions countOnly = new QueryOptions()
					.withRunQuery(false)
					.withRunCount(true)
					.withReturnSelectColumns(false)
					.withReturnFacets(false);
			QueryResultBundle countResult = tableQueryManager.querySinglePage(
					progressCallback, authenticatedUser, query, countOnly);
			Long rowCount = countResult.getQueryCount();
			if (rowCount != null && rowCount > MAX_ROWS) {
				throw new IllegalStateException(
						"Search index would exceed maximum of " + MAX_ROWS
								+ " rows. Row count: " + rowCount);
			}

			final String[] appliedConfigJson = {null};
			tableQueryManager.runQueryAsStream(progressCallback, authenticatedUser, query,
					(QueryTranslations translations) -> {
						List<ColumnModel> selectedColumns = translations.getMainQuery()
								.getTranslator().getSchemaOfSelect();
						List<SelectColumn> selectColumns = translations.getMainQuery()
								.getTranslator().getSelectColumns();

						for (int i = 0; i < selectedColumns.size() && i < selectColumns.size(); i++) {
							if (selectedColumns.get(i).getId() == null && selectColumns.get(i).getId() != null) {
								selectedColumns.get(i).setId(selectColumns.get(i).getId());
							}
						}

						Map<String, TextAnalyzer> analyzers = collectAndLoadAnalyzers(
								config, overrides, selectedColumns);

						String indexName = getIndexName(entityId);
						String defaultAnalyzer = config != null ? config.getDefaultAnalyzer() : null;
						if (deleteExistingFirst) {
							openSearchManager.deleteIndex(indexName);
						}
						appliedConfigJson[0] = openSearchManager.createIndex(indexName,
								selectedColumns, defaultAnalyzer,
								synonymSets, overrides, analyzers).orElse(null);
						return new SearchIndexRowHandler(indexName, selectColumns,
								openSearchManager);
					}, ACCESS_TYPE.READ);

			statusDao.createOrUpdate(searchIndexId, SearchIndexState.ACTIVE, null, appliedConfigJson[0]);
		} catch (RecoverableMessageException e) {
			throw e;
		} catch (Exception e) {
			LOG.error("Failed to build search index for entity: " + entityId, e);
			String errorMessage = e.getMessage();
			if (errorMessage != null && errorMessage.length() > MAX_ERROR_MESSAGE_LENGTH) {
				errorMessage = errorMessage.substring(0, MAX_ERROR_MESSAGE_LENGTH);
			}
			// Set FAILED first — status table is the source of truth
			statusDao.createOrUpdate(searchIndexId, SearchIndexState.FAILED, errorMessage, null);
			// Best-effort cleanup of partial AOSS index
			try {
				openSearchManager.deleteIndex(getIndexName(entityId));
			} catch (Exception deleteEx) {
				LOG.error("Failed to clean up partial index for entity: " + entityId, deleteEx);
			}
		}
	}

	@Override
	public void handleDelete(String entityId) throws RecoverableMessageException {
		ValidateArgument.required(entityId, "entityId");
		Long searchIndexId = KeyFactory.stringToKey(entityId);
		SearchIndexStatusDao statusDao = connectionFactory.getSearchIndexStatusDao();

		Optional<SearchIndexState> stateOpt = statusDao.getState(searchIndexId);
		if (stateOpt.isEmpty()) {
			// No status row — already cleaned up, nothing to do
			return;
		}
		SearchIndexState currentState = stateOpt.get();
		if (currentState == SearchIndexState.CREATING) {
			throw new RecoverableMessageException(
					"Search index " + entityId + " is still building. Will retry delete later.");
		}
		// ACTIVE, FAILED, or DELETING (retry) — proceed with delete.
		// DELETING is retried rather than no-oped to avoid zombie rows from transient failures.
		try {
			statusDao.createOrUpdate(searchIndexId, SearchIndexState.DELETING, null, null);
			openSearchManager.deleteIndex(getIndexName(entityId));
			statusDao.delete(searchIndexId);
		} catch (Exception e) {
			LOG.error("Failed to delete search index for entity: " + entityId, e);
		}
	}

	void checkSourceTablesReady(String definingSQL) throws RecoverableMessageException {
		List<IdAndVersion> sourceTableIds = TableModelUtils.getSourceTableIds(definingSQL);
		if (sourceTableIds.isEmpty()) {
			throw new IllegalArgumentException("No source tables found in defining SQL: " + definingSQL);
		}
		boolean hasProcessing = false;
		for (IdAndVersion sourceTableId : sourceTableIds) {
			TableState state = tableManagerSupport.getTableStatusOrCreateIfNotExists(sourceTableId).getState();
			switch (state) {
				case PROCESSING_FAILED:
					throw new IllegalStateException(
							"Cannot build search index: source entity " + sourceTableId + " is in PROCESSING_FAILED state.");
				case PROCESSING:
					hasProcessing = true;
					break;
				case AVAILABLE:
					break;
				default:
					throw new IllegalStateException("Unknown table state: " + state);
			}
		}
		if (hasProcessing) {
			throw new RecoverableMessageException(
					"One or more source tables are still processing. Deferring search index build.");
		}
	}

	Map<String, TextAnalyzer> collectAndLoadAnalyzers(SearchConfiguration config,
			List<ColumnAnalyzerOverride> overrides, List<ColumnModel> columns) {
		Set<String> qualifiedNames = new HashSet<>();

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

	private List<SynonymSet> loadSynonymSets(SearchConfiguration config) {
		if (config == null || config.getSynonymSets() == null || config.getSynonymSets().isEmpty()) {
			return Collections.emptyList();
		}
		return new ArrayList<>(synonymSetDao.getByQualifiedNames(config.getSynonymSets()).values());
	}

	private List<ColumnAnalyzerOverride> loadColumnAnalyzerOverrides(SearchConfiguration config) {
		if (config == null || config.getColumnAnalyzerOverrides() == null
				|| config.getColumnAnalyzerOverrides().isEmpty()) {
			return Collections.emptyList();
		}
		return new ArrayList<>(columnAnalyzerOverrideDao.getByQualifiedNames(
				config.getColumnAnalyzerOverrides()).values());
	}

	private String getIndexName(String entityId) {
		return INDEX_PREFIX + entityId;
	}

	private static class SearchIndexRowHandler implements RowHandler {

		private final String indexName;
		private final List<SelectColumn> columns;
		private final OpenSearchManager client;
		private final List<BulkOperation> batch = new ArrayList<>();
		private long totalRows = 0;

		SearchIndexRowHandler(String indexName, List<SelectColumn> columns, OpenSearchManager client) {
			this.indexName = indexName;
			this.columns = columns;
			this.client = client;
		}

		@Override
		public void nextRow(Row row) {
			if (totalRows >= MAX_ROWS) {
				throw new IllegalStateException(
						"Search index exceeds maximum of " + MAX_ROWS + " rows.");
			}
			Map<String, Object> doc = new HashMap<>();
			doc.put("_row_id", row.getRowId());
			doc.put("_row_version", row.getVersionNumber());
			List<String> values = row.getValues();
			for (int i = 0; i < columns.size() && i < values.size(); i++) {
				String value = values.get(i);
				if (value != null) {
					doc.put(columns.get(i).getId(), value);
				}
			}
			String docId = String.valueOf(row.getRowId());
			batch.add(BulkOperation.of(op -> op
					.index(idx -> idx
							.index(indexName)
							.id(docId)
							.document(doc))));
			totalRows++;
			if (batch.size() >= BATCH_SIZE) {
				flush();
			}
		}

		private void flush() {
			if (!batch.isEmpty()) {
				client.bulkIndex(indexName, new ArrayList<>(batch));
				batch.clear();
			}
		}

		@Override
		public void close() throws IOException {
			flush();
		}
	}
}
