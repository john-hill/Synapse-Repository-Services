package org.sagebionetworks.repo.manager.grid.internal.replica.merge;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.sagebionetworks.repo.manager.EntityManager;
import org.sagebionetworks.repo.manager.file.BucketObjectReader;
import org.sagebionetworks.repo.manager.file.BucketObjectReaderProvider;
import org.sagebionetworks.repo.manager.file.FileHandleManager;
import org.sagebionetworks.repo.manager.grid.GridManager;
import org.sagebionetworks.repo.model.Entity;
import org.sagebionetworks.repo.model.RecordSet;
import org.sagebionetworks.repo.model.UserInfo;
import org.sagebionetworks.repo.model.dao.asynch.AsyncJobProgressCallback;
import org.sagebionetworks.repo.model.file.CloudProviderFileHandleInterface;
import org.sagebionetworks.repo.model.file.FileHandle;
import org.sagebionetworks.repo.model.grid.GridCsvImportRequest;
import org.sagebionetworks.repo.model.grid.GridCsvImportResponse;
import org.sagebionetworks.repo.model.grid.GridSession;
import org.sagebionetworks.repo.model.table.CsvTableDescriptor;
import org.sagebionetworks.table.cluster.utils.CSVUtils;
import org.sagebionetworks.util.ValidateArgument;
import org.springframework.stereotype.Service;

import au.com.bytecode.opencsv.CSVReader;

@Service
public class GridCsvImporterImpl implements GridCsvImporter {

	private final GridManager gridManager;
	private final EntityManager entityManager;
	private final FileHandleManager fileHandleManager;
	private final BucketObjectReaderProvider fileReaderProvider;
	
	public GridCsvImporterImpl(GridManager gridManager, EntityManager entityManager, FileHandleManager fileHandleManager, BucketObjectReaderProvider fileReaderProvider) {
		this.gridManager = gridManager;
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
		
		GridSession gridSession = gridManager.getGridSession(user, request.getSessionId());
		
		Entity entity = entityManager.getEntity(user, gridSession.getSourceEntityId());
		
		ValidateArgument.requirement(entity instanceof RecordSet, "Unsupported grid session: only a grid created from a record set is supported.");
		
		RecordSet recordSet = (RecordSet) entity;
		List<String> upsertKey = recordSet.getUpsertKey();
		
		try (CSVReader csvReader = getCsvReader(fileHandleManager.getRawFileHandle(user, recordSet.getDataFileHandleId()), recordSet.getCsvDescriptor())) {
			// TODO
			
			// Option 1: Read a chunk and check against the grid in a batch query?
			// Option 2: Save the CSV in a temporary table and use a LEFT JOIN?
			
		} catch (IOException ex) {
			throw new IllegalStateException(ex);
		}
		
		return null;
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
