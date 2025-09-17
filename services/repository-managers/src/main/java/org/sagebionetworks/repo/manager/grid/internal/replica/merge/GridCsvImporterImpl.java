package org.sagebionetworks.repo.manager.grid.internal.replica.merge;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import org.sagebionetworks.repo.manager.EntityManager;
import org.sagebionetworks.repo.manager.file.BucketObjectReader;
import org.sagebionetworks.repo.manager.file.BucketObjectReaderProvider;
import org.sagebionetworks.repo.manager.file.FileHandleManager;
import org.sagebionetworks.repo.manager.grid.GridManager;
import org.sagebionetworks.repo.manager.grid.internal.replica.model.GridHeader;
import org.sagebionetworks.repo.manager.grid.internal.replica.view.GridReplicaViewManager;
import org.sagebionetworks.repo.model.Entity;
import org.sagebionetworks.repo.model.RecordSet;
import org.sagebionetworks.repo.model.UserInfo;
import org.sagebionetworks.repo.model.dao.asynch.AsyncJobProgressCallback;
import org.sagebionetworks.repo.model.file.CloudProviderFileHandleInterface;
import org.sagebionetworks.repo.model.file.FileHandle;
import org.sagebionetworks.repo.model.grid.EventSource;
import org.sagebionetworks.repo.model.grid.GridConnectionInfo;
import org.sagebionetworks.repo.model.grid.GridCsvImportRequest;
import org.sagebionetworks.repo.model.grid.GridCsvImportResponse;
import org.sagebionetworks.repo.model.grid.GridSession;
import org.sagebionetworks.repo.model.table.CsvTableDescriptor;
import org.sagebionetworks.table.cluster.utils.CSVUtils;
import org.sagebionetworks.util.ValidateArgument;
import org.sagebionetworks.workers.util.aws.message.RecoverableMessageException;
import org.springframework.stereotype.Service;

import au.com.bytecode.opencsv.CSVReader;

@Service
public class GridCsvImporterImpl implements GridCsvImporter {
	
	private final GridManager gridManager;
	private final GridReplicaViewManager gridViewManager;
	private final EntityManager entityManager;
	private final FileHandleManager fileHandleManager;
	private final BucketObjectReaderProvider fileReaderProvider;
	
	public GridCsvImporterImpl(GridManager gridManager, GridReplicaViewManager gridViewManager, EntityManager entityManager, FileHandleManager fileHandleManager, BucketObjectReaderProvider fileReaderProvider) {
		this.gridManager = gridManager;
		this.gridViewManager = gridViewManager;
		this.entityManager = entityManager;
		this.fileHandleManager = fileHandleManager;
		this.fileReaderProvider = fileReaderProvider;
	}
	
	@Override
	public GridCsvImportResponse importCsv(UserInfo user, GridCsvImportRequest request, AsyncJobProgressCallback jobCallback) {
		ValidateArgument.required(user, "user");
		ValidateArgument.required(request, "request");
		ValidateArgument.required(request.getSessionId(), "request.sessionId");
		ValidateArgument.required(request.getFileHandleId(), "request.fileHandleId");
		ValidateArgument.required(request.getCsvDescriptor(), "request.csvDescriptor");
		ValidateArgument.requirement(Boolean.TRUE.equals(request.getCsvDescriptor().getIsFirstLineHeader()), "The request.csvDescriptor.isFirstLineHeader must be true.");
		
		GridSession gridSession = gridManager.getGridSession(user, request.getSessionId());
		
		Entity entity = entityManager.getEntity(user, gridSession.getSourceEntityId());
		
		ValidateArgument.requirement(entity instanceof RecordSet, "Unsupported grid session: only a grid created from a record set is supported.");
		
		CsvTableDescriptor csvDescriptor = request.getCsvDescriptor();
		
		RecordSet recordSet = (RecordSet) entity;
		
		GridHeader gridHeader = getGridHeader(gridSession);
		
		BatchMergeProcessor batchProcessor;

		try (CSVReader csvReader = getCsvReader(fileHandleManager.getRawFileHandle(user, recordSet.getDataFileHandleId()), csvDescriptor)) {
			String[] headerRow = csvReader.readNext();

			batchProcessor = getBatchProcessor(gridHeader, headerRow, recordSet);
			
			String[] row;
			
			while ((row = csvReader.readNext()) != null) {
				batchProcessor.next(row);
			}
			
		} catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
		
		batchProcessor.flush();
		
		return new GridCsvImportResponse()
			.setSessionId(request.getSessionId())
			.setTotalCount(Long.valueOf(batchProcessor.getProcessedCount()))
			.setCreatedCount(Long.valueOf(batchProcessor.getCreatedCount()))
			.setUpdatedCount(Long.valueOf(batchProcessor.getUpdatedCount()));
	}
	
	GridHeader getGridHeader(GridSession gridSession) {
		GridConnectionInfo connectionInfo = gridManager.getSingletonConnection(gridSession.getSessionId(), EventSource.INTERNAL)
			.orElseThrow(() -> new RecoverableMessageException("No internal connection found for session: " + gridSession.getSessionId()));
		
		return gridViewManager.readHeader(connectionInfo.getSessionId(), connectionInfo.getReplicaId())
			.orElseThrow(() -> new RecoverableMessageException("Grid header has not yet been instantiated for sessionId: " + gridSession.getSessionId()));
	}
	
	CSVReader getCsvReader(FileHandle fileHandle, CsvTableDescriptor csvDescriptor) {
		ValidateArgument.requirement(fileHandle instanceof CloudProviderFileHandleInterface, "Only S3 and Google Cloud Storage files that Synapse can access are supported.");
		
		CloudProviderFileHandleInterface cpFileHandle = (CloudProviderFileHandleInterface) fileHandle;
		
		BucketObjectReader fileReader = fileReaderProvider.getBucketObjectReader(cpFileHandle.getClass());

		return CSVUtils.createCSVReader(
				new InputStreamReader(fileReader.openStream(cpFileHandle.getBucketName(), cpFileHandle.getKey()),
						StandardCharsets.UTF_8),
				csvDescriptor, null);
	}
	
	BatchMergeProcessor getBatchProcessor(GridHeader gridHeader, String[] csvHeader, RecordSet recordSet) {
		return new BatchMergeProcessor(gridViewManager, gridHeader, csvHeader, recordSet.getUpsertKey());
	}
}
