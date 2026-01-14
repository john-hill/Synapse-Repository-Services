package org.sagebionetworks.grid.workers;

import org.sagebionetworks.repo.model.UserInfo;
import org.sagebionetworks.repo.model.dao.asynch.AsyncJobProgressCallback;
import org.sagebionetworks.repo.model.grid.GridViewSynchronizationRequest;
import org.sagebionetworks.repo.model.grid.GridViewSynchronizationResponse;
import org.sagebionetworks.worker.AsyncJobRunner;
import org.sagebionetworks.workers.util.aws.message.RecoverableMessageException;
import org.springframework.stereotype.Service;

@Service
public class GridViewSynchronizationWorker
		implements AsyncJobRunner<GridViewSynchronizationRequest, GridViewSynchronizationResponse> {

	@Override
	public Class<GridViewSynchronizationRequest> getRequestType() {
		return GridViewSynchronizationRequest.class;
	}

	@Override
	public Class<GridViewSynchronizationResponse> getResponseType() {
		return GridViewSynchronizationResponse.class;
	}

	@Override
	public GridViewSynchronizationResponse run(String jobId, UserInfo user, GridViewSynchronizationRequest request,
			AsyncJobProgressCallback jobProgressCallback) throws RecoverableMessageException, Exception {
		// TODO Auto-generated method stub
		return null;
	}

}
