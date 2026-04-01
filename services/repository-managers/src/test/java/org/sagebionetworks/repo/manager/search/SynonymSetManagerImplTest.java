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
import org.sagebionetworks.repo.model.dbo.search.SynonymSetDao;
import org.sagebionetworks.repo.model.schema.Organization;
import org.sagebionetworks.repo.model.search.table.ListSynonymSetsRequest;
import org.sagebionetworks.repo.model.search.table.ListSynonymSetsResponse;
import org.sagebionetworks.repo.model.search.table.SynonymSet;
import org.sagebionetworks.repo.web.NotFoundException;

@ExtendWith(MockitoExtension.class)
public class SynonymSetManagerImplTest {

	@Mock
	private SynonymSetDao synonymSetDao;
	// TODO: PLFM-9512 — uncomment when SearchConfigurationDao is available
	// @Mock
	// private SearchConfigurationDao searchConfigurationDao;
	@Mock
	private AccessControlListDAO aclDao;
	@Mock
	private OrganizationDao organizationDao;

	private SynonymSetManagerImpl manager;

	@BeforeEach
	void setUp() {
		manager = new SynonymSetManagerImpl(synonymSetDao, aclDao, organizationDao);
	}

	// --- Sage employee / admin authorization ---

	@Test
	public void testNonSageUserCannotCreate() {
		UserInfo user = new UserInfo(false);
		user.setId(1L);
		user.setGroups(Set.of(1L));

		assertThrows(UnauthorizedException.class, () ->
			manager.create(user, new SynonymSet().setOrganizationName("test-org").setName("test")));
		verifyZeroInteractions(aclDao);
		verifyZeroInteractions(synonymSetDao);
	}

	@Test
	public void testNonSageUserCannotUpdate() {
		UserInfo user = new UserInfo(false);
		user.setId(1L);
		user.setGroups(Set.of(1L));

		assertThrows(UnauthorizedException.class, () ->
			manager.update(user, new SynonymSet().setId("1").setOrganizationName("test-org").setName("test")));
		verifyZeroInteractions(aclDao);
		verifyZeroInteractions(synonymSetDao);
	}

	@Test
	public void testNonSageUserCannotDelete() {
		UserInfo user = new UserInfo(false);
		user.setId(1L);
		user.setGroups(Set.of(1L));

		assertThrows(UnauthorizedException.class, () ->
			manager.delete(user, "1"));
		verifyZeroInteractions(aclDao);
		verifyZeroInteractions(synonymSetDao);
	}

	@Test
	public void testAnonymousUserCannotCreate() {
		UserInfo anon = new UserInfo(false);
		anon.setId(AuthorizationConstants.BOOTSTRAP_PRINCIPAL.ANONYMOUS_USER.getPrincipalId());
		anon.setGroups(Set.of(anon.getId()));

		assertThrows(UnauthorizedException.class, () ->
			manager.create(anon, new SynonymSet().setOrganizationName("test-org").setName("test")));
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
			manager.create(user, new SynonymSet().setOrganizationName("test-org").setName("test")));
	}

	@Test
	public void testAuthenticatedUserWithOrgAclCanCreate() {
		UserInfo user = new UserInfo(false);
		user.setId(1L);
		user.setGroups(Set.of(1L, BOOTSTRAP_PRINCIPAL.SAGE_BIONETWORKS.getPrincipalId()));
		when(organizationDao.getOrganizationByName("test-org")).thenReturn(new Organization().setId("42"));
		when(aclDao.canAccess(any(UserInfo.class), eq("42"), eq(ObjectType.ORGANIZATION), eq(ACCESS_TYPE.CREATE)))
			.thenReturn(AuthorizationStatus.authorized());
		SynonymSet input = new SynonymSet().setOrganizationName("test-org").setName("test");
		when(synonymSetDao.create(eq(1L), any())).thenReturn(input.setId("1"));

		SynonymSet result = manager.create(user, input);
		assertNotNull(result);
	}

	@Test
	public void testAdminBypassesOrgAcl() {
		UserInfo admin = new UserInfo(true);
		admin.setId(1L);
		SynonymSet input = new SynonymSet().setOrganizationName("test-org").setName("test");
		when(synonymSetDao.create(eq(1L), any())).thenReturn(input.setId("1"));

		SynonymSet result = manager.create(admin, input);
		assertNotNull(result);
		verifyZeroInteractions(aclDao);
	}

	// --- Public read ---

	@Test
	public void testGetIsPublicNoAuthCheck() {
		when(synonymSetDao.get("1")).thenReturn(Optional.of(new SynonymSet()));
		manager.get(new UserInfo(false), "1");
		verifyZeroInteractions(aclDao);
	}

	// --- Deletion protection ---
	// TODO: PLFM-9512 — uncomment when SearchConfigurationDao is available
	// @Test
	// public void testDeleteBlockedBySearchConfigReference() { ... }

	@Test
	public void testDeleteSucceedsWhenUnreferenced() {
		UserInfo admin = new UserInfo(true);
		admin.setId(1L);
		SynonymSet existing = new SynonymSet().setId("1").setOrganizationName("test-org");
		when(synonymSetDao.get("1")).thenReturn(Optional.of(existing));

		manager.delete(admin, "1");
		verify(synonymSetDao).delete("1");
	}

	// --- Not found ---

	@Test
	public void testGetNotFoundThrows() {
		when(synonymSetDao.get("999")).thenReturn(Optional.empty());
		assertThrows(NotFoundException.class, () -> manager.get(new UserInfo(false), "999"));
	}

	// --- List / pagination ---

	@Test
	public void testListAllWhenNoOrgName() {
		SynonymSet item = new SynonymSet().setId("1");
		when(synonymSetDao.listAll(anyLong(), anyLong()))
			.thenReturn(Arrays.asList(item));

		ListSynonymSetsRequest request = new ListSynonymSetsRequest();

		ListSynonymSetsResponse response = manager.list(new UserInfo(false), request);
		assertEquals(1, response.getResults().size());
		verify(synonymSetDao).listAll(anyLong(), anyLong());
		verify(synonymSetDao, never()).list(anyString(), anyLong(), anyLong());
	}

	@Test
	public void testListDelegatesToDaoWithPagination() {
		SynonymSet item = new SynonymSet().setId("1");
		when(synonymSetDao.list(eq("test-org"), anyLong(), anyLong()))
			.thenReturn(Arrays.asList(item));

		ListSynonymSetsRequest request = new ListSynonymSetsRequest();
		request.setOrganizationName("test-org");

		ListSynonymSetsResponse response = manager.list(new UserInfo(false), request);
		assertEquals(1, response.getResults().size());
		assertEquals("1", response.getResults().get(0).getId());
	}

	// --- Update ACL ---

	@Test
	public void testUpdateRequiresOrgAcl() {
		UserInfo user = new UserInfo(false);
		user.setId(1L);
		user.setGroups(Set.of(1L, BOOTSTRAP_PRINCIPAL.SAGE_BIONETWORKS.getPrincipalId()));
		SynonymSet request = new SynonymSet().setId("1").setOrganizationName("test-org").setName("updated");
		when(synonymSetDao.get("1")).thenReturn(Optional.of(new SynonymSet().setId("1").setOrganizationName("test-org")));
		when(organizationDao.getOrganizationByName("test-org")).thenReturn(new Organization().setId("42"));
		when(aclDao.canAccess(any(UserInfo.class), eq("42"), eq(ObjectType.ORGANIZATION), eq(ACCESS_TYPE.UPDATE)))
			.thenReturn(AuthorizationStatus.accessDenied("no"));

		assertThrows(UnauthorizedException.class, () -> manager.update(user, request));
	}

	@Test
	public void testUpdateRejectsOrgNameMismatch() {
		UserInfo admin = new UserInfo(true);
		admin.setId(1L);
		SynonymSet request = new SynonymSet().setId("1").setOrganizationName("other-org").setName("updated");
		when(synonymSetDao.get("1")).thenReturn(Optional.of(new SynonymSet().setId("1").setOrganizationName("test-org")));

		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> manager.update(admin, request));
		assertTrue(ex.getMessage().contains("organizationName cannot be changed"));
	}

	@Test
	public void testListReturnsNextPageTokenWhenMoreResults() {
		List<SynonymSet> page = new ArrayList<>();
		for (int i = 0; i < 51; i++) {
			page.add(new SynonymSet().setId(String.valueOf(i)));
		}
		when(synonymSetDao.listAll(eq(51L), eq(0L))).thenReturn(page);

		ListSynonymSetsRequest request = new ListSynonymSetsRequest();
		ListSynonymSetsResponse response = manager.list(new UserInfo(false), request);

		assertNotNull(response.getNextPageToken(), "Expected a next page token when DAO returns more than limit results");
		assertEquals(50, response.getResults().size());
	}

	@Test
	public void testDeleteNotFoundThrows() {
		UserInfo admin = new UserInfo(true);
		admin.setId(1L);
		when(synonymSetDao.get("999")).thenReturn(Optional.empty());

		assertThrows(NotFoundException.class, () -> manager.delete(admin, "999"));
	}

	@Test
	public void testUpdateNotFoundThrows() {
		UserInfo admin = new UserInfo(true);
		admin.setId(1L);
		SynonymSet request = new SynonymSet().setId("999").setOrganizationName("test-org").setName("updated");
		when(synonymSetDao.get("999")).thenReturn(Optional.empty());

		assertThrows(NotFoundException.class, () -> manager.update(admin, request));
	}

	@Test
	public void testDeleteChecksOrgAclFromStoredEntity() {
		UserInfo user = new UserInfo(false);
		user.setId(1L);
		user.setGroups(Set.of(1L, BOOTSTRAP_PRINCIPAL.SAGE_BIONETWORKS.getPrincipalId()));
		SynonymSet existing = new SynonymSet().setId("1").setOrganizationName("other-org");
		when(synonymSetDao.get("1")).thenReturn(Optional.of(existing));
		when(organizationDao.getOrganizationByName("other-org")).thenReturn(new Organization().setId("99"));
		when(aclDao.canAccess(any(UserInfo.class), eq("99"), eq(ObjectType.ORGANIZATION), eq(ACCESS_TYPE.DELETE)))
			.thenReturn(AuthorizationStatus.accessDenied("no"));

		assertThrows(UnauthorizedException.class, () -> manager.delete(user, "1"));
		verify(aclDao).canAccess(any(UserInfo.class), eq("99"), eq(ObjectType.ORGANIZATION), eq(ACCESS_TYPE.DELETE));
	}

	@Test
	public void testListReturnsNullNextPageTokenWhenNoMoreResults() {
		List<SynonymSet> page = Arrays.asList(new SynonymSet().setId("1"));
		when(synonymSetDao.listAll(eq(51L), eq(0L))).thenReturn(page);

		ListSynonymSetsRequest request = new ListSynonymSetsRequest();
		ListSynonymSetsResponse response = manager.list(new UserInfo(false), request);

		assertNull(response.getNextPageToken(), "Expected null next page token when all results fit in one page");
		assertEquals(1, response.getResults().size());
	}
}
