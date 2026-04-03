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
import org.sagebionetworks.repo.model.ObjectType;
import org.sagebionetworks.repo.model.UnauthorizedException;
import org.sagebionetworks.repo.model.UserInfo;
import org.sagebionetworks.repo.model.auth.AuthorizationStatus;
import org.sagebionetworks.repo.model.dbo.schema.OrganizationDao;
import org.sagebionetworks.repo.model.dbo.search.SearchConfigurationDao;
import org.sagebionetworks.repo.model.schema.Organization;
import org.sagebionetworks.repo.model.search.table.ListSearchConfigurationsRequest;
import org.sagebionetworks.repo.model.search.table.ListSearchConfigurationsResponse;
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

	private SearchConfigurationManagerImpl manager;

	@BeforeEach
	void setUp() {
		manager = new SearchConfigurationManagerImpl(searchConfigurationDao, aclDao, organizationDao);
	}

	// --- Sage employee / admin authorization ---

	@Test
	public void testNonSageUserCannotCreate() {
		UserInfo user = new UserInfo(false);
		user.setId(1L);
		user.setGroups(Set.of(1L));

		assertThrows(UnauthorizedException.class, () ->
			manager.create(user, new SearchConfiguration().setOrganizationName("test-org").setName("test")));
		verifyZeroInteractions(aclDao);
		verifyZeroInteractions(searchConfigurationDao);
	}

	@Test
	public void testNonSageUserCannotUpdate() {
		UserInfo user = new UserInfo(false);
		user.setId(1L);
		user.setGroups(Set.of(1L));

		assertThrows(UnauthorizedException.class, () ->
			manager.update(user, new SearchConfiguration().setId("1").setOrganizationName("test-org").setName("test")));
		verifyZeroInteractions(aclDao);
		verifyZeroInteractions(searchConfigurationDao);
	}

	@Test
	public void testNonSageUserCannotDelete() {
		UserInfo user = new UserInfo(false);
		user.setId(1L);
		user.setGroups(Set.of(1L));

		assertThrows(UnauthorizedException.class, () ->
			manager.delete(user, "1"));
		verifyZeroInteractions(aclDao);
		verifyZeroInteractions(searchConfigurationDao);
	}

	@Test
	public void testAnonymousUserCannotCreate() {
		UserInfo anon = new UserInfo(false);
		anon.setId(AuthorizationConstants.BOOTSTRAP_PRINCIPAL.ANONYMOUS_USER.getPrincipalId());
		anon.setGroups(Set.of(anon.getId()));

		assertThrows(UnauthorizedException.class, () ->
			manager.create(anon, new SearchConfiguration().setOrganizationName("test-org").setName("test")));
	}

	// --- ACL authorization (user is Sage employee) ---

	@Test
	public void testAuthenticatedUserWithoutOrgAclCannotCreate() {
		UserInfo user = new UserInfo(false);
		user.setId(1L);
		user.setGroups(Set.of(1L, BOOTSTRAP_PRINCIPAL.SAGE_BIONETWORKS.getPrincipalId()));
		when(organizationDao.getOrganizationByName("test-org")).thenReturn(new Organization().setId("42"));
		when(aclDao.canAccess(any(UserInfo.class), eq("42"), eq(ObjectType.ORGANIZATION), eq(ACCESS_TYPE.CREATE)))
			.thenReturn(AuthorizationStatus.accessDenied("no"));

		assertThrows(UnauthorizedException.class, () ->
			manager.create(user, new SearchConfiguration().setOrganizationName("test-org").setName("test")));
	}

	@Test
	public void testAuthenticatedUserWithOrgAclCanCreate() {
		UserInfo user = new UserInfo(false);
		user.setId(1L);
		user.setGroups(Set.of(1L, BOOTSTRAP_PRINCIPAL.SAGE_BIONETWORKS.getPrincipalId()));
		when(organizationDao.getOrganizationByName("test-org")).thenReturn(new Organization().setId("42"));
		when(aclDao.canAccess(any(UserInfo.class), eq("42"), eq(ObjectType.ORGANIZATION), eq(ACCESS_TYPE.CREATE)))
			.thenReturn(AuthorizationStatus.authorized());
		SearchConfiguration input = new SearchConfiguration().setOrganizationName("test-org").setName("test");
		when(searchConfigurationDao.create(eq(1L), any())).thenReturn(input.setId("1"));

		SearchConfiguration result = manager.create(user, input);
		assertNotNull(result);
	}

	@Test
	public void testAdminBypassesOrgAcl() {
		UserInfo admin = new UserInfo(true);
		admin.setId(1L);
		SearchConfiguration input = new SearchConfiguration().setOrganizationName("test-org").setName("test");
		when(searchConfigurationDao.create(eq(1L), any())).thenReturn(input.setId("1"));

		SearchConfiguration result = manager.create(admin, input);
		assertNotNull(result);
		verifyZeroInteractions(aclDao);
	}

	// --- Public read ---

	@Test
	public void testGetIsPublicNoAuthCheck() {
		when(searchConfigurationDao.get("1")).thenReturn(Optional.of(new SearchConfiguration()));
		manager.get(new UserInfo(false), "1");
		verifyZeroInteractions(aclDao);
	}

	// --- Not found ---

	@Test
	public void testGetNotFoundThrows() {
		when(searchConfigurationDao.get("999")).thenReturn(Optional.empty());
		assertThrows(NotFoundException.class, () -> manager.get(new UserInfo(false), "999"));
	}

	@Test
	public void testDeleteNotFoundThrows() {
		UserInfo admin = new UserInfo(true);
		admin.setId(1L);
		when(searchConfigurationDao.get("1")).thenReturn(Optional.empty());

		assertThrows(NotFoundException.class, () -> manager.delete(admin, "1"));
	}

	@Test
	public void testUpdateNotFoundThrows() {
		UserInfo admin = new UserInfo(true);
		admin.setId(1L);
		SearchConfiguration request = new SearchConfiguration().setId("999").setOrganizationName("test-org").setName("updated");
		when(searchConfigurationDao.get("999")).thenReturn(Optional.empty());

		assertThrows(NotFoundException.class, () -> manager.update(admin, request));
	}

	// --- Update ACL ---

	@Test
	public void testUpdateRequiresOrgAcl() {
		UserInfo user = new UserInfo(false);
		user.setId(1L);
		user.setGroups(Set.of(1L, BOOTSTRAP_PRINCIPAL.SAGE_BIONETWORKS.getPrincipalId()));
		SearchConfiguration request = new SearchConfiguration().setId("1").setOrganizationName("test-org").setName("updated");
		when(searchConfigurationDao.get("1")).thenReturn(Optional.of(new SearchConfiguration().setId("1").setOrganizationName("test-org")));
		when(organizationDao.getOrganizationByName("test-org")).thenReturn(new Organization().setId("42"));
		when(aclDao.canAccess(any(UserInfo.class), eq("42"), eq(ObjectType.ORGANIZATION), eq(ACCESS_TYPE.UPDATE)))
			.thenReturn(AuthorizationStatus.accessDenied("no"));

		assertThrows(UnauthorizedException.class, () -> manager.update(user, request));
	}

	@Test
	public void testUpdateRejectsOrgNameMismatch() {
		UserInfo admin = new UserInfo(true);
		admin.setId(1L);
		SearchConfiguration request = new SearchConfiguration().setId("1").setOrganizationName("other-org").setName("updated");
		when(searchConfigurationDao.get("1")).thenReturn(Optional.of(new SearchConfiguration().setId("1").setOrganizationName("test-org")));

		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> manager.update(admin, request));
		assertTrue(ex.getMessage().contains("organizationName cannot be changed"));
	}

	// --- Delete ACL from stored entity ---

	@Test
	public void testDeleteChecksOrgAclFromStoredEntity() {
		UserInfo user = new UserInfo(false);
		user.setId(1L);
		user.setGroups(Set.of(1L, BOOTSTRAP_PRINCIPAL.SAGE_BIONETWORKS.getPrincipalId()));
		SearchConfiguration existing = new SearchConfiguration().setId("1").setOrganizationName("other-org");
		when(searchConfigurationDao.get("1")).thenReturn(Optional.of(existing));
		when(organizationDao.getOrganizationByName("other-org")).thenReturn(new Organization().setId("99"));
		when(aclDao.canAccess(any(UserInfo.class), eq("99"), eq(ObjectType.ORGANIZATION), eq(ACCESS_TYPE.DELETE)))
			.thenReturn(AuthorizationStatus.accessDenied("no"));

		assertThrows(UnauthorizedException.class, () -> manager.delete(user, "1"));
		verify(aclDao).canAccess(any(UserInfo.class), eq("99"), eq(ObjectType.ORGANIZATION), eq(ACCESS_TYPE.DELETE));
	}

	// --- List / pagination ---

	@Test
	public void testListAllWhenNoOrgName() {
		SearchConfiguration item = new SearchConfiguration().setId("1");
		when(searchConfigurationDao.listAll(anyLong(), anyLong()))
			.thenReturn(Arrays.asList(item));

		ListSearchConfigurationsRequest request = new ListSearchConfigurationsRequest();

		ListSearchConfigurationsResponse response = manager.list(new UserInfo(false), request);
		assertEquals(1, response.getResults().size());
		verify(searchConfigurationDao).listAll(anyLong(), anyLong());
		verify(searchConfigurationDao, never()).list(anyString(), anyLong(), anyLong());
	}

	@Test
	public void testListDelegatesToDaoWithPagination() {
		SearchConfiguration item = new SearchConfiguration().setId("1");
		when(searchConfigurationDao.list(eq("test-org"), anyLong(), anyLong()))
			.thenReturn(Arrays.asList(item));

		ListSearchConfigurationsRequest request = new ListSearchConfigurationsRequest();
		request.setOrganizationName("test-org");

		ListSearchConfigurationsResponse response = manager.list(new UserInfo(false), request);
		assertEquals(1, response.getResults().size());
		assertEquals("1", response.getResults().get(0).getId());
	}

	@Test
	public void testListReturnsNextPageTokenWhenMoreResults() {
		// NextPageToken default limit is 50, so limitForQuery is 51
		List<SearchConfiguration> page = new ArrayList<>();
		for (int i = 0; i < 51; i++) {
			page.add(new SearchConfiguration().setId(String.valueOf(i)));
		}
		when(searchConfigurationDao.listAll(eq(51L), eq(0L))).thenReturn(page);

		ListSearchConfigurationsRequest request = new ListSearchConfigurationsRequest();
		ListSearchConfigurationsResponse response = manager.list(new UserInfo(false), request);

		assertNotNull(response.getNextPageToken(), "Expected a next page token when DAO returns more than limit results");
		assertEquals(50, response.getResults().size());
	}

	@Test
	public void testListReturnsNullNextPageTokenWhenNoMoreResults() {
		List<SearchConfiguration> page = Arrays.asList(new SearchConfiguration().setId("1"));
		when(searchConfigurationDao.listAll(eq(51L), eq(0L))).thenReturn(page);

		ListSearchConfigurationsRequest request = new ListSearchConfigurationsRequest();
		ListSearchConfigurationsResponse response = manager.list(new UserInfo(false), request);

		assertNull(response.getNextPageToken(), "Expected null next page token when all results fit in one page");
		assertEquals(1, response.getResults().size());
	}
}
