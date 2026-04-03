package org.sagebionetworks.repo.service.search;

import org.sagebionetworks.repo.manager.UserManager;
import org.sagebionetworks.repo.manager.search.SearchConfigurationManager;
import org.sagebionetworks.repo.model.UserInfo;
import org.sagebionetworks.repo.model.search.table.ListSearchConfigurationsRequest;
import org.sagebionetworks.repo.model.search.table.ListSearchConfigurationsResponse;
import org.sagebionetworks.repo.model.search.table.SearchConfiguration;
import org.springframework.stereotype.Service;

@Service
public class SearchConfigurationServiceImpl implements SearchConfigurationService {

	private final UserManager userManager;
	private final SearchConfigurationManager searchConfigurationManager;

	public SearchConfigurationServiceImpl(UserManager userManager, SearchConfigurationManager searchConfigurationManager) {
		this.userManager = userManager;
		this.searchConfigurationManager = searchConfigurationManager;
	}

	@Override
	public SearchConfiguration create(Long userId, SearchConfiguration request) {
		UserInfo user = userManager.getUserInfo(userId);
		return searchConfigurationManager.create(user, request);
	}

	@Override
	public SearchConfiguration get(Long userId, String id) {
		UserInfo user = userManager.getUserInfo(userId);
		return searchConfigurationManager.get(user, id);
	}

	@Override
	public SearchConfiguration update(Long userId, SearchConfiguration request) {
		UserInfo user = userManager.getUserInfo(userId);
		return searchConfigurationManager.update(user, request);
	}

	@Override
	public void delete(Long userId, String id) {
		UserInfo user = userManager.getUserInfo(userId);
		searchConfigurationManager.delete(user, id);
	}

	@Override
	public ListSearchConfigurationsResponse list(Long userId, ListSearchConfigurationsRequest request) {
		UserInfo user = userManager.getUserInfo(userId);
		return searchConfigurationManager.list(user, request);
	}
}
