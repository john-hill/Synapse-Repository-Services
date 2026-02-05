package org.sagebionetworks.repo.manager.grid.synch.v2;

public interface Merge<I extends Item> {

	/**
	 * Merge the accumulated changes between two items that do not match.
	 * 
	 * @param copyItem
	 * @param sourceItem
	 */
	void merge(String key, I copyItem, I sourceItem);

	/**
	 * Create a no-op instance of Merge that performs no merge operation.
	 * @param <I>
	 * @return
	 */
	public static <I extends Item> Merge<I> noOp() {
		return (key, copyItem, sourceItem) -> {
		};
	}
}
