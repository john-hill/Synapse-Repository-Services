package org.sagebionetworks.repo.manager.table;

import java.util.Map;

import org.sagebionetworks.repo.model.EntityType;
import org.sagebionetworks.util.ValidateArgument;
import org.springframework.beans.factory.annotation.Autowired;

public class AppendableTableManagerFactoryImpl implements AppendableTableManagerFactory {
	
	@Autowired
	TableManagerSupport tableManagerSupport;
	
	Map<EntityType, AppendableTableManager> typeToManager;
	
	/**
	 * Injected.
	 * @param typeToManager
	 */
	public void setTypeToManager(Map<EntityType, AppendableTableManager> typeToManager) {
		this.typeToManager = typeToManager;
	}

	@Override
	public AppendableTableManager getManagerForType(EntityType type) {
		ValidateArgument.required(type, "EntityType");
		AppendableTableManager manager = typeToManager.get(type);
		if(manager == null){
			throw new IllegalArgumentException("Invalid type: "+type.name());
		}
		return manager;
	}

	@Override
	public AppendableTableManager getManagerForEntity(String tableId) {
		// Lookup the type for this entity
		EntityType type = tableManagerSupport.getTableEntityType(tableId);
		return getManagerForType(type);
	}

}
