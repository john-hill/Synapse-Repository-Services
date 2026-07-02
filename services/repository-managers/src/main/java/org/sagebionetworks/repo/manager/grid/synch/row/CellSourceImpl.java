package org.sagebionetworks.repo.manager.grid.synch.row;

import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import org.sagebionetworks.repo.manager.grid.synch.core.Source;
import org.sagebionetworks.repo.manager.grid.synch.io.RowSourceItem;
import org.sagebionetworks.repo.model.grid.patch.ConValue;

public class CellSourceImpl implements Source<CellCopyItem, CellSourceItem> {

	private final Map<String, CellSourceItem> sourceMap;
	private final Map<String, ConValue> userChangedCells;
	private final Set<String> userDeletedCells;

	public CellSourceImpl(RowSourceItem sourceItem, Set<String> userDeletedCells) {
		this.sourceMap = new HashMap<>();
		for (Entry<String, ConValue> e : sourceItem.getData().entrySet()) {
			sourceMap.put(e.getKey(), new CellSourceItem().setColumnName(e.getKey()).setValue(e.getValue()));
		}
		this.userChangedCells = new HashMap<>();
		this.userDeletedCells = userDeletedCells;
	}

	@Override
	public String getKey(CellCopyItem item) {
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
	public void addItem(CellCopyItem item) {
		userChangedCells.put(item.getName(), item.getValue());
	}

	@Override
	public void removeItem(CellSourceItem toRemove) {
		// Cell deleted by user - track deletion for source update
		userChangedCells.put(toRemove.getColumnName(), null);
	}

	@Override
	public boolean matches(CellCopyItem copyItem, CellSourceItem sourceItem) {
		return Objects.equals(copyItem.getValue(), sourceItem.getValue());
	}

	/**
	 * A source cell absent from the copy was deleted by the user when its column is
	 * in the user-deleted set (the user cleared the cell to null/undefined in the
	 * CRDT). This is a copy-side fact supplied by the merge, since the source cell
	 * itself carries no user-change tracking.
	 *
	 * @param sourceItem the unmatched source cell
	 * @return true if the user cleared this cell in the copy
	 */
	@Override
	public boolean wasDeletedByUser(CellSourceItem sourceItem) {
		return userDeletedCells.contains(sourceItem.getKey());
	}

	public Map<String, ConValue> getUserChangedCells(){
		return this.userChangedCells;
	}

}
