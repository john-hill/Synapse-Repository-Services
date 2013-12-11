package org.sagebionetworks.repo.model.dbo.dao.table;

import org.springframework.jdbc.core.simple.SimpleJdbcTemplate;

public class DBODatabaseClusterDAOImpl implements DBODatabaseClusterDAO {
	

	private boolean isDatabaseClusterEnabled;
	
	private int maxNumberDatabaseInstances;
	
	/**
	 * Injected via Spring
	 * @param isDatabaseClusterEnabled
	 */
	public void setDatabaseClusterEnabled(boolean isDatabaseClusterEnabled) {
		this.isDatabaseClusterEnabled = isDatabaseClusterEnabled;
	}

	/**
	 * Ijected via Spring
	 * @param maxNumberDatabaseInstances
	 */
	public void setMaxNumberDatabaseInstances(int maxNumberDatabaseInstances) {
		this.maxNumberDatabaseInstances = maxNumberDatabaseInstances;
	}

	@Override
	public SimpleJdbcTemplate getDatabaseConnectionForTable(Long tableId) {
		validateFeatureEnable();
		return null;
	}

	/**
	 * Called when this bean is created.
	 */
	public void initialize(){
		// there is nothing to do if this feature is not enabled.
		if(!isDatabaseClusterEnabled){
			// Validate that all of the required instances are running.
			if(maxNumberDatabaseInstances < 1) throw new IllegalArgumentException("There must be at least one database instances in the cluster.");
		}
	}

	@Override
	public boolean isDatabaseClusterEnabled() {
		return this.isDatabaseClusterEnabled;
	}
	
	/**
	 * If the feature is not enabled then throw an exception.
	 */
	private void validateFeatureEnable(){
		if(!isDatabaseClusterEnabled) throw new IllegalStateException("The database cluster is not enabled so this feature cannot be used.");
	}
}
