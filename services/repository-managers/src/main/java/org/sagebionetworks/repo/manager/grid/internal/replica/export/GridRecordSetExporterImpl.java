package org.sagebionetworks.repo.manager.grid.internal.replica.export;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.sagebionetworks.repo.manager.file.FileHandleManager;
import org.sagebionetworks.repo.manager.file.LocalFileUploadRequest;
import org.sagebionetworks.repo.manager.grid.GridManager;
import org.sagebionetworks.repo.manager.grid.internal.replica.GridReplicaSupport;
import org.sagebionetworks.repo.manager.grid.internal.replica.model.RowView;
import org.sagebionetworks.repo.manager.grid.internal.replica.validation.GridRowValidator;
import org.sagebionetworks.repo.manager.grid.internal.replica.validation.ValidationSummaryAccumulator;
import org.sagebionetworks.repo.model.RecordSet;
import org.sagebionetworks.repo.model.UserInfo;
import org.sagebionetworks.repo.model.dao.asynch.AsyncJobProgressCallback;
import org.sagebionetworks.repo.model.dbo.schema.EntitySchemaValidationResultDao;
import org.sagebionetworks.repo.model.grid.DownloadFromGridRequest;
import org.sagebionetworks.repo.model.grid.DownloadFromGridResult;
import org.sagebionetworks.repo.model.grid.GridRecordSetExportRequest;
import org.sagebionetworks.repo.model.grid.GridRecordSetExportResponse;
import org.sagebionetworks.repo.model.grid.GridSession;
import org.sagebionetworks.repo.model.jdo.KeyFactory;
import org.sagebionetworks.repo.model.schema.JsonSchema;
import org.sagebionetworks.repo.model.schema.ValidationSummaryStatistics;
import org.sagebionetworks.repo.model.table.CsvTableDescriptor;
import org.sagebionetworks.repo.service.EntityService;
import org.sagebionetworks.repo.transactions.WriteTransaction;
import org.sagebionetworks.util.ValidateArgument;
import org.sagebionetworks.util.csv.CSVWriterProvider;
import org.springframework.stereotype.Service;

import au.com.bytecode.opencsv.CSVWriter;

@Service
public class GridRecordSetExporterImpl implements GridRecordSetExporter {

	private static final int VALIDATION_BATCH_SIZE = 1000;

	private final GridManager gridManager;
	private final GridReplicaSupport gridReplicaSupport;
	private final EntityService entityService;
	private final GridReplicaCsvExporter csvExporter;
	private final EntitySchemaValidationResultDao validationResultDao;
	private final CSVWriterProvider csvWriterProvider;
	private final FileHandleManager fileHandleManager;
	private final GridRowValidator gridRowValidator;

	public GridRecordSetExporterImpl(GridManager gridManager, GridReplicaSupport gridReplicaSupport, EntityService entityService, GridReplicaCsvExporter csvExporter, EntitySchemaValidationResultDao validationResultDao, CSVWriterProvider csvWriterProvider, FileHandleManager fileHandleManager, GridRowValidator gridRowValidator) {
		this.gridManager = gridManager;
		this.gridReplicaSupport = gridReplicaSupport;
		this.entityService = entityService;
		this.csvExporter = csvExporter;
		this.validationResultDao = validationResultDao;
		this.csvWriterProvider = csvWriterProvider;
		this.fileHandleManager = fileHandleManager;
		this.gridRowValidator = gridRowValidator;
	}
	
	@Override
	@WriteTransaction
	public GridRecordSetExportResponse exportGrid(UserInfo user, GridRecordSetExportRequest request, AsyncJobProgressCallback jobCallback) {
		ValidateArgument.required(user, "user");
		ValidateArgument.required(request, "request");
		ValidateArgument.required(request.getSessionId(), "request.sessionId");
		
		GridSession gridSession = gridManager.getGridSession(user, request.getSessionId());
		
		RecordSet recordSet = gridReplicaSupport.getRecordSetOrThrow(user, gridSession);
		
		String exportedFileId;
		File tmpValidationFile = null;
		ValidationSummaryStatistics validationSummary;
		String validationFileId;
		
		try {
			
			tmpValidationFile = File.createTempFile(jobCallback.getJobId() + "_validation_details", ".csv");
			
			try (CSVWriter validationCsvWriter = csvWriterProvider.createWriter(new FileWriter(tmpValidationFile, StandardCharsets.UTF_8), null)) {
				ValidationSummaryBuilder validationSummaryBuilder = new ValidationSummaryBuilder(recordSet.getId(), validationCsvWriter);
				
				// First export to a CSV file
				exportedFileId = exportToCsv(user, gridSession.getSessionId(), recordSet.getCsvDescriptor(), jobCallback, validationSummaryBuilder);
				
				validationSummary = validationSummaryBuilder.getValidationSummary();
			}
			
			// Uploads the validation details as a file handle
			validationFileId = fileHandleManager.uploadLocalFile(new LocalFileUploadRequest()
				.withUserId(user.getId().toString())
				.withFileName("grid_validation_details.csv")
				.withContentType("text/csv")
				.withFileToUpload(tmpValidationFile)					
			).getId();
		
		} catch (IOException e) {
			throw new IllegalStateException("Could not export the grid to a new record set version.", e);
		} finally {
			if (tmpValidationFile != null) {
				tmpValidationFile.delete();
			}
		}
		
		// Creates a new version of the record set that points to the new file and persist the validation summary
		recordSet = createRecordSetVersionFromArtifacts(user, recordSet, exportedFileId, validationSummary, validationFileId);

		// Update the GridSession to denote that it is in sync with the record set
		gridManager.updateSourceEntityVersion(request.getSessionId(), recordSet.getVersionNumber());

		return new GridRecordSetExportResponse()
			.setSessionId(request.getSessionId())
			.setRecordSetId(recordSet.getId())
			.setRecordSetVersionNumber(recordSet.getVersionNumber())
			.setValidationSummaryStatistics(recordSet.getValidationSummary())
			.setValidationFileHandleId(recordSet.getValidationFileHandleId());
	}

	String exportToCsv(UserInfo user, String sessionId, CsvTableDescriptor csvDescriptor, AsyncJobProgressCallback jobCallback, RowViewCallbackHandler rowCallback) {
		
		DownloadFromGridRequest request = new DownloadFromGridRequest()
			.setSessionId(sessionId)
			.setWriteHeader(true)
			.setIncludeEtag(false)
			.setIncludeRowIdAndRowVersion(false)
			.setCsvTableDescriptor(csvDescriptor);
		
		DownloadFromGridResult result;
		
		try {
			result = csvExporter.exportGridAsCsv(user, request, jobCallback, rowCallback);
		} catch (IOException e) {
			throw new IllegalStateException("Could not export the grid to a CSV file.", e);
		}
		
		return result.getResultsFileHandleId();
	}


	/**
	 * Create a new RecordSet version pointing at the provided data file handle and
	 * validation-details file handle, and persist the validation summary. This is
	 * the shared "create version" tail used by both {@link #exportGrid} and
	 * {@link #pushFromArtifactBuilder}.
	 *
	 * @param user                       the calling user
	 * @param recordSet                  the source RecordSet
	 * @param newFileHandleId            the new data CSV file handle id
	 * @param validationSummary          the validation summary to persist
	 * @param validationFileHandleId	 the validation-details file handle id
	 * @return the updated RecordSet
	 */
	RecordSet createRecordSetVersionFromArtifacts(UserInfo user, RecordSet recordSet, String newFileHandleId, ValidationSummaryStatistics validationSummary, String validationFileHandleId) {
		recordSet.setDataFileHandleId(newFileHandleId);
		// The file handle with the validation details is stored in the revision table
		recordSet.setValidationFileHandleId(validationFileHandleId);
		recordSet.setVersionLabel(null);
		
		// Update through the EntityService so the RecordSetMetadataProvider is invoked
		// (entityUpdated → rebinds the column schema and triggers the index rebuild).
		// We skip sanitization because the sanitize step is intended to strip the validation file handle,
		// when a user directly updates the RecordSet; updating the record set via the grid should persist the
		// validation data.
		boolean skipSanitization = true;
		RecordSet updated = entityService.updateEntity(user.getId(), recordSet, true, null, skipSanitization);

		Long recordSetId = KeyFactory.stringToKey(recordSet.getId());
		Long recordSetVersion = updated.getVersionNumber();
		
		// Persists the validation summary 
		validationResultDao.setRecordSetValidationSummaryStatistics(recordSetId, recordSetVersion, validationSummary);
		
		// We set this manually since this is set at the service layer and not stored in the revision table
		updated.setValidationSummary(validationSummary);
		
		return updated;
	}
	
	final class ValidationSummaryBuilder implements RowViewCallbackHandler {

		private final String recordSetId;
		private final ValidationSummaryAccumulator accumulator;

		ValidationSummaryBuilder(String recordSetId, CSVWriter validationCsvWriter) throws IOException {
			this.recordSetId = recordSetId;
			this.accumulator = new ValidationSummaryAccumulator(validationCsvWriter);
		}
		
		@Override
		public void next(RowView rowView) {
			accumulator.record(rowView.getRowValidationResults());
		}
		
		ValidationSummaryStatistics getValidationSummary() {
			return accumulator.getValidationSummary(recordSetId);
		}
		
	}
	
}
