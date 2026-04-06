package org.sagebionetworks.repo.manager.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.sagebionetworks.repo.manager.ProjectSettingsManager;
import org.sagebionetworks.repo.model.UserInfo;
import org.sagebionetworks.repo.model.dbo.search.SearchConfigurationDao;
import org.sagebionetworks.repo.model.project.ProjectSettingsType;
import org.sagebionetworks.repo.model.project.SearchConfigurationListSetting;
import org.sagebionetworks.repo.model.search.table.SearchConfiguration;

@ExtendWith(MockitoExtension.class)
public class SearchConfigurationResolverTest {

	@Mock
	private SearchConfigurationDao mockSearchConfigurationDao;

	@Mock
	private ProjectSettingsManager mockProjectSettingsManager;

	private SearchConfigurationResolver resolver;

	private UserInfo user;

	@BeforeEach
	public void before() {
		resolver = new SearchConfigurationResolver(mockSearchConfigurationDao, mockProjectSettingsManager);
		user = new UserInfo(false);
		user.setId(123L);
	}

	@Test
	public void testResolveWithExplicitId() {
		SearchConfiguration config = new SearchConfiguration();
		when(mockSearchConfigurationDao.get("config-1")).thenReturn(Optional.of(config));

		// call under test
		Optional<SearchConfiguration> result = resolver.resolve(user, "config-1", "syn456");

		assertEquals(Optional.of(config), result);
		verify(mockSearchConfigurationDao).get("config-1");
	}

	@Test
	public void testResolveWithProjectSettingsFallback() {
		SearchConfigurationListSetting setting = new SearchConfigurationListSetting();
		setting.setSearchConfigurationId("config-2");

		when(mockProjectSettingsManager.getProjectSettingForNode(
			eq(user), eq("syn456"), eq(ProjectSettingsType.search), eq(SearchConfigurationListSetting.class)
		)).thenReturn(Optional.of(setting));

		SearchConfiguration config = new SearchConfiguration();
		when(mockSearchConfigurationDao.get("config-2")).thenReturn(Optional.of(config));

		// call under test
		Optional<SearchConfiguration> result = resolver.resolve(user, null, "syn456");

		assertEquals(Optional.of(config), result);
		verify(mockSearchConfigurationDao).get("config-2");
	}

	@Test
	public void testResolveWithNoConfigReturnsEmpty() {
		when(mockProjectSettingsManager.getProjectSettingForNode(
			eq(user), eq("syn456"), eq(ProjectSettingsType.search), eq(SearchConfigurationListSetting.class)
		)).thenReturn(Optional.empty());

		// call under test
		Optional<SearchConfiguration> result = resolver.resolve(user, null, "syn456");

		assertTrue(result.isEmpty());
	}

	@Test
	public void testResolveWithExplicitIdNotFound() {
		when(mockSearchConfigurationDao.get("config-missing")).thenReturn(Optional.empty());

		// call under test
		Optional<SearchConfiguration> result = resolver.resolve(user, "config-missing", "syn456");

		assertTrue(result.isEmpty());
		verify(mockSearchConfigurationDao).get("config-missing");
	}
}
