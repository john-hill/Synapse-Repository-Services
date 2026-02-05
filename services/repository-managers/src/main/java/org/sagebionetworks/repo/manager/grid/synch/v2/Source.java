package org.sagebionetworks.repo.manager.grid.synch.v2;

import java.util.Optional;
import java.util.stream.Stream;

/**
 * Represents the source of truth for items that are synchronized with a copy.
 * During synchronization, items from the source are compared with items in the
 * copy to determine what changes need to be made.
 * 
 * @param <S> the type of items managed by this source, must extend Item<I>
 */
public interface Source<S extends Item> {

	/**
	 * Returns a unique key for the given item. This key is used to match items
	 * between the copy and source during synchronization.
	 *
	 * @param item the item to get the key for
	 * @return a unique string key identifying the item
	 */
	String getKey(S item);

	/**
	 * Retrieves and removes an item with the given key from the source. This is
	 * used during synchronization to match items from the copy with items in the
	 * source.
	 *
	 * @param key the unique key identifying the item
	 * @return an Optional containing the item if found, or empty if not found
	 */
	Optional<S> consume(String key);

	/**
	 * Returns a stream of all remaining items in the source that have not been
	 * consumed. Used after processing copy items to find items that exist only in
	 * the source.
	 *
	 * @return a stream of remaining items
	 */
	Stream<S> streamRemaining();

	/**
	 * Adds a new item to the source. Called when an item exists in the copy but not
	 * in the source.
	 *
	 * @param toAdd the item to add
	 */
	void addItem(S toAdd);

	/**
	 * Removes an item from the source. Called when an item exists in the source but
	 * was deleted by the user in the copy.
	 *
	 * @param toRemove the item to remove
	 */
	void removeItem(S toRemove);

}
