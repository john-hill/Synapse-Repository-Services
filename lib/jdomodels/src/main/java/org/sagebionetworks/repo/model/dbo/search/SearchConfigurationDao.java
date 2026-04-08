package org.sagebionetworks.repo.model.dbo.search;

import java.util.List;
import java.util.Optional;

import org.sagebionetworks.repo.model.search.table.SearchConfigBinding;
import org.sagebionetworks.repo.model.search.table.SearchConfiguration;

public interface SearchConfigurationDao {
	SearchConfiguration create(Long createdBy, SearchConfiguration config);
	Optional<SearchConfiguration> get(String id);
	SearchConfiguration update(Long modifiedBy, SearchConfiguration config);
	void delete(String id);
	List<SearchConfiguration> list(String organizationName, long limit, long offset);
	List<SearchConfiguration> listAll(long limit, long offset);

	void bindSearchConfigToObject(Long searchConfigId, Long objectId, String objectType, Long createdBy);
	Optional<SearchConfigBinding> getSearchConfigBindingForObject(Long objectId, String objectType);
	void clearSearchConfigBinding(Long objectId, String objectType);

	void truncateAll();
}
