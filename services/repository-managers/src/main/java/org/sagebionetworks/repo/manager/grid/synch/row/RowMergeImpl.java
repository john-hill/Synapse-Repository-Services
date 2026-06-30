package org.sagebionetworks.repo.manager.grid.synch.row;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.sagebionetworks.repo.manager.grid.internal.replica.change.DeleteArrayNodeChange;
import org.sagebionetworks.repo.manager.grid.internal.replica.change.IntendedChangePublisher;
import org.sagebionetworks.repo.manager.grid.internal.replica.change.UpdateRowChange;
import org.sagebionetworks.repo.manager.grid.internal.replica.model.Column;
import org.sagebionetworks.repo.manager.grid.internal.replica.model.SynapseRow;
import org.sagebionetworks.repo.manager.grid.synch.core.SynchronizationLogic;
import org.sagebionetworks.repo.manager.grid.synch.handler.CopyHandler;
import org.sagebionetworks.repo.manager.grid.synch.handler.SourceHandler;
import org.sagebionetworks.repo.manager.grid.synch.io.RowSourceItem;
import org.sagebionetworks.repo.manager.grid.synch.io.RowSourceItemReference;
import org.sagebionetworks.repo.model.UnauthorizedException;
import org.sagebionetworks.repo.model.grid.patch.ConValue;
import org.sagebionetworks.repo.model.grid.patch.LogicalTimestamp;
import org.sagebionetworks.repo.web.NotFoundException;

import com.google.common.base.Functions;

/**
 * Implementation of {@link RowMerge} that performs cell-level conflict
 * resolution when copy rows and source rows don't match during Phase 2 row
 * synchronization, and applies the merged result both to the source and to
 * the grid (copy).
 *
 * <p>
 * This class implements a nested synchronization strategy: when two rows
 * conflict (different row hashes), it recursively applies the
 * {@link SynchronizationLogic#synchronize} algorithm at the cell level, treating
 * individual cells as items to be synchronized. User changes to specific cells
 * take precedence (and are written back to the source) while external source
 * changes to other cells are pulled into the copy.
 *
 * <p>
 * Error handling: If the source row is deleted or becomes unauthorized between
 * reading and writing (race condition), the copy row is deleted to maintain
 * consistency; a non-fatal write failure leaves the copy row as-is.
 *
 * <p>
 * When {@code preserveUserAttribution == true} (e.g. a PULL) the cells the user changed
 * are <em>not</em> rewritten in the grid, so their user-owned CRDT nodes (and
 * thus change-attribution) survive subsequent syncs.
 */
public class RowMergeImpl implements RowMerge {

	private static final Logger log = LogManager.getLogger(RowMergeImpl.class);

	private final SynchronizationLogic logic;
	private final SourceHandler sourceHandler;
	private final IntendedChangePublisher intendedChangePublisher;
	private final LogicalTimestamp rowsArrayId;
	private final Map<String, Column> columnNameMap;
	private final boolean preserveUserAttribution;

	/**
	 * Creates a new row merge implementation for cell-level conflict resolution.
	 *
	 * @param logic                   the synchronization logic for recursive
	 *                                cell-level synchronization
	 * @param sourceHandler           the handler for applying cell changes to the
	 *                                source and observing surviving rows
	 * @param intendedChangePublisher the publisher for applying changes to the copy
	 *                                replica
	 * @param copyReader              the handler for reading CRDT metadata (rows
	 *                                array ID)
	 * @param finalSchema             the synchronized schema after Phase 1, used to
	 *                                map column names to vector indices
	 * @param preserveUserAttribution when true (PULL), cells the user changed are
	 *                                not rewritten in the grid, preserving their
	 *                                user-owned CRDT nodes (and thus attribution)
	 */
	public RowMergeImpl(SynchronizationLogic logic, SourceHandler sourceHandler,
			IntendedChangePublisher intendedChangePublisher, CopyHandler copyReader, List<Column> finalSchema,
			boolean preserveUserAttribution) {
		this.logic = logic;
		this.sourceHandler = sourceHandler;
		this.intendedChangePublisher = intendedChangePublisher;
		this.rowsArrayId = copyReader.getHeader().getRowsId();
		this.columnNameMap = finalSchema.stream().collect(Collectors.toMap(Column::getName, Functions.identity()));
		this.preserveUserAttribution = preserveUserAttribution;
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
	 * @param rowKey        the source system's identifier for this row
	 * @param copyItem      the row from the copy (CRDT replica)
	 * @param sourceItemRef the row header from the source (fetches actual row data)
	 */
	@Override
	public void merge(String rowKey, RowCopyItem copyItem, RowSourceItemReference sourceItemRef) {

		RowSourceItem sourceItem = sourceItemRef.fetchRow();
		CellCopyImpl cellCopy = new CellCopyImpl(copyItem, columnNameMap.keySet());
		CellSourceImpl cellSource = new CellSourceImpl(sourceItem);

		// Cells the user changed that diverge from the source (resolved via the
		// wasChangedByUser branch). On a PULL these are preserved rather than rewritten,
		// so their user-owned CRDT nodes keep their attribution.
		Set<String> userWonCells = new HashSet<>();

		// Synchronize cells using nested application of SynchronizationLogic
		logic.synchronize(cellCopy, cellSource, (key, copyCellItem, sourceCellItem) -> {
			// Cell-level merge: user changes win, otherwise take source value
			cellCopy.getMergedCells().put(copyCellItem.getName(),
					copyCellItem.wasChangedByUser() ? copyCellItem.getValue() : sourceCellItem.getValue());

			// Track cells changed by user for source update
			if (copyCellItem.wasChangedByUser()) {
				cellSource.getUserChangedCells().put(copyCellItem.getName(), copyCellItem.getValue());
				userWonCells.add(copyCellItem.getName());
			}
		});

		// Apply accumulated user cell changes to source row
		if (!cellSource.getUserChangedCells().isEmpty()) {
			try {
				sourceHandler.applyCellChangesFromCopyToSource(rowKey, cellSource.getUserChangedCells());
			} catch (IllegalArgumentException ex) {
				//
				log.warn("Failed to merge row: {}.  Row will not be reset in the grid.  Error message: {}", rowKey,
						ex.getMessage());
				return;
			} catch (NotFoundException | UnauthorizedException ex) {
				log.warn("Row: {} will be removed from the grid with message: {}", rowKey, ex.getMessage());
				// Source row was deleted or became unauthorized - delete from copy for
				// consistency
				intendedChangePublisher.publish(new DeleteArrayNodeChange(rowsArrayId, copyItem.getRgaNodeId()));
				return;
			}
		}

		// Reset copy row with merged result, then report it as a surviving row.
		Set<String> excludedCells = preserveUserAttribution ? userWonCells : Set.of();
		resetCopyRow(copyItem.getVectorNodeId(), cellCopy.getMergedCells(), excludedCells, copyItem.getMetadataNodeId(),
				sourceItem.getSynapseRow().map(SynapseRow::toConValue).orElse(null));
		sourceHandler.onSurvivingRow(cellCopy.getMergedCells());
	}

	/**
	 * Resets a copy row with the merged cell values by publishing an update change
	 * to the CRDT replica, omitting any preserved (user-winning) cells so their
	 * existing user-owned CRDT nodes — and thus their change-attribution — survive.
	 * When every writable cell is preserved there is nothing to rewrite, so no
	 * (empty) update is published. Maps column names to vector indices using the
	 * synchronized schema to construct the CRDT update operation.
	 *
	 * @param vectorNodeId       the vector clock node ID of the row to update
	 * @param mergeCells         the merged cell values (column name to value map)
	 * @param excludedCells      cells to omit from the rewrite (preserved user cells)
	 * @param metadataNodeId     the metadata node ID
	 * @param synapseRowConValue the Synapse row metadata value
	 */
	void resetCopyRow(LogicalTimestamp vectorNodeId, Map<String, ConValue> mergeCells, Set<String> excludedCells,
			LogicalTimestamp metadataNodeId, ConValue synapseRowConValue) {
		List<ConValue> values = new ArrayList<>();
		List<Integer> valueIndex = new ArrayList<>();
		for (Entry<String, ConValue> e : mergeCells.entrySet()) {
			if (excludedCells.contains(e.getKey())) {
				continue;
			}
			Column column = columnNameMap.get(e.getKey());
			if (column != null) {
				values.add(e.getValue());
				valueIndex.add(column.getVectorIndex());
			}
		}
		if (values.isEmpty() && !excludedCells.isEmpty()) {
			// Every writable cell was a preserved user cell; nothing to rewrite.
			return;
		}
		intendedChangePublisher.publish(new UpdateRowChange(vectorNodeId, values, valueIndex.toArray(Integer[]::new),
				metadataNodeId, synapseRowConValue));
	}

}
