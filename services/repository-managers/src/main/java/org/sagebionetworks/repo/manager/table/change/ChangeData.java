package org.sagebionetworks.repo.manager.table.change;

import java.util.List;

import org.sagebionetworks.repo.model.table.ColumnModel;

/**
 * Abstraction for all of the data associated with a single table change.
 *
 */
public interface ChangeData {

	/**
	 * Get the table schema associated with this change. For schema changes, this
	 * will be the schema after the change has been applied.
	 * 
	 * @return
	 */
	List<ColumnModel> getChangeSchema();
}
