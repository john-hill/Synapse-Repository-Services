package org.sagebionetworks.repo.manager.grid.synch.row;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
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
 * It depends only on the copy (CRDT) side: it never consults the source. All
 * source-derived decisions (keying, matchability, deletion detection) live on the
 * {@link org.sagebionetworks.repo.manager.grid.synch.core.Source} side, and
 * surviving-row observation is handled by a
 * {@link org.sagebionetworks.repo.manager.grid.synch.core.SyncOutcomeListener}.
 */
public class RowCopyImpl implements RowCopy {

	private final IntendedChangePublisher intendedChangePublisher;
	private final CopyHandler copyHandler;
	private final LogicalTimestamp rowsArrayId;
	private final LogicalTimestamp lastRowId;
	private final Map<String, Column> columnNameMap;

	/**
	 * @param finalSchema             the synchronized schema after Phase 1, used to
	 *                                map column names to vector indices
	 * @param intendedChangePublisher the publisher for sending row changes to the
	 *                                CRDT replica
	 * @param copyHandler             the handler for reading rows from the replica
	 */
	public RowCopyImpl(List<Column> finalSchema, IntendedChangePublisher intendedChangePublisher,
			CopyHandler copyHandler) {
		super();
		this.intendedChangePublisher = intendedChangePublisher;
		this.copyHandler = copyHandler;
		this.rowsArrayId = copyHandler.getHeader().getRowsId();
		this.lastRowId = copyHandler.getLastRowsRgaNodeId();
		this.columnNameMap = finalSchema.stream().collect(Collectors.toMap(Column::getName, Functions.identity()));
	}

	/**
	 * Streams all rows from the copy (CRDT replica) for synchronization with the
	 * source. Uses the {@link CopyHandler} to provide memory-efficient streaming of
	 * rows without loading all data into memory.
	 *
	 * @return a stream of rows from the copy
	 */
	@Override
	public Stream<RowCopyItem> streamItems() {
		return StreamSupport.stream(Spliterators.spliteratorUnknownSize(copyHandler.getRows(),
				Spliterator.ORDERED | Spliterator.NONNULL | Spliterator.IMMUTABLE), false);
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
	 * to the source).
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
	}

}
