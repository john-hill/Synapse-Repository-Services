package org.sagebionetworks.repo.manager.search;

import org.sagebionetworks.repo.model.UserInfo;
import org.sagebionetworks.repo.model.search.table.ListSearchConfigurationsRequest;
import org.sagebionetworks.repo.model.search.table.ListSearchConfigurationsResponse;
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
	 * Delete a search configuration by its ID.
	 *
	 * @param user The user performing the operation
	 * @param id The ID of the search configuration to delete
	 */
	void delete(UserInfo user, String id);

	/**
	 * List search configurations, optionally filtered by organization.
	 *
	 * @param user The user performing the operation
	 * @param request The list request with optional filters and pagination
	 * @return The page of search configurations
	 */
	ListSearchConfigurationsResponse list(UserInfo user, ListSearchConfigurationsRequest request);
}
