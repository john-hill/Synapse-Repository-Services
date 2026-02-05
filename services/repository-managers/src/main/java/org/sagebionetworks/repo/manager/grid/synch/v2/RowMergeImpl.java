package org.sagebionetworks.repo.manager.grid.synch.v2;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import org.sagebionetworks.repo.manager.grid.synch.SourceHandler;
import org.sagebionetworks.repo.model.grid.patch.ConType;
import org.sagebionetworks.repo.model.grid.patch.ConValue;

public class RowMergeImpl implements RowMerge {

	private final SynchronizationLogic logic;
	private final SourceHandler sourceHandler;

	public RowMergeImpl(SynchronizationLogic logic, SourceHandler sourceHandler) {
		this.logic = logic;
		this.sourceHandler = sourceHandler;
	}

	@Override
	public void merge(String rowKey, RowItem copyItem, RowItem sourceItem) {
		// Track cells for the merged result
		List<CellItem> mergedCells = new ArrayList<>();
		// Track user-deleted cells
		Set<String> userDeletedCells = copyItem.getCells().stream()
				.filter(cell -> cell.wasChangedByUser() && cell.getValue() != null
						&& (ConType.UNDEFINED.equals(cell.getValue().getType())
								|| ConType.NULL.equals(cell.getValue().getType())))
				.map(CellItem::getName).collect(java.util.stream.Collectors.toSet());
		Map<String, ConValue> userChangedCells = new HashMap<>();

		// Create Copy implementation for cells
		Copy<CellItem> cellCopy = new Copy<CellItem>() {
			@Override
			public Stream<CellItem> streamItems() {
				return copyItem.getCells().stream();
			}

			@Override
			public boolean wasDeletedByUser(String key) {
				return userDeletedCells.contains(key);
			}

			@Override
			public void removeItem(CellItem item) {
			    // Item was removed from source - remove from merged result
			    mergedCells.removeIf(cell -> cell.getName().equals(item.getName()));
			}

			@Override
			public void addItem(CellItem item) {
				// Add to merged result with user change flag reset
				CellItem resetCell = new CellItem().setName(item.getName()).setValue(item.getValue())
						.setWasChangedByUser(false);
				mergedCells.add(resetCell);
			}
		};

		// Create Source implementation for cells
		Map<String, CellItem> sourceMap = new HashMap<>();
		sourceItem.getCells().forEach(cell -> sourceMap.put(cell.getName(), cell));

		Source<CellItem> cellSource = new Source<CellItem>() {
			@Override
			public String getKey(CellItem item) {
				return item.getName();
			}

			@Override
			public Optional<CellItem> consume(String key) {
				return Optional.ofNullable(sourceMap.remove(key));
			}

			@Override
			public Stream<CellItem> streamRemaining() {
				return sourceMap.values().stream();
			}

			@Override
			public void addItem(CellItem toAdd) {
				// Only push cells changed by user to source
				if (toAdd.wasChangedByUser()) {
					userChangedCells.put(toAdd.getName(), toAdd.getValue());
				}
			}

			@Override
			public void removeItem(CellItem toRemove) {
				// Track user deletions
				userChangedCells.put(toRemove.getName(), null);
			}
		};

		// Synchronize cells
		logic.synchronize(cellCopy, cellSource, (key, copyCellItem, sourceCellItem) -> {
			// Merge: prioritize user changes in copy, otherwise take source
			CellItem merged = new CellItem().setName(copyCellItem.getName())
					.setValue(copyCellItem.wasChangedByUser() ? copyCellItem.getValue() : sourceCellItem.getValue())
					.setWasChangedByUser(false);
			mergedCells.add(merged);

			// Track user changes
			if (copyCellItem.wasChangedByUser()) {
				userChangedCells.put(copyCellItem.getName(), copyCellItem.getValue());
			}
		});

		// Apply user changes to source
		if (!userChangedCells.isEmpty()) {
			sourceHandler.applyCellChangesFromCopyToSource(rowKey, userChangedCells);
		}

		// Reset the copy row with merged cells
		copyItem.getCells().clear();
		copyItem.getCells().addAll(mergedCells);
	}
}
