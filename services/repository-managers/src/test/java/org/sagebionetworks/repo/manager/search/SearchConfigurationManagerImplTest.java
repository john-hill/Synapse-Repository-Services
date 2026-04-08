package org.sagebionetworks.repo.manager.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.sagebionetworks.repo.model.ACCESS_TYPE;
import org.sagebionetworks.repo.model.AccessControlListDAO;
import org.sagebionetworks.repo.model.AuthorizationConstants;
import org.sagebionetworks.repo.model.AuthorizationConstants.BOOTSTRAP_PRINCIPAL;
import org.sagebionetworks.repo.model.NodeDAO;
import org.sagebionetworks.repo.model.ObjectType;
import org.sagebionetworks.repo.model.UnauthorizedException;
import org.sagebionetworks.repo.model.UserInfo;
import org.sagebionetworks.repo.model.auth.AuthorizationStatus;
import org.sagebionetworks.repo.model.dbo.schema.OrganizationDao;
import org.sagebionetworks.repo.model.dbo.search.SearchConfigurationDao;
import org.sagebionetworks.repo.model.dbo.search.SynonymSetDao;
import org.sagebionetworks.repo.model.dbo.search.ColumnAnalyzerOverrideDao;
import org.sagebionetworks.repo.model.schema.Organization;
import org.sagebionetworks.repo.model.search.table.BindSearchConfigToEntityRequest;
import org.sagebionetworks.repo.model.search.table.ListSearchConfigurationsRequest;
import org.sagebionetworks.repo.model.search.table.ListSearchConfigurationsResponse;
import org.sagebionetworks.repo.model.search.table.SearchConfigBinding;
import org.sagebionetworks.repo.model.search.table.SearchConfiguration;
import org.sagebionetworks.repo.web.NotFoundException;

@ExtendWith(MockitoExtension.class)
public class SearchConfigurationManagerImplTest {

	@Mock
	private SearchConfigurationDao searchConfigurationDao;
	@Mock
	private AccessControlListDAO aclDao;
	@Mock
	private OrganizationDao organizationDao;
	@Mock
	private SynonymSetDao synonymSetDao;
	@Mock
	private ColumnAnalyzerOverrideDao columnAnalyzerOverrideDao;
	@Mock
	private NodeDAO nodeDAO;

	private SearchConfigurationManagerImpl manager;

	@BeforeEach
	void setUp() {
		manager = new SearchConfigurationManagerImpl(searchConfigurationDao, aclDao, organizationDao,
				synonymSetDao, columnAnalyzerOverrideDao, nodeDAO);
	}

	// --- Sage employee / admin authorization ---

	@Test
	public void testCreateWithNonSageUser() {
		UserInfo user = new UserInfo(false);
		user.setId(1L);
		user.setGroups(Set.of(1L));

		// call under test
		assertThrows(UnauthorizedException.class, () ->
			manager.create(user, new SearchConfiguration().setOrganizationName("test-org").setName("test")));
		verifyZeroInteractions(aclDao);
		verifyZeroInteractions(searchConfigurationDao);
	}

	@Test
	public void testUpdateWithNonSageUser() {
		UserInfo user = new UserInfo(false);
		user.setId(1L);
		user.setGroups(Set.of(1L));

		// call under test
		assertThrows(UnauthorizedException.class, () ->
			manager.update(user, new SearchConfiguration().setId("1").setOrganizationName("test-org").setName("test")));
		verifyZeroInteractions(aclDao);
		verifyZeroInteractions(searchConfigurationDao);
	}

	@Test
	public void testCreateWithAnonymousUser() {
		UserInfo anon = new UserInfo(false);
		anon.setId(AuthorizationConstants.BOOTSTRAP_PRINCIPAL.ANONYMOUS_USER.getPrincipalId());
		anon.setGroups(Set.of(anon.getId()));

		// call under test
		assertThrows(UnauthorizedException.class, () ->
			manager.create(anon, new SearchConfiguration().setOrganizationName("test-org").setName("test")));
		verifyZeroInteractions(aclDao);
		verifyZeroInteractions(searchConfigurationDao);
		verifyZeroInteractions(organizationDao);
	}

	// --- ACL authorization (user is Sage employee) ---

	@Test
	public void testCreateWithUserWithoutOrgAcl() {
		UserInfo user = new UserInfo(false);
		user.setId(1L);
		user.setGroups(Set.of(1L, BOOTSTRAP_PRINCIPAL.SAGE_BIONETWORKS.getPrincipalId()));
		when(organizationDao.getOrganizationByName("test-org")).thenReturn(new Organization().setId("42"));
		when(aclDao.canAccess(any(UserInfo.class), eq("42"), eq(ObjectType.ORGANIZATION), eq(ACCESS_TYPE.CREATE)))
			.thenReturn(AuthorizationStatus.accessDenied("no"));

		// call under test
		assertThrows(UnauthorizedException.class, () ->
			manager.create(user, new SearchConfiguration().setOrganizationName("test-org").setName("test")));
		verifyZeroInteractions(searchConfigurationDao);
	}

	@Test
	public void testCreateWithUserWithOrgAcl() {
		UserInfo user = new UserInfo(false);
		user.setId(1L);
		user.setGroups(Set.of(1L, BOOTSTRAP_PRINCIPAL.SAGE_BIONETWORKS.getPrincipalId()));
		when(organizationDao.getOrganizationByName("test-org")).thenReturn(new Organization().setId("42"));
		when(aclDao.canAccess(any(UserInfo.class), eq("42"), eq(ObjectType.ORGANIZATION), eq(ACCESS_TYPE.CREATE)))
			.thenReturn(AuthorizationStatus.authorized());
		SearchConfiguration input = new SearchConfiguration().setOrganizationName("test-org").setName("test");
		when(searchConfigurationDao.create(eq(1L), any())).thenReturn(input.setId("1"));

		// call under test
		SearchConfiguration result = manager.create(user, input);
		assertNotNull(result);
	}

	@Test
	public void testCreateWithAdminBypassesOrgAcl() {
		UserInfo admin = new UserInfo(true);
		admin.setId(1L);
		SearchConfiguration input = new SearchConfiguration().setOrganizationName("test-org").setName("test");
		when(searchConfigurationDao.create(eq(1L), any())).thenReturn(input.setId("1"));

		// call under test
		SearchConfiguration result = manager.create(admin, input);
		assertNotNull(result);
		verifyZeroInteractions(aclDao);
	}

	// --- Public read ---

	@Test
	public void testGetWithPublicAccess() {
		when(searchConfigurationDao.get("1")).thenReturn(Optional.of(new SearchConfiguration()));

		// call under test
		manager.get(new UserInfo(false), "1");
		verifyZeroInteractions(aclDao);
	}

	// --- Not found ---

	@Test
	public void testGetWithNonExistentId() {
		when(searchConfigurationDao.get("999")).thenReturn(Optional.empty());

		// call under test
		NotFoundException ex = assertThrows(NotFoundException.class, () -> manager.get(new UserInfo(false), "999"));
		assertEquals("A search configuration with the given id does not exist.", ex.getMessage());
	}

	@Test
	public void testUpdateWithNonExistentId() {
		UserInfo admin = new UserInfo(true);
		admin.setId(1L);
		SearchConfiguration request = new SearchConfiguration().setId("999").setOrganizationName("test-org").setName("updated");
		when(searchConfigurationDao.get("999")).thenReturn(Optional.empty());

		// call under test
		NotFoundException ex = assertThrows(NotFoundException.class, () -> manager.update(admin, request));
		assertEquals("A search configuration with the given id does not exist.", ex.getMessage());
	}

	// --- Update ACL ---

	@Test
	public void testUpdateWithUserWithoutOrgAcl() {
		UserInfo user = new UserInfo(false);
		user.setId(1L);
		user.setGroups(Set.of(1L, BOOTSTRAP_PRINCIPAL.SAGE_BIONETWORKS.getPrincipalId()));
		SearchConfiguration request = new SearchConfiguration().setId("1").setOrganizationName("test-org").setName("updated");
		when(searchConfigurationDao.get("1")).thenReturn(Optional.of(new SearchConfiguration().setId("1").setOrganizationName("test-org")));
		when(organizationDao.getOrganizationByName("test-org")).thenReturn(new Organization().setId("42"));
		when(aclDao.canAccess(any(UserInfo.class), eq("42"), eq(ObjectType.ORGANIZATION), eq(ACCESS_TYPE.UPDATE)))
			.thenReturn(AuthorizationStatus.accessDenied("no"));

		// call under test
		assertThrows(UnauthorizedException.class, () -> manager.update(user, request));
		verify(searchConfigurationDao, never()).update(anyLong(), any());
	}

	@Test
	public void testUpdateWithOrgNameMismatch() {
		UserInfo admin = new UserInfo(true);
		admin.setId(1L);
		SearchConfiguration request = new SearchConfiguration().setId("1").setOrganizationName("other-org").setName("updated");
		when(searchConfigurationDao.get("1")).thenReturn(Optional.of(new SearchConfiguration().setId("1").setOrganizationName("test-org")));

		// call under test
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> manager.update(admin, request));
		assertTrue(ex.getMessage().contains("organizationName cannot be changed"));
	}

	// --- List / pagination ---

	@Test
	public void testListWithNoOrgName() {
		SearchConfiguration item = new SearchConfiguration().setId("1");
		when(searchConfigurationDao.listAll(anyLong(), anyLong()))
			.thenReturn(Arrays.asList(item));

		ListSearchConfigurationsRequest request = new ListSearchConfigurationsRequest();

		// call under test
		ListSearchConfigurationsResponse response = manager.list(new UserInfo(false), request);
		assertEquals(1, response.getResults().size());
		verify(searchConfigurationDao).listAll(anyLong(), anyLong());
		verify(searchConfigurationDao, never()).list(anyString(), anyLong(), anyLong());
	}

	@Test
	public void testListWithOrgName() {
		SearchConfiguration item = new SearchConfiguration().setId("1");
		when(searchConfigurationDao.list(eq("test-org"), anyLong(), anyLong()))
			.thenReturn(Arrays.asList(item));

		ListSearchConfigurationsRequest request = new ListSearchConfigurationsRequest();
		request.setOrganizationName("test-org");

		// call under test
		ListSearchConfigurationsResponse response = manager.list(new UserInfo(false), request);
		assertEquals(1, response.getResults().size());
		assertEquals("1", response.getResults().get(0).getId());
	}

	@Test
	public void testListWithMoreResults() {
		List<SearchConfiguration> page = new ArrayList<>();
		for (int i = 0; i < 51; i++) {
			page.add(new SearchConfiguration().setId(String.valueOf(i)));
		}
		when(searchConfigurationDao.listAll(eq(51L), eq(0L))).thenReturn(page);

		ListSearchConfigurationsRequest request = new ListSearchConfigurationsRequest();

		// call under test
		ListSearchConfigurationsResponse response = manager.list(new UserInfo(false), request);

		assertNotNull(response.getNextPageToken(), "Expected a next page token when DAO returns more than limit results");
		assertEquals(50, response.getResults().size());
	}

	@Test
	public void testListWithNoMoreResults() {
		List<SearchConfiguration> page = Arrays.asList(new SearchConfiguration().setId("1"));
		when(searchConfigurationDao.listAll(eq(51L), eq(0L))).thenReturn(page);

		ListSearchConfigurationsRequest request = new ListSearchConfigurationsRequest();

		// call under test
		ListSearchConfigurationsResponse response = manager.list(new UserInfo(false), request);

		assertNull(response.getNextPageToken(), "Expected null next page token when all results fit in one page");
		assertEquals(1, response.getResults().size());
	}

	// --- Bind / Unbind ---

	@Test
	public void testBindSearchConfigToEntityWithValidRequest() {
		UserInfo user = new UserInfo(false);
		user.setId(1L);
		user.setGroups(Set.of(1L));

		BindSearchConfigToEntityRequest request = new BindSearchConfigToEntityRequest();
		request.setEntityId("syn123");
		request.setSearchConfigurationId("456");

		when(aclDao.canAccess(any(UserInfo.class), eq("123"), eq(ObjectType.ENTITY), eq(ACCESS_TYPE.UPDATE)))
			.thenReturn(AuthorizationStatus.authorized());
		when(searchConfigurationDao.get("456")).thenReturn(Optional.of(new SearchConfiguration()));
		SearchConfigBinding expectedBinding = new SearchConfigBinding();
		expectedBinding.setBindId("1");
		when(searchConfigurationDao.getSearchConfigBindingForObject(123L, "entity"))
			.thenReturn(Optional.of(expectedBinding));

		// call under test
		SearchConfigBinding result = manager.bindSearchConfigToEntity(user, request);

		assertEquals(expectedBinding, result);
		verify(searchConfigurationDao).bindSearchConfigToObject(456L, 123L, "entity", 1L);
	}

	@Test
	public void testBindSearchConfigToEntityWithAnonymousUser() {
		UserInfo anon = new UserInfo(false);
		anon.setId(null);

		BindSearchConfigToEntityRequest request = new BindSearchConfigToEntityRequest();
		request.setEntityId("syn123");
		request.setSearchConfigurationId("456");

		// call under test
		String message = assertThrows(UnauthorizedException.class, () -> manager.bindSearchConfigToEntity(anon, request)).getMessage();
		assertEquals("Must login to perform this action", message);
		verifyZeroInteractions(aclDao);
		verifyZeroInteractions(searchConfigurationDao);
	}

	@Test
	public void testBindSearchConfigToEntityWithMissingEntityId() {
		UserInfo user = new UserInfo(false);
		user.setId(1L);
		user.setGroups(Set.of(1L));

		BindSearchConfigToEntityRequest request = new BindSearchConfigToEntityRequest();
		request.setSearchConfigurationId("456");

		// call under test
		String message = assertThrows(IllegalArgumentException.class, () -> manager.bindSearchConfigToEntity(user, request)).getMessage();
		assertEquals("entityId is required and must not be the empty string.", message);
		verifyZeroInteractions(searchConfigurationDao);
	}

	@Test
	public void testBindSearchConfigToEntityWithMissingConfigId() {
		UserInfo user = new UserInfo(false);
		user.setId(1L);
		user.setGroups(Set.of(1L));

		BindSearchConfigToEntityRequest request = new BindSearchConfigToEntityRequest();
		request.setEntityId("syn123");

		// call under test
		String message = assertThrows(IllegalArgumentException.class, () -> manager.bindSearchConfigToEntity(user, request)).getMessage();
		assertEquals("searchConfigurationId is required and must not be the empty string.", message);
		verifyZeroInteractions(searchConfigurationDao);
	}

	@Test
	public void testBindSearchConfigToEntityWithNonExistentConfig() {
		UserInfo user = new UserInfo(false);
		user.setId(1L);
		user.setGroups(Set.of(1L));

		BindSearchConfigToEntityRequest request = new BindSearchConfigToEntityRequest();
		request.setEntityId("syn123");
		request.setSearchConfigurationId("999");

		when(aclDao.canAccess(any(UserInfo.class), eq("123"), eq(ObjectType.ENTITY), eq(ACCESS_TYPE.UPDATE)))
			.thenReturn(AuthorizationStatus.authorized());
		when(searchConfigurationDao.get("999")).thenReturn(Optional.empty());

		// call under test
		String message = assertThrows(NotFoundException.class, () -> manager.bindSearchConfigToEntity(user, request)).getMessage();
		assertEquals("A search configuration with the given id does not exist.", message);
		verify(searchConfigurationDao, never()).bindSearchConfigToObject(anyLong(), anyLong(), anyString(), anyLong());
	}

	@Test
	public void testClearSearchConfigBindingWithValidEntity() {
		UserInfo user = new UserInfo(false);
		user.setId(1L);
		user.setGroups(Set.of(1L));

		when(aclDao.canAccess(any(UserInfo.class), eq("123"), eq(ObjectType.ENTITY), eq(ACCESS_TYPE.UPDATE)))
			.thenReturn(AuthorizationStatus.authorized());

		// call under test
		manager.clearSearchConfigBinding(user, "syn123");

		verify(searchConfigurationDao).clearSearchConfigBinding(123L, "entity");
	}

	@Test
	public void testClearSearchConfigBindingWithAnonymousUser() {
		UserInfo anon = new UserInfo(false);
		anon.setId(null);

		// call under test
		String message = assertThrows(UnauthorizedException.class, () -> manager.clearSearchConfigBinding(anon, "syn123")).getMessage();
		assertEquals("Must login to perform this action", message);
		verifyZeroInteractions(aclDao);
		verifyZeroInteractions(searchConfigurationDao);
	}
}
