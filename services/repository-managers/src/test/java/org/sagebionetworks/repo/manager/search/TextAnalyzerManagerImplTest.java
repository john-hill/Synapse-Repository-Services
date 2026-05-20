package org.sagebionetworks.repo.manager.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
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
import org.sagebionetworks.repo.model.dbo.search.SynonymSetDao;
import org.sagebionetworks.repo.model.dbo.search.TextAnalyzerDao;
import org.sagebionetworks.repo.model.schema.Organization;
import org.sagebionetworks.repo.model.search.table.ListTextAnalyzersRequest;
import org.sagebionetworks.repo.model.search.table.ListTextAnalyzersResponse;
import org.sagebionetworks.repo.model.search.table.TextAnalyzer;
import org.sagebionetworks.repo.web.NotFoundException;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Unit tests for {@link TextAnalyzerManagerImpl}: covers the three-layer authorization
 * gate (anonymous → Sage employee → org ACL), name immutability, opaque-JSON parse +
 * $ref existence validation, and the FK-protection re-throw on delete.
 */
@ExtendWith(MockitoExtension.class)
public class TextAnalyzerManagerImplTest {

	private static final String ORG_NAME = "org.sagebionetworks";
	private static final String ORG_ID = "42";
	private static final String VALID_SETTINGS =
			"{\"analyzer\":{\"default\":{\"type\":\"custom\",\"tokenizer\":\"standard\"}}}";
	private static final String SETTINGS_WITH_REF =
			"{\"filter\":{\"med\":{\"$ref\":\"biomed-medical_terms\"}},"
			+ "\"analyzer\":{\"default\":{\"type\":\"custom\",\"tokenizer\":\"standard\","
			+ "\"filter\":[\"med\"]}}}";

	@Mock
	private TextAnalyzerDao textAnalyzerDao;
	@Mock
	private AccessControlListDAO aclDao;
	@Mock
	private OrganizationDao organizationDao;
	@Mock
	private SynonymSetDao synonymSetDao;

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
				.setSettings(VALID_SETTINGS);
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
	public void testCreateWithMalformedSettingsJsonThrows() {
		TextAnalyzer bad = validRequest().setSettings("{not_valid");
		// call under test
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
				() -> manager.create(adminUser(), bad));
		assertTrue(e.getMessage().startsWith("Invalid JSON"));
		verify(textAnalyzerDao, never()).create(any(), any());
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
	public void testCreateWithRefAndAllExistResolves() {
		when(synonymSetDao.findNonExistentNames(any())).thenReturn(Collections.emptyList());
		when(textAnalyzerDao.create(any(TextAnalyzer.class), eq(1L)))
				.thenReturn(validRequest().setId("1"));
		TextAnalyzer request = validRequest().setSettings(SETTINGS_WITH_REF);

		// call under test
		manager.create(adminUser(), request);

		verify(synonymSetDao).findNonExistentNames(Collections.singletonList("biomed-medical_terms"));
		verify(textAnalyzerDao).create(any(TextAnalyzer.class), eq(1L));
	}

	@Test
	public void testCreateWithUnresolvedRefThrows() {
		when(synonymSetDao.findNonExistentNames(any()))
				.thenReturn(Collections.singletonList("biomed-medical_terms"));
		TextAnalyzer request = validRequest().setSettings(SETTINGS_WITH_REF);

		// call under test
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
				() -> manager.create(adminUser(), request));
		assertTrue(e.getMessage().contains("biomed-medical_terms"));
		verify(textAnalyzerDao, never()).create(any(), any());
	}

	@Test
	public void testCreateWithMalformedRefQnameThrows() {
		TextAnalyzer request = validRequest().setSettings(
				"{\"filter\":{\"x\":{\"$ref\":\"not-a-valid-qname-9starts\"}},"
				+ "\"analyzer\":{\"default\":{\"type\":\"custom\",\"tokenizer\":\"standard\","
				+ "\"filter\":[\"x\"]}}}");

		// call under test
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
				() -> manager.create(adminUser(), request));
		assertTrue(e.getMessage().contains("Invalid qualified name format"),
				"Format error must mention qualified-name: " + e.getMessage());
		verify(textAnalyzerDao, never()).create(any(), any());
		verifyZeroInteractions(synonymSetDao);
	}

	@Test
	public void testCreateWithoutDefaultAnalyzerThrows() {
		// SearchConfiguration binds to a TextAnalyzer by its bare qualified name; the index-
		// build code resolves that to the analyzer entry named "default". A settings blob
		// that declares only registries (or names its analyzer something else) would never
		// be reachable, so the manager rejects it at create time.
		TextAnalyzer bad = validRequest().setSettings(
				"{\"filter\":{\"english_stop\":{\"type\":\"stop\"}}}");

		// call under test
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
				() -> manager.create(adminUser(), bad));
		assertTrue(e.getMessage().contains("analyzer.default"),
				"Error must name analyzer.default: " + e.getMessage());
		verifyZeroInteractions(textAnalyzerDao);
		verifyZeroInteractions(synonymSetDao);
	}

	@Test
	public void testCreateWithDefaultSearchOnlyThrows() {
		// `default_search` alone (without `default`) is rejected: bindings always resolve to
		// the `default` entry first, so a record without it can never be addressed.
		TextAnalyzer bad = validRequest().setSettings(
				"{\"analyzer\":{\"default_search\":{\"type\":\"custom\",\"tokenizer\":\"standard\"}}}");

		// call under test
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
				() -> manager.create(adminUser(), bad));
		assertTrue(e.getMessage().contains("analyzer.default"),
				"Error must name analyzer.default: " + e.getMessage());
		verifyZeroInteractions(textAnalyzerDao);
		verifyZeroInteractions(synonymSetDao);
	}

	@Test
	public void testCreateWithNonObjectAnalyzerKeyThrows() {
		// `analyzer` present but not an object — surfaced through the same missing-default
		// path so the user sees the actionable error, not a Jackson type mismatch.
		TextAnalyzer bad = validRequest().setSettings("{\"analyzer\":\"oops\"}");

		// call under test
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
				() -> manager.create(adminUser(), bad));
		assertTrue(e.getMessage().contains("analyzer.default"),
				"Error must name analyzer.default: " + e.getMessage());
		verifyZeroInteractions(textAnalyzerDao);
	}

	@Test
	public void testCreateWithDefaultAndDefaultSearchSucceeds() {
		// Asymmetric index/search analysis (the edge_ngram autocomplete case) is the one
		// supported reason to declare multiple entries inside the inner `analyzer` map.
		when(textAnalyzerDao.create(any(TextAnalyzer.class), eq(1L)))
				.thenReturn(validRequest().setId("1"));
		String settings = "{\"analyzer\":{"
				+ "\"default\":{\"type\":\"custom\",\"tokenizer\":\"standard\"},"
				+ "\"default_search\":{\"type\":\"custom\",\"tokenizer\":\"keyword\"}"
				+ "}}";
		TextAnalyzer request = validRequest().setSettings(settings);

		// call under test
		manager.create(adminUser(), request);

		verify(textAnalyzerDao).create(any(TextAnalyzer.class), eq(1L));
	}

	@Test
	public void testCreateWithExtraNamedAnalyzerThrows() {
		// One TextAnalyzer record exposes one externally-addressable analyzer. Curators
		// who want a separate `headline` analyzer create a separate TextAnalyzer record;
		// the inner analyzer map cannot carry sibling entries beyond `default` /
		// `default_search`.
		String settings = "{\"analyzer\":{"
				+ "\"default\":{\"type\":\"custom\",\"tokenizer\":\"standard\"},"
				+ "\"headline\":{\"type\":\"custom\",\"tokenizer\":\"standard\"}"
				+ "}}";
		TextAnalyzer bad = validRequest().setSettings(settings);

		// call under test
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
				() -> manager.create(adminUser(), bad));
		assertTrue(e.getMessage().contains("rejected: [headline]"),
				"Error must list the rejected sibling key(s): " + e.getMessage());
		assertTrue(e.getMessage().contains("default")
						&& e.getMessage().contains("default_search"),
				"Error must mention the allowed keys: " + e.getMessage());
		verifyZeroInteractions(textAnalyzerDao);
		verifyZeroInteractions(synonymSetDao);
	}

	@Test
	public void testCreateWithSageEmployeeChecksOrgAcl() {
		when(organizationDao.getOrganizationByName(ORG_NAME)).thenReturn(new Organization().setId(ORG_ID));
		when(aclDao.canAccess(any(UserInfo.class), eq(ORG_ID), eq(ObjectType.ORGANIZATION), eq(ACCESS_TYPE.CREATE)))
				.thenReturn(AuthorizationStatus.authorized());
		when(textAnalyzerDao.create(any(TextAnalyzer.class), eq(100L)))
				.thenReturn(validRequest().setId("1"));

		// call under test
		manager.create(sageUser(), validRequest());

		verify(textAnalyzerDao).create(any(TextAnalyzer.class), eq(100L));
	}

	// --- get ---

	@Test
	public void testGetExisting() {
		TextAnalyzer analyzer = validRequest().setId("1");
		when(textAnalyzerDao.get(1L)).thenReturn(Optional.of(analyzer));

		// call under test
		assertEquals("1", manager.get(adminUser(), 1L).getId());
		verifyZeroInteractions(aclDao);
	}

	@Test
	public void testGetNotFoundThrows() {
		when(textAnalyzerDao.get(999L)).thenReturn(Optional.empty());

		// call under test
		assertThrows(NotFoundException.class, () -> manager.get(adminUser(), 999L));
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

	@Test
	public void testUpdateNotFoundThrows() {
		TextAnalyzer input = validRequest().setId("999");
		when(textAnalyzerDao.get(999L)).thenReturn(Optional.empty());

		// call under test
		assertThrows(NotFoundException.class, () -> manager.update(sageUser(), input));
		verify(textAnalyzerDao, never()).update(any(TextAnalyzer.class),
				org.mockito.ArgumentMatchers.anyLong());
	}

	@Test
	public void testUpdateWithNullSettingsThrows() {
		TextAnalyzer input = validRequest().setId("1").setSettings(null);

		// call under test
		assertThrows(IllegalArgumentException.class, () -> manager.update(sageUser(), input));
		verifyZeroInteractions(textAnalyzerDao);
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

	@Test
	public void testDeleteNotFoundThrows() {
		when(textAnalyzerDao.get(1L)).thenReturn(Optional.empty());

		// call under test
		assertThrows(NotFoundException.class, () -> manager.delete(adminUser(), 1L));
		verify(textAnalyzerDao, never()).delete(org.mockito.ArgumentMatchers.anyLong());
	}

	@Test
	public void testDeleteHappyPath() {
		TextAnalyzer existing = validRequest().setId("1");
		when(textAnalyzerDao.get(1L)).thenReturn(Optional.of(existing));
		when(organizationDao.getOrganizationByName(ORG_NAME)).thenReturn(new Organization().setId(ORG_ID));
		when(aclDao.canAccess(any(UserInfo.class), eq(ORG_ID), eq(ObjectType.ORGANIZATION), eq(ACCESS_TYPE.DELETE)))
				.thenReturn(AuthorizationStatus.authorized());

		// call under test
		manager.delete(sageUser(), 1L);

		verify(textAnalyzerDao).delete(1L);
	}

	// --- list ---

	@Test
	public void testListByOrganizationDelegates() {
		when(textAnalyzerDao.listByOrganization(ORG_NAME, 51L, 0L))
				.thenReturn(Collections.singletonList(validRequest().setId("1")));

		// call under test
		manager.list(adminUser(),
				new ListTextAnalyzersRequest().setOrganizationName(ORG_NAME));

		verify(textAnalyzerDao).listByOrganization(ORG_NAME, 51L, 0L);
		verify(textAnalyzerDao, never()).listAll(
				org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong());
	}

	@Test
	public void testListWithoutOrganizationFallsBackToListAll() {
		when(textAnalyzerDao.listAll(51L, 0L)).thenReturn(Collections.emptyList());

		// call under test
		manager.list(adminUser(), new ListTextAnalyzersRequest());

		verify(textAnalyzerDao).listAll(51L, 0L);
		verify(textAnalyzerDao, never()).listByOrganization(
				org.mockito.ArgumentMatchers.any(String.class),
				org.mockito.ArgumentMatchers.anyLong(),
				org.mockito.ArgumentMatchers.anyLong());
	}

	@Test
	public void testListReturnsNextPageTokenWhenMoreResults() {
		List<TextAnalyzer> page = new ArrayList<>();
		for (int i = 0; i < 51; i++) {
			page.add(validRequest().setId(String.valueOf(i)));
		}
		when(textAnalyzerDao.listAll(51L, 0L)).thenReturn(page);

		// call under test
		ListTextAnalyzersResponse response = manager.list(adminUser(), new ListTextAnalyzersRequest());

		assertNotNull(response.getNextPageToken());
		assertEquals(50, response.getResults().size());
	}

	@Test
	public void testListReturnsNullNextPageTokenWhenNoMoreResults() {
		List<TextAnalyzer> page = Collections.singletonList(validRequest().setId("1"));
		when(textAnalyzerDao.listAll(51L, 0L)).thenReturn(page);

		// call under test
		ListTextAnalyzersResponse response = manager.list(adminUser(), new ListTextAnalyzersRequest());

		assertNull(response.getNextPageToken());
		assertEquals(1, response.getResults().size());
	}
}
