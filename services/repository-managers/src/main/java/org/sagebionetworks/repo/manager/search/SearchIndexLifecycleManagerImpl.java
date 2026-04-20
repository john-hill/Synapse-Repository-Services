package org.sagebionetworks.repo.manager.search;

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
import org.sagebionetworks.table.cluster.ConnectionFactory;
import org.sagebionetworks.table.cluster.search.SearchIndexStatusDao;
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

	static final Pattern FROM_TABLE_PATTERN = Pattern.compile(
			"FROM\\s+(syn\\d+(?:\\.\\d+)?)", Pattern.CASE_INSENSITIVE);

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

			checkSourceTableReady(definingSQL);

			statusDao.createOrUpdate(searchIndexId, SearchIndexState.CREATING, null, null);

			Optional<SearchConfiguration> configOpt = searchConfigurationResolver.resolve(
					user, searchIndex.getSearchConfigurationId(), searchIndex.getParentId());
			SearchConfiguration config = configOpt.orElse(null);

			final List<ColumnAnalyzerOverride> overrides;
			if (config != null && config.getColumnAnalyzerOverrides() != null
					&& !config.getColumnAnalyzerOverrides().isEmpty()) {
				overrides = new ArrayList<>(columnAnalyzerOverrideDao.getByQualifiedNames(
						config.getColumnAnalyzerOverrides()).values());
			} else {
				overrides = Collections.emptyList();
			}
			final List<SynonymSet> synonymSets;
			if (config != null && config.getSynonymSets() != null
					&& !config.getSynonymSets().isEmpty()) {
				synonymSets = new ArrayList<>(synonymSetDao.getByQualifiedNames(
						config.getSynonymSets()).values());
			} else {
				synonymSets = Collections.emptyList();
			}

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
			if (rowCount != null && rowCount > MAX_ROWS) {
				throw new IllegalStateException(
						"Search index would exceed maximum of " + MAX_ROWS
								+ " rows. Row count: " + rowCount);
			}

			final String[] appliedConfigJson = {null};
			tableQueryManager.runQueryAsStream(progressCallback, anonymousUser, query,
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

						String indexName = getIndexName(entityId);
						if (deleteExistingFirst) {
							openSearchManager.deleteIndex(indexName);
						}
						SearchIndexContextProvider context = new SearchIndexContextProviderImpl(
								config, selectedColumns, overrides, synonymSets, textAnalyzerDao);
						appliedConfigJson[0] = openSearchManager.createIndex(indexName, context);
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
