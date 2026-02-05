package org.sagebionetworks.repo.manager.grid.synch.v2;

import java.util.Optional;

/**
 * Implements bidirectional synchronization logic between a copy and a source of
 * truth. The synchronization process compares items in both directions and
 * resolves conflicts by merging changes together.
 */
public class SynchronizationLogic {

	/**
	 * Synchronizes items between a copy and a source. The synchronization process
	 * works in two phases:
	 * 
	 * <p>
	 * Phase 1: Process all items in the copy
	 * <ul>
	 * <li>If an item exists in both copy and source but doesn't match:
	 * <ul>
	 * <li>Merge the changes from both items together</li>
	 * </ul>
	 * </li>
	 * <li>If an item exists only in the copy:
	 * <ul>
	 * <li>If changed by user → add to source</li>
	 * <li>If not changed by user → remove from copy</li>
	 * </ul>
	 * </li>
	 * </ul>
	 * 
	 * <p>
	 * Phase 2: Process remaining items in the source (items that don't exist in the
	 * copy)
	 * <ul>
	 * <li>If deleted by user in copy → remove from source</li>
	 * <li>If not deleted by user → add to copy</li>
	 * </ul>
	 * 
	 * @param <T>    the type of items being synchronized, must extend Item
	 * @param copy   the copy to synchronize
	 * @param source the source of truth to synchronize with
	 * @param merge  the merge strategy to apply when items don't match
	 */
	public <T extends Item> void synchronize(Copy<T> copy, Source<T> source, Merge<T> merge) {

		// Phase 1: Process all items in the copy
		copy.streamItems().forEach(copyItem -> {
			String key = source.getKey(copyItem);
			Optional<T> sourceValue = source.consume(key);
			if (sourceValue.isPresent()) {
				// Item exists in both copy and source
				T sourceItem = sourceValue.get();
				if (!copyItem.matches(sourceItem)) {
					// Items don't match - merge them together.
					merge.merge(key, copyItem, sourceItem);
				}
				// If items match, no action needed
			} else {
				// Item exists only in copy
				if (copyItem.wasChangedByUser()) {
					// User added item - push to source
					source.addItem(copyItem);
				} else {
					// Item was removed from source - remove from copy
					copy.removeItem(copyItem);
				}
			}
		});

		// Phase 2: Process remaining items in source (items not in copy)
		source.streamRemaining().forEach(sourceItem -> {
			String key = source.getKey(sourceItem);
			if (copy.wasDeletedByUser(key)) {
				// User deleted item from copy - remove from source
				source.removeItem(sourceItem);
			} else {
				// Item was added to source - pull to copy
				copy.addItem(sourceItem);
			}
		});

	}

}
