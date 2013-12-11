package org.sagebionetworks.repo.model.dbo.dao.table;

import org.springframework.jdbc.core.simple.SimpleJdbcTemplate;

/**
 * This dao tracks the database instances of the cluster and allocates connections
 * for use.
 * 
 * @author John
 *
 */
public interface DBODatabaseClusterDAO {

	/**
	 * Get the the database connection used for a table.
	 * @param tableId
	 * @return
	 */
	public SimpleJdbcTemplate getDatabaseConnectionForTable(Long tableId);
	
	/**
	 * Is the cluster feature enabled?
	 * 
	 * @return
	 */
	public boolean isDatabaseClusterEnabled();
}
