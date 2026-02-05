package org.sagebionetworks.repo.manager.grid.synch.v2;

/**
 * Represents an item that can be synchronized between a copy and a source.
 * 
 */
public interface Item {

	/**
	 * Determines if this item was modified by the user. Used during synchronization
	 * to decide whether changes should be pushed to the source or pulled from the
	 * source.
	 * 
	 * @return true if the user made changes to this item, false otherwise
	 */
	boolean wasChangedByUser();

	/**
	 * Checks if this item matches another item based on their values. Used during
	 * synchronization to determine if items in the copy and source are equivalent.
	 * 
	 * @param item the item to compare with
	 * @return true if the items match, false otherwise
	 */
	boolean matches(Item item);


}
