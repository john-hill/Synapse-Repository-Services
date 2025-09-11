package org.sagebionetworks.grid.workers;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.sagebionetworks.repo.manager.grid.internal.replica.export.GridRecordSetExporter;
import org.sagebionetworks.repo.model.UserInfo;
import org.sagebionetworks.repo.model.dao.asynch.AsyncJobProgressCallback;
import org.sagebionetworks.repo.model.grid.GridRecordSetExportRequest;
import org.sagebionetworks.repo.model.grid.GridRecordSetExportResponse;
import org.sagebionetworks.worker.AsyncJobRunner;
import org.sagebionetworks.workers.util.aws.message.RecoverableMessageException;
import org.springframework.stereotype.Service;

@Service
public class GridRecordSetExportWorker implements AsyncJobRunner<GridRecordSetExportRequest, GridRecordSetExportResponse> {

	private static final Logger LOGGER = LogManager.getLogger(GridRecordSetExportWorker.class);
	
	private GridRecordSetExporter exporter;
	
	public GridRecordSetExportWorker(GridRecordSetExporter exporter) {
		this.exporter = exporter;
	}

	@Override
	public Class<GridRecordSetExportRequest> getRequestType() {
		return GridRecordSetExportRequest.class;
	}

	@Override
	public Class<GridRecordSetExportResponse> getResponseType() {
		return GridRecordSetExportResponse.class;
	}

	@Override
	public GridRecordSetExportResponse run(String jobId, UserInfo user, GridRecordSetExportRequest request, AsyncJobProgressCallback jobProgressCallback)
		throws RecoverableMessageException, Exception {
		
		try {
			return exporter.exportGrid(user, request, jobProgressCallback);
		} catch (RecoverableMessageException e) {
			throw e;
		} catch (Exception e) {
			LOGGER.error("Failed to export a record set grid: " + e.getMessage(), e);
			throw e;
		}
	}

}
