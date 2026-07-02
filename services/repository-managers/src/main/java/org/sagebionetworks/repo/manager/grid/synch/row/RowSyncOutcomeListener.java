package org.sagebionetworks.repo.manager.grid.synch.row;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

import org.sagebionetworks.repo.manager.grid.synch.core.SyncOutcomeListener;
import org.sagebionetworks.repo.manager.grid.synch.handler.SourceHandler;
import org.sagebionetworks.repo.manager.grid.synch.io.RowSourceItemReference;
import org.sagebionetworks.repo.model.grid.patch.ConValue;

/**
 * Row-phase {@link SyncOutcomeListener} that forwards every surviving grid row
 * to {@link SourceHandler#onSurvivingRow(Map)} so a source that builds a pushed
 * artifact (e.g. RecordSet PULL_PUSH) can capture the full final grid contents.
 *
 * <p>
 * The synchronization engine reports retained and pulled rows here;
 * merged rows are reported separately by {@link RowMergeImpl}.
 */
public class RowSyncOutcomeListener implements SyncOutcomeListener<RowCopyItem, RowSourceItemReference> {

	private final SourceHandler sourceHandler;

	public RowSyncOutcomeListener(SourceHandler sourceHandler) {
		this.sourceHandler = sourceHandler;
	}

	@Override
	public void onRetainedInCopy(RowCopyItem copyItem) {
		sourceHandler.onSurvivingRow(cellsAsMap(copyItem));
	}

	@Override
	public void onPulledFromSourceToCopy(RowSourceItemReference sourceItem) {
		sourceHandler.onSurvivingRow(sourceItem.fetchRow().getData());
	}

	/**
	 * Extract a copy row's cells into a column-name to value map.
	 */
	static Map<String, ConValue> cellsAsMap(RowCopyItem copyItem) {
		return copyItem.getCells().stream().collect(Collectors.toMap(CellCopyItem::getName, CellCopyItem::getValue,
				(v1, v2) -> v2, LinkedHashMap::new));
	}

}
