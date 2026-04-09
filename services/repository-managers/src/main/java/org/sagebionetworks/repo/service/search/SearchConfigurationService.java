package org.sagebionetworks.repo.service.search;

import org.sagebionetworks.repo.model.search.table.BindSearchConfigToEntityRequest;
import org.sagebionetworks.repo.model.search.table.ListSearchConfigurationsRequest;
import org.sagebionetworks.repo.model.search.table.ListSearchConfigurationsResponse;
import org.sagebionetworks.repo.model.search.table.SearchConfigBinding;
import org.sagebionetworks.repo.model.search.table.SearchConfiguration;

public interface SearchConfigurationService {

	SearchConfiguration create(Long userId, SearchConfiguration request);

	SearchConfiguration get(Long userId, String id);

	SearchConfiguration update(Long userId, SearchConfiguration request);

	ListSearchConfigurationsResponse list(Long userId, ListSearchConfigurationsRequest request);

	SearchConfigBinding bindSearchConfigToEntity(Long userId, BindSearchConfigToEntityRequest request);

	SearchConfigBinding getSearchConfigBinding(Long userId, String entityId);

	void clearSearchConfigBinding(Long userId, String entityId);
}
