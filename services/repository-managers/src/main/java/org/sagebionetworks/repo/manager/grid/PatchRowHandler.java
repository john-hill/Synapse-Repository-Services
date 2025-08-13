package org.sagebionetworks.repo.manager.grid;

import org.sagebionetworks.repo.manager.grid.row.translator.ColumnTypeToConType;
import org.sagebionetworks.repo.manager.grid.row.translator.Translator;
import org.sagebionetworks.repo.model.dao.table.RowHandler;
import org.sagebionetworks.repo.model.grid.patch.ConType;
import org.sagebionetworks.repo.model.grid.patch.ConValue;
import org.sagebionetworks.repo.model.grid.patch.LogicalTimestamp;
import org.sagebionetworks.repo.model.grid.patch.Patch;
import org.sagebionetworks.repo.model.grid.patch.compact.PatchCompactSerializable;
import org.sagebionetworks.repo.model.grid.patch.operation.InsertArray;
import org.sagebionetworks.repo.model.grid.patch.operation.NewArray;
import org.sagebionetworks.repo.model.grid.patch.operation.NewConstant;
import org.sagebionetworks.repo.model.grid.patch.operation.NewObject;
import org.sagebionetworks.repo.model.grid.patch.operation.NewVector;
import org.sagebionetworks.repo.model.grid.patch.operation.builder.Operations;
import org.sagebionetworks.repo.model.table.ColumnModel;
import org.sagebionetworks.repo.model.table.Row;
import org.sagebionetworks.util.ValidateArgument;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A handler that can build a series of patches from a table row query.
 */
public class PatchRowHandler implements RowHandler {

	private final PatchStore patchStore;
	private final String sessionId;
	private final int rowsPerPatch;
	private final Translator[] translators;
	private final LogicalTimestamp rowsArrayRef;
	private int rowCount;
	private Patch currentPatch;
	private LogicalTimestamp lastRowRef;

	public PatchRowHandler(PatchStore patchStore, String sessionId, Long replicaId, List<ColumnModel> schema,
			Long maxRowSizeBytes) {
		super();
		ValidateArgument.required(patchStore, "patchStore");
		this.patchStore = patchStore;
		this.sessionId = sessionId;
		this.rowsPerPatch = PatchUtils.calculateRowsPerPatch(maxRowSizeBytes);

		this.currentPatch = new Patch()
				.setPatchId(new LogicalTimestamp().setReplicaId(replicaId).setSequenceNumber(1L));

		// initialize an empty document

		LogicalTimestamp rootObjectOperationId = currentPatch.addNewOperation(Operations.newObject());
		LogicalTimestamp documentVersionOperationId = currentPatch.addNewOperation(Operations.newConstant().setValue(new ConValue(ConType.STRING, "0.1.0")));
		LogicalTimestamp columnNamesOperationId = currentPatch.addNewOperation(Operations.newVector());
		LogicalTimestamp columnOrderOperationId = currentPatch.addNewOperation(Operations.newArray());
		LogicalTimestamp rowsOperationId = currentPatch.addNewOperation(Operations.newArray());
		rowsArrayRef = rowsOperationId;
		lastRowRef = rowsOperationId;
		Map<String, LogicalTimestamp> objectMap = new LinkedHashMap<>();
		objectMap.put("doc_version", documentVersionOperationId);
		objectMap.put("columnNames", columnNamesOperationId);
		objectMap.put("columnOrder", columnOrderOperationId);
		objectMap.put("rows", rowsOperationId);
		currentPatch.addNewOperation(
				Operations.insertObject().setObjectId(rootObjectOperationId).setMap(objectMap)
		);
		currentPatch.addNewOperation(Operations.insertValue()
				.setValueId(new LogicalTimestamp().setReplicaId(0L).setSequenceNumber(0L))
				.setReferenceId(rootObjectOperationId)
		);

		if (!schema.isEmpty()) {
			translators = new Translator[schema.size()];
			// build the column names from the schema
			Map<Integer, LogicalTimestamp> columnNameMap = new LinkedHashMap<>();
			List<LogicalTimestamp> indexList = new ArrayList<>();
			for (int i = 0; i < schema.size(); i++) {
				ColumnModel cm = schema.get(i);
				// column name
				LogicalTimestamp nameConstRef = currentPatch.addNewOperation(Operations.newConstant().setValue(new ConValue(ConType.STRING, cm.getName())));
				columnNameMap.put(i, nameConstRef);
				// column index
                LogicalTimestamp columnIndexRef = currentPatch.addNewOperation(Operations.newConstant().setValue(new ConValue(ConType.LONG, i)));
				indexList.add(columnIndexRef);

				translators[i] = ColumnTypeToConType.lookUpType(cm.getColumnType()).getTranslator();

			}
			currentPatch.addNewOperation(Operations.insertVector()
					.setVectorId(columnNamesOperationId)
					.setMap(columnNameMap));
			currentPatch.addNewOperation(Operations.insertArray().setArrayId(columnOrderOperationId)
					.setReferenceId(columnNamesOperationId).setElementIds(indexList));
		} else {
			translators = new Translator[0];
		}
	}


	/**
	 * Adds the RowMetadata object to the patch. The row metadata has the following pseudo-schema. Fields that can be
	 * undefined are not guaranteed to be present.
	 * ```
	 * obj({
	 *     synapseRow: SynapseRowMetadataSchema | undefined
	 * })
	 * ```
	 *
	 * @param row the table query Row for which metadata should be extracted
	 * @return a reference to the object node containing the row metadata.
	 */
	private LogicalTimestamp getRowMetadata(Row row) {
		LogicalTimestamp metadataObjectForRowRef = currentPatch.addNewOperation(Operations.newObject());

		// Create the "synapseRow" object
		LogicalTimestamp synapseRowMetadataRef = getSynapseRowMetadata(row);

		// Attach the "synapseRow" object to the row metadata map
		Map<String, LogicalTimestamp> metadataMapForRow = new LinkedHashMap<>();
		metadataMapForRow.put("synapseRow", synapseRowMetadataRef);

		currentPatch.addNewOperation(Operations.insertObject()
				.setObjectId(metadataObjectForRowRef)
				.setMap(metadataMapForRow)
		);

		return metadataObjectForRowRef;

	}

	/**
	 * Creates the SynapseRowMetadata object. The row metadata has the following pseudo-schema. Fields are not
	 * guaranteed to be present.
	 * ```
	 * s.obj({
	 *     rowId: s.const(double)
	 *     versionNumber: s.const(double)
	 *     etag: s.const(str)
	 * })
	 * ```
	 *
	 * @param row the table query Row for which Synapse Row metadata should be created
	 * @return a reference to the object node containing the synapseRowMetadata
	 */
	private LogicalTimestamp getSynapseRowMetadata(Row row) {
		// Create the `synapseRow` object
        LogicalTimestamp synapseRowMetadataRef = currentPatch.addNewOperation(Operations.newObject());

		Map<String, LogicalTimestamp> synapseRowMetadataObjectMap = new LinkedHashMap<>();
		Long rowId = row.getRowId();
		if (rowId != null) {
            LogicalTimestamp rowIdConstRef = currentPatch.addNewOperation(Operations.newConstant().setValue(new ConValue(ConType.LONG, rowId)));
			synapseRowMetadataObjectMap.put("rowId", rowIdConstRef);
		}

		Long versionNumber = row.getVersionNumber();
		if (versionNumber != null) {
            LogicalTimestamp versionNumberConstRef = currentPatch.addNewOperation(Operations.newConstant().setValue(new ConValue(ConType.LONG, versionNumber)));
			synapseRowMetadataObjectMap.put("versionNumber", versionNumberConstRef);
		}

		String etag = row.getEtag();
		if (etag != null) {
            LogicalTimestamp etagConstRef = currentPatch.addNewOperation(Operations.newConstant().setValue(new ConValue(ConType.STRING, etag)));
			synapseRowMetadataObjectMap.put("etag", etagConstRef);
		}

		if (!synapseRowMetadataObjectMap.isEmpty()) {
			// fill the `synapseRow` object
			currentPatch.addNewOperation(Operations.insertObject()
                    .setObjectId(synapseRowMetadataRef)
					.setMap(synapseRowMetadataObjectMap));
		}
		return synapseRowMetadataRef;
	}

	/**
	 * Creates and returns a NewVector containing the values for the row.
	 *
	 * @param row the table query Row for which Synapse Row metadata should be created
	 * @return a reference to the vector node containing the row values.
	 */
	private LogicalTimestamp getRowData(Row row) {
		LogicalTimestamp rowValuesVectorRef = currentPatch.addNewOperation(Operations.newVector());
		Map<Integer, LogicalTimestamp> cellValues = new LinkedHashMap<>();
		for (int i = 0; i < row.getValues().size(); i++) {
			String cellValue = row.getValues().get(i);
            LogicalTimestamp conRef = currentPatch.addNewOperation(
					Operations.newConstant().setValue(translators[i].translateNullable(cellValue))
			);
			cellValues.put(i, conRef);
		}
		currentPatch.addNewOperation(Operations.insertVector()
				.setVectorId(rowValuesVectorRef)
				.setMap(cellValues)
		);

		return rowValuesVectorRef;
	}

	@Override
	public void nextRow(Row row) {
		this.rowCount++;
		LogicalTimestamp rowObjectRef = currentPatch.addNewOperation(Operations.newObject());

        LogicalTimestamp rowDataRef = getRowData(row);
        LogicalTimestamp rowMetadataRef = getRowMetadata(row);

		Map<String, LogicalTimestamp> rowObjectMap = new LinkedHashMap<>();
		rowObjectMap.put("data", rowDataRef);
		rowObjectMap.put("metadata", rowMetadataRef);
		currentPatch.addNewOperation(Operations.insertObject()
				.setObjectId(rowObjectRef)
				.setMap(rowObjectMap)
		);

		LogicalTimestamp insertArrayRef = currentPatch.addNewOperation(Operations.insertArray()
				.setArrayId(rowsArrayRef)
				.setReferenceId(lastRowRef)
				.setElementIds(Collections.singletonList(rowObjectRef))
		);

		lastRowRef = insertArrayRef;

		if (this.rowCount >= rowsPerPatch) {
			saveCurrentPatch();
		}
	}

	void saveCurrentPatch() {
		if (currentPatch.getOperations() == null || currentPatch.getOperations().isEmpty()) {
			// do not save an empty patch
			return;
		}
		// save the current patch and create a new one.
		String patchBody = PatchCompactSerializable.serialize(currentPatch).toString();
		patchStore.savePatch(sessionId, currentPatch.getPatchId(), patchBody);

		// start a new patch with an ID = previous.clock+1;
		currentPatch = new Patch()
				.setPatchId(LogicalTimestamp.newIncrement(currentPatch.getPatchId(), currentPatch.getSpan()));
		this.rowCount = 0;
	}

	@Override
	public void close() throws IOException {
		saveCurrentPatch();
	}

	public int getRowsPerPatch() {
		return rowsPerPatch;
	}

}
