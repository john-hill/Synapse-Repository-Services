package org.sagebionetworks.repo.manager.search;

import org.sagebionetworks.repo.model.UserInfo;
import org.sagebionetworks.repo.model.search.table.BindSearchConfigToEntityRequest;
import org.sagebionetworks.repo.model.search.table.ListSearchConfigurationsRequest;
import org.sagebionetworks.repo.model.search.table.ListSearchConfigurationsResponse;
import org.sagebionetworks.repo.model.search.table.SearchConfigBinding;
import org.sagebionetworks.repo.model.search.table.SearchConfiguration;

public interface SearchConfigurationManager {

	SearchConfiguration create(UserInfo user, SearchConfiguration request);

	SearchConfiguration get(UserInfo user, String id);

	SearchConfiguration update(UserInfo user, SearchConfiguration request);

	/**
	 * Bind a search configuration to an entity. The caller must have edit permission on the entity.
	 * Replaces any existing binding for the entity. The effective configuration for any entity is the
	 * first binding found by walking up the hierarchy (entity → folder → project).
	 */
	SearchConfigBinding bindSearchConfigToEntity(UserInfo user, BindSearchConfigToEntityRequest request);

	/**
	 * Get the effective search configuration binding for an entity by walking up the hierarchy.
	 * Returns the first binding found on the entity or any ancestor.
	 */
	SearchConfigBinding getSearchConfigBinding(UserInfo user, String entityId);

	/**
	 * Clear the search configuration binding on a specific entity. Does not affect ancestor bindings.
	 * The caller must have edit permission on the entity.
	 */
	void clearSearchConfigBinding(UserInfo user, String entityId);

	ListSearchConfigurationsResponse list(UserInfo user, ListSearchConfigurationsRequest request);
}
