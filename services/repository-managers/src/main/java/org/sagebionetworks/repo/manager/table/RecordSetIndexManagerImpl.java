package org.sagebionetworks.repo.manager.table;

import java.io.IOException;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.stream.Collectors;

import org.apache.logging.log4j.Logger;
import org.sagebionetworks.LoggerProvider;
import org.sagebionetworks.repo.manager.UserManager;
import org.sagebionetworks.repo.manager.EntityManager;
import org.sagebionetworks.repo.manager.file.CsvFileHandleProvider;
import org.sagebionetworks.repo.manager.file.FileHandleManager;
import org.sagebionetworks.repo.model.AuthorizationConstants.BOOTSTRAP_PRINCIPAL;
import org.sagebionetworks.repo.model.NodeDAO;
import org.sagebionetworks.repo.model.UserInfo;
import org.sagebionetworks.repo.model.dbo.dao.table.CSVToRowIterator;
import org.sagebionetworks.repo.model.entity.IdAndVersion;
import org.sagebionetworks.repo.model.file.FileHandle;
import org.sagebionetworks.repo.model.semaphore.LockContext;
import org.sagebionetworks.repo.model.semaphore.LockContext.ContextType;
import org.sagebionetworks.repo.model.table.ColumnModel;
import org.sagebionetworks.repo.model.table.CsvTableDescriptor;
import org.sagebionetworks.repo.model.RecordSet;
import org.sagebionetworks.repo.model.table.SparseRowDto;
import org.sagebionetworks.repo.model.table.UploadToTablePreviewRequest;
import org.sagebionetworks.table.cluster.description.IndexDescription;
import org.sagebionetworks.table.cluster.description.RecordSetIndexDescription;
import org.sagebionetworks.table.cluster.utils.TableModelUtils;
import org.sagebionetworks.table.model.SparseChangeSet;
import org.sagebionetworks.util.ValidateArgument;
import org.sagebionetworks.util.progress.ProgressCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import au.com.bytecode.opencsv.CSVReader;

@Service
public class RecordSetIndexManagerImpl implements RecordSetIndexManager {

	private static final String DEFAULT_ETAG = "DEFAULT";
	// Match the change-set sizing used by TableEntityManagerImpl (configurable
	// there, but a sensible fixed default is fine for the worker path).
	private static final int MAX_BYTES_PER_BATCH = 1024 * 1024 * 2;

	private final TableManagerSupport tableManagerSupport;
	private final TableIndexConnectionFactory connectionFactory;
	private final ColumnModelManager columnModelManager;
	private final EntityManager entityManager;
	private final UserManager userManager;
	private final FileHandleManager fileHandleManager;
	private final CsvFileHandleProvider csvFileHandleProvider;
	private final NodeDAO nodeDao;
	private final Logger log;

	@Autowired
	public RecordSetIndexManagerImpl(TableManagerSupport tableManagerSupport,
			TableIndexConnectionFactory connectionFactory, ColumnModelManager columnModelManager,
			EntityManager entityManager, UserManager userManager, FileHandleManager fileHandleManager,
			CsvFileHandleProvider csvFileHandleProvider, NodeDAO nodeDao, LoggerProvider loggerProvider) {
		this.tableManagerSupport = tableManagerSupport;
		this.connectionFactory = connectionFactory;
		this.columnModelManager = columnModelManager;
		this.entityManager = entityManager;
		this.userManager = userManager;
		this.fileHandleManager = fileHandleManager;
		this.csvFileHandleProvider = csvFileHandleProvider;
		this.nodeDao = nodeDao;
		this.log = loggerProvider.getLogger(RecordSetIndexManagerImpl.class.getName());
	}

	@Override
	public void createOrUpdateRecordSetIndex(IdAndVersion idAndVersion, ProgressCallback progressCallback)
			throws Exception {
		ValidateArgument.required(idAndVersion, "idAndVersion");
		ValidateArgument.required(progressCallback, "progressCallback");
		// Entity-level state (TableStatus, exclusive lock) is keyed at the
		// unversioned IdAndVersion: an unversioned query "select * from syn123"
		// checks status at (id, null), so we keep it there. The per-version
		// immutable index table T{id}_{v} is identified by the IndexDescription
		// returned from the factory, which resolves the current version for us.
		IdAndVersion entityKey = IdAndVersion.newBuilder().setId(idAndVersion.getId()).build();
		tableManagerSupport.tryRunWithTableExclusiveLock(progressCallback,
				new LockContext(ContextType.BuildTableIndex, entityKey), entityKey,
				(ProgressCallback inner) -> {
					createOrUpdateHoldingLock(entityKey);
					return null;
				});
	}

	void createOrUpdateHoldingLock(IdAndVersion entityKey) {
		final String token;
		try {
			if (!tableManagerSupport.isIndexWorkRequired(entityKey)) {
				return;
			}
			token = tableManagerSupport.startTableProcessing(entityKey);
		} catch (Exception e) {
			// Could not even reach the status table — let the worker retry.
			throw new RuntimeException(e);
		}
		try {
			long currentRevision = nodeDao.getCurrentRevisionNumber(entityKey.getId().toString());
			IdAndVersion versionedKey = IdAndVersion.newBuilder()
					.setId(entityKey.getId())
					.setVersion(currentRevision)
					.build();
			// Entity-level index (T{id}) — the alias target for "syn123" queries.
			IndexDescription entityDescription = new RecordSetIndexDescription(entityKey, currentRevision);
			// Per-version immutable snapshot (T{id}_{v}) — the target for "syn123.{v}" queries.
			IndexDescription versionedDescription = new RecordSetIndexDescription(versionedKey, currentRevision);

			RecordSet recordSet = entityManager.getEntityForVersion(getAdminUser(),
					entityKey.getId().toString(), currentRevision, RecordSet.class);
			FileHandle dataFileHandle = fileHandleManager.getRawFileHandleUnchecked(recordSet.getDataFileHandleId());
			// RecordSet.csvDescriptor is optional; the grid create flow defaults to an
			// isFirstLineHeader=true descriptor (RecordSetCreateGridHandler.createGrid),
			// and CsvFileHandleProvider.getCsvReader requires a non-null descriptor.
			CsvTableDescriptor csvDescriptor = recordSet.getCsvDescriptor() != null
					? recordSet.getCsvDescriptor()
					: new CsvTableDescriptor().setIsFirstLineHeader(true);
			List<ColumnModel> schema = inferSchema(dataFileHandle, csvDescriptor);
			if (schema.isEmpty()) {
				throw new IllegalArgumentException("RecordSet CSV contains no columns to index.");
			}
			List<ColumnModel> persistedColumns = columnModelManager
					.createColumnModels(getAdminUser(), schema);
			List<String> columnIds = persistedColumns.stream().map(ColumnModel::getId).collect(Collectors.toList());
			// Default binding serves "syn123" + the current revision's "syn123.{currentRev}".
			// Versioned binding preserves the schema for snapshots after they're superseded.
			columnModelManager.bindColumnsToDefaultVersionOfObject(columnIds, entityKey.getId().toString());
			columnModelManager.bindColumnsToVersionOfObject(columnIds, versionedKey);

			TableIndexManager indexManager = connectionFactory.connectToTableIndex(entityKey);

			// (1) Entity-level T{id} — overwritten with the latest CSV each version.
			indexManager.resetTableIndex(entityDescription, persistedColumns, false);
			long rowCount = loadRows(indexManager, entityDescription, persistedColumns, dataFileHandle, csvDescriptor,
					currentRevision);
			indexManager.buildTableIndexIndices(entityDescription, persistedColumns);
			indexManager.setIndexVersion(entityKey, currentRevision);

			// (2) Per-version snapshot T{id}_{v} — built once per version, immutable thereafter.
			indexManager.resetTableIndex(versionedDescription, persistedColumns, false);
			loadRows(indexManager, versionedDescription, persistedColumns, dataFileHandle, csvDescriptor,
					currentRevision);
			indexManager.buildTableIndexIndices(versionedDescription, persistedColumns);
			indexManager.setIndexVersion(versionedKey, currentRevision);

			// Per-version TableStatus so "syn123.{v}" queries find AVAILABLE without re-triggering.
			String versionedToken = tableManagerSupport.startTableProcessing(versionedKey);
			tableManagerSupport.attemptToSetTableStatusToAvailable(versionedKey, versionedToken, DEFAULT_ETAG);
			// Entity-level TableStatus last — what unversioned queries read.
			tableManagerSupport.attemptToSetTableStatusToAvailable(entityKey, token, DEFAULT_ETAG);
			log.info("Built RecordSet index {} (rev {}) with {} rows", entityKey, currentRevision, rowCount);
		} catch (Exception e) {
			log.error("Failed to build RecordSet index for " + entityKey, e);
			tableManagerSupport.attemptToSetTableStatusToFailed(entityKey, e);
			throw e instanceof RuntimeException ? (RuntimeException) e : new RuntimeException(e);
		}
	}

	UserInfo getAdminUser() {
		return userManager.getUserInfo(BOOTSTRAP_PRINCIPAL.THE_ADMIN_USER.getPrincipalId());
	}

	List<ColumnModel> inferSchema(FileHandle dataFileHandle, CsvTableDescriptor csvDescriptor) throws IOException {
		try (CSVReader csvReader = csvFileHandleProvider.getCsvReader(dataFileHandle, csvDescriptor)) {
			UploadToTablePreviewRequest request = new UploadToTablePreviewRequest()
					.setCsvTableDescriptor(csvDescriptor)
					.setDoFullFileScan(true);
			List<ColumnModel> suggested = new UploadPreviewBuilder(csvReader, request).buildResult()
					.getSuggestedColumns();
			return suggested == null ? Collections.emptyList() : suggested;
		}
	}

	long loadRows(TableIndexManager indexManager, IndexDescription indexDescription, List<ColumnModel> schema,
			FileHandle dataFileHandle, CsvTableDescriptor csvDescriptor, long changeSetVersion) throws IOException {
		long rowCount = 0;
		// CSV rows have no inherent rowId. We're applying directly to the index (no truth
		// layer), so SQLUtils.bindParametersForCreateOrUpdate requires us to assign rowId
		// + versionNumber ourselves. Each fresh per-version index starts at rowId 1.
		long nextRowId = 1L;
		try (CSVReader csvReader = csvFileHandleProvider.getCsvReader(dataFileHandle, csvDescriptor)) {
			boolean isFirstLineHeader = isFirstLineHeader(csvDescriptor);
			CSVToRowIterator iterator = new CSVToRowIterator(schema, csvReader, isFirstLineHeader, null);
			List<SparseRowDto> batch = new LinkedList<>();
			int batchBytes = 0;
			while (iterator.hasNext()) {
				SparseRowDto row = iterator.next();
				row.setRowId(nextRowId++);
				row.setVersionNumber(changeSetVersion);
				batch.add(row);
				rowCount++;
				batchBytes += TableModelUtils.calculateActualRowSize(row);
				if (batchBytes >= MAX_BYTES_PER_BATCH) {
					applyBatch(indexManager, indexDescription, schema, batch, changeSetVersion);
					batch.clear();
					batchBytes = 0;
				}
			}
			if (!batch.isEmpty()) {
				applyBatch(indexManager, indexDescription, schema, batch, changeSetVersion);
			}
		}
		return rowCount;
	}

	private void applyBatch(TableIndexManager indexManager, IndexDescription indexDescription,
			List<ColumnModel> schema, List<SparseRowDto> batch, long changeSetVersion) {
		SparseChangeSet delta = new SparseChangeSet(indexDescription.getIdAndVersion().getId().toString(), schema,
				batch, null);
		indexManager.applyChangeSetToIndex(indexDescription.getIdAndVersion(), delta, changeSetVersion);
	}

	private static boolean isFirstLineHeader(CsvTableDescriptor csvDescriptor) {
		if (csvDescriptor == null || csvDescriptor.getIsFirstLineHeader() == null) {
			return true;
		}
		return csvDescriptor.getIsFirstLineHeader();
	}

	@Override
	public void deleteRecordSetIndex(IdAndVersion idAndVersion) {
		ValidateArgument.required(idAndVersion, "idAndVersion");
		// Drop the entity-level stub (T{id} + T{id}_STATUS) and unbind all columns. Per-version
		// snapshots T{id}_{v} are left as orphans — they're unreachable now that the entity is
		// gone (matches how TableEntity treats versioned snapshot index tables after delete).
		IdAndVersion entityKey = IdAndVersion.newBuilder().setId(idAndVersion.getId()).build();
		try {
			TableIndexManager indexManager = connectionFactory.connectToTableIndex(entityKey);
			indexManager.deleteTableIndex(entityKey);
		} catch (TableIndexConnectionUnavailableException e) {
			log.warn("Index unavailable while deleting RecordSet index for " + idAndVersion, e);
		}
		columnModelManager.unbindAllColumnsAndOwnerFromObject(idAndVersion.getId().toString());
	}

}
