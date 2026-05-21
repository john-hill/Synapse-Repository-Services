package org.sagebionetworks.repo.service.metadata;

import org.sagebionetworks.repo.manager.table.TableManagerSupport;
import org.sagebionetworks.repo.model.DatastoreException;
import org.sagebionetworks.repo.model.InvalidModelException;
import org.sagebionetworks.repo.model.ObjectType;
import org.sagebionetworks.repo.model.RecordSet;
import org.sagebionetworks.repo.model.UnauthorizedException;
import org.sagebionetworks.repo.model.UserInfo;
import org.sagebionetworks.repo.model.dbo.schema.EntitySchemaValidationResultDao;
import org.sagebionetworks.repo.model.entity.IdAndVersion;
import org.sagebionetworks.repo.model.jdo.KeyFactory;
import org.sagebionetworks.repo.model.message.ChangeType;
import org.sagebionetworks.repo.model.message.MessageToSend;
import org.sagebionetworks.repo.model.message.TransactionalMessenger;
import org.sagebionetworks.repo.web.NotFoundException;
import org.sagebionetworks.util.ValidateArgument;
import org.springframework.stereotype.Service;

@Service
public class RecordSetMetadataProvider implements EntityValidator<RecordSet>, TypeSpecificCreateProvider<RecordSet>, TypeSpecificUpdateProvider<RecordSet>, TypeSpecificMetadataProvider<RecordSet>, TypeSpecificDeleteProvider<RecordSet> {

	private FileEntityMetadataProvider fileEntityMetadataProvider;
	private EntitySchemaValidationResultDao validationResultDao;
	private TableManagerSupport tableManagerSupport;
	private TransactionalMessenger transactionalMessenger;

	public RecordSetMetadataProvider(FileEntityMetadataProvider fileEntityMetadataProvider,
			EntitySchemaValidationResultDao validationResultDao, TableManagerSupport tableManagerSupport,
			TransactionalMessenger transactionalMessenger) {
		this.fileEntityMetadataProvider = fileEntityMetadataProvider;
		this.validationResultDao = validationResultDao;
		this.tableManagerSupport = tableManagerSupport;
		this.transactionalMessenger = transactionalMessenger;
	}

	@Override
	public void validateEntity(RecordSet entity, EntityEvent event) throws InvalidModelException, NotFoundException, DatastoreException, UnauthorizedException {

		if (EventType.CREATE == event.getType() || EventType.UPDATE == event.getType()) {
			ValidateArgument.requiredNotEmpty(entity.getUpsertKey(), "The upsertKey");

			if (entity.getCsvDescriptor() != null) {
				ValidateArgument.requirement(Boolean.TRUE.equals(entity.getCsvDescriptor().getIsFirstLineHeader()), "The csvDescriptor.isFirstLineHeader must be true.");
			}

			// The validation summary and file are only set in a grid session export job
			entity.setValidationSummary(null);
			entity.setValidationFileHandleId(null);
		}

		fileEntityMetadataProvider.validateEntity(entity, event);
	}

	@Override
	public void entityUpdated(UserInfo userInfo, RecordSet entity, boolean wasNewVersionCreated) {
		fileEntityMetadataProvider.entityUpdated(userInfo, entity, wasNewVersionCreated);
		triggerIndexRebuild(entity);
	}

	@Override
	public void entityCreated(UserInfo userInfo, RecordSet entity) {
		fileEntityMetadataProvider.entityCreated(userInfo, entity);
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

	private void triggerIndexRebuild(RecordSet entity) {
		// RecordSet versions are immutable per-version indexes (T{id}_{v}), but an
		// unversioned reference ("select * from syn123") aliases to the latest
		// version. To keep that alias working, we track entity-level TableStatus
		// at the unversioned key — the worker is triggered with no version, then
		// resolves the current revision via the IndexDescription factory and
		// builds the per-version index.
		Long id = KeyFactory.stringToKey(entity.getId());
		IdAndVersion idAndVersion = IdAndVersion.newBuilder().setId(id).build();
		tableManagerSupport.setTableToProcessingAndTriggerUpdate(idAndVersion);
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
