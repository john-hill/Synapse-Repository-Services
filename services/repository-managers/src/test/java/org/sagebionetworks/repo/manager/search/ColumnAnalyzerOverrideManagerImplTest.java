package org.sagebionetworks.repo.manager.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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

import java.util.Arrays;
import java.util.Collections;
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
import org.sagebionetworks.repo.model.dbo.search.ColumnAnalyzerOverrideDao;
import org.sagebionetworks.repo.model.dbo.search.SearchConfigurationDao;
import org.sagebionetworks.repo.model.table.search.ColumnAnalyzerOverride;
import org.sagebionetworks.repo.model.table.search.ListColumnAnalyzerOverridesRequest;
import org.sagebionetworks.repo.model.table.search.ListColumnAnalyzerOverridesResponse;
import org.sagebionetworks.repo.model.table.search.SearchConfiguration;
import org.sagebionetworks.repo.web.NotFoundException;

@ExtendWith(MockitoExtension.class)
public class ColumnAnalyzerOverrideManagerImplTest {

	@Mock
	private ColumnAnalyzerOverrideDao columnAnalyzerOverrideDao;
	@Mock
	private SearchConfigurationDao searchConfigurationDao;
	@Mock
	private AccessControlListDAO aclDao;

	private ColumnAnalyzerOverrideManagerImpl manager;

	@BeforeEach
	void setUp() {
		manager = new ColumnAnalyzerOverrideManagerImpl(columnAnalyzerOverrideDao, searchConfigurationDao, aclDao);
	}

	// --- Sage employee / admin authorization ---

	@Test
	public void testNonSageUserCannotCreate() {
		UserInfo user = new UserInfo(false);
		user.setId(1L);
		user.setGroups(Set.of(1L));

		assertThrows(UnauthorizedException.class, () ->
			manager.create(user, new ColumnAnalyzerOverride().setOrganizationId("42").setName("test")));
		verifyZeroInteractions(aclDao);
		verifyZeroInteractions(columnAnalyzerOverrideDao);
	}

	@Test
	public void testNonSageUserCannotUpdate() {
		UserInfo user = new UserInfo(false);
		user.setId(1L);
		user.setGroups(Set.of(1L));

		assertThrows(UnauthorizedException.class, () ->
			manager.update(user, new ColumnAnalyzerOverride().setId("1").setOrganizationId("42").setName("test")));
		verifyZeroInteractions(aclDao);
		verifyZeroInteractions(columnAnalyzerOverrideDao);
	}

	@Test
	public void testNonSageUserCannotDelete() {
		UserInfo user = new UserInfo(false);
		user.setId(1L);
		user.setGroups(Set.of(1L));

		assertThrows(UnauthorizedException.class, () ->
			manager.delete(user, "1"));
		verifyZeroInteractions(aclDao);
		verifyZeroInteractions(columnAnalyzerOverrideDao);
	}

	@Test
	public void testAnonymousUserCannotCreate() {
		UserInfo anon = new UserInfo(false);
		anon.setId(AuthorizationConstants.BOOTSTRAP_PRINCIPAL.ANONYMOUS_USER.getPrincipalId());
		anon.setGroups(Set.of(anon.getId()));

		assertThrows(UnauthorizedException.class, () ->
			manager.create(anon, new ColumnAnalyzerOverride().setOrganizationId("42").setName("test")));
	}

	@Test
	public void testAnonymousUserCannotUpdate() {
		UserInfo anon = new UserInfo(false);
		anon.setId(AuthorizationConstants.BOOTSTRAP_PRINCIPAL.ANONYMOUS_USER.getPrincipalId());
		anon.setGroups(Set.of(anon.getId()));

		assertThrows(UnauthorizedException.class, () ->
			manager.update(anon, new ColumnAnalyzerOverride().setId("1").setOrganizationId("42").setName("test")));
	}

	@Test
	public void testAnonymousUserCannotDelete() {
		UserInfo anon = new UserInfo(false);
		anon.setId(AuthorizationConstants.BOOTSTRAP_PRINCIPAL.ANONYMOUS_USER.getPrincipalId());
		anon.setGroups(Set.of(anon.getId()));

		assertThrows(UnauthorizedException.class, () ->
			manager.delete(anon, "1"));
	}

	// --- ACL authorization (user is Sage employee) ---

	@Test
	public void testAuthenticatedUserWithoutOrgAclCannotCreate() {
		UserInfo user = new UserInfo(false);
		user.setId(1L);
		user.setGroups(Set.of(1L, BOOTSTRAP_PRINCIPAL.SAGE_BIONETWORKS.getPrincipalId()));
		when(aclDao.canAccess(any(UserInfo.class), eq("42"), eq(ObjectType.ORGANIZATION), eq(ACCESS_TYPE.CREATE)))
			.thenReturn(AuthorizationStatus.accessDenied("no"));

		assertThrows(UnauthorizedException.class, () ->
			manager.create(user, new ColumnAnalyzerOverride().setOrganizationId("42").setName("test")));
	}

	@Test
	public void testAuthenticatedUserWithOrgAclCanCreate() {
		UserInfo user = new UserInfo(false);
		user.setId(1L);
		user.setGroups(Set.of(1L, BOOTSTRAP_PRINCIPAL.SAGE_BIONETWORKS.getPrincipalId()));
		when(aclDao.canAccess(any(UserInfo.class), eq("42"), eq(ObjectType.ORGANIZATION), eq(ACCESS_TYPE.CREATE)))
			.thenReturn(AuthorizationStatus.authorized());
		ColumnAnalyzerOverride input = new ColumnAnalyzerOverride().setOrganizationId("42").setName("test");
		when(columnAnalyzerOverrideDao.create(eq(1L), any())).thenReturn(input.setId("1"));

		ColumnAnalyzerOverride result = manager.create(user, input);
		assertNotNull(result);
	}

	@Test
	public void testAdminBypassesOrgAclOnCreate() {
		UserInfo admin = new UserInfo(true);
		admin.setId(1L);
		ColumnAnalyzerOverride input = new ColumnAnalyzerOverride().setOrganizationId("42").setName("test");
		when(columnAnalyzerOverrideDao.create(eq(1L), any())).thenReturn(input.setId("1"));

		ColumnAnalyzerOverride result = manager.create(admin, input);
		assertNotNull(result);
		verifyZeroInteractions(aclDao);
	}

	@Test
	public void testAdminBypassesOrgAclOnUpdate() {
		UserInfo admin = new UserInfo(true);
		admin.setId(1L);
		ColumnAnalyzerOverride request = new ColumnAnalyzerOverride().setId("1").setOrganizationId("42").setName("updated");
		when(columnAnalyzerOverrideDao.get("1")).thenReturn(Optional.of(request));
		when(columnAnalyzerOverrideDao.update(eq(1L), any())).thenReturn(request);

		ColumnAnalyzerOverride result = manager.update(admin, request);
		assertNotNull(result);
		verifyZeroInteractions(aclDao);
	}

	@Test
	public void testAdminBypassesOrgAclOnDelete() {
		UserInfo admin = new UserInfo(true);
		admin.setId(1L);
		ColumnAnalyzerOverride existing = new ColumnAnalyzerOverride().setId("1").setOrganizationId("42");
		when(columnAnalyzerOverrideDao.get("1")).thenReturn(Optional.of(existing));
		when(searchConfigurationDao.findByColumnAnalyzerOverrideId("1")).thenReturn(Collections.emptyList());

		manager.delete(admin, "1");
		verify(columnAnalyzerOverrideDao).delete("1");
		verifyZeroInteractions(aclDao);
	}

	// --- Public read ---

	@Test
	public void testGetIsPublicNoAuthCheck() {
		when(columnAnalyzerOverrideDao.get("1")).thenReturn(Optional.of(new ColumnAnalyzerOverride()));
		manager.get(new UserInfo(false), "1");
		verifyZeroInteractions(aclDao);
	}

	// --- Deletion protection ---

	@Test
	public void testDeleteBlockedBySearchConfigReference() {
		UserInfo admin = new UserInfo(true);
		admin.setId(1L);
		ColumnAnalyzerOverride existing = new ColumnAnalyzerOverride().setId("1").setOrganizationId("42");
		when(columnAnalyzerOverrideDao.get("1")).thenReturn(Optional.of(existing));
		when(searchConfigurationDao.findByColumnAnalyzerOverrideId("1"))
			.thenReturn(List.of(new SearchConfiguration().setId("99")));

		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
			manager.delete(admin, "1"));
		assertTrue(ex.getMessage().contains("referenced"));
	}

	@Test
	public void testDeleteSucceedsWhenUnreferenced() {
		UserInfo admin = new UserInfo(true);
		admin.setId(1L);
		ColumnAnalyzerOverride existing = new ColumnAnalyzerOverride().setId("1").setOrganizationId("42");
		when(columnAnalyzerOverrideDao.get("1")).thenReturn(Optional.of(existing));
		when(searchConfigurationDao.findByColumnAnalyzerOverrideId("1")).thenReturn(Collections.emptyList());

		manager.delete(admin, "1");
		verify(columnAnalyzerOverrideDao).delete("1");
	}

	// --- Not found ---

	@Test
	public void testGetNotFoundThrows() {
		when(columnAnalyzerOverrideDao.get("999")).thenReturn(Optional.empty());
		assertThrows(NotFoundException.class, () -> manager.get(new UserInfo(false), "999"));
	}

	@Test
	public void testDeleteNotFoundThrows() {
		UserInfo admin = new UserInfo(true);
		admin.setId(1L);
		when(columnAnalyzerOverrideDao.get("1")).thenReturn(Optional.empty());

		assertThrows(NotFoundException.class, () -> manager.delete(admin, "1"));
	}

	@Test
	public void testUpdateNotFoundThrows() {
		UserInfo admin = new UserInfo(true);
		admin.setId(1L);
		ColumnAnalyzerOverride request = new ColumnAnalyzerOverride().setId("999").setOrganizationId("42").setName("updated");
		when(columnAnalyzerOverrideDao.get("999")).thenReturn(Optional.empty());

		assertThrows(NotFoundException.class, () -> manager.update(admin, request));
	}

	// --- Update ACL ---

	@Test
	public void testUpdateRequiresOrgAcl() {
		UserInfo user = new UserInfo(false);
		user.setId(1L);
		user.setGroups(Set.of(1L, BOOTSTRAP_PRINCIPAL.SAGE_BIONETWORKS.getPrincipalId()));
		ColumnAnalyzerOverride request = new ColumnAnalyzerOverride().setId("1").setOrganizationId("42").setName("updated");
		when(aclDao.canAccess(any(UserInfo.class), eq("42"), eq(ObjectType.ORGANIZATION), eq(ACCESS_TYPE.UPDATE)))
			.thenReturn(AuthorizationStatus.accessDenied("no"));

		assertThrows(UnauthorizedException.class, () -> manager.update(user, request));
	}

	// --- Delete ACL from stored entity ---

	@Test
	public void testDeleteChecksOrgAclFromStoredEntity() {
		UserInfo user = new UserInfo(false);
		user.setId(1L);
		user.setGroups(Set.of(1L, BOOTSTRAP_PRINCIPAL.SAGE_BIONETWORKS.getPrincipalId()));
		ColumnAnalyzerOverride existing = new ColumnAnalyzerOverride().setId("1").setOrganizationId("99");
		when(columnAnalyzerOverrideDao.get("1")).thenReturn(Optional.of(existing));
		when(aclDao.canAccess(any(UserInfo.class), eq("99"), eq(ObjectType.ORGANIZATION), eq(ACCESS_TYPE.DELETE)))
			.thenReturn(AuthorizationStatus.accessDenied("no"));

		assertThrows(UnauthorizedException.class, () -> manager.delete(user, "1"));
		verify(aclDao).canAccess(any(UserInfo.class), eq("99"), eq(ObjectType.ORGANIZATION), eq(ACCESS_TYPE.DELETE));
	}

	// --- List / pagination ---

	@Test
	public void testListAllWhenNoOrgId() {
		ColumnAnalyzerOverride item = new ColumnAnalyzerOverride().setId("1");
		when(columnAnalyzerOverrideDao.listAll(anyLong(), anyLong()))
			.thenReturn(Arrays.asList(item));

		ListColumnAnalyzerOverridesRequest request = new ListColumnAnalyzerOverridesRequest();

		ListColumnAnalyzerOverridesResponse response = manager.list(new UserInfo(false), request);
		assertEquals(1, response.getResults().size());
		verify(columnAnalyzerOverrideDao).listAll(anyLong(), anyLong());
		verify(columnAnalyzerOverrideDao, never()).list(anyString(), anyLong(), anyLong());
	}

	@Test
	public void testListDelegatesToDaoWithPagination() {
		ColumnAnalyzerOverride item = new ColumnAnalyzerOverride().setId("1");
		when(columnAnalyzerOverrideDao.list(eq("42"), anyLong(), anyLong()))
			.thenReturn(Arrays.asList(item));

		ListColumnAnalyzerOverridesRequest request = new ListColumnAnalyzerOverridesRequest();
		request.setOrganizationId("42");

		ListColumnAnalyzerOverridesResponse response = manager.list(new UserInfo(false), request);
		assertEquals(1, response.getResults().size());
		assertEquals("1", response.getResults().get(0).getId());
	}
}
