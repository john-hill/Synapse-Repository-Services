package org.sagebionetworks.search.workers;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.sagebionetworks.asynchronous.workers.changes.BatchChangeMessageDrivenRunner;
import org.sagebionetworks.repo.manager.EntityManager;
import org.sagebionetworks.repo.manager.UserManager;
import org.sagebionetworks.repo.manager.search.ColumnTypeToOpenSearchMapping;
import org.sagebionetworks.repo.manager.search.OpenSearchManager;
import org.sagebionetworks.repo.manager.search.SearchConfigurationResolver;
import org.sagebionetworks.repo.manager.table.TableManagerSupport;
import org.sagebionetworks.repo.manager.table.TableQueryManager;
import org.sagebionetworks.repo.manager.table.query.QueryTranslations;
import org.sagebionetworks.repo.model.ACCESS_TYPE;
import org.sagebionetworks.repo.model.jdo.KeyFactory;
import org.sagebionetworks.repo.model.AuthorizationConstants;
import org.sagebionetworks.repo.model.EntityType;
import org.sagebionetworks.repo.model.NodeDAO;
import org.sagebionetworks.repo.model.ObjectType;
import org.sagebionetworks.repo.model.UserInfo;
import org.sagebionetworks.repo.model.dao.table.RowHandler;
import org.sagebionetworks.repo.model.dbo.search.ColumnAnalyzerOverrideDao;
import org.sagebionetworks.repo.model.dbo.search.SynonymSetDao;
import org.sagebionetworks.repo.model.dbo.search.TextAnalyzerDao;
import org.sagebionetworks.repo.model.entity.IdAndVersion;
import org.sagebionetworks.repo.model.message.ChangeMessage;
import org.sagebionetworks.repo.model.table.ColumnModel;
import org.sagebionetworks.repo.model.table.ColumnType;
import org.sagebionetworks.repo.model.table.Query;
import org.sagebionetworks.repo.model.table.QueryOptions;
import org.sagebionetworks.repo.model.table.QueryResultBundle;
import org.sagebionetworks.repo.model.table.Row;
import org.sagebionetworks.repo.model.table.SelectColumn;
import org.sagebionetworks.repo.model.table.TableState;
import org.sagebionetworks.repo.model.search.table.ColumnAnalyzerOverride;
import org.sagebionetworks.repo.model.search.table.ColumnAnalyzerOverrideEntry;
import org.sagebionetworks.repo.model.search.table.SearchConfiguration;
import org.sagebionetworks.repo.model.search.table.SearchIndex;
import org.sagebionetworks.repo.model.search.table.SearchIndexState;
import org.sagebionetworks.repo.model.search.table.SynonymSet;
import org.sagebionetworks.repo.model.search.table.TextAnalyzer;
import org.sagebionetworks.repo.web.NotFoundException;
import org.sagebionetworks.table.cluster.ConnectionFactory;
import org.sagebionetworks.table.cluster.search.SearchIndexStatusDao;
import org.sagebionetworks.util.progress.ProgressCallback;
import org.sagebionetworks.database.semaphore.LockReleaseFailedException;
import org.sagebionetworks.workers.util.aws.message.RecoverableMessageException;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.stereotype.Service;

@Service
public class SearchIndexLifecycleWorker implements BatchChangeMessageDrivenRunner {

	private static final Logger LOG = LogManager.getLogger(SearchIndexLifecycleWorker.class);
	private static final String INDEX_PREFIX = "search-index-";
	private static final int MAX_ERROR_MESSAGE_LENGTH = 3000;

	static final Pattern FROM_TABLE_PATTERN = Pattern.compile(
			"FROM\\s+(syn\\d+(?:\\.\\d+)?)", Pattern.CASE_INSENSITIVE);

	private final NodeDAO nodeDao;
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

	public SearchIndexLifecycleWorker(NodeDAO nodeDao, ConnectionFactory connectionFactory,
			OpenSearchManager openSearchManager,
			SearchConfigurationResolver searchConfigurationResolver,
			TableManagerSupport tableManagerSupport,
			TableQueryManager tableQueryManager, UserManager userManager,
			EntityManager entityManager,
			SynonymSetDao synonymSetDao, ColumnAnalyzerOverrideDao columnAnalyzerOverrideDao,
			TextAnalyzerDao textAnalyzerDao) {
		this.nodeDao = nodeDao;
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
	public void run(ProgressCallback progressCallback, List<ChangeMessage> messages)
			throws RecoverableMessageException, Exception {
		for (ChangeMessage message : messages) {
			if (message.getObjectType() != ObjectType.ENTITY) {
				continue;
			}
			processMessage(progressCallback, message);
		}
	}

	private void processMessage(ProgressCallback progressCallback, ChangeMessage message)
			throws RecoverableMessageException {
		String entityId = message.getObjectId();
		SearchIndexStatusDao statusDao = connectionFactory.getSearchIndexStatusDao();
		try {
			EntityType nodeType = nodeDao.getNodeTypeById(entityId);
			if (nodeType != EntityType.searchindex) {
				return;
			}
			switch (message.getChangeType()) {
				case CREATE:
					handleCreate(progressCallback, entityId, statusDao);
					break;
				case UPDATE:
					handleCreate(progressCallback, entityId, statusDao);
					break;
				case DELETE:
					handleDelete(entityId, statusDao);
					break;
				default:
					break;
			}
		} catch (RecoverableMessageException e) {
			throw e;
		} catch (LockReleaseFailedException | CannotAcquireLockException | DeadlockLoserDataAccessException e) {
			throw new RecoverableMessageException(e);
		} catch (NotFoundException e) {
			handleEntityNotFound(entityId, statusDao);
		} catch (Exception e) {
			LOG.error("Failed to process lifecycle message for entity: " + entityId, e);
		}
	}

	private void handleCreate(ProgressCallback progressCallback, String entityId,
			SearchIndexStatusDao statusDao) throws RecoverableMessageException {
		Long searchIndexId = KeyFactory.stringToKey(entityId);
		LOG.info("Building search index for entity: {}", entityId);
		try {
			UserInfo adminUser = userManager.getUserInfo(
					AuthorizationConstants.BOOTSTRAP_PRINCIPAL.THE_ADMIN_USER.getPrincipalId());
			SearchIndex searchIndex = entityManager.getEntity(adminUser, entityId, SearchIndex.class);

			String definingSQL = searchIndex.getDefiningSQL();

			checkSourceTableReady(definingSQL);

			statusDao.createOrUpdate(searchIndexId, SearchIndexState.CREATING, null, null);

			Optional<SearchConfiguration> configOpt = searchConfigurationResolver.resolve(
					adminUser, searchIndex.getSearchConfigurationId(), searchIndex.getParentId());
			SearchConfiguration config = configOpt.orElse(null);

			List<SynonymSet> synonymSets = new ArrayList<>(loadSynonymSets(config).values());
			List<ColumnAnalyzerOverride> overrides = new ArrayList<>(loadColumnAnalyzerOverrides(config).values());

			UserInfo anonymousUser = userManager.getUserInfo(
					AuthorizationConstants.BOOTSTRAP_PRINCIPAL.ANONYMOUS_USER.getPrincipalId());
			Query query = new Query();
			query.setSql(definingSQL);

			QueryOptions countOnly = new QueryOptions()
					.withRunQuery(false)
					.withRunCount(true)
					.withReturnSelectColumns(false)
					.withReturnFacets(false);
			QueryResultBundle countResult = tableQueryManager.querySinglePage(
					progressCallback, anonymousUser, query, countOnly);
			Long rowCount = countResult.getQueryCount();
			if (rowCount != null && rowCount > SearchIndexRowHandler.MAX_ROWS) {
				throw new IllegalStateException(
						"Search index would exceed maximum of " + SearchIndexRowHandler.MAX_ROWS
								+ " rows. Row count: " + rowCount);
			}

			final String[] appliedConfigJson = {null};
			tableQueryManager.runQueryAsStream(progressCallback, adminUser, query,
					(QueryTranslations translations) -> {
						List<ColumnModel> selectedColumns = translations.getMainQuery()
								.getTranslator().getSchemaOfSelect();
						List<SelectColumn> selectColumns = translations.getMainQuery()
								.getTranslator().getSelectColumns();

						// Copy column IDs from SelectColumn into ColumnModel
						// (getSchemaOfSelect returns null IDs)
						for (int i = 0; i < selectedColumns.size() && i < selectColumns.size(); i++) {
							if (selectedColumns.get(i).getId() == null && selectColumns.get(i).getId() != null) {
								selectedColumns.get(i).setId(selectColumns.get(i).getId());
							}
						}

						// Collect all required analyzer IDs and load them
						Map<String, TextAnalyzer> analyzers = collectAndLoadAnalyzers(
								config, overrides, selectedColumns);

						String indexName = getIndexName(entityId);
						String defaultAnalyzer = config != null ? config.getDefaultAnalyzer() : null;
						// Delete any existing index before creating (no-op if missing).
						// This ensures UPDATE messages get fresh mappings instead of
						// silently keeping stale ones from a prior build.
						openSearchManager.deleteIndex(indexName);
						// createIndex returns the serialized CreateIndexRequest JSON
						appliedConfigJson[0] = openSearchManager.createIndex(indexName,
								selectedColumns, defaultAnalyzer,
								synonymSets, overrides, analyzers);
						return new SearchIndexRowHandler(indexName, selectColumns,
								openSearchManager);
					}, ACCESS_TYPE.READ);

			statusDao.createOrUpdate(searchIndexId, SearchIndexState.ACTIVE, null, appliedConfigJson[0]);
		} catch (RecoverableMessageException e) {
			throw e;
		} catch (Exception e) {
			LOG.error("Failed to build search index for entity: " + entityId, e);
			try {
				openSearchManager.deleteIndex(getIndexName(entityId));
			} catch (Exception deleteEx) {
				LOG.error("Failed to clean up partial index for entity: " + entityId, deleteEx);
			}
			String errorMessage = e.getMessage();
			if (errorMessage != null && errorMessage.length() > MAX_ERROR_MESSAGE_LENGTH) {
				errorMessage = errorMessage.substring(0, MAX_ERROR_MESSAGE_LENGTH);
			}
			statusDao.createOrUpdate(searchIndexId, SearchIndexState.FAILED, errorMessage, null);
		}
	}

	/**
	 * Collect all required analyzers by qualified name from config, overrides, and column type defaults,
	 * then load them from the DAO.
	 */
	Map<String, TextAnalyzer> collectAndLoadAnalyzers(SearchConfiguration config,
			List<ColumnAnalyzerOverride> overrides, List<ColumnModel> columns) {
		Set<String> qualifiedNames = new HashSet<>();

		// SCIENTIFIC is always needed: buildKeywordWithSearchableProperty uses it
		// unconditionally for the .searchable sub-field on keyword/link columns.
		qualifiedNames.add(ColumnTypeToOpenSearchMapping.getDefaultAnalyzerQualifiedName(ColumnType.STRING));

		// From overrides
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

		// From config default
		if (config != null && config.getDefaultAnalyzer() != null) {
			qualifiedNames.add(config.getDefaultAnalyzer());
		}

		// From column type defaults
		for (ColumnModel column : columns) {
			qualifiedNames.add(ColumnTypeToOpenSearchMapping.getDefaultAnalyzerQualifiedName(column.getColumnType()));
		}

		// Single batch load — all analyzers by qualified name
		return new HashMap<>(textAnalyzerDao.getByQualifiedNames(new ArrayList<>(qualifiedNames)));
	}

	void checkSourceTableReady(String definingSQL) throws RecoverableMessageException {
		Matcher matcher = FROM_TABLE_PATTERN.matcher(definingSQL);
		if (!matcher.find()) {
			return;
		}
		String sourceTableRef = matcher.group(1);
		IdAndVersion sourceTableId = IdAndVersion.parse(sourceTableRef);
		Optional<TableState> stateOpt = tableManagerSupport.getTableStatusState(sourceTableId);
		if (stateOpt.isEmpty()) {
			throw new RecoverableMessageException(
					"Source entity " + sourceTableId + " has no status yet. Deferring search index build.");
		}
		switch (stateOpt.get()) {
			case PROCESSING:
				throw new RecoverableMessageException(
						"Source entity " + sourceTableId + " is still processing. Deferring search index build.");
			case PROCESSING_FAILED:
				throw new IllegalStateException(
						"Cannot build search index: source entity " + sourceTableId + " is in PROCESSING_FAILED state.");
			case AVAILABLE:
				break;
			default:
				break;
		}
	}

	private void handleDelete(String entityId, SearchIndexStatusDao statusDao) {
		Long searchIndexId = KeyFactory.stringToKey(entityId);
		try {
			statusDao.createOrUpdate(searchIndexId, SearchIndexState.DELETING, null, null);
			openSearchManager.deleteIndex(getIndexName(entityId));
			statusDao.delete(searchIndexId);
		} catch (Exception e) {
			LOG.error("Failed to delete search index for entity: " + entityId, e);
		}
	}

	private void handleEntityNotFound(String entityId, SearchIndexStatusDao statusDao) {
		LOG.info("SearchIndex entity {} was deleted/trashed, cleaning up AOSS index.", entityId);
		handleDelete(entityId, statusDao);
	}

	private Map<String, SynonymSet> loadSynonymSets(SearchConfiguration config) {
		if (config == null || config.getSynonymSets() == null || config.getSynonymSets().isEmpty()) {
			return Collections.emptyMap();
		}
		return synonymSetDao.getByQualifiedNames(config.getSynonymSets());
	}

	private Map<String, ColumnAnalyzerOverride> loadColumnAnalyzerOverrides(SearchConfiguration config) {
		if (config == null || config.getColumnAnalyzerOverrides() == null
				|| config.getColumnAnalyzerOverrides().isEmpty()) {
			return Collections.emptyMap();
		}
		return columnAnalyzerOverrideDao.getByQualifiedNames(config.getColumnAnalyzerOverrides());
	}

	private String getIndexName(String entityId) {
		return INDEX_PREFIX + entityId;
	}

	private static class SearchIndexRowHandler implements RowHandler {

		private static final int BATCH_SIZE = 1000;
		private static final long MAX_ROWS = 500_000L;

		private final String indexName;
		private final List<SelectColumn> columns;
		private final OpenSearchManager client;
		private final List<Map<String, Object>> batch = new ArrayList<>();
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
			batch.add(doc);
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
