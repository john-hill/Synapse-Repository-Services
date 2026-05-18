package org.sagebionetworks.repo.manager.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.sagebionetworks.repo.model.ACCESS_TYPE;
import org.sagebionetworks.repo.model.AccessControlListDAO;
import org.sagebionetworks.repo.model.AuthorizationConstants;
import org.sagebionetworks.repo.model.NodeDAO;
import org.sagebionetworks.repo.model.ObjectType;
import org.sagebionetworks.repo.model.TeamConstants;
import org.sagebionetworks.repo.model.UnauthorizedException;
import org.sagebionetworks.repo.model.UserInfo;
import org.sagebionetworks.repo.model.auth.AuthorizationStatus;
import org.sagebionetworks.repo.model.dbo.schema.OrganizationDao;
import org.sagebionetworks.repo.model.dbo.search.ColumnAnalyzerOverrideDao;
import org.sagebionetworks.repo.model.dbo.search.SearchConfigurationDao;
import org.sagebionetworks.repo.model.dbo.search.SynonymSetDao;
import org.sagebionetworks.repo.model.dbo.search.TextAnalyzerDao;
import org.sagebionetworks.repo.model.schema.Organization;
import org.sagebionetworks.repo.model.search.table.SearchConfigBinding;
import org.sagebionetworks.repo.model.search.table.SearchConfiguration;
import org.sagebionetworks.repo.web.NotFoundException;

/**
 * Mockito unit tests for {@link SearchConfigurationManagerImpl}. Covers the three-layer
 * authorization gate (anonymous → Sage employee → org ACL), the qualified-name format /
 * existence validation that runs before any DAO write, and the immutability checks
 * applied on update. The bind/getSearchConfigBinding/clear flows are also exercised
 * because they have their own auth path against the entity (not the org).
 */
@ExtendWith(MockitoExtension.class)
public class SearchConfigurationManagerImplTest {

	private static final String ORG_NAME = "org.sagebionetworks";
	private static final String ORG_ID = "42";
	private static final String DEFAULT_INDEX_ANALYZER = "org.sagebionetworks-SCIENTIFIC";
	private static final String DEFAULT_SEARCH_ANALYZER = "org.sagebionetworks-SCIENTIFIC";

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
	private TextAnalyzerDao textAnalyzerDao;
	@Mock
	private NodeDAO nodeDAO;

	@InjectMocks
	private SearchConfigurationManagerImpl manager;

	private UserInfo sageUser() {
		UserInfo user = new UserInfo(false, 100L, "default-realm");
		Set<Long> groups = new LinkedHashSet<>();
		groups.add(TeamConstants.SAGE_BIONETWORKS_TEAM_ID);
		user.setGroups(groups);
		return user;
	}

	private UserInfo adminUser() {
		return new UserInfo(true, 1L, "default-realm");
	}

	private UserInfo nonSageUser() {
		return new UserInfo(false, 200L, "default-realm");
	}

	private UserInfo anonymousUser() {
		UserInfo user = new UserInfo(false, AuthorizationConstants.BOOTSTRAP_PRINCIPAL.ANONYMOUS_USER.getPrincipalId(),
				"default-realm");
		user.setRealmAnonymousUserId(AuthorizationConstants.BOOTSTRAP_PRINCIPAL.ANONYMOUS_USER.getPrincipalId());
		return user;
	}

	private SearchConfiguration validRequest() {
		return new SearchConfiguration()
				.setName("my_config")
				.setOrganizationName(ORG_NAME)
				.setDefaultIndexAnalyzer(DEFAULT_INDEX_ANALYZER)
				.setDefaultSearchAnalyzer(DEFAULT_SEARCH_ANALYZER);
	}

	// --- create authorization gates ---

	@Test
	public void testCreateWithAnonymousThrows() {
		// call under test
		UnauthorizedException e = assertThrows(UnauthorizedException.class,
				() -> manager.create(anonymousUser(), validRequest()));

		assertTrue(e.getMessage().contains("Must login"),
				"Anonymous gate must surface a login-required message: " + e.getMessage());
		verifyZeroInteractions(searchConfigurationDao);
	}

	@Test
	public void testCreateWithNonSageUserThrows() {
		// call under test
		UnauthorizedException e = assertThrows(UnauthorizedException.class,
				() -> manager.create(nonSageUser(), validRequest()));

		assertTrue(e.getMessage().contains("Sage Bionetworks"));
		verifyZeroInteractions(searchConfigurationDao);
	}

	@Test
	public void testCreateWithSageEmployeeChecksOrgAcl() {
		when(organizationDao.getOrganizationByName(ORG_NAME)).thenReturn(new Organization().setId(ORG_ID));
		when(aclDao.canAccess(any(UserInfo.class), eq(ORG_ID), eq(ObjectType.ORGANIZATION), eq(ACCESS_TYPE.CREATE)))
				.thenReturn(AuthorizationStatus.accessDenied("nope"));

		// call under test — non-admin Sage employee must hold the org's CREATE ACL.
		assertThrows(UnauthorizedException.class, () -> manager.create(sageUser(), validRequest()));
		verifyZeroInteractions(searchConfigurationDao);
	}

	@Test
	public void testCreateWithAdminBypassesOrgAcl() {
		when(textAnalyzerDao.findNonExistentNames(any())).thenReturn(Collections.emptyList());
		SearchConfiguration saved = validRequest().setId("999");
		when(searchConfigurationDao.create(eq(1L), eq(validRequest()))).thenReturn(saved);

		// call under test — admin skips the org ACL check entirely.
		SearchConfiguration result = manager.create(adminUser(), validRequest());

		assertEquals(saved, result);
		verifyZeroInteractions(aclDao);
	}

	// --- create reference validation ---

	@Test
	public void testCreateWithUnknownDefaultAnalyzerThrows() {
		when(organizationDao.getOrganizationByName(ORG_NAME)).thenReturn(new Organization().setId(ORG_ID));
		when(aclDao.canAccess(any(UserInfo.class), eq(ORG_ID), eq(ObjectType.ORGANIZATION), eq(ACCESS_TYPE.CREATE)))
				.thenReturn(AuthorizationStatus.authorized());
		when(textAnalyzerDao.findNonExistentNames(
				Arrays.asList(DEFAULT_INDEX_ANALYZER, DEFAULT_SEARCH_ANALYZER)))
				.thenReturn(Collections.singletonList(DEFAULT_INDEX_ANALYZER));

		// call under test
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
				() -> manager.create(sageUser(), validRequest()));

		assertTrue(e.getMessage().contains(DEFAULT_INDEX_ANALYZER));
		assertTrue(e.getMessage().contains("do not exist"));
		verify(searchConfigurationDao, never()).create(any(), any());
	}

	@Test
	public void testCreateWithUnknownSynonymSetThrows() {
		when(organizationDao.getOrganizationByName(ORG_NAME)).thenReturn(new Organization().setId(ORG_ID));
		when(aclDao.canAccess(any(UserInfo.class), eq(ORG_ID), eq(ObjectType.ORGANIZATION), eq(ACCESS_TYPE.CREATE)))
				.thenReturn(AuthorizationStatus.authorized());
		when(textAnalyzerDao.findNonExistentNames(any())).thenReturn(Collections.emptyList());
		when(synonymSetDao.findNonExistentNames(Arrays.asList("biomed-medical_terms", "biomed-ghost")))
				.thenReturn(Collections.singletonList("biomed-ghost"));

		SearchConfiguration request = validRequest()
				.setSynonymSets(Arrays.asList("biomed-medical_terms", "biomed-ghost"));

		// call under test
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
				() -> manager.create(sageUser(), request));

		assertTrue(e.getMessage().contains("biomed-ghost"),
				"Missing names must be enumerated in the error: " + e.getMessage());
		verify(searchConfigurationDao, never()).create(any(), any());
	}

	@Test
	public void testCreateWithUnknownColumnAnalyzerOverrideThrows() {
		when(organizationDao.getOrganizationByName(ORG_NAME)).thenReturn(new Organization().setId(ORG_ID));
		when(aclDao.canAccess(any(UserInfo.class), eq(ORG_ID), eq(ObjectType.ORGANIZATION), eq(ACCESS_TYPE.CREATE)))
				.thenReturn(AuthorizationStatus.authorized());
		when(textAnalyzerDao.findNonExistentNames(any())).thenReturn(Collections.emptyList());
		when(columnAnalyzerOverrideDao.findNonExistentNames(Collections.singletonList("biomed-ghost_override")))
				.thenReturn(Collections.singletonList("biomed-ghost_override"));

		SearchConfiguration request = validRequest()
				.setColumnAnalyzerOverrides(Collections.singletonList("biomed-ghost_override"));

		// call under test
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
				() -> manager.create(sageUser(), request));

		assertTrue(e.getMessage().contains("biomed-ghost_override"));
	}

	@Test
	public void testCreateWithMalformedQualifiedNameThrows() {
		when(organizationDao.getOrganizationByName(ORG_NAME)).thenReturn(new Organization().setId(ORG_ID));
		when(aclDao.canAccess(any(UserInfo.class), eq(ORG_ID), eq(ObjectType.ORGANIZATION), eq(ACCESS_TYPE.CREATE)))
				.thenReturn(AuthorizationStatus.authorized());

		// Missing the org segment — no hyphen — fails format validation before DAO is touched.
		SearchConfiguration request = validRequest().setDefaultIndexAnalyzer("malformed_no_hyphen");

		// call under test
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
				() -> manager.create(sageUser(), request));

		assertTrue(e.getMessage().contains("Invalid qualified name format"),
				"Format error must mention qualified-name: " + e.getMessage());
		verifyZeroInteractions(textAnalyzerDao);
	}

	@Test
	public void testCreateWithMalformedResourceNameThrows() {
		// Resource name starts with a digit — fails resource-name validation before any auth.
		SearchConfiguration request = validRequest().setName("9invalid");

		// call under test
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
				() -> manager.create(sageUser(), request));

		assertEquals(SearchResourceConstants.RESOURCE_NAME_PATTERN_MSG, e.getMessage());
		verifyZeroInteractions(searchConfigurationDao);
	}

	@Test
	public void testCreateHappyPathPersists() {
		when(textAnalyzerDao.findNonExistentNames(any())).thenReturn(Collections.emptyList());
		SearchConfiguration saved = validRequest().setId("999");
		when(searchConfigurationDao.create(eq(1L), eq(validRequest()))).thenReturn(saved);

		// call under test
		SearchConfiguration result = manager.create(adminUser(), validRequest());

		assertEquals(saved, result);
	}

	// --- update ---

	@Test
	public void testUpdateWithChangedNameThrows() {
		SearchConfiguration request = validRequest().setId("999").setName("renamed");
		SearchConfiguration existing = validRequest().setId("999").setName("original");
		when(searchConfigurationDao.get("999")).thenReturn(Optional.of(existing));

		// call under test
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
				() -> manager.update(adminUser(), request));

		assertEquals(SearchResourceConstants.NAME_IMMUTABLE_MSG, e.getMessage());
		verify(searchConfigurationDao, never()).update(any(), any());
	}

	@Test
	public void testUpdateWithChangedOrganizationNameThrows() {
		SearchConfiguration request = validRequest().setId("999").setOrganizationName("other.org");
		SearchConfiguration existing = validRequest().setId("999");
		when(searchConfigurationDao.get("999")).thenReturn(Optional.of(existing));

		// call under test
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
				() -> manager.update(adminUser(), request));

		assertEquals(SearchResourceConstants.ORG_NAME_IMMUTABLE_MSG, e.getMessage());
	}

	@Test
	public void testUpdateWithMissingExistingThrowsNotFound() {
		SearchConfiguration request = validRequest().setId("999");
		when(searchConfigurationDao.get("999")).thenReturn(Optional.empty());

		// call under test
		assertThrows(NotFoundException.class, () -> manager.update(adminUser(), request));
	}

	@Test
	public void testUpdateWithNonAdminEnforcesUpdateAclOnStoredOrgId() {
		SearchConfiguration request = validRequest().setId("999");
		SearchConfiguration existing = validRequest().setId("999");
		when(searchConfigurationDao.get("999")).thenReturn(Optional.of(existing));
		when(organizationDao.getOrganizationByName(existing.getOrganizationName()))
				.thenReturn(new Organization().setId(ORG_ID));
		when(aclDao.canAccess(any(UserInfo.class), eq(ORG_ID), eq(ObjectType.ORGANIZATION), eq(ACCESS_TYPE.UPDATE)))
				.thenReturn(AuthorizationStatus.accessDenied("nope"));

		// call under test — ACL check resolves the **stored** org name so callers can't
		// re-route to a different org by mutating the request.
		assertThrows(UnauthorizedException.class, () -> manager.update(sageUser(), request));
		verify(searchConfigurationDao, never()).update(any(), any());
	}

	// --- bindSearchConfigToEntity ---

	@Test
	public void testBindSearchConfigWithAnonymousThrows() {
		// call under test
		assertThrows(UnauthorizedException.class, () -> manager.bindSearchConfigToEntity(anonymousUser(),
				new org.sagebionetworks.repo.model.search.table.BindSearchConfigToEntityRequest()
						.setEntityId("syn1").setSearchConfigurationId("2")));
		verifyZeroInteractions(searchConfigurationDao);
	}

	@Test
	public void testBindSearchConfigWithNonSageThrows() {
		// call under test
		assertThrows(UnauthorizedException.class, () -> manager.bindSearchConfigToEntity(nonSageUser(),
				new org.sagebionetworks.repo.model.search.table.BindSearchConfigToEntityRequest()
						.setEntityId("syn1").setSearchConfigurationId("2")));
	}

	@Test
	public void testBindSearchConfigWithUnknownConfigThrowsNotFound() {
		when(searchConfigurationDao.get("2")).thenReturn(Optional.empty());

		// call under test
		assertThrows(NotFoundException.class, () -> manager.bindSearchConfigToEntity(adminUser(),
				new org.sagebionetworks.repo.model.search.table.BindSearchConfigToEntityRequest()
						.setEntityId("syn1").setSearchConfigurationId("2")));
		verify(searchConfigurationDao, never()).bindSearchConfigToObject(
				org.mockito.ArgumentMatchers.anyLong(),
				org.mockito.ArgumentMatchers.anyLong(),
				any(String.class),
				org.mockito.ArgumentMatchers.anyLong());
	}

	@Test
	public void testBindSearchConfigHappyPath() {
		when(searchConfigurationDao.get("2")).thenReturn(Optional.of(validRequest().setId("2")));
		SearchConfigBinding binding = new SearchConfigBinding().setBindId("7").setSearchConfigurationId("2")
				.setObjectId("1").setObjectType("entity");
		when(searchConfigurationDao.getSearchConfigBindingForObject(1L, "entity"))
				.thenReturn(Optional.of(binding));

		// call under test
		SearchConfigBinding result = manager.bindSearchConfigToEntity(adminUser(),
				new org.sagebionetworks.repo.model.search.table.BindSearchConfigToEntityRequest()
						.setEntityId("syn1").setSearchConfigurationId("2"));

		assertEquals(binding, result);
		verify(searchConfigurationDao).bindSearchConfigToObject(2L, 1L, "entity", 1L);
	}

	// --- getSearchConfigBinding ---

	@Test
	public void testGetSearchConfigBindingWalksHierarchyAndThrowsWhenAbsent() {
		when(nodeDAO.getEntityIdOfFirstBoundSearchConfig(1L)).thenReturn(Optional.empty());

		// call under test
		NotFoundException e = assertThrows(NotFoundException.class,
				() -> manager.getSearchConfigBinding(adminUser(), "syn1"));

		assertTrue(e.getMessage().contains("any of its ancestors"));
	}

	@Test
	public void testGetSearchConfigBindingFollowsHierarchyToAncestor() {
		// nodeDAO returns ancestor 999 as the first bound node; fetch the binding under that.
		when(nodeDAO.getEntityIdOfFirstBoundSearchConfig(1L)).thenReturn(Optional.of(999L));
		SearchConfigBinding binding = new SearchConfigBinding().setBindId("7").setObjectId("999");
		when(searchConfigurationDao.getSearchConfigBindingForObject(999L, "entity"))
				.thenReturn(Optional.of(binding));

		// call under test
		assertEquals(binding, manager.getSearchConfigBinding(adminUser(), "syn1"));
	}

	// --- clearSearchConfigBinding ---

	@Test
	public void testClearSearchConfigBindingWithAnonymousThrows() {
		// call under test
		assertThrows(UnauthorizedException.class,
				() -> manager.clearSearchConfigBinding(anonymousUser(), "syn1"));
	}

	@Test
	public void testClearSearchConfigBindingWithNonSageThrows() {
		// call under test
		assertThrows(UnauthorizedException.class,
				() -> manager.clearSearchConfigBinding(nonSageUser(), "syn1"));
	}

	@Test
	public void testClearSearchConfigBindingNonAdminEnforcesEntityUpdateAcl() {
		when(aclDao.canAccess(any(UserInfo.class), eq("1"), eq(ObjectType.ENTITY), eq(ACCESS_TYPE.UPDATE)))
				.thenReturn(AuthorizationStatus.accessDenied("nope"));

		// call under test
		assertThrows(UnauthorizedException.class,
				() -> manager.clearSearchConfigBinding(sageUser(), "syn1"));
		verify(searchConfigurationDao, never()).clearSearchConfigBinding(
				org.mockito.ArgumentMatchers.anyLong(), any(String.class));
	}

	@Test
	public void testClearSearchConfigBindingAdminBypassesAcl() {
		// call under test — admin skips the ACL check and proceeds straight to the DAO.
		manager.clearSearchConfigBinding(adminUser(), "syn1");

		verify(searchConfigurationDao).clearSearchConfigBinding(1L, "entity");
		verifyZeroInteractions(aclDao);
	}

	// --- list ---

	@Test
	public void testListByOrganizationDelegatesToScopedListMethod() {
		when(searchConfigurationDao.list(ORG_NAME, 51L, 0L))
				.thenReturn(Collections.singletonList(validRequest().setId("1")));

		// call under test
		manager.list(adminUser(),
				new org.sagebionetworks.repo.model.search.table.ListSearchConfigurationsRequest()
						.setOrganizationName(ORG_NAME));

		verify(searchConfigurationDao).list(ORG_NAME, 51L, 0L);
		verify(searchConfigurationDao, never()).listAll(
				org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong());
	}

	@Test
	public void testListWithoutOrganizationDelegatesToListAll() {
		when(searchConfigurationDao.listAll(51L, 0L))
				.thenReturn(Collections.singletonList(validRequest().setId("1")));

		// call under test
		manager.list(adminUser(),
				new org.sagebionetworks.repo.model.search.table.ListSearchConfigurationsRequest());

		verify(searchConfigurationDao).listAll(51L, 0L);
		verify(searchConfigurationDao, never()).list(
				any(String.class),
				org.mockito.ArgumentMatchers.anyLong(),
				org.mockito.ArgumentMatchers.anyLong());
	}
}
