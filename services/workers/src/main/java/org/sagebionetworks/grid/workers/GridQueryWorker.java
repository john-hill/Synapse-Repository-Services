package org.sagebionetworks.grid.workers;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.sagebionetworks.repo.manager.grid.GridManager;
import org.sagebionetworks.repo.model.UserInfo;
import org.sagebionetworks.repo.model.dao.asynch.AsyncJobProgressCallback;
import org.sagebionetworks.repo.model.grid.GridQueryJobRequest;
import org.sagebionetworks.repo.model.grid.GridQueryJobResponse;
import org.sagebionetworks.worker.AsyncJobRunner;
import org.sagebionetworks.workers.util.aws.message.RecoverableMessageException;
import org.springframework.stereotype.Service;

@Service
public class GridQueryWorker implements AsyncJobRunner<GridQueryJobRequest, GridQueryJobResponse> {

	private static final Logger log = LogManager.getLogger(GridQueryWorker.class);

	private final GridManager gridManager;

	public GridQueryWorker(GridManager gridManager) {
		super();
		this.gridManager = gridManager;
	}

	@Override
	public Class<GridQueryJobRequest> getRequestType() {
		return GridQueryJobRequest.class;
	}

	@Override
	public Class<GridQueryJobResponse> getResponseType() {
		return GridQueryJobResponse.class;
	}

	@Override
	public GridQueryJobResponse run(String jobId, UserInfo user, GridQueryJobRequest request,
			AsyncJobProgressCallback jobProgressCallback) throws RecoverableMessageException, Exception {
		try {
			return gridManager.queryGrid(user, request);
		} catch (RecoverableMessageException e) {
			log.warn("Recoverable message: " + e.getMessage());
			throw e;
		}
	}

}
