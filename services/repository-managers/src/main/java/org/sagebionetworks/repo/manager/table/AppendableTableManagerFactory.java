package org.sagebionetworks.repo.manager.table;

import org.sagebionetworks.repo.model.EntityType;

/**
 * Factory for AppendableTableManager by entity type.
 *
 */
public interface AppendableTableManagerFactory {

	/**
	 * Get the appropriate AppendableTableManager to be used for the given entity type.
	 * 
	 * @param type
	 * @return
	 */
	public AppendableTableManager getManagerForType(EntityType type);
	
	/**
	 * Get the appropriate AppendableTableManager to be used for the given entity ID.
	 * 
	 * @param type
	 * @return
	 */
	public AppendableTableManager getManagerForEntity(String entityId);
}
