package org.sagebionetworks.repo.manager.grid.internal.replica.export;

import org.sagebionetworks.repo.model.RecordSet;
import org.sagebionetworks.repo.model.UserInfo;
import org.sagebionetworks.repo.model.dao.asynch.AsyncJobProgressCallback;
import org.sagebionetworks.repo.model.grid.GridRecordSetExportRequest;
import org.sagebionetworks.repo.model.grid.GridRecordSetExportResponse;
import org.sagebionetworks.repo.model.schema.ValidationSummaryStatistics;

public interface GridRecordSetExporter {

	GridRecordSetExportResponse exportGrid(UserInfo user, GridRecordSetExportRequest request, AsyncJobProgressCallback jobCallback);

	/**
	 * Create a new RecordSet version pointing at the provided data file handle and
	 * validation-details file handle, and persist the validation summary.
	 *
	 * @param user                       the calling user
	 * @param recordSet                  the source RecordSet
	 * @param csvFileHandleId            the new data CSV file handle id
	 * @param validationSummary          the validation summary to persist
	 * @param validationDetailsFileHandleId the validation-details file handle id
	 * @return the updated RecordSet
	 */
	RecordSet createRecordSetVersionFromArtifacts(UserInfo user, RecordSet recordSet, String csvFileHandleId,
			ValidationSummaryStatistics validationSummary, String validationDetailsFileHandleId);

}
