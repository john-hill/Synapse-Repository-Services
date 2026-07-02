package org.sagebionetworks.repo.manager.grid.synch.core;

import java.util.Optional;
import java.util.stream.Stream;

/**
 * Represents the source of truth for items that are synchronized with a copy.
 * During synchronization, items from the source are compared with items in the
 * copy to determine what changes need to be made in both directions.
 *
 * @param <C> the type of items in the copy
 * @param <S> the type of items in the source
 */
public interface Source<C extends CopyItem, S extends SourceItem> {

 /**
  * Returns a unique key for the given copy item. This key is used to match
  * items between the copy and source during Phase 1 of synchronization.
  *
  * @param copyItem the copy item to get the key for
  * @return a unique string key identifying the item
  */
 String getKey(C copyItem);

 /**
  * Retrieves and removes an item with the given key from the source. Called
  * during Phase 1 for each copy item to find and consume its matching source
  * item.
  *
  * @param key the unique key identifying the item
  * @return an Optional containing the item if found, or empty if not found
  */
 Optional<S> consume(String key);

 /**
  * Returns a stream of all remaining items in the source that have not been
  * consumed. Used during Phase 2 to process items that exist only in the
  * source (items not found in the copy).
  *
  * @return a stream of remaining unconsumed items
  */
 Stream<S> streamRemaining();

 /**
  * Adds a new item to the source. Called during Phase 1 when an item exists
  * in the copy but not in the source and was changed by the user (push user's
  * addition).
  *
  * @param copyItem the copy item to add to the source
  */
 void addItem(C copyItem);

 /**
  * Removes an item from the source. Called during Phase 2 when an item exists
  * in the source but not in the copy and was deleted by the user (push user's
  * deletion).
  *
  * @param toRemove the source item to remove
  */
 void removeItem(S toRemove);

 /**
  * Determines if a copy item matches a source item. Called during Phase 1
  * when both items exist to decide if merging is needed.
  *
  * @param copyItem   the item from the copy
  * @param sourceItem the item from the source
  * @return true if items match (no merge needed), false if they differ
  */
 boolean matches(C copyItem, S sourceItem);

 /**
  * Returns whether this source supports adding new items. When false, items
  * that exist in the copy but not in the source will always be removed from
  * the copy, even if they were changed by the user.
  *
  * <p>
  * Defaults to true. Read-only sources (e.g. entity views) should return
  * false.
  *
  * @return true if new items can be added to this source, false otherwise
  */
 default boolean isItemAdditionSupported() {
  return true;
 }

 /**
  * Returns whether this source supports removing items. When false, items
  * that the user deleted from the copy but still exist in the source will be
  * pulled back into the copy rather than removed from the source.
  *
  * <p>
  * Defaults to true. Read-only sources (e.g. entity views) should return
  * false.
  *
  * @return true if items can be removed from this source, false otherwise
  */
 default boolean isItemRemovalSupported() {
  return true;
 }

 /**
  * Returns whether the given copy item should be excluded from matching during
  * Phase 1 traversal. Items that are excluded from matching cannot be merged,
  * or removed, so they survive in the copy. The source owns this decision because it
  * depends on the source's keying rules (e.g. a RecordSet copy's row with an incomplete
  * upsert key cannot be matched to a source row, but should not be removed from the copy).
  *
  * <p>
  * Defaults to false. Sources whose row identity is intrinsic (e.g. entity views)
  * inherit the default and exclude nothing.
  *
  * @param copyItem the copy item to test
  * @return true if the item should be left untouched by synchronization
  */
 default boolean isExcludedFromMatching(C copyItem) {
  return false;
 }

 /**
  * Determines whether the given source item — present in the source but absent
  * from the copy during Phase 2 — was deleted by the user in the copy. When true,
  * the item is removed from the source (push the user's deletion); when false it
  * is added back to the copy (a source-side addition).
  *
  * <p>
  * The source owns this decision because, for row-based sources, the copy (CRDT)
  * is not rich enough to determine that a user deleted the row. The answer can
  * only be inferred from the synced baseline, which is source-side state.
  * <p>
  * Defaults to false; sources without a baseline concept (e.g. entity views)
  * inherit the default.
  *
  * @param sourceItem the unmatched source item to test
  * @return true if the user deleted this item from the copy, false otherwise
  */
 default boolean wasDeletedByUser(S sourceItem) {
  return false;
 }
}
