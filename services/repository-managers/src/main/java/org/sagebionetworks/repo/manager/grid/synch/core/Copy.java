package org.sagebionetworks.repo.manager.grid.synch.core;

import java.util.stream.Stream;

/**
 * Represents a copy of items that are synchronized with a source of truth.
 * During synchronization, items in the copy are compared with items in the
 * source to determine what changes need to be made in both directions.
 * 
 * @param <C> the type of items in the copy
 * @param <S> the type of items in the source
 */
public interface Copy<C extends CopyItem, S extends SourceItem> {

	/**
	 * Returns a stream of all items currently in the copy. Used during Phase 1
	 * of synchronization to compare with items in the source.
	 *
	 * @return a stream of all items in the copy
	 */
	Stream<C> streamItems();

	/**
	 * Removes an item from the copy. Called during Phase 1 when an item exists
	 * in the copy but not in the source and was not changed by the user
	 * (indicating the item was deleted from the source).
	 *
	 * @param item the item to remove
	 */
	void removeItem(C item);

	/**
	 * Adds a new item to the copy. Called during Phase 2 when an item exists
	 * in the source but not in the copy and was not deleted by the user
	 * (indicating the item was added to the source).
	 *
	 * @param sourceItem the source item to add to the copy
	 */
	void addItem(S sourceItem);

}
