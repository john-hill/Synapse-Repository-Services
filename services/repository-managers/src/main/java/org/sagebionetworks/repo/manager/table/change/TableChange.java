package org.sagebionetworks.repo.manager.table.change;

import org.sagebionetworks.repo.model.table.TableChangeType;

/**
 * Abstraction for inspecting a single change to a table.
 *
 */
public interface TableChange {
	
	/**
	 * The number assigned to this table change.
	 * 
	 * @return
	 */
	Long getChangeNumber();
	
	/**
	 * The type of this change.
	 * 
	 * @return
	 */
	TableChangeType getChangeType();
	
	/**
	 * Load the actual change data for this change.
	 * @return
	 */
	ChangeData loadChangeData();

}
