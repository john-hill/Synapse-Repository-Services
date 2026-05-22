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
