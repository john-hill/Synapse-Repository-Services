package org.sagebionetworks.repo.manager.search;

import java.util.Optional;

import org.sagebionetworks.repo.manager.ProjectSettingsManager;
import org.sagebionetworks.repo.model.UserInfo;
import org.sagebionetworks.repo.model.dbo.search.SearchConfigurationDao;
import org.sagebionetworks.repo.model.project.ProjectSettingsType;
import org.sagebionetworks.repo.model.project.SearchConfigurationListSetting;
import org.sagebionetworks.repo.model.search.table.SearchConfiguration;
import org.springframework.stereotype.Service;

@Service
public class SearchConfigurationResolver {

	private final SearchConfigurationDao searchConfigurationDao;
	private final ProjectSettingsManager projectSettingsManager;

	public SearchConfigurationResolver(SearchConfigurationDao searchConfigurationDao, ProjectSettingsManager projectSettingsManager) {
		this.searchConfigurationDao = searchConfigurationDao;
		this.projectSettingsManager = projectSettingsManager;
	}

	/**
	 * Resolves the effective SearchConfiguration for a SearchIndex entity.
	 * Resolution order:
	 * 1. Explicit searchConfigurationId on the entity
	 * 2. SearchConfigurationListSetting from the project/folder hierarchy
	 * 3. null (platform defaults)
	 *
	 * @param user The user performing the operation
	 * @param searchConfigurationId The explicit search configuration ID, or null
	 * @param parentId The parent entity ID for project settings lookup
	 * @return The resolved SearchConfiguration, or empty if platform defaults should be used
	 */
	public Optional<SearchConfiguration> resolve(UserInfo user, String searchConfigurationId, String parentId) {
		// 1. Explicit configuration
		if (searchConfigurationId != null && !searchConfigurationId.isEmpty()) {
			return searchConfigurationDao.get(searchConfigurationId);
		}
		// 2. Project settings fallback
		Optional<SearchConfigurationListSetting> setting = projectSettingsManager.getProjectSettingForNode(
			user, parentId, ProjectSettingsType.search, SearchConfigurationListSetting.class);
		if (setting.isPresent() && setting.get().getSearchConfigurationId() != null) {
			return searchConfigurationDao.get(setting.get().getSearchConfigurationId());
		}
		// 3. No configuration -- use platform defaults
		return Optional.empty();
	}
}
