package org.sagebionetworks.repo.model.grid;

public class GridConstants {

	/**
	 * JSON-Joy defined minimum replica ID. See: <a
	 * href=https://jsonjoy.com/specs/json-crdt-patch/patch-document/logical-clock>logical-clock</a>
	 */
	public static final Long MIN_REPICA_ID = (long) 0xffff;

	/**
	 * The starting user replica ID for a grid session. User replicas will increment
	 * this value.
	 */
	public static final Long START_REPLICA_ID_USER = MIN_REPICA_ID + 1001;
	/**
	 * The starting internal replica ID for grid session. Internal replicas will
	 * decrement this value. This ensure all internal replica ID are smaller than
	 * all user replica ID. This means when an internal and user replica have a
	 * conflict, the user's replica always wins.
	 */
	public static final Long START_REPLICA_ID_INTERNAL = START_REPLICA_ID_USER - 1;

}
