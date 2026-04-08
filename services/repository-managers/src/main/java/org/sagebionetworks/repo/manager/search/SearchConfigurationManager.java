package org.sagebionetworks.repo.manager.search;

import org.sagebionetworks.repo.model.UserInfo;
import org.sagebionetworks.repo.model.search.table.BindSearchConfigToEntityRequest;
import org.sagebionetworks.repo.model.search.table.ListSearchConfigurationsRequest;
import org.sagebionetworks.repo.model.search.table.ListSearchConfigurationsResponse;
import org.sagebionetworks.repo.model.search.table.SearchConfigBinding;
import org.sagebionetworks.repo.model.search.table.SearchConfiguration;

public interface SearchConfigurationManager {

	/**
	 * Create a new search configuration.
	 *
	 * @param user The user performing the operation
	 * @param request The search configuration to create
	 * @return The created search configuration
	 */
	SearchConfiguration create(UserInfo user, SearchConfiguration request);

	/**
	 * Get a search configuration by its ID.
	 *
	 * @param user The user performing the operation
	 * @param id The ID of the search configuration
	 * @return The search configuration
	 */
	SearchConfiguration get(UserInfo user, String id);

	/**
	 * Update an existing search configuration.
	 *
	 * @param user The user performing the operation
	 * @param request The search configuration with updated fields
	 * @return The updated search configuration
	 */
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

	/**
	 * List search configurations, optionally filtered by organization.
	 *
	 * @param user The user performing the operation
	 * @param request The list request with optional filters and pagination
	 * @return The page of search configurations
	 */
	ListSearchConfigurationsResponse list(UserInfo user, ListSearchConfigurationsRequest request);
}
