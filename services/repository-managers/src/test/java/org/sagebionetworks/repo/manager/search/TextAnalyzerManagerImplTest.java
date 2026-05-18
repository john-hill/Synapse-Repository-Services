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
import static org.mockito.Mockito.doThrow;

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
import org.sagebionetworks.repo.model.ObjectType;
import org.sagebionetworks.repo.model.TeamConstants;
import org.sagebionetworks.repo.model.UnauthorizedException;
import org.sagebionetworks.repo.model.UserInfo;
import org.sagebionetworks.repo.model.auth.AuthorizationStatus;
import org.sagebionetworks.repo.model.dbo.schema.OrganizationDao;
import org.sagebionetworks.repo.model.dbo.search.TextAnalyzerDao;
import org.sagebionetworks.repo.model.schema.Organization;
import org.sagebionetworks.repo.model.search.table.AnalyzerComponent;
import org.sagebionetworks.repo.model.search.table.TextAnalyzer;
import org.sagebionetworks.repo.model.search.table.TextAnalyzerSettings;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Unit tests for {@link TextAnalyzerManagerImpl}: covers the three-layer authorization
 * gate (anonymous → Sage employee → org ACL), name immutability, AOSS analyzer validation
 * delegation, and the FK-protection re-throw on delete.
 */
@ExtendWith(MockitoExtension.class)
public class TextAnalyzerManagerImplTest {

	private static final String ORG_NAME = "org.sagebionetworks";
	private static final String ORG_ID = "42";

	@Mock
	private TextAnalyzerDao textAnalyzerDao;
	@Mock
	private AccessControlListDAO aclDao;
	@Mock
	private OrganizationDao organizationDao;
	@Mock
	private OpenSearchManager openSearchManager;

	@InjectMocks
	private TextAnalyzerManagerImpl manager;

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
		Long id = AuthorizationConstants.BOOTSTRAP_PRINCIPAL.ANONYMOUS_USER.getPrincipalId();
		UserInfo user = new UserInfo(false, id, "default-realm");
		user.setRealmAnonymousUserId(id);
		return user;
	}

	private TextAnalyzer validRequest() {
		return new TextAnalyzer()
				.setName("my_analyzer")
				.setOrganizationName(ORG_NAME)
				.setSettings(new TextAnalyzerSettings()
						.setTokenizer(new AnalyzerComponent().setName("standard")));
	}

	// --- create ---

	@Test
	public void testCreateWithAnonymousThrows() {
		// call under test
		assertThrows(UnauthorizedException.class, () -> manager.create(anonymousUser(), validRequest()));
		verifyZeroInteractions(textAnalyzerDao);
	}

	@Test
	public void testCreateWithNonSageUserThrows() {
		// call under test
		UnauthorizedException e = assertThrows(UnauthorizedException.class,
				() -> manager.create(nonSageUser(), validRequest()));
		assertTrue(e.getMessage().contains("Sage Bionetworks"));
		verifyZeroInteractions(textAnalyzerDao);
	}

	@Test
	public void testCreateWithMissingSettingsThrows() {
		TextAnalyzer bad = validRequest().setSettings(null);
		// call under test
		assertThrows(IllegalArgumentException.class, () -> manager.create(adminUser(), bad));
		verifyZeroInteractions(textAnalyzerDao);
	}

	@Test
	public void testCreateWithInvalidResourceNameThrows() {
		TextAnalyzer bad = validRequest().setName("9invalid");
		// call under test
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
				() -> manager.create(adminUser(), bad));
		assertEquals(SearchResourceConstants.RESOURCE_NAME_PATTERN_MSG, e.getMessage());
	}

	@Test
	public void testCreateWithSageEmployeeChecksOrgAclAndValidatesAgainstAOSS() {
		when(organizationDao.getOrganizationByName(ORG_NAME)).thenReturn(new Organization().setId(ORG_ID));
		when(aclDao.canAccess(any(UserInfo.class), eq(ORG_ID), eq(ObjectType.ORGANIZATION), eq(ACCESS_TYPE.CREATE)))
				.thenReturn(AuthorizationStatus.authorized());
		when(textAnalyzerDao.create(any(TextAnalyzer.class), eq(100L))).thenReturn(validRequest().setId("1"));

		// call under test
		manager.create(sageUser(), validRequest());

		// AOSS analyzer validation runs BEFORE the DAO write so a misconfiguration cannot
		// be persisted in a half-broken state.
		verify(openSearchManager).validateAnalyzerSettings(any(TextAnalyzerSettings.class));
		verify(textAnalyzerDao).create(any(TextAnalyzer.class), eq(100L));
	}

	@Test
	public void testCreateWhenAOSSValidationFailsDoesNotPersist() {
		doThrow(new IllegalArgumentException("AOSS rejected the analyzer"))
				.when(openSearchManager).validateAnalyzerSettings(any(TextAnalyzerSettings.class));

		// call under test — admin bypasses ACL but still gets AOSS validation gate.
		assertThrows(IllegalArgumentException.class, () -> manager.create(adminUser(), validRequest()));
		verify(textAnalyzerDao, never()).create(any(TextAnalyzer.class),
				org.mockito.ArgumentMatchers.anyLong());
	}

	// --- update ---

	@Test
	public void testUpdateWithChangedNameThrows() {
		TextAnalyzer request = validRequest().setId("1").setName("renamed");
		TextAnalyzer existing = validRequest().setId("1").setName("original");
		when(textAnalyzerDao.get(1L)).thenReturn(Optional.of(existing));

		// call under test
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
				() -> manager.update(adminUser(), request));

		assertEquals(SearchResourceConstants.NAME_IMMUTABLE_MSG, e.getMessage());
		verify(textAnalyzerDao, never()).update(any(TextAnalyzer.class),
				org.mockito.ArgumentMatchers.anyLong());
	}

	@Test
	public void testUpdateWithChangedOrgThrows() {
		TextAnalyzer request = validRequest().setId("1").setOrganizationName("other.org");
		TextAnalyzer existing = validRequest().setId("1");
		when(textAnalyzerDao.get(1L)).thenReturn(Optional.of(existing));

		// call under test
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
				() -> manager.update(adminUser(), request));

		assertEquals(SearchResourceConstants.ORG_NAME_IMMUTABLE_MSG, e.getMessage());
	}

	// --- delete ---

	@Test
	public void testDeleteTranslatesFkViolationToFriendlyError() {
		when(textAnalyzerDao.get(1L)).thenReturn(Optional.of(validRequest().setId("1")));
		doThrow(new DataIntegrityViolationException("FK fail")).when(textAnalyzerDao).delete(1L);

		// call under test — the DAO catches FK violations but the manager-level test ensures
		// the user-facing message ("still referenced") is preserved.
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
				() -> manager.delete(adminUser(), 1L));

		assertTrue(e.getMessage().contains("still referenced"));
	}

	@Test
	public void testDeleteWithNonAdminEnforcesOrgDeleteAcl() {
		when(textAnalyzerDao.get(1L)).thenReturn(Optional.of(validRequest().setId("1")));
		when(organizationDao.getOrganizationByName(ORG_NAME)).thenReturn(new Organization().setId(ORG_ID));
		when(aclDao.canAccess(any(UserInfo.class), eq(ORG_ID), eq(ObjectType.ORGANIZATION), eq(ACCESS_TYPE.DELETE)))
				.thenReturn(AuthorizationStatus.accessDenied("nope"));

		// call under test
		assertThrows(UnauthorizedException.class, () -> manager.delete(sageUser(), 1L));
		verify(textAnalyzerDao, never()).delete(org.mockito.ArgumentMatchers.anyLong());
	}

	// --- list ---

	@Test
	public void testListByOrganizationDelegates() {
		when(textAnalyzerDao.listByOrganization(ORG_NAME, 51L, 0L))
				.thenReturn(Collections.singletonList(validRequest().setId("1")));

		// call under test
		manager.list(adminUser(),
				new org.sagebionetworks.repo.model.search.table.ListTextAnalyzersRequest()
						.setOrganizationName(ORG_NAME));

		verify(textAnalyzerDao).listByOrganization(ORG_NAME, 51L, 0L);
		verify(textAnalyzerDao, never()).listAll(
				org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong());
	}

	@Test
	public void testListWithoutOrganizationFallsBackToListAll() {
		when(textAnalyzerDao.listAll(51L, 0L)).thenReturn(Collections.emptyList());

		// call under test
		manager.list(adminUser(),
				new org.sagebionetworks.repo.model.search.table.ListTextAnalyzersRequest());

		verify(textAnalyzerDao).listAll(51L, 0L);
		verify(textAnalyzerDao, never()).listByOrganization(
				org.mockito.ArgumentMatchers.any(String.class),
				org.mockito.ArgumentMatchers.anyLong(),
				org.mockito.ArgumentMatchers.anyLong());
	}
}
