package org.sagebionetworks.repo.manager.grid.internal.replica.merge;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.sagebionetworks.grid.db.GridTransaction;
import org.sagebionetworks.repo.manager.EntityManager;
import org.sagebionetworks.repo.manager.file.BucketObjectReader;
import org.sagebionetworks.repo.manager.file.BucketObjectReaderProvider;
import org.sagebionetworks.repo.manager.file.FileHandleManager;
import org.sagebionetworks.repo.manager.grid.GridManager;
import org.sagebionetworks.repo.manager.grid.internal.replica.model.Column;
import org.sagebionetworks.repo.manager.grid.internal.replica.model.GridHeader;
import org.sagebionetworks.repo.manager.grid.internal.replica.model.RowView;
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
import org.sagebionetworks.repo.model.table.ColumnModel;
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
	private final JoinedRowChangePublisher changePublisher;
	
	public GridCsvImporterImpl(GridCsvImportDao importDao, GridManager gridManager, GridReplicaViewManager gridViewManager, EntityManager entityManager, FileHandleManager fileHandleManager, BucketObjectReaderProvider fileReaderProvider, JoinedRowChangePublisher changePublisher) {
		this.importDao = importDao;
		this.gridManager = gridManager;
		this.gridViewManager = gridViewManager;
		this.entityManager = entityManager;
		this.fileHandleManager = fileHandleManager;
		this.fileReaderProvider = fileReaderProvider;
		this.changePublisher = changePublisher;
	}
	
	@Override
	@GridTransaction(readOnly = false)
	public GridCsvImportResponse importCsv(UserInfo user, GridCsvImportRequest request, AsyncJobProgressCallback jobCallback) {
		ValidateArgument.required(user, "user");
		ValidateArgument.required(request, "request");
		ValidateArgument.required(request.getSessionId(), "request.sessionId");
		ValidateArgument.required(request.getFileHandleId(), "request.fileHandleId");
		ValidateArgument.required(request.getCsvDescriptor(), "request.csvDescriptor");
		ValidateArgument.required(request.getSchema(), "request.schema");
	
		GridSession gridSession = gridManager.getGridSession(user, request.getSessionId());
		
		GridHeader gridHeader = getGridHeader(gridSession);
		
		// Gets the connection info for the publisher now so that we fail fast, note that we cannot
		// reuse the INTERNAL connection for writes for now, so we fallback to the VALIDATION connection
		GridConnectionInfo publisherConnInfo = gridManager.getSingletonConnection(gridSession.getSessionId(), EventSource.VALIDATION)
			.orElseThrow(() -> new RecoverableMessageException("No internal connection found for session: " + gridSession.getSessionId()));
		
		List<String> upsertKey = getUpsertKey(user, gridSession);
		
		ColumnMapping[] columnMapping;
		
		// First create a temporary table containing the CSV data
		try (CSVReader csvReader = getCsvReader(fileHandleManager.getRawFileHandle(user, request.getFileHandleId()), request.getCsvDescriptor())) {
			
			// We need to make sure that the schema is sorted according to the potential CSV header
			List<ColumnModel> csvSchema = getSortedSchema(csvReader, request.getCsvDescriptor(), request.getSchema());
			
			// Computes the driving column mapping
			columnMapping = getColumnMapping(upsertKey, csvSchema, gridHeader.getOrderedColumns());
			
			importDao.streamToCsvTempTable(new CsvDataStream(csvReader, columnMapping), columnMapping);
		} catch(IllegalArgumentException e) {
			throw e;
		} catch (IOException ex) {
			throw new IllegalStateException(ex);
		}
		
		Iterator<RowView> gridDataIterator = gridViewManager.getQueryIterator(gridHeader, Collections.emptyList());
		
		// Now create a temporary table containing the grid data
		importDao.streamToGridTempTable(new GridDataStream(gridDataIterator, columnMapping), columnMapping);
		
		// Now join the two temporary tables to determine which rows are new and which rows are updates
		Iterator<JoinedRow> joinResult = importDao.getJoinedTempTableIterator(columnMapping);
		
		return changePublisher.processJoinedRows(gridHeader, publisherConnInfo, joinResult, columnMapping);
	}
	
	List<ColumnModel> getSortedSchema(CSVReader reader, CsvTableDescriptor descriptor, List<ColumnModel> schema) throws IOException {
		if (Boolean.TRUE.equals(descriptor.getIsFirstLineHeader())) {
			String[] header = reader.readNext();
			
			ValidateArgument.requirement(header != null, "The CSV file cannot be empty.");
			ValidateArgument.requirement(header.length == schema.size(), "The CSV header does not match the schema size.");

			Map<String, ColumnModel> csvSchemaMap = schema.stream()
				.collect(Collectors.toMap(ColumnModel::getName, Function.identity()));
			
			List<ColumnModel> sortedSchema = new ArrayList<>(schema.size());
		
			for (String columnName : header) {
				ColumnModel cm = csvSchemaMap.get(columnName);
				ValidateArgument.requirement(cm != null, "The CSV header column \"" + columnName + "\" does not exist in the schema.");
				sortedSchema.add(cm);
			}
			
			return sortedSchema;
		} else {
			return schema;
		}
	}
	
	// Computes an ordered mapping by upsert key first and then the rest of the columns of the CSV that exist in the grid.
	ColumnMapping[] getColumnMapping(List<String> upsertKey, List<ColumnModel> csvSchema, List<Column> gridSchema) throws IOException {
		List<ColumnMapping> columnMapping = new ArrayList<>();
		
		Map<String, Integer> csvColumnIndex = new HashMap<>(csvSchema.size());
		
		IntStream.range(0, csvSchema.size())
			.forEach(i -> csvColumnIndex.put(csvSchema.get(i).getName(), i));
		
		Map<String, Integer> gridColumnIndex = new HashMap<>();
		
		IntStream.range(0, gridSchema.size())
			.forEach(i -> gridColumnIndex.put(gridSchema.get(i).getName(), i));
			
		// We first map by the upsert key order
		for (int i = 0; i < upsertKey.size(); i++) {
			String columnName = upsertKey.get(i);
			
			int csvIndex = csvColumnIndex.getOrDefault(columnName, -1);
			
			ValidateArgument.requirement(csvIndex >= 0, "The upsert key column \"" + columnName + "\" does not exist in the CSV schema.");
			
			int gridIndex = gridColumnIndex.getOrDefault(columnName, -1);
			
			ValidateArgument.requirement(gridIndex >= 0, "The upsert key column \"" + columnName + "\" does not exist in the grid schema.");
			
			columnMapping.add(new ColumnMapping(columnName, csvSchema.get(csvIndex).getColumnType(), csvIndex, gridIndex, true));
		}
		
		// Now maps the rest of the columns
		for (int csvIndex = 0; csvIndex < csvSchema.size(); csvIndex++) {
			String columnName = csvSchema.get(csvIndex).getName();
			
			if (upsertKey.contains(columnName)) {
				continue;
			}
			
			int gridIndex = gridColumnIndex.getOrDefault(columnName, -1);
			
			// We ignore columns that do not exist in the grid
			if (gridIndex < 0) {
				continue;
			}
			
			columnMapping.add(new ColumnMapping(columnName, csvSchema.get(csvIndex).getColumnType(), csvIndex, gridIndex, false));
		}
		
		return columnMapping.toArray(new ColumnMapping[0]);
	}
	
	GridHeader getGridHeader(GridSession gridSession) {
		GridConnectionInfo connectionInfo = gridManager.getSingletonConnection(gridSession.getSessionId(), EventSource.INTERNAL)
			.orElseThrow(() -> new RecoverableMessageException("No internal connection found for session: " + gridSession.getSessionId()));
		
		return gridViewManager.readHeader(connectionInfo.getSessionId(), connectionInfo.getReplicaId())
			.orElseThrow(() -> new RecoverableMessageException("Grid header has not yet been instantiated for sessionId: " + gridSession.getSessionId()));
	}
	
	List<String> getUpsertKey(UserInfo user, GridSession gridSession) {
		Entity entity = entityManager.getEntity(user, gridSession.getSourceEntityId());
		
		ValidateArgument.requirement(entity instanceof RecordSet, "Unsupported grid session: only a grid created from a record set is supported.");
		
		return ((RecordSet) entity).getUpsertKey();
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
