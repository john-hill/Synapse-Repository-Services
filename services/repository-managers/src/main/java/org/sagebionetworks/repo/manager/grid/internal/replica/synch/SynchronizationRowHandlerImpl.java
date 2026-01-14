package org.sagebionetworks.repo.manager.grid.internal.replica.synch;

import java.util.Collections;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.sagebionetworks.repo.manager.grid.internal.replica.model.RowView;
import org.sagebionetworks.repo.manager.grid.internal.replica.model.SynapseRow;
import org.sagebionetworks.repo.manager.schema.AnnotationsTranslator;
import org.sagebionetworks.repo.model.NodeDAO;
import org.sagebionetworks.repo.model.UnauthorizedException;
import org.sagebionetworks.repo.model.grid.EntitySynchronizationStatus;
import org.sagebionetworks.repo.model.grid.SynchronizationErrorType;
import org.sagebionetworks.repo.model.grid.SynchronizationOperation;
import org.sagebionetworks.repo.transactions.NewWriteTransaction;
import org.sagebionetworks.repo.web.NotFoundException;
import org.sagebionetworks.util.ValidateArgument;
import org.springframework.stereotype.Service;

@Service
public class SynchronizationRowHandlerImpl implements SynchronizationRowHandler {

	private static final Logger LOGGER = LogManager.getLogger(SynchronizationRowHandlerImpl.class);

	private final NodeDAO nodeDao;
	private final AnnotationsTranslator annotationsTranslator;

	public SynchronizationRowHandlerImpl(NodeDAO nodeDao, AnnotationsTranslator annotationsTranslator) {
		super();
		this.nodeDao = nodeDao;
		this.annotationsTranslator = annotationsTranslator;
	}

	@NewWriteTransaction
	@Override
	public SynchronizationResult processRow(RowView row, SynchronizationOperation operation) {
		ValidateArgument.required(operation, "operation");
		ValidateArgument.required(row, "row");
		SynapseRow synRow = row.getSynapseRow();
		if (synRow == null || synRow.getEtag() == null || synRow.getRowId() == null) {
			throw new IllegalArgumentException(
					"This operation is only valid for a grid containing Synapse Entity data.");
		}
		try {
			// ensure the entity cannot change during this operation.
			String externalEtag = nodeDao.lockNode(synRow.getRowId().toString());
			if (!externalEtag.equals(synRow.getEtag())) {
				// there are external changes that need to be pull in.
			}
			return null;

		} catch (NotFoundException e) {
			return createErrorResult(synRow, SynchronizationErrorType.not_found, e.getMessage());
		} catch (UnauthorizedException e) {
			return createErrorResult(synRow, SynchronizationErrorType.unauthorized, e.getMessage());
		} catch (Throwable e) {
			LOGGER.error("Failed to synchronize row: " + synRow, e);
			return createErrorResult(synRow, SynchronizationErrorType.undefined, e.getMessage());
		}
	}

	SynchronizationResult createErrorResult(SynapseRow row, SynchronizationErrorType errorType, String errorMessage) {
		return new SynchronizationResult(new EntitySynchronizationStatus().setEntityId(row.getRowId().toString())
				.setErrorType(errorType).setErrorMessage(errorMessage), Collections.emptyList());
	}

}
