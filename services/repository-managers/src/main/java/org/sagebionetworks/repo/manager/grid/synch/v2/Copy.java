package org.sagebionetworks.repo.manager.grid.synch.v2;

import java.util.stream.Stream;

/**
 * Represents a copy of items that are synchronized with a source of truth.
 * During synchronization, items in the copy are compared with items in the
 * source to determine what changes need to be made in both directions.
 *
 * @param <I> the type of the value contained in the items
 * @param <C> the type of items managed by this copy, must extend Item<I>
 */
public interface Copy<C extends Item> {

	/**
	 * Returns a stream of all items currently in the copy. Used during
	 * synchronization to compare with items in the source.
	 *
	 * @return a stream of all items in the copy
	 */
	Stream<C> streamItems();

	/**
	 * Checks if an item with the given key was deleted by the user in the copy.
	 * Used during synchronization to determine if items that exist only in the
	 * source should also be removed from the source.
	 *
	 * @param key the unique key identifying the item
	 * @return true if the user deleted the item from the copy, false otherwise
	 */
	boolean wasDeletedByUser(String key);

	/**
	 * Removes an item from the copy. Called when an item exists in the copy but not
	 * in the source and was not changed by the user.
	 *
	 * @param item the item to remove
	 */
	void removeItem(C item);

	/**
	 * Adds a new item to the copy. Called when an item exists in the source but not
	 * in the copy.
	 *
	 * @param item the item to add
	 */
	void addItem(C item);

}
