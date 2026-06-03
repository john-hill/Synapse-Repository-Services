package org.sagebionetworks.repo.manager.table;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

import org.sagebionetworks.repo.manager.EntityManager;
import org.sagebionetworks.repo.manager.file.CsvFileHandleProvider;
import org.sagebionetworks.repo.manager.grid.CsvSchemaReconciler;
import org.sagebionetworks.repo.manager.schema.JsonSchemaManager;
import org.sagebionetworks.repo.model.file.FileHandle;
import org.sagebionetworks.repo.model.schema.JsonSchema;
import org.sagebionetworks.repo.model.table.ColumnModel;
import org.sagebionetworks.repo.model.table.CsvTableDescriptor;
import org.sagebionetworks.repo.model.table.UploadToTablePreviewRequest;
import org.springframework.stereotype.Service;

import au.com.bytecode.opencsv.CSVReader;

/**
 * Infers a {@link ColumnModel} schema from a RecordSet's CSV data file and
 * reconciles it with the RecordSet's bound JSON Schema (if any). This logic is
 * shared by the grid create flow ({@code RecordSetCreateGridHandler}) and the
 * RecordSetMetadataOrovider, which binds the column schema on to the table index
 * upon create/update.
 */
@Service
public class RecordSetSchemaResolver {

	/**
	 * The number of CSV rows scanned when inferring the schema on the synchronous
	 * create/update path. This is a sample: a column whose type only widens later
	 * in the file (e.g. a late decimal in an otherwise-integer column, or a longer
	 * string that would increase the maximum allowed string length) may not be
	 * captured. Users can bind a JSON Schema for explicit typing or cast in a
	 * downstream materialized view.
	 */
	static final int SAMPLE_ROWS_TO_SCAN = 10;

	private final CsvFileHandleProvider csvFileHandleProvider;
	private final EntityManager entityManager;
	private final JsonSchemaManager jsonSchemaManager;

	public RecordSetSchemaResolver(CsvFileHandleProvider csvFileHandleProvider, EntityManager entityManager,
			JsonSchemaManager jsonSchemaManager) {
		this.csvFileHandleProvider = csvFileHandleProvider;
		this.entityManager = entityManager;
		this.jsonSchemaManager = jsonSchemaManager;
	}

	/**
	 * Result of {@link #getReconciledSchema}: the reconciled schema plus the indices
	 * (into that schema) of the columns the bound JSON Schema marked as required.
	 */
	public static class ReconciledSchema {
		private final List<ColumnModel> schema;
		private final List<Integer> requiredColumnIndices;

		public ReconciledSchema(List<ColumnModel> schema, List<Integer> requiredColumnIndices) {
			this.schema = schema;
			this.requiredColumnIndices = requiredColumnIndices;
		}

		public List<ColumnModel> getSchema() {
			return schema;
		}

		public List<Integer> getRequiredColumnIndices() {
			return requiredColumnIndices;
		}
	}

	/**
	 * Infer the schema from the CSV file and reconcile it with the RecordSet's bound
	 * JSON Schema, upgrading scalar columns to list types where the JSON Schema
	 * declares an array. Used on the synchronous create/update path where a full
	 * scan would add too much latency.
	 *
	 * @param entityId      the RecordSet entity id, used to look up the bound schema
	 * @param fileHandle    the CSV data file handle
	 * @param csvDescriptor CSV parsing options
	 * @param fullScan		whether to scan the full CSV (most accurate) or a small sample of rows
	 * @return the reconciled schema (never null, possibly empty)
	 */
	public ReconciledSchema getReconciledSchema(String entityId, FileHandle fileHandle,
			CsvTableDescriptor csvDescriptor, boolean fullScan) {
		List<ColumnModel> schema = inferSchemaFromCsv(fileHandle, csvDescriptor, fullScan);
		Optional<JsonSchema> validationSchema = getBoundValidationSchema(entityId);
		validationSchema.ifPresent(vs -> CsvSchemaReconciler.reconcile(schema, vs));

		List<String> required = validationSchema.map(JsonSchema::getRequired).orElse(new ArrayList<>());
		Map<String, Integer> columnNameToIndex = new HashMap<>();
		for (int i = 0; i < schema.size(); i++) {
			columnNameToIndex.put(schema.get(i).getName(), i);
		}
		List<Integer> requiredColumnIndices = required.stream()
				.map(columnNameToIndex::get)
				.filter(Objects::nonNull)
				.collect(Collectors.toList());

		return new ReconciledSchema(schema, requiredColumnIndices);
	}

	List<ColumnModel> inferSchemaFromCsv(FileHandle fileHandle, CsvTableDescriptor csvDescriptor, boolean fullScan) {
		try (CSVReader csvReader = csvFileHandleProvider.getCsvReader(fileHandle, csvDescriptor)) {
			UploadToTablePreviewRequest request = new UploadToTablePreviewRequest()
					.setCsvTableDescriptor(csvDescriptor)
					.setDoFullFileScan(fullScan);
			UploadPreviewBuilder builder = new UploadPreviewBuilder(csvReader, request);
			if (!fullScan) {
				builder.setMaxRowsInpartialScan(SAMPLE_ROWS_TO_SCAN);
			}
			List<ColumnModel> suggested = builder.buildResult().getSuggestedColumns();
			return suggested == null ? Collections.emptyList() : suggested;
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}
	}

	Optional<JsonSchema> getBoundValidationSchema(String entityId) {
		return entityManager.findBoundSchema(entityId)
				.map(binding -> binding.getJsonSchemaVersionInfo().get$id())
				.map(jsonSchemaManager::getValidationSchema);
	}

}
