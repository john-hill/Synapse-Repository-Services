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
import org.sagebionetworks.repo.model.schema.Type;
import org.sagebionetworks.repo.model.table.ColumnModel;
import org.sagebionetworks.repo.model.table.ColumnType;
import org.sagebionetworks.repo.model.table.CsvTableDescriptor;
import org.sagebionetworks.repo.model.table.UploadToTablePreviewRequest;
import org.springframework.stereotype.Service;

import au.com.bytecode.opencsv.CSVReader;

/**
 * Infers a {@link ColumnModel} schema from a RecordSet's CSV data file and
 * reconciles it with the RecordSet's bound JSON Schema (if any). This logic is
 * shared by the grid create flow ({@code RecordSetCreateGridHandler}) and the
 * RecordSetMetadataProvider, which binds the column schema on to the table index
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


	public static List<ColumnModel> getJsonSchemaColumns(JsonSchema validationSchema) {
		if (validationSchema == null || validationSchema.getProperties() == null) {
			return Collections.emptyList();
		}
		return validationSchema
				.getProperties()
				.entrySet()
				.stream()
				.map(e -> toColumnModel(e.getKey(), e.getValue()))
				.toList();
	}

	/**
	 * Map a single JSON Schema property to a {@link ColumnModel}. Scalar types map
	 * to their column equivalents; length-constrained strings map to strings, arrays
	 * map to a string list; objects, nulls, untyped properties and non-length-constrained
	 * strings map to MEDIUMTEXT.
	 *
	 * @param name     the property (column) name
	 * @param property the property's JSON Schema
	 * @return a ColumnModel for the property
	 */
	static ColumnModel toColumnModel(String name, JsonSchema property) {
		ColumnModel column = new ColumnModel().setName(name);

		Type type = property == null ? null : property.getType();
		if (type == null) {
			return column.setColumnType(ColumnType.MEDIUMTEXT);
		}
		return switch (type) {
			case integer -> column.setColumnType(ColumnType.INTEGER);
			case number -> column.setColumnType(ColumnType.DOUBLE);
			case _boolean -> column.setColumnType(ColumnType.BOOLEAN);
			case array -> {
				column = toColumnModel(name, property.getItems());
				if (column.getColumnType().equals(ColumnType.STRING)) {
					column.setColumnType(ColumnType.STRING_LIST);
				} else if (column.getColumnType().equals(ColumnType.INTEGER)) {
					column.setColumnType(ColumnType.INTEGER_LIST);
				} else if (column.getColumnType().equals(ColumnType.BOOLEAN)) {
					column.setColumnType(ColumnType.BOOLEAN_LIST);
				} else {
					column.setColumnType(ColumnType.MEDIUMTEXT);
				}
				yield column;
			}
			case string -> {
				if (property.getMaxLength() != null) {
					yield column.setColumnType(ColumnType.STRING).setMaximumSize(property.getMaxLength());
				}
				yield column.setColumnType(ColumnType.MEDIUMTEXT);
			}
			default -> column.setColumnType(ColumnType.MEDIUMTEXT);
		};
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

	public Optional<JsonSchema> getBoundValidationSchema(String entityId) {
		return entityManager.findBoundSchema(entityId)
				.map(binding -> binding.getJsonSchemaVersionInfo().get$id())
				.map(jsonSchemaManager::getValidationSchema);
	}

}
