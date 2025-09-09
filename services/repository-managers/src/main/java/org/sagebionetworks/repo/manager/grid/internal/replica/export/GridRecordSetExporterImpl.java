package org.sagebionetworks.repo.manager.grid.internal.replica.export;

import java.io.IOException;
import java.util.Date;

import org.sagebionetworks.repo.manager.grid.GridManager;
import org.sagebionetworks.repo.manager.grid.internal.replica.model.RowView;
import org.sagebionetworks.repo.model.Entity;
import org.sagebionetworks.repo.model.RecordSet;
import org.sagebionetworks.repo.model.UserInfo;
import org.sagebionetworks.repo.model.dao.asynch.AsyncJobProgressCallback;
import org.sagebionetworks.repo.model.grid.DownloadFromGridRequest;
import org.sagebionetworks.repo.model.grid.DownloadFromGridResult;
import org.sagebionetworks.repo.model.grid.GridRecordSetExportRequest;
import org.sagebionetworks.repo.model.grid.GridRecordSetExportResponse;
import org.sagebionetworks.repo.model.grid.GridSession;
import org.sagebionetworks.repo.model.schema.ValidationResults;
import org.sagebionetworks.repo.model.schema.ValidationSummaryStatistics;
import org.sagebionetworks.repo.model.table.CsvTableDescriptor;
import org.sagebionetworks.repo.service.EntityService;
import org.sagebionetworks.util.ValidateArgument;
import org.springframework.stereotype.Service;

@Service
public class GridRecordSetExporterImpl implements GridRecordSetExporter {

	private final GridManager gridManager;
	private final EntityService entityService;
	private final GridReplicaCsvExporter csvExporter;
	
	public GridRecordSetExporterImpl(GridManager gridManager, EntityService entityService, GridReplicaCsvExporter csvExporter) {
		this.gridManager = gridManager;
		this.entityService = entityService;
		this.csvExporter = csvExporter;
	}
	
	public GridRecordSetExportResponse exportGrid(UserInfo user, GridRecordSetExportRequest request, AsyncJobProgressCallback jobCallback) {
		ValidateArgument.required(user, "user");
		ValidateArgument.required(request, "request");
		ValidateArgument.required(request.getSessionId(), "request.sessionId");
		
		GridSession gridSession = gridManager.getGridSession(user, request.getSessionId());
		
		Entity entity = entityService.getEntity(user.getId(), gridSession.getSourceEntityId());
		
		ValidateArgument.requirement(entity instanceof RecordSet, "Unsupported grid session: only a grid created from a record set is supported.");
		
		RecordSet recordSet = (RecordSet) entity;
		
		ValidationSummaryBuilder validationSummaryBuilder = new ValidationSummaryBuilder(recordSet.getId());
		
		// First export to a CSV file
		String exportedFileId = exportToCsv(user, gridSession.getSessionId(), recordSet.getCsvDescriptor(), jobCallback, validationSummaryBuilder);
		
		ValidationSummaryStatistics validationSummary = validationSummaryBuilder.getValidationSummary();
		
		// Creates a new version of the record set that points to the new file
		recordSet = createNewVersion(user, recordSet, exportedFileId, validationSummary);
		
		return new GridRecordSetExportResponse()
			.setSessionId(request.getSessionId())
			.setRecordSetId(recordSet.getId())
			.setRecordSetVersionNumber(recordSet.getVersionNumber())
			.setValidationSummaryStatistics(validationSummary);
	}
	
	String exportToCsv(UserInfo user, String sessionId, CsvTableDescriptor csvDescriptor, AsyncJobProgressCallback jobCallback, RowViewCallbackHandler rowCallback) {
		
		DownloadFromGridRequest request = new DownloadFromGridRequest()
			.setSessionId(sessionId)
			.setWriteHeader(true)
			.setCsvTableDescriptor(csvDescriptor);
		
		DownloadFromGridResult result;
		
		try {
			result = csvExporter.exportGridAsCsv(user, request, jobCallback, rowCallback);
		} catch (IOException e) {
			throw new IllegalStateException("Could not export the grid to a CSV file.", e);
		}
		
		return result.getResultsFileHandleId();
	}
	
	RecordSet createNewVersion(UserInfo user, RecordSet recordSet, String newFileHandleId, ValidationSummaryStatistics validationSummary) {
		recordSet.setDataFileHandleId(newFileHandleId);
		recordSet.setVersionLabel(null);
		
		// TODO Should the validation summary be stored somewhere?
		
		// Updates the entity
		return entityService.updateEntity(user.getId(), recordSet, true, null);
	}
	
	final class ValidationSummaryBuilder implements RowViewCallbackHandler {

		private String recordSetId;
		private int totalCount = 0;
		private int validCount = 0;
		private int invalidCount = 0;
		private int unknownCount = 0;
		
		ValidationSummaryBuilder(String recordSetId) {
			this.recordSetId = recordSetId;
		}
		
		@Override
		public void next(RowView rowView) {
			totalCount++;			
			ValidationResults validationResult = rowView.getRowValidationResults();
			if (validationResult == null) {
				unknownCount++;
			} else if (Boolean.TRUE.equals(validationResult.getIsValid())) {
				validCount++;
			} else {
				invalidCount++;
			}
		}
		
		ValidationSummaryStatistics getValidationSummary() {
			return new ValidationSummaryStatistics()
				.setContainerId(recordSetId)
				.setTotalNumberOfChildren(Long.valueOf(totalCount))
				.setNumberOfValidChildren(Long.valueOf(validCount))
				.setNumberOfInvalidChildren(Long.valueOf(invalidCount))
				.setNumberOfUnknownChildren(Long.valueOf(unknownCount))
				.setGeneratedOn(new Date());
		}
		
	}
	
}
