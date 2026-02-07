package org.sagebionetworks.repo.manager.grid.synch.row;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.sagebionetworks.repo.manager.grid.internal.replica.change.DeleteRowChange;
import org.sagebionetworks.repo.manager.grid.internal.replica.change.IntendedChangePublisher;
import org.sagebionetworks.repo.manager.grid.internal.replica.change.UpdateRowChange;
import org.sagebionetworks.repo.manager.grid.internal.replica.model.Column;
import org.sagebionetworks.repo.manager.grid.synch.SynchronizationLogic;
import org.sagebionetworks.repo.manager.grid.synch.core.Copy;
import org.sagebionetworks.repo.manager.grid.synch.core.Source;
import org.sagebionetworks.repo.manager.grid.synch.handler.CopyHandler;
import org.sagebionetworks.repo.manager.grid.synch.handler.SourceHandler;
import org.sagebionetworks.repo.manager.grid.synch.io.RowHeader;
import org.sagebionetworks.repo.model.UnauthorizedException;
import org.sagebionetworks.repo.model.grid.patch.ConType;
import org.sagebionetworks.repo.model.grid.patch.ConValue;
import org.sagebionetworks.repo.model.grid.patch.LogicalTimestamp;
import org.sagebionetworks.repo.web.NotFoundException;

import com.google.common.base.Functions;

/**
 * Implementation of {@link RowMerge} that performs cell-level conflict
 * resolution when copy rows and source rows don't match during Phase 2 row
 * synchronization.
 *
 * <p>
 * This class implements a nested synchronization strategy: when two rows
 * conflict (different row hashes), it recursively applies the
 * {@link SynchronizationLogic#synchronize} algorithm at the cell level,
 * treating individual cells as items to be synchronized. This enables
 * fine-grained conflict resolution where user changes to specific cells take
 * precedence while external source changes to other cells are pulled into the
 * copy.
 *
 * <p>
 * The merge process:
 * <ol>
 * <li>Creates a cell-level {@link Copy} and {@link Source} from the row's
 * cells</li>
 * <li>Applies {@link SynchronizationLogic#synchronize} to compare cells
 * individually</li>
 * <li>For each cell conflict: user changes win (push to source), external
 * changes pulled otherwise</li>
 * <li>Applies accumulated user cell changes to the source via
 * {@link SourceHandler#applyCellChangesFromCopyToSource}</li>
 * <li>Resets the copy row with the merged result via
 * {@link IntendedChangePublisher}</li>
 * </ol>
 *
 * <p>
 * Error handling: If the source row is deleted or becomes unauthorized between
 * reading and writing (race condition), the copy row is deleted to maintain
 * consistency.
 */
public class RowMergeImpl implements RowMerge {

	private final SynchronizationLogic logic;
	private final SourceHandler sourceHandler;
	private final IntendedChangePublisher intendedChangePublisher;
	private final LogicalTimestamp rowsArrayId;
	private final Map<String, Column> columnNameMap;

	/**
	 * Creates a new row merge implementation for cell-level conflict resolution.
	 *
	 * @param logic                   the synchronization logic for recursive
	 *                                cell-level synchronization
	 * @param sourceHandler           the handler for applying cell changes to the
	 *                                source
	 * @param intendedChangePublisher the publisher for applying changes to the copy
	 *                                replica
	 * @param copyReader              the handler for reading CRDT metadata (rows
	 *                                array ID)
	 * @param finalSchema             the synchronized schema after Phase 1, used to
	 *                                map column names to vector indices
	 */
	public RowMergeImpl(SynchronizationLogic logic, SourceHandler sourceHandler,
			IntendedChangePublisher intendedChangePublisher, CopyHandler copyReader, List<Column> finalSchema) {
		this.logic = logic;
		this.sourceHandler = sourceHandler;
		this.intendedChangePublisher = intendedChangePublisher;
		this.rowsArrayId = copyReader.getHeader().getRowsId();
		this.columnNameMap = finalSchema.stream().collect(Collectors.toMap(Column::getName, Functions.identity()));
	}

	/**
	 * Merges conflicting copy and source rows by performing cell-level
	 * synchronization. This method is called by {@link SynchronizationLogic} during
	 * Phase 1 when a row exists in both copy and source but they don't match.
	 *
	 * <p>
	 * The merge strategy:
	 * <ol>
	 * <li>Initialize merge result with copy cells</li>
	 * <li>Track user-deleted cells (cells changed by user to null/undefined)</li>
	 * <li>Create cell-level Copy/Source adapters around the row's cells</li>
	 * <li>Apply {@link SynchronizationLogic#synchronize} recursively at cell
	 * level</li>
	 * <li>For matching cells: no action</li>
	 * <li>For conflicting cells: user changes win (push to source)</li>
	 * <li>For cells only in copy: push additions if user-changed, remove if
	 * source-deleted</li>
	 * <li>For cells only in source: remove if user-deleted, add to copy
	 * otherwise</li>
	 * <li>Apply accumulated user changes to source row</li>
	 * <li>Reset copy row with merged result</li>
	 * <li>If source row deleted/unauthorized: delete copy row for consistency</li>
	 * </ol>
	 *
	 * @param rowKey     the source system's identifier for this row
	 * @param copyItem   the row from the copy (CRDT replica)
	 * @param sourceItem the row header from the source (fetches actual row data)
	 */
	@Override
	public void merge(String rowKey, CopyRow copyItem, RowHeader sourceItem) {
		// Track cells for the merged result - starts with all copy cells
		Map<String, ConValue> mergeCells = copyItem.getCells().stream()
				.collect(Collectors.toMap(CopyCell::getName, CopyCell::getValue));

		// Track user-deleted cells (cells changed by user to null/undefined)
		Set<String> userDeletedCells = copyItem.getCells().stream()
				.filter(cell -> cell.wasChangedByUser() && cell.getValue() != null
						&& (ConType.UNDEFINED.equals(cell.getValue().getType())
								|| ConType.NULL.equals(cell.getValue().getType())))
				.map(CopyCell::getName).collect(java.util.stream.Collectors.toSet());
		Map<String, ConValue> userChangedCells = new HashMap<>();

		// Create Copy implementation for cells - adapts row cells to Copy interface
		Copy<CopyCell, CellSourceItem> cellCopy = new Copy<CopyCell, CellSourceItem>() {
			@Override
			public Stream<CopyCell> streamItems() {
				return copyItem.getCells().stream();
			}

			@Override
			public boolean wasDeletedByUser(String key) {
				return userDeletedCells.contains(key);
			}

			@Override
			public void removeItem(CopyCell item) {
				// Cell was removed from source - remove from merged result
				mergeCells.remove(item.getName());
			}

			@Override
			public void addItem(CellSourceItem item) {
				// Cell was added to source - add to merged result
				mergeCells.put(item.getColumnName(), item.getValue());
			}
		};

		// Create Source implementation for cells - adapts source row data to Source
		// interface
		Map<String, CellSourceItem> sourceMap = new HashMap<>();
		for (Entry<String, ConValue> e : sourceItem.fetchRow().getData().entrySet()) {
			sourceMap.put(e.getKey(), new CellSourceItem().setColumnName(e.getKey()).setValue(e.getValue()));
		}

		Source<CopyCell, CellSourceItem> cellSource = new Source<CopyCell, CellSourceItem>() {
			@Override
			public String getKey(CopyCell item) {
				return item.getName();
			}

			@Override
			public Optional<CellSourceItem> consume(String key) {
				return Optional.ofNullable(sourceMap.remove(key));
			}

			@Override
			public Stream<CellSourceItem> streamRemaining() {
				return sourceMap.values().stream();
			}

			@Override
			public void addItem(CopyCell toAdd) {
				// Cell exists only in copy - push if changed by user
				if (toAdd.wasChangedByUser()) {
					userChangedCells.put(toAdd.getName(), toAdd.getValue());
				}
			}

			@Override
			public void removeItem(CellSourceItem toRemove) {
				// Cell deleted by user - track deletion for source update
				userChangedCells.put(toRemove.getColumnName(), null);
			}

			@Override
			public boolean matches(CopyCell copyItem, CellSourceItem sourceItem) {
				return Objects.equals(copyItem.getValue(), sourceItem.getValue());
			}
		};

		// Synchronize cells using nested application of SynchronizationLogic
		logic.synchronize(cellCopy, cellSource, (key, copyCellItem, sourceCellItem) -> {
			// Cell-level merge: user changes win, otherwise take source value
			mergeCells.put(copyCellItem.getName(),
					copyCellItem.wasChangedByUser() ? copyCellItem.getValue() : sourceCellItem.getValue());

			// Track cells changed by user for source update
			if (copyCellItem.wasChangedByUser()) {
				userChangedCells.put(copyCellItem.getName(), copyCellItem.getValue());
			}
		});

		try {
			// Apply accumulated user cell changes to source row
			if (!userChangedCells.isEmpty()) {
				sourceHandler.applyCellChangesFromCopyToSource(rowKey, userChangedCells);
			}
			// Reset copy row with merged result
			resetCopyRow(copyItem.getVectorNodeId(), mergeCells);
		} catch (NotFoundException | UnauthorizedException e) {
			// Source row was deleted or became unauthorized - delete from copy for
			// consistency
			intendedChangePublisher.publish(new DeleteRowChange(rowsArrayId, copyItem.getRgaNodeId()));
		}
	}

	/**
	 * Resets a copy row with the merged cell values by publishing an update change
	 * to the CRDT replica. Maps column names to vector indices using the
	 * synchronized schema to construct the CRDT update operation.
	 *
	 * @param vectorNodeId the vector clock node ID of the row to update
	 * @param mergeCells   the merged cell values (column name to value map)
	 */
	void resetCopyRow(LogicalTimestamp vectorNodeId, Map<String, ConValue> mergeCells) {
		List<ConValue> values = new ArrayList<>();
		List<Integer> valueIndex = new ArrayList<>();
		for (Entry<String, ConValue> e : mergeCells.entrySet()) {
			Column column = columnNameMap.get(e.getKey());
			values.add(e.getValue());
			valueIndex.add(column.getVectorIndex());
		}
		intendedChangePublisher.publish(new UpdateRowChange(vectorNodeId, values, valueIndex.toArray(Integer[]::new)));
	}

}
