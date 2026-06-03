package org.sagebionetworks.repo.service.metadata;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import org.sagebionetworks.repo.manager.file.FileHandleManager;
import org.sagebionetworks.repo.manager.grid.create.RecordSetCreateGridHandler;
import org.sagebionetworks.repo.manager.table.ColumnModelManager;
import org.sagebionetworks.repo.manager.table.RecordSetSchemaResolver;
import org.sagebionetworks.repo.manager.table.TableManagerSupport;
import org.sagebionetworks.repo.model.DatastoreException;
import org.sagebionetworks.repo.model.InvalidModelException;
import org.sagebionetworks.repo.model.NodeDAO;
import org.sagebionetworks.repo.model.ObjectType;
import org.sagebionetworks.repo.model.RecordSet;
import org.sagebionetworks.repo.model.UnauthorizedException;
import org.sagebionetworks.repo.model.UserInfo;
import org.sagebionetworks.repo.model.dbo.schema.EntitySchemaValidationResultDao;
import org.sagebionetworks.repo.model.entity.IdAndVersion;
import org.sagebionetworks.repo.model.file.FileHandle;
import org.sagebionetworks.repo.model.jdo.KeyFactory;
import org.sagebionetworks.repo.model.message.ChangeType;
import org.sagebionetworks.repo.model.message.MessageToSend;
import org.sagebionetworks.repo.model.message.TransactionalMessenger;
import org.sagebionetworks.repo.model.table.ColumnModel;
import org.sagebionetworks.repo.model.table.CsvTableDescriptor;
import org.sagebionetworks.repo.web.NotFoundException;
import org.sagebionetworks.util.ValidateArgument;
import org.springframework.stereotype.Service;

@Service
public class RecordSetMetadataProvider implements EntityValidator<RecordSet>, TypeSpecificEntitySanitizer<RecordSet>, TypeSpecificCreateProvider<RecordSet>, TypeSpecificUpdateProvider<RecordSet>, TypeSpecificMetadataProvider<RecordSet>, TypeSpecificDeleteProvider<RecordSet> {

	private FileEntityMetadataProvider fileEntityMetadataProvider;
	private EntitySchemaValidationResultDao validationResultDao;
	private TableManagerSupport tableManagerSupport;
	private TransactionalMessenger transactionalMessenger;
	private RecordSetSchemaResolver schemaResolver;
	private ColumnModelManager columnModelManager;
	private FileHandleManager fileHandleManager;
	private NodeDAO nodeDao;

	public RecordSetMetadataProvider(FileEntityMetadataProvider fileEntityMetadataProvider,
			EntitySchemaValidationResultDao validationResultDao, TableManagerSupport tableManagerSupport,
			TransactionalMessenger transactionalMessenger, RecordSetSchemaResolver schemaResolver,
			ColumnModelManager columnModelManager, FileHandleManager fileHandleManager, NodeDAO nodeDao) {
		this.fileEntityMetadataProvider = fileEntityMetadataProvider;
		this.validationResultDao = validationResultDao;
		this.tableManagerSupport = tableManagerSupport;
		this.transactionalMessenger = transactionalMessenger;
		this.schemaResolver = schemaResolver;
		this.columnModelManager = columnModelManager;
		this.fileHandleManager = fileHandleManager;
		this.nodeDao = nodeDao;
	}

	@Override
	public void validateEntity(RecordSet entity, EntityEvent event) throws InvalidModelException, NotFoundException, DatastoreException, UnauthorizedException {

		if (EventType.CREATE == event.getType() || EventType.UPDATE == event.getType()) {
			ValidateArgument.requiredNotEmpty(entity.getUpsertKey(), "The upsertKey");

			if (entity.getCsvDescriptor() != null) {
				ValidateArgument.requirement(Boolean.TRUE.equals(entity.getCsvDescriptor().getIsFirstLineHeader()), "The csvDescriptor.isFirstLineHeader must be true.");
			}
		}

		fileEntityMetadataProvider.validateEntity(entity, event);
	}

	@Override
	public void sanitizeEntity(RecordSet entity, EntityEvent event) {
		if (EventType.CREATE == event.getType() || EventType.UPDATE == event.getType()) {
			// The validation summary and file are server-controlled and only set by the
			// grid session export job. A client must not be able to set them directly, so
			// they are stripped here. Trusted internal callers (the exporter) skip
			// sanitization via EntityService.updateEntity(..., skipSanitization=true).
			entity.setValidationSummary(null);
			entity.setValidationFileHandleId(null);
		}
	}

	@Override
	public void entityUpdated(UserInfo userInfo, RecordSet entity, boolean wasNewVersionCreated) {
		fileEntityMetadataProvider.entityUpdated(userInfo, entity, wasNewVersionCreated);
		bindSchemaToRecordSet(userInfo, entity);
		triggerIndexRebuild(entity);
	}

	@Override
	public void entityCreated(UserInfo userInfo, RecordSet entity) {
		fileEntityMetadataProvider.entityCreated(userInfo, entity);
		bindSchemaToRecordSet(userInfo, entity);
		triggerIndexRebuild(entity);
	}

	@Override
	public void entityDeleted(String deletedId) {
		// Drops the entity-level index (T{id} + T{id}_STATUS) and unbinds all
		// columns. Per-version snapshot tables T{id}_{v} are intentionally
		// left as orphans — they're unreachable now that the entity is gone,
		// matching how TableEntity treats versioned snapshot index tables.
		transactionalMessenger.sendMessageAfterCommit(new MessageToSend()
				.withObjectId(deletedId)
				.withObjectType(ObjectType.RECORDSET)
				.withChangeType(ChangeType.DELETE));
	}

	/**
	 * Infers the column schema from the RecordSet's CSV (reconciled with any bound
	 * JSON Schema) and binds it both to this revision's immutable snapshot
	 * (T{id}_{v}) and to the entity-level default that unversioned queries read
	 * (T{id}).
	 */
	private void bindSchemaToRecordSet(UserInfo userInfo, RecordSet entity) {
		FileHandle dataFileHandle = fileHandleManager.getRawFileHandleUnchecked(entity.getDataFileHandleId());
		// RecordSet.csvDescriptor is optional, so fall back to the same default as the grid create flow
		CsvTableDescriptor csvDescriptor = Optional.ofNullable(entity.getCsvDescriptor())
				.orElse(RecordSetCreateGridHandler.DEFAULT_RECORD_SET_CSV_DESCRIPTOR);

		// At the risk of inaccurate columns, we avoid a full CSV scan since this occurs within the entity update transaction
		// A bound JSON Schema can be used to 'reconcile' the column models and ensure the schema is correct
		boolean doFullCsvScan = false;
		List<ColumnModel> schema = schemaResolver.getReconciledSchema(entity.getId(), dataFileHandle, csvDescriptor, doFullCsvScan).getSchema();
		if (schema.isEmpty()) {
			throw new IllegalArgumentException("Cannot determine the schema from the CSV file, at least one column header must be present.");
		}

		List<ColumnModel> persistedColumns = columnModelManager.createColumnModels(userInfo, schema);
		List<String> columnIds = persistedColumns.stream().map(ColumnModel::getId).collect(Collectors.toList());

		Long id = KeyFactory.stringToKey(entity.getId());
		Long versionNumber = nodeDao.getCurrentRevisionNumber(KeyFactory.keyToString(id));
		IdAndVersion versionedKey = IdAndVersion.newBuilder().setId(id).setVersion(versionNumber).build();
		// Versioned binding preserves the schema for this specific snapshot.
		columnModelManager.bindColumnsToVersionOfObject(columnIds, versionedKey);
		// Default binding serves queries against "syn123" (no version). The provider
		// always fires for the current revision, so this is always correct here.
		columnModelManager.bindColumnsToDefaultVersionOfObject(columnIds, entity.getId());
	}

	private void triggerIndexRebuild(RecordSet entity) {
		// We fire two triggers per create/update:
		//   1. A versioned trigger so this specific revision's immutable
		//      snapshot T{id}_{v} gets built
		//   2. A versionless trigger so the entity-level alias T{id} (the
		//      target for "select * from syn123") gets rebuilt and the
		//      unversioned TableStatus flips to PROCESSING
		// The worker dedupes via isIndexWorkRequired, so a successful build
		// for one message short-circuits the other.
		Long id = KeyFactory.stringToKey(entity.getId());
		IdAndVersion versionedKey = IdAndVersion.newBuilder().setId(id).setVersion(entity.getVersionNumber()).build();
		IdAndVersion entityKey = IdAndVersion.newBuilder().setId(id).build();
		tableManagerSupport.setTableToProcessingAndTriggerUpdate(versionedKey);
		tableManagerSupport.setTableToProcessingAndTriggerUpdate(entityKey);
	}

	@Override
	public void addTypeSpecificMetadata(RecordSet entity, UserInfo user, EventType eventType) throws DatastoreException, NotFoundException, UnauthorizedException {
		Long recordSetId = KeyFactory.stringToKey(entity.getId());
		Long recordSetVersion = entity.getVersionNumber();

		validationResultDao.getRecordSetValidationSummaryStatistics(recordSetId, recordSetVersion).ifPresent( result ->
			entity.setValidationSummary(result)
		);
	}


}
