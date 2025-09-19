package org.sagebionetworks.repo.manager.grid.internal.replica.merge;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

import org.json.JSONArray;
import org.sagebionetworks.grid.db.GridTransaction;
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
	private final GridCsvImportDao importDao;
	
	public GridCsvImporterImpl(GridCsvImportDao importDao, GridManager gridManager, GridReplicaViewManager gridViewManager, EntityManager entityManager, FileHandleManager fileHandleManager, BucketObjectReaderProvider fileReaderProvider) {
		this.importDao = importDao;
		this.gridManager = gridManager;
		this.gridViewManager = gridViewManager;
		this.entityManager = entityManager;
		this.fileHandleManager = fileHandleManager;
		this.fileReaderProvider = fileReaderProvider;
	}
	
	@Override
	@GridTransaction(readOnly = false)
	public GridCsvImportResponse importCsv(UserInfo user, GridCsvImportRequest request, AsyncJobProgressCallback jobCallback) {
		ValidateArgument.required(user, "user");
		ValidateArgument.required(request, "request");
		ValidateArgument.required(request.getSessionId(), "request.sessionId");
		ValidateArgument.required(request.getFileHandleId(), "request.fileHandleId");
		ValidateArgument.required(request.getCsvDescriptor(), "request.csvDescriptor");
		
		ValidateArgument.requirement(Boolean.TRUE.equals(request.getCsvDescriptor().getIsFirstLineHeader()), "The request.csvDescriptor.isFirstLineHeader must be true.");
		
		GridSession gridSession = gridManager.getGridSession(user, request.getSessionId());
		
		RecordSet recordSet = getRecordSet(user, gridSession);
		
		GridHeader gridHeader = getGridHeader(gridSession);
		
		List<String> upsertKey = recordSet.getUpsertKey();
		
		// First create a temporary table containing the CSV data
		DataStream csvStream;
		
		try (CSVReader csvReader = getCsvReader(fileHandleManager.getRawFileHandle(user, request.getFileHandleId()), recordSet.getCsvDescriptor())) {
			csvStream = new CsvDataStream(csvReader, gridHeader, upsertKey);
			importDao.streamToCsvTempTable(csvStream);
		} catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
		
		// Now create a temporary table containing the grid data
		DataStream gridStream = new GridDataStream(gridViewManager.getQueryIterator(gridHeader, Collections.emptyList()), gridHeader, upsertKey);
		
		importDao.streamToGridTempTable(gridStream);
		
		// Now join the two temporary tables
		Iterator<JoinedRow> joinResult = importDao.getJoinedTempTableIterator(csvStream.getColumnMapping(), gridStream.getColumnMapping());
		
		long rowCount = 0;
		long updatedCount = 0;
		long createdCount = 0;
		
		while (joinResult.hasNext()) {
			JoinedRow joinedRow = joinResult.next();
			
			// Object[] upsertKeyValues = joinedRow.getUpsertKeyValues();
			// JSONArray csvData = joinedRow.getCsvData();
			JSONArray gridData = joinedRow.getGridData();
			
			if (gridData != null) {
				updatedCount++;
			} else {
				createdCount++;
			}
			
			rowCount++;
		}
		
		return new GridCsvImportResponse()
			.setTotalCount(rowCount)
			.setUpdatedCount(updatedCount)
			.setCreatedCount(createdCount)
			.setSessionId(request.getSessionId());
	}
	
	GridHeader getGridHeader(GridSession gridSession) {
		GridConnectionInfo connectionInfo = gridManager.getSingletonConnection(gridSession.getSessionId(), EventSource.INTERNAL)
			.orElseThrow(() -> new RecoverableMessageException("No internal connection found for session: " + gridSession.getSessionId()));
		
		return gridViewManager.readHeader(connectionInfo.getSessionId(), connectionInfo.getReplicaId())
			.orElseThrow(() -> new RecoverableMessageException("Grid header has not yet been instantiated for sessionId: " + gridSession.getSessionId()));
	}
	
	RecordSet getRecordSet(UserInfo user, GridSession gridSession) {
		Entity entity = entityManager.getEntity(user, gridSession.getSourceEntityId());
		
		ValidateArgument.requirement(entity instanceof RecordSet, "Unsupported grid session: only a grid created from a record set is supported.");
		
		return (RecordSet) entity;
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
}
