package org.sagebionetworks.repo.manager.grid.synch;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.bouncycastle.util.Arrays;
import org.sagebionetworks.repo.manager.grid.PatchUtils;
import org.sagebionetworks.repo.manager.grid.internal.replica.change.AddColumn;
import org.sagebionetworks.repo.manager.grid.internal.replica.change.DeleteColumn;
import org.sagebionetworks.repo.manager.grid.internal.replica.change.DeleteRowChange;
import org.sagebionetworks.repo.manager.grid.internal.replica.change.InsertRowChange;
import org.sagebionetworks.repo.manager.grid.internal.replica.change.IntendedChangePublisher;
import org.sagebionetworks.repo.manager.grid.internal.replica.change.PatchBuilderPublisher;
import org.sagebionetworks.repo.manager.grid.internal.replica.change.UpdateRowChange;
import org.sagebionetworks.repo.manager.grid.internal.replica.model.Column;
import org.sagebionetworks.repo.manager.grid.internal.replica.model.GridHeader;
import org.sagebionetworks.repo.model.UnauthorizedException;
import org.sagebionetworks.repo.model.UserInfo;
import org.sagebionetworks.repo.model.dao.asynch.AsyncJobProgressCallback;
import org.sagebionetworks.repo.model.grid.GridConnectionInfo;
import org.sagebionetworks.repo.model.grid.GridSession;
import org.sagebionetworks.repo.model.grid.patch.ConType;
import org.sagebionetworks.repo.model.grid.patch.ConValue;
import org.sagebionetworks.repo.model.grid.patch.LogicalTimestamp;
import org.sagebionetworks.repo.model.table.ColumnModel;
import org.sagebionetworks.repo.web.NotFoundException;
import org.springframework.stereotype.Service;

@Service
public class GridSynchronizationManagerImpl implements GridSynchronizationManager {

	private final PatchBuilderPublisher patchBuilderPublisher;
	private final SourceHandlerProvdier sourceHandlerProvdier;
	private final CopyReaderProvider copyReaderProvider;

	public GridSynchronizationManagerImpl(PatchBuilderPublisher patchBuilderPublisher,
			SourceHandlerProvdier synchronizeHandlerProvdier, CopyReaderProvider copyReaderProvider) {
		this.patchBuilderPublisher = patchBuilderPublisher;
		this.sourceHandlerProvdier = synchronizeHandlerProvdier;
		this.copyReaderProvider = copyReaderProvider;
	}

	@Override
	public void synchronizeCopyWithSource(AsyncJobProgressCallback callback, UserInfo user, GridSession session)
			throws Exception {
		try (CopyReader copyReader = copyReaderProvider.createCopyReader(session);
				SourceHandler sourceHandler = sourceHandlerProvdier.createNewProvider(callback, user, session,
						copyReader.getGridSource());
				RowReader sourceReader = sourceHandler.getSourceRowReader()) {

			try (IntendedChangePublisher icp = newIntendedChangePublisher(copyReader)) {
				List<Column> finalSchema = synchronizeSchema(sourceHandler, copyReader, icp);
				Map<String, Column> columnNameMap = toColumnMap(finalSchema, Column::getName);

				synchronizeExistingRows(copyReader, sourceHandler, icp, columnNameMap, sourceReader);
				addRemainingSourceRows(copyReader, sourceReader, icp, columnNameMap);
			}
		}
	}

	private <K> Map<K, Column> toColumnMap(List<Column> columns, Function<Column, K> keyMapper) {
		return columns.stream().collect(Collectors.toMap(keyMapper, Function.identity()));
	}

	private List<Column> synchronizeSchema(SourceHandler sourceHandler, CopyReader copyReader,
			IntendedChangePublisher icp) {
		GridHeader header = copyReader.getHeader();
		Map<String, ColumnModel> sourceSchema = toMap(sourceHandler.getCurrentSourceSchema(), ColumnModel::getName);
		List<Column> finalSchema = new ArrayList<>();
		int maxColumnIndex = header.getOrderedColumns().stream().mapToInt(Column::getVectorIndex).max().orElse(0);

		for (Column copyColumn : header.getOrderedColumns()) {
			ColumnModel sourceColumn = sourceSchema.remove(copyColumn.getName());
			if (sourceColumn == null) {
				handleMissingSourceColumn(sourceHandler, copyReader, icp, copyColumn, finalSchema);
			} else {
				finalSchema.add(copyColumn);
			}
		}

		maxColumnIndex = addNewColumns(sourceSchema, copyReader, icp, finalSchema, maxColumnIndex);
		updateColumnNames(copyReader, icp, finalSchema);
		return finalSchema;
	}

	private <K, V> Map<K, V> toMap(List<V> items, Function<V, K> keyMapper) {
		return items.stream().collect(Collectors.toMap(keyMapper, Function.identity()));
	}

	private void handleMissingSourceColumn(SourceHandler sourceHandler, CopyReader copyReader,
			IntendedChangePublisher icp, Column copyColumn, List<Column> finalSchema) {
		GridHeader header = copyReader.getHeader();
		GridConnectionInfo con = copyReader.getConnectionInfo();
		if (wasChangedInCopy(copyColumn.getColumnOrderNodeId().getRep(), con.getReplicaId())) {
			sourceHandler.addColumnToSource(copyColumn.getName());
			finalSchema.add(copyColumn);
		} else {
			icp.publish(new DeleteColumn(header.getColumnOrderArrId(), copyColumn.getColumnOrderNodeIdAsLogical()));
		}
	}

	private int addNewColumns(Map<String, ColumnModel> sourceSchema, CopyReader copyReader, IntendedChangePublisher icp,
			List<Column> finalSchema, int maxColumnIndex) {
		GridHeader header = copyReader.getHeader();
		for (Entry<String, ColumnModel> e : sourceSchema.entrySet()) {
			icp.publish(new AddColumn(header.getColumnOrderArrId(), new ConValue(ConType.LONG, maxColumnIndex)));
			finalSchema.add(new Column().setName(e.getKey()).setVectorIndex(maxColumnIndex));
			maxColumnIndex++;
		}
		return maxColumnIndex;
	}

	private void updateColumnNames(CopyReader copyReader, IntendedChangePublisher icp, List<Column> finalSchema) {
		GridHeader header = copyReader.getHeader();
		boolean hasNewColumns = finalSchema.stream().anyMatch(column -> column.getColumnOrderNodeId() == null);
		if (hasNewColumns) {
			List<ConValue> updatedValues = finalSchema.stream().map(c -> new ConValue(ConType.STRING, c.getName()))
					.collect(Collectors.toList());
			Integer[] updatedIndex = finalSchema.stream().map(Column::getVectorIndex).toArray(Integer[]::new);
			icp.publish(new UpdateRowChange(header.getColumnNamesVecId(), updatedValues, updatedIndex));
		}
	}

	private void synchronizeExistingRows(CopyReader copyReader, SourceHandler sourceHandler,
	        IntendedChangePublisher icp, Map<String, Column> columnNameMap, RowReader sourceReader) throws IOException {
	    Iterator<CopyRow> iterator = copyReader.getRows();

	    while (iterator.hasNext()) {
	        CopyRow copyRow = iterator.next();
	        String key = sourceHandler.getRowKey(copyRow);

	        Optional<RowHeader> sourceOp = sourceReader.removeRow(key);
	        if (sourceOp.isPresent()) {
	            RowHeader sourceRowHeader = sourceOp.get();
	            byte[] copyHash = new SynchRow(copyRow.getData(), key).getHash();
	            
	            if (!Arrays.areEqual(sourceRowHeader.getHash(), copyHash)) {
	                handleMatchingRow(sourceHandler, copyReader, icp, columnNameMap, copyRow, sourceRowHeader);
	            }
	        } else {
	            handleMissingSourceRow(sourceHandler, copyReader, icp, copyRow, key);
	        }
	    }
	}

	private void handleMatchingRow(SourceHandler sourceHandler, CopyReader copyReader, IntendedChangePublisher icp,
	        Map<String, Column> columnNameMap, CopyRow copyRow, RowHeader sourceRowHeader)
	        throws IOException {
	    GridConnectionInfo con = copyReader.getConnectionInfo();
	    SynchRow sourceRow = sourceRowHeader.fetchRow();
	    Map<String, ConValue> mergedRow = mergeChanges(con.getReplicaId(), copyRow, sourceRow);
	    Map<String, ConValue> copyOnlyChanges = getCopyOnlyChanges(con.getReplicaId(), copyRow, sourceRow);

	    try {
	        sourceHandler.applyCellChangesFromCopyToSource(sourceRow.getKey(), copyOnlyChanges);
	        publishRowUpdate(icp, copyRow, mergedRow, columnNameMap);
	    } catch (NotFoundException | UnauthorizedException e) {
	        icp.publish(new DeleteRowChange(copyRow.getArrNodeId()));
	    }
	}


	private void publishRowUpdate(IntendedChangePublisher icp, CopyRow copyRow, Map<String, ConValue> mergedRow,
			Map<String, Column> columnNameMap) {
		List<ConValue> values = new ArrayList<>();
		List<Integer> valueIndex = new ArrayList<>();
		for (Entry<String, ConValue> e : mergedRow.entrySet()) {
			Column column = columnNameMap.get(e.getKey());
			values.add(e.getValue());
			valueIndex.add(column.getVectorIndex());
		}
		icp.publish(new UpdateRowChange(copyRow.getArrNodeId(), values, valueIndex.toArray(Integer[]::new)));
	}

	static Map<String, ConValue> getCopyOnlyChanges(Long replicaId, CopyRow copyRow, SynchRow source) {
		Map<String, ConValue> copyChanges = new HashMap<>();
		Map<String, LogicalTimestamp> cellTimestamps = copyRow.getCellTimestamps();

		for (Entry<String, ConValue> e : copyRow.getData().entrySet()) {
			String columnName = e.getKey();
			LogicalTimestamp timestamp = cellTimestamps.get(columnName);
			if (timestamp != null && wasChangedInCopy(timestamp.getReplicaId(), replicaId)) {
				copyChanges.put(columnName, e.getValue());
			}
		}
		return copyChanges;
	}

	private void handleMissingSourceRow(SourceHandler sourceHandler, CopyReader copyReader, IntendedChangePublisher icp,
	        CopyRow copyRow, String key) {
	    GridConnectionInfo con = copyReader.getConnectionInfo();
	    if (wasChangedInCopy(copyRow.getArrNodeId().getReplicaId(), con.getReplicaId())) {
	        SynchRow copy = new SynchRow(copyRow.getData(), key);
	        sourceHandler.addNewRowToSource(copy);
	    } else {
	        icp.publish(new DeleteRowChange(copyRow.getArrNodeId()));
	    }
	}

	private void addRemainingSourceRows(CopyReader copyReader, RowReader sourceReader, IntendedChangePublisher icp,
			Map<String, Column> columnNameMap) throws IOException {
		Iterator<RowHeader> remainingRows = sourceReader.remainingRows();
		LogicalTimestamp rowsArrayId = copyReader.getHeader().getRowsId();
		LogicalTimestamp lastRowId = copyReader.getLastRgaNodeId();

		while (remainingRows.hasNext()) {
			RowHeader remainingRow = remainingRows.next();
			SynchRow toAdd = remainingRow.fetchRow();
			publishInsertRow(icp, rowsArrayId, lastRowId, toAdd, columnNameMap);
		}
	}

	private void publishInsertRow(IntendedChangePublisher icp, LogicalTimestamp rowsArrayId, LogicalTimestamp lastRowId,
			SynchRow toAdd, Map<String, Column> columnNameMap) {
		List<ConValue> values = new ArrayList<>();
		List<Integer> valueIndex = new ArrayList<>();
		for (Entry<String, ConValue> e : toAdd.getData().entrySet()) {
			Column column = columnNameMap.get(e.getKey());
			values.add(e.getValue());
			valueIndex.add(column.getVectorIndex());
		}
		icp.publish(new InsertRowChange(rowsArrayId, lastRowId, values, valueIndex.toArray(Integer[]::new)));
	}

	static Map<String, ConValue> mergeChanges(Long replicaId, CopyRow copyRow, SynchRow source) {
		Map<String, ConValue> merged = new HashMap<>();
		Map<String, ConValue> sourceMap = new HashMap<>(source.getData());
		Map<String, LogicalTimestamp> cellTimestamps = copyRow.getCellTimestamps();

		for (Entry<String, ConValue> e : copyRow.getData().entrySet()) {
			String columnName = e.getKey();
			ConValue copyValue = e.getValue();
			ConValue sourceValue = sourceMap.remove(columnName);
			LogicalTimestamp timestamp = cellTimestamps.get(columnName);

			if (timestamp != null && wasChangedInCopy(timestamp.getReplicaId(), replicaId)) {
				merged.put(columnName, copyValue);
			} else if (sourceValue != null) {
				merged.put(columnName, sourceValue);
			}
		}
		merged.putAll(sourceMap);
		return merged;
	}

	static boolean wasChangedInCopy(Long nodeReplicaId, Long copyReplicaId) {
		return !nodeReplicaId.equals(copyReplicaId);
	}

	IntendedChangePublisher newIntendedChangePublisher(CopyReader copyReader) {
		GridHeader header = copyReader.getHeader();
		GridConnectionInfo connInfo = copyReader.getConnectionInfo();
		return new IntendedChangePublisher(connInfo, header.getClockSequenceMaximum(), patchBuilderPublisher,
				PatchUtils.MAX_CHANGE_SET_SIZE);
	}
}
