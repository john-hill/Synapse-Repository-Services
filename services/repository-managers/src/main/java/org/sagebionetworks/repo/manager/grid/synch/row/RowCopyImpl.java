package org.sagebionetworks.repo.manager.grid.synch.row;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import org.sagebionetworks.repo.manager.grid.internal.replica.change.DeleteArrayNodeChange;
import org.sagebionetworks.repo.manager.grid.internal.replica.change.InsertRowChange;
import org.sagebionetworks.repo.manager.grid.internal.replica.change.IntendedChangePublisher;
import org.sagebionetworks.repo.manager.grid.internal.replica.model.Column;
import org.sagebionetworks.repo.manager.grid.internal.replica.model.SynapseRow;
import org.sagebionetworks.repo.manager.grid.synch.handler.CopyHandler;
import org.sagebionetworks.repo.manager.grid.synch.handler.SourceHandler;
import org.sagebionetworks.repo.manager.grid.synch.io.RowSourceItem;
import org.sagebionetworks.repo.manager.grid.synch.io.RowSourceItemReference;
import org.sagebionetworks.repo.model.grid.patch.ConValue;
import org.sagebionetworks.repo.model.grid.patch.LogicalTimestamp;

import com.google.common.base.Functions;

/**
 * Implementation of {@link RowCopy} that provides access to rows from the CRDT
 * replica during Phase 2 row synchronization and applies the resulting changes
 * directly to the grid (copy).
 *
 * <p>
 * This class bridges the synchronization logic with the underlying CRDT replica
 * by:
 * <ul>
 * <li>Streaming rows from the replica via {@link CopyHandler}</li>
 * <li>Publishing intended changes to the replica via
 * {@link IntendedChangePublisher}</li>
 * <li>Managing CRDT metadata (RGA node IDs) for row ordering and identity</li>
 * <li>Mapping column names to column metadata for proper row construction</li>
 * </ul>
 *
 * <p>
 * Key responsibilities:
 * <ul>
 * <li>Provide streaming access to copy rows during synchronization (memory
 * efficient)</li>
 * <li>Add new rows to the copy when they're added to the source (pulling source
 * additions)</li>
 * <li>Remove rows from the copy when they're deleted from the source (pulling
 * source deletions)</li>
 * <li>Track which rows were deleted by the user (to push deletions to
 * source)</li>
 * <li>Notify the {@link SourceHandler} of every surviving row (so a source
 * that pushes an artifact (e.g. RecordSet PULL_PUSH) can capture the full grid
 * contents)</li>
 * </ul>
 */
public class RowCopyImpl implements RowCopy {

	private final IntendedChangePublisher intendedChangePublisher;
	private final CopyHandler copyHandler;
	private final SourceHandler sourceHandler;
	private final LogicalTimestamp rowsArrayId;
	private final LogicalTimestamp lastRowId;
	private final Map<String, Column> columnNameMap;

	/**
	 * @param finalSchema             the synchronized schema after Phase 1, used to
	 *                                map column names to vector indices
	 * @param intendedChangePublisher the publisher for sending row changes to the
	 *                                CRDT replica
	 * @param copyHandler             the handler for reading rows from the replica
	 * @param sourceHandler           the source handler, used to get information
	 *                                about source data that varies by type
	 */
	public RowCopyImpl(List<Column> finalSchema, IntendedChangePublisher intendedChangePublisher,
			CopyHandler copyHandler, SourceHandler sourceHandler) {
		super();
		this.intendedChangePublisher = intendedChangePublisher;
		this.copyHandler = copyHandler;
		this.sourceHandler = sourceHandler;
		this.rowsArrayId = copyHandler.getHeader().getRowsId();
		this.lastRowId = copyHandler.getLastRowsRgaNodeId();
		this.columnNameMap = finalSchema.stream().collect(Collectors.toMap(Column::getName, Functions.identity()));
	}

	/**
	 * Streams all rows from the copy (CRDT replica) for synchronization with the
	 * source. Uses the {@link CopyHandler} to provide memory-efficient streaming of
	 * rows without loading all data into memory.
	 *
	 * <p>
	 * A frozen row is excluded from the keyed Phase 1 traversal — it is never
	 * matched, merged, or removed — but still survives in the grid, so it is
	 * reported to the source handler as a surviving row (which a push build
	 * includes). A row is frozen when it cannot participate in keyed matching for
	 * either reason:
	 * <ul>
	 * <li>its upsert key is incomplete (see {@link SourceHandler#isFrozenCopyRow}), or</li>
	 * <li>its key duplicates an earlier row in the copy — the first occurrence of a
	 * key is kept and matched, while every later duplicate is frozen.</li>
	 * </ul>
	 *
	 * @return a stream of rows from the copy
	 */
	@Override
	public Stream<RowCopyItem> streamItems() {
		Set<String> seenKeys = new HashSet<>();
		return StreamSupport.stream(Spliterators.spliteratorUnknownSize(copyHandler.getRows(),
				Spliterator.ORDERED | Spliterator.NONNULL | Spliterator.IMMUTABLE), false).filter(item -> {
					// The isFrozenCopyRow check is first so an incomplete-key row never has a key
					// computed; seenKeys.add() returns false when this row's key was already seen,
					// marking it a duplicate of an earlier row. Either way the row is frozen:
					// excluded from Phase 1 but still reported as a surviving row.
					if (sourceHandler.isFrozenCopyRow(item) || !seenKeys.add(sourceHandler.getRowKey(item))) {
						sourceHandler.onSurvivingRow(cellsAsMap(item));
						return false;
					}
					return true;
				});
	}

	/**
	 * Determines whether a row with the given key was deleted by the user in the
	 * copy. A row present in the source but absent from the copy was deleted by the
	 * user iff its key existed in the synced baseline AND the source row has not
	 * changed since then (otherwise the deletion was made against stale data and the
	 * row is re-imported). Delegated to {@link SourceHandler}; sources without a
	 * baseline concept (e.g. entity views) return false.
	 * <p>
	 * This method assumes that the key is already known to exist in the source, but
	 * not in the copy.
	 *
	 * @param key the source identifier of the row to check
	 * @return true if the user deleted this row, false otherwise
	 */
	@Override
	public boolean wasDeletedByUser(String key) {
		return sourceHandler.wasInSyncedBaseline(key) && !sourceHandler.changedSinceBaseline(key);
	}

	/**
	 * Removes a row from the copy by publishing a delete change to the CRDT
	 * replica. Called during synchronization when a row exists in the copy but not
	 * in the source, and the row was not changed by the user (meaning it was
	 * deleted from the source).
	 *
	 * <p>
	 * The delete uses the row's RGA node ID to maintain consistent CRDT semantics
	 * across replicas.
	 *
	 * @param item the row to remove from the copy
	 */
	@Override
	public void removeItem(RowCopyItem item) {
		intendedChangePublisher.publish(new DeleteArrayNodeChange(rowsArrayId, item.getRgaNodeId()));
	}

	/**
	 * Adds a new row to the copy by publishing an insert change to the CRDT
	 * replica. Called during synchronization when a row exists in the source but
	 * not in the copy, and the row was not deleted by the user (meaning it was added
	 * to the source). The pulled row survives, so it is also reported to the source
	 * handler.
	 *
	 *
	 * <p>
	 * The insert operation:
	 * <ul>
	 * <li>Fetches the row data from the source via {@link RowSourceItemReference#fetchRow()}</li>
	 * <li>Maps column names to column indices using the synchronized schema</li>
	 * <li>Filters out columns that don't exist in the schema</li>
	 * <li>Inserts the row after the last known row (using {@link #lastRowId})</li>
	 * <li>Assigns proper RGA ordering within the rows array</li>
	 * </ul>
	 *
	 * @param sourceItem the header for the source row to add to the copy
	 */
	@Override
	public void addItem(RowSourceItemReference sourceItem) {
		List<ConValue> values = new ArrayList<>();
		List<Integer> valueIndex = new ArrayList<>();
		RowSourceItem synchRow = sourceItem.fetchRow();
		for (Entry<String, ConValue> e : synchRow.getData().entrySet()) {
			Column column = columnNameMap.get(e.getKey());
			if (column != null) {
				values.add(e.getValue());
				valueIndex.add(column.getVectorIndex());
			}
		}
		intendedChangePublisher.publish(new InsertRowChange(rowsArrayId, lastRowId, values,
				valueIndex.toArray(Integer[]::new), synchRow.getSynapseRow().map(SynapseRow::toConValue).orElse(null)));
		sourceHandler.onSurvivingRow(synchRow.getData());
	}

	/**
	 * The copy row exists in the source unchanged; no grid mutation is needed, but
	 * the row still survives, so it is reported to the source handler (a push build
	 * includes it).
	 */
	@Override
	public void onItemRetained(RowCopyItem copyItem, RowSourceItemReference sourceItem) {
		sourceHandler.onSurvivingRow(cellsAsMap(copyItem));
	}

	/**
	 * Extract a copy row's cells into a column-name to value map.
	 */
	static Map<String, ConValue> cellsAsMap(RowCopyItem copyItem) {
		return copyItem.getCells().stream().collect(Collectors.toMap(CellCopyItem::getName, CellCopyItem::getValue,
				(v1, v2) -> v2, LinkedHashMap::new));
	}

}
