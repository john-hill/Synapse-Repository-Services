package org.sagebionetworks.repo.manager.grid.synch.core;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

import org.sagebionetworks.util.ValidateArgument;
import org.springframework.stereotype.Component;

/**
 * Implements bidirectional synchronization logic between a copy (CRDT replica)
 * and a source of truth. The synchronization process compares items in both
 * directions and resolves conflicts by merging changes together.
 */
@Component
public class SynchronizationLogic {

	/**
	 * Synchronizes items between a copy and a source. The synchronization process
	 * works in two phases:
	 * 
	 * <p>
	 * Phase 1: Process all items in the copy
	 * <ul>
	 * <li>For each item in the copy, consume the matching item from the source (if
	 * it exists)</li>
	 * <li>If matching source item exists:
	 * <ul>
	 * <li>If items match → no action needed</li>
	 * <li>If items don't match → merge changes together using the merge
	 * strategy</li>
	 * </ul>
	 * </li>
	 * <li>If no matching source item exists:
	 * <ul>
	 * <li>If changed by user → add to source (push user's addition)</li>
	 * <li>If not changed by user → remove from copy (item was deleted from
	 * source)</li>
	 * </ul>
	 * </li>
	 * </ul>
	 * 
	 * <p>
	 * Phase 2: Process remaining items in the source
	 * <ul>
	 * <li>After consuming all matching items, remaining items in source are items
	 * that don't exist in the copy</li>
	 * <li>For each remaining source item:
	 * <ul>
	 * <li>If deleted by user in copy → remove from source (push user's
	 * deletion)</li>
	 * <li>If not deleted by user → add to copy (item was added to source)</li>
	 * </ul>
	 * </li>
	 * </ul>
	 * 
	 * @param <C>    the type of items in the copy
	 * @param <S>    the type of items in the source
	 * @param copy   the copy to synchronize
	 * @param source the source of truth to synchronize with
	 * @param merge  the merge strategy to apply when items don't match
	 */
	public <C extends CopyItem, S extends SourceItem> void synchronize(Copy<C, S> copy, Source<C, S> source,
			Merge<C, S> merge) {
		synchronize(copy, source, merge, SyncOutcomeListener.noOp());
	}

	/**
	 * Synchronizes items between a copy and a source, notifying the given
	 * {@link SyncOutcomeListener} of the terminal fate of each surviving item so a
	 * downstream consumer can capture the reconciled result. See
	 * {@link #synchronize(Copy, Source, Merge)} for the two-phase algorithm.
	 *
	 * @param <C>      the type of items in the copy
	 * @param <S>      the type of items in the source
	 * @param copy     the copy to synchronize
	 * @param source   the source of truth to synchronize with
	 * @param merge    the merge strategy to apply when items don't match
	 * @param listener notified of retained and pulled items
	 */
	public <C extends CopyItem, S extends SourceItem> void synchronize(Copy<C, S> copy, Source<C, S> source,
			Merge<C, S> merge, SyncOutcomeListener<C, S> listener) {
		ValidateArgument.required(copy, "copy");
		ValidateArgument.required(source, "source");
		ValidateArgument.required(merge, "merge");
		ValidateArgument.required(listener, "listener");

		// Phase 1: Process all items in the copy
		Set<String> seenKeys = new HashSet<>();
		copy.streamItems().forEach(copyItem -> {
			// A frozen item is excluded from keyed matching (the source cannot match it)
			// but still survives in the copy.
			if (source.isExcludedFromMatching(copyItem)) {
				listener.onRetainedInCopy(copyItem);
				return;
			}
			String key = source.getKey(copyItem);
			ValidateArgument.required(key, "key");
			// A duplicate key cannot be matched a second time; the first occurrence is kept
			// and matched, every later duplicate is retained, but excluded from the merge.
			if (!seenKeys.add(key)) {
				listener.onRetainedInCopy(copyItem);
				return;
			}
			Optional<S> sourceValue = source.consume(key);
			if (sourceValue.isPresent()) {
				// Item exists in both copy and source
				S sourceItem = sourceValue.get();
				if (!source.matches(copyItem, sourceItem)) {
					// Items don't match - merge them together
					merge.merge(key, copyItem, sourceItem);
				} else {
					// Items match - no mutation needed; the item survives unchanged.
					listener.onRetainedInCopy(copyItem);
				}
			} else {
				// Item exists only in copy
				if (copyItem.wasChangedByUser() && source.isItemAdditionSupported()) {
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
			if (source.wasDeletedByUser(sourceItem) && source.isItemRemovalSupported()) {
				// User deleted item from copy - remove from source
				source.removeItem(sourceItem);
			} else {
				// Item was added to source, or source does not support removal - pull to copy
				copy.addItem(sourceItem);
				listener.onPulledFromSourceToCopy(sourceItem);
			}
		});

	}

}
