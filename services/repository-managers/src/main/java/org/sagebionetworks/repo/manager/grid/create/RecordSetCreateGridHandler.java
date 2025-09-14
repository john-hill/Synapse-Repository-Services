package org.sagebionetworks.repo.manager.grid.create;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import org.sagebionetworks.repo.manager.EntityManager;
import org.sagebionetworks.repo.manager.file.BucketObjectReader;
import org.sagebionetworks.repo.manager.file.BucketObjectReaderProvider;
import org.sagebionetworks.repo.manager.file.FileHandleManager;
import org.sagebionetworks.repo.manager.grid.PatchRowHandler;
import org.sagebionetworks.repo.manager.grid.PatchStore;
import org.sagebionetworks.repo.manager.table.UploadPreviewBuilder;
import org.sagebionetworks.repo.model.RecordSet;
import org.sagebionetworks.repo.model.UserInfo;
import org.sagebionetworks.repo.model.dao.asynch.AsyncJobProgressCallback;
import org.sagebionetworks.repo.model.dbo.grid.CreateGridSession;
import org.sagebionetworks.repo.model.dbo.grid.GridDao;
import org.sagebionetworks.repo.model.file.CloudProviderFileHandleInterface;
import org.sagebionetworks.repo.model.file.FileHandle;
import org.sagebionetworks.repo.model.grid.CreateGridRequest;
import org.sagebionetworks.repo.model.grid.EventSource;
import org.sagebionetworks.repo.model.grid.GridReplica;
import org.sagebionetworks.repo.model.grid.GridSession;
import org.sagebionetworks.repo.model.table.ColumnModel;
import org.sagebionetworks.repo.model.table.CsvTableDescriptor;
import org.sagebionetworks.repo.model.table.Row;
import org.sagebionetworks.repo.model.table.UploadToTablePreviewRequest;
import org.sagebionetworks.table.cluster.utils.CSVUtils;
import org.sagebionetworks.table.cluster.utils.TableModelUtils;
import org.sagebionetworks.util.ValidateArgument;
import org.springframework.stereotype.Service;

import au.com.bytecode.opencsv.CSVReader;

@Service
public class RecordSetCreateGridHandler implements CreateGridHandler {

	private final GridDao gridDao;
	private final EntityManager entityManager;
	private final FileHandleManager fileHandleManager;
	private final BucketObjectReaderProvider fileReaderProvider;

	public RecordSetCreateGridHandler(GridDao gridDao, EntityManager entityManager, FileHandleManager fileHandleManager,
			BucketObjectReaderProvider fileReaderProvider) {
		super();
		this.gridDao = gridDao;
		this.entityManager = entityManager;
		this.fileHandleManager = fileHandleManager;
		this.fileReaderProvider = fileReaderProvider;
	}

	@Override
	public boolean canCreate(CreateGridRequest request) {
		return request.getRecordSetId() != null;
	}

	@Override
	public CreateGridHandlerResult createGrid(AsyncJobProgressCallback callback, UserInfo user, CreateGridRequest request,
			PatchStore patchStore) {
		String recordSetId = request.getRecordSetId();
		RecordSet recordSet = entityManager.getEntity(user, recordSetId, RecordSet.class);

		Optional<String> validationSchemaId = entityManager.findBoundSchema(recordSetId)
				.map(binding -> binding.getJsonSchemaVersionInfo().get$id());

		GridSession session = gridDao.createGridSession(new CreateGridSession().setUserId(user.getId())
				.setSourceId(recordSet.getId()).setSchemaId(validationSchemaId.orElse(null)));

		GridReplica replica = gridDao.createReplica(user.getId(), session.getSessionId(), false, EventSource.INTERNAL);

		FileHandle fileHandle = fileHandleManager.getRawFileHandle(user, recordSet.getDataFileHandleId());

		ValidateArgument.requirement(fileHandle instanceof CloudProviderFileHandleInterface,
				"Only S3 and Google Cloud Storage files that Synapse can access are supported.");

		CloudProviderFileHandleInterface cpFileHandle = (CloudProviderFileHandleInterface) fileHandle;

		CsvTableDescriptor csvDescriptor = recordSet.getCsvDescriptor();

		if (csvDescriptor == null) {
			csvDescriptor = new CsvTableDescriptor().setIsFirstLineHeader(true);
		}

		// In order to emit patches using the PatchRowHandler we need a starting schema,
		// this is needed so that
		// the values in a row are emitted with some sensible data types. Additionally,
		// we split into multiple
		// patches according to the max size of each row.
		//
		// In order to determine the correct schema and size we first scan the CSV file
		// reusing the UploadPreviewBuilder
		// that allows to compute a suggested schema from a CSV file.
		List<ColumnModel> schema = getSchemaFromCsv(cpFileHandle, csvDescriptor);

		if (schema == null || schema.isEmpty()) {
			throw new IllegalArgumentException(
					"Cannot determine the schema from the CSV file, at least one column header must be present.");
		}

		Long maxBytesPerRow = (long) TableModelUtils.calculateMaxRowSize(schema);

		// We can now read the CSV file again and reuse the PatchRowHandler.
		try (CSVReader csvReader = getCsvReader(((CloudProviderFileHandleInterface) fileHandle), csvDescriptor);
				PatchRowHandler rowHandler = getPatchRowHandler(patchStore, session, replica, schema, maxBytesPerRow)) {

			// Skip the header
			csvReader.readNext();

			String[] csvRow;

			while ((csvRow = csvReader.readNext()) != null) {
				rowHandler.nextRow(new Row().setValues(Arrays.asList(csvRow)));
			}

		} catch (IOException e) {
			throw new IllegalStateException(e);
		}

		return new CreateGridHandlerResult().setGridSession(session).setGridReplica(replica);
	}

	CSVReader getCsvReader(CloudProviderFileHandleInterface fileHandle, CsvTableDescriptor csvDescriptor) {
		BucketObjectReader fileReader = fileReaderProvider.getBucketObjectReader(fileHandle.getClass());

		return CSVUtils.createCSVReader(
				new InputStreamReader(fileReader.openStream(fileHandle.getBucketName(), fileHandle.getKey()),
						StandardCharsets.UTF_8),
				csvDescriptor, null);
	}

	List<ColumnModel> getSchemaFromCsv(CloudProviderFileHandleInterface fileHandle, CsvTableDescriptor csvDescriptor) {
		try (CSVReader csvReader = getCsvReader(fileHandle, csvDescriptor)) {

			// Reuse the CSV preview builder to extract the schema
			UploadToTablePreviewRequest request = new UploadToTablePreviewRequest().setCsvTableDescriptor(csvDescriptor)
					// We do a full scan so that the row size is accurate
					.setDoFullFileScan(true);

			return new UploadPreviewBuilder(csvReader, request).buildResult().getSuggestedColumns();

		} catch (IOException e) {
			throw new IllegalStateException(e);
		}
	}

	PatchRowHandler getPatchRowHandler(PatchStore patchStore, GridSession session, GridReplica replica,
			List<ColumnModel> schema, Long maxBytesPerRow) {
		return new PatchRowHandler(patchStore, session.getSessionId(), replica.getReplicaId(), schema, maxBytesPerRow);
	}

}
