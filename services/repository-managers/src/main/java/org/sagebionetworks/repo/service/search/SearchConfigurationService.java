package org.sagebionetworks.repo.service.search;

import org.sagebionetworks.repo.model.search.table.ListSearchConfigurationsRequest;
import org.sagebionetworks.repo.model.search.table.ListSearchConfigurationsResponse;
import org.sagebionetworks.repo.model.search.table.SearchConfiguration;

public interface SearchConfigurationService {

	SearchConfiguration create(Long userId, SearchConfiguration request);

	SearchConfiguration get(Long userId, String id);

	SearchConfiguration update(Long userId, SearchConfiguration request);

	void delete(Long userId, String id);

	ListSearchConfigurationsResponse list(Long userId, ListSearchConfigurationsRequest request);
}
