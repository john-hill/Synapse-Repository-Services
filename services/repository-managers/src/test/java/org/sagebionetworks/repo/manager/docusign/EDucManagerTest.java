package org.sagebionetworks.repo.manager.docusign;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.sagebionetworks.repo.model.AuthorizationConstants.DEFAULT_REALM_ID;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.sagebionetworks.docusign.DocuSignClient;
import org.sagebionetworks.docusign.RoleLabelKey;
import org.sagebionetworks.repo.model.AccessRequirementDAO;
import org.sagebionetworks.repo.model.ManagedACTAccessRequirement;
import org.sagebionetworks.repo.model.TeamConstants;
import org.sagebionetworks.repo.model.TermsOfUseAccessRequirement;
import org.sagebionetworks.repo.model.UnauthorizedException;
import org.sagebionetworks.repo.model.UserInfo;
import org.sagebionetworks.repo.model.UserProfile;
import org.sagebionetworks.repo.model.UserProfileDAO;
import org.sagebionetworks.repo.model.dao.NotificationEmailDAO;
import org.sagebionetworks.repo.model.dataaccess.AccessType;
import org.sagebionetworks.repo.model.dataaccess.AccessorChange;
import org.sagebionetworks.repo.model.dataaccess.PrincipalInvestigator;
import org.sagebionetworks.repo.model.dataaccess.Request;
import org.sagebionetworks.repo.model.dataaccess.RequestInterface;
import org.sagebionetworks.repo.model.dataaccess.SigningOfficial;
import org.sagebionetworks.repo.model.dbo.dao.dataaccess.RequestDAO;
import org.sagebionetworks.repo.model.educ.EDucTemplate;
import org.sagebionetworks.repo.model.educ.EDucTemplateListRequest;
import org.sagebionetworks.repo.model.educ.EDucTemplatePage;
import org.sagebionetworks.repo.model.principal.PrincipalAliasDAO;

@ExtendWith(MockitoExtension.class)
public class EDucManagerTest {

	@Mock
	private DocuSignClient mockDocuSignClient;
	@Mock
	private RequestDAO mockRequestDao;
	@Mock
	private AccessRequirementDAO mockAccessRequirementDao;
	@Mock
	private PrincipalAliasDAO mockPrincipalAliasDao;
	@Mock
	private NotificationEmailDAO mockNotificationEmailDao;
	@Mock
	private UserProfileDAO mockUserProfileDao;

	private EDucManager manager;

	private UserInfo adminUser;
	private UserInfo actUser;
	private UserInfo regularUser;

	@BeforeEach
	public void before() {
		manager = new EDucManager(mockDocuSignClient, mockRequestDao, mockAccessRequirementDao,
				mockPrincipalAliasDao, mockNotificationEmailDao, mockUserProfileDao);

		adminUser = new UserInfo(true, 1L, DEFAULT_REALM_ID);

		actUser = new UserInfo(false, 2L, DEFAULT_REALM_ID);
		actUser.setGroups(new HashSet<>(Collections.singleton(TeamConstants.ACT_TEAM_ID)));

		regularUser = new UserInfo(false, 3L, DEFAULT_REALM_ID);
		regularUser.setGroups(new HashSet<>());
	}

	private EDucTemplate template(String id) {
		EDucTemplate t = new EDucTemplate();
		t.setTemplateId(id);
		t.setName("template-" + id);
		return t;
	}

	// --- listTemplates tests ---

	@Test
	public void testListTemplatesWithNullUserInfo() {
		// call under test
		assertThrows(IllegalArgumentException.class,
				() -> manager.listTemplates(null, new EDucTemplateListRequest()));
		verifyNoInteractions(mockDocuSignClient);
	}

	@Test
	public void testListTemplatesWithNullRequest() {
		// call under test
		assertThrows(IllegalArgumentException.class,
				() -> manager.listTemplates(adminUser, null));
		verifyNoInteractions(mockDocuSignClient);
	}

	@Test
	public void testListTemplatesWithUnauthorizedUser() {
		// call under test
		assertThrows(UnauthorizedException.class,
				() -> manager.listTemplates(regularUser, new EDucTemplateListRequest()));
		verifyNoInteractions(mockDocuSignClient);
	}

	@Test
	public void testListTemplatesAsAdminFirstPageWithNoNextPage() throws Exception {
		EDucTemplatePage clientPage = new EDucTemplatePage();
		clientPage.setResults(new ArrayList<>(Arrays.asList(template("a"), template("b"))));
		when(mockDocuSignClient.listTemplates(0, 51)).thenReturn(clientPage);

		// call under test
		EDucTemplatePage page = manager.listTemplates(adminUser, new EDucTemplateListRequest());

		assertNotNull(page);
		assertEquals(2, page.getResults().size());
		assertNull(page.getNextPageToken());
		verify(mockDocuSignClient).listTemplates(0, 51);
	}

	@Test
	public void testListTemplatesAsACTFirstPageWithNextPage() throws Exception {
		List<EDucTemplate> results = new ArrayList<>();
		for (int i = 0; i < 51; i++) {
			results.add(template(String.valueOf(i)));
		}
		EDucTemplatePage clientPage = new EDucTemplatePage();
		clientPage.setResults(results);
		when(mockDocuSignClient.listTemplates(0, 51)).thenReturn(clientPage);

		// call under test
		EDucTemplatePage page = manager.listTemplates(actUser, new EDucTemplateListRequest());

		assertNotNull(page);
		assertEquals(50, page.getResults().size());
		assertNotNull(page.getNextPageToken());
	}

	@Test
	public void testListTemplatesHonorsIncomingNextPageToken() throws Exception {
		EDucTemplatePage clientPage = new EDucTemplatePage();
		clientPage.setResults(new ArrayList<>(Arrays.asList(template("x"))));
		when(mockDocuSignClient.listTemplates(50, 51)).thenReturn(clientPage);

		EDucTemplateListRequest request = new EDucTemplateListRequest();
		request.setNextPageToken("50a50");

		// call under test
		EDucTemplatePage page = manager.listTemplates(adminUser, request);

		assertEquals(1, page.getResults().size());
		assertNull(page.getNextPageToken());
		verify(mockDocuSignClient).listTemplates(50, 51);
	}

	@Test
	public void testListTemplatesWithEmptyClientResponse() throws Exception {
		EDucTemplatePage clientPage = new EDucTemplatePage();
		clientPage.setResults(new ArrayList<>());
		when(mockDocuSignClient.listTemplates(anyInt(), anyInt())).thenReturn(clientPage);

		// call under test
		EDucTemplatePage page = manager.listTemplates(adminUser, new EDucTemplateListRequest());

		assertEquals(0, page.getResults().size());
		assertNull(page.getNextPageToken());
	}

	// --- routeForSignature tests ---

	private Request buildValidRequest() {
		PrincipalInvestigator pi = new PrincipalInvestigator();
		pi.setUserId("200");
		pi.setName("Dr. Jones");
		pi.setTitle("Professor");
		pi.setInstitutionalEmail("pi@university.edu");

		SigningOfficial so = new SigningOfficial();
		so.setName("Jane Admin");
		so.setTitle("VP Research");
		so.setInstitutionalEmail("so@university.edu");

		AccessorChange collab1 = new AccessorChange();
		collab1.setUserId("301");
		collab1.setType(AccessType.GAIN_ACCESS);

		AccessorChange collab2 = new AccessorChange();
		collab2.setUserId("302");
		collab2.setType(AccessType.RENEW_ACCESS);

		AccessorChange revoked = new AccessorChange();
		revoked.setUserId("303");
		revoked.setType(AccessType.REVOKE_ACCESS);

		Request request = new Request();
		request.setId("req-1");
		request.setCreatedBy("100");
		request.setAccessRequirementId("ar-1");
		request.setInstitution("MIT");
		request.setPrincipalInvestigator(pi);
		request.setSigningOfficial(so);
		request.setAccessorChanges(List.of(collab1, collab2, revoked));
		return request;
	}

	private ManagedACTAccessRequirement buildValidAccessRequirement() {
		ManagedACTAccessRequirement ar = new ManagedACTAccessRequirement();
		ar.setId(1L);
		ar.setIsDUCRequired(true);
		ar.setEDucTemplateId("tpl-abc");
		return ar;
	}

	@Test
	public void testRouteForSignatureSuccess() {
		UserInfo user = new UserInfo(false, 100L, DEFAULT_REALM_ID);
		Request request = buildValidRequest();
		when(mockRequestDao.get("req-1")).thenReturn(request);
		when(mockAccessRequirementDao.get("ar-1")).thenReturn(buildValidAccessRequirement());
		when(mockPrincipalAliasDao.getUserName(200L)).thenReturn("drjones");
		when(mockPrincipalAliasDao.getUserName(301L)).thenReturn("collab1user");
		when(mockPrincipalAliasDao.getUserName(302L)).thenReturn("collab2user");
		when(mockNotificationEmailDao.getNotificationEmailForPrincipal(301L)).thenReturn("c1@example.com");
		when(mockNotificationEmailDao.getNotificationEmailForPrincipal(302L)).thenReturn("c2@example.com");
		UserProfile profile1 = new UserProfile();
		profile1.setFirstName("Alice");
		profile1.setLastName("Smith");
		when(mockUserProfileDao.get("301")).thenReturn(profile1);
		UserProfile profile2 = new UserProfile();
		profile2.setFirstName("Bob");
		profile2.setLastName("Brown");
		when(mockUserProfileDao.get("302")).thenReturn(profile2);
		when(mockDocuSignClient.createAndSendEnvelope(eq("tpl-abc"), any(), any())).thenReturn("env-xyz");
		when(mockRequestDao.update(any())).thenAnswer(i -> i.getArgument(0));

		// call under test
		RequestInterface result = manager.routeForSignature(user, "req-1");

		assertEquals("env-xyz", result.getEDucSignatureEnvelopeId());

		@SuppressWarnings("unchecked")
		ArgumentCaptor<Map<String, String>> emailsCaptor = ArgumentCaptor.forClass(Map.class);
		@SuppressWarnings("unchecked")
		ArgumentCaptor<Map<RoleLabelKey, String>> tabsCaptor = ArgumentCaptor.forClass(Map.class);
		verify(mockDocuSignClient).createAndSendEnvelope(eq("tpl-abc"), emailsCaptor.capture(), tabsCaptor.capture());

		Map<String, String> roleEmails = emailsCaptor.getValue();
		assertEquals("pi@university.edu", roleEmails.get("principal_investigator"));
		assertEquals("so@university.edu", roleEmails.get("signing_official"));
		assertEquals("c1@example.com", roleEmails.get("collaborator_1"));
		assertEquals("c2@example.com", roleEmails.get("collaborator_2"));
		assertEquals(4, roleEmails.size());

		Map<RoleLabelKey, String> tabValues = tabsCaptor.getValue();
		assertEquals("Dr. Jones", tabValues.get(new RoleLabelKey("principal_investigator", "principal_investigator_name")));
		assertEquals("Professor", tabValues.get(new RoleLabelKey("principal_investigator", "principal_investigator_title")));
		assertEquals("pi@university.edu", tabValues.get(new RoleLabelKey("principal_investigator", "principal_investigator_email")));
		assertEquals("MIT", tabValues.get(new RoleLabelKey("principal_investigator", "principal_investigator_institution")));
		assertEquals("drjones", tabValues.get(new RoleLabelKey("principal_investigator", "principal_investigator_user_name")));
		assertEquals("Jane Admin", tabValues.get(new RoleLabelKey("signing_official", "signing_official_name")));
		assertEquals("VP Research", tabValues.get(new RoleLabelKey("signing_official", "signing_official_title")));
		assertEquals("so@university.edu", tabValues.get(new RoleLabelKey("signing_official", "signing_official_email")));
		assertEquals("collab1user", tabValues.get(new RoleLabelKey("collaborator_1", "collaborator_1_user_name")));
		assertEquals("Alice Smith", tabValues.get(new RoleLabelKey("collaborator_1", "collaborator_1_name")));
		assertEquals("collab2user", tabValues.get(new RoleLabelKey("collaborator_2", "collaborator_2_user_name")));
		assertEquals("Bob Brown", tabValues.get(new RoleLabelKey("collaborator_2", "collaborator_2_name")));
	}

	@Test
	public void testRouteForSignatureWithUnauthorizedUser() {
		Request request = buildValidRequest();
		when(mockRequestDao.get("req-1")).thenReturn(request);

		// call under test
		assertThrows(UnauthorizedException.class, () -> manager.routeForSignature(regularUser, "req-1"));

		verifyNoInteractions(mockDocuSignClient);
	}

	@Test
	public void testRouteForSignatureWithAdminUser() {
		Request request = buildValidRequest();
		when(mockRequestDao.get("req-1")).thenReturn(request);
		when(mockAccessRequirementDao.get("ar-1")).thenReturn(buildValidAccessRequirement());
		when(mockPrincipalAliasDao.getUserName(any(Long.class))).thenReturn("someuser");
		when(mockNotificationEmailDao.getNotificationEmailForPrincipal(any(Long.class))).thenReturn("x@y.com");
		UserProfile profile = new UserProfile();
		profile.setFirstName("A");
		profile.setLastName("B");
		when(mockUserProfileDao.get(any(String.class))).thenReturn(profile);
		when(mockDocuSignClient.createAndSendEnvelope(any(), any(), any())).thenReturn("env-1");
		when(mockRequestDao.update(any())).thenAnswer(i -> i.getArgument(0));

		// call under test
		RequestInterface result = manager.routeForSignature(adminUser, "req-1");

		assertEquals("env-1", result.getEDucSignatureEnvelopeId());
	}

	@Test
	public void testRouteForSignatureWithExistingEnvelope() {
		Request request = buildValidRequest();
		request.setEDucSignatureEnvelopeId("existing-env");
		UserInfo user = new UserInfo(false, 100L, DEFAULT_REALM_ID);
		when(mockRequestDao.get("req-1")).thenReturn(request);

		// call under test
		assertThrows(IllegalArgumentException.class, () -> manager.routeForSignature(user, "req-1"));

		verifyNoInteractions(mockDocuSignClient);
	}

	@Test
	public void testRouteForSignatureWithNonManagedACTRequirement() {
		Request request = buildValidRequest();
		UserInfo user = new UserInfo(false, 100L, DEFAULT_REALM_ID);
		when(mockRequestDao.get("req-1")).thenReturn(request);
		when(mockAccessRequirementDao.get("ar-1")).thenReturn(new TermsOfUseAccessRequirement());

		// call under test
		assertThrows(IllegalArgumentException.class, () -> manager.routeForSignature(user, "req-1"));

		verifyNoInteractions(mockDocuSignClient);
	}

	@Test
	public void testRouteForSignatureWithDUCNotRequired() {
		Request request = buildValidRequest();
		UserInfo user = new UserInfo(false, 100L, DEFAULT_REALM_ID);
		ManagedACTAccessRequirement ar = buildValidAccessRequirement();
		ar.setIsDUCRequired(false);
		when(mockRequestDao.get("req-1")).thenReturn(request);
		when(mockAccessRequirementDao.get("ar-1")).thenReturn(ar);

		// call under test
		assertThrows(IllegalArgumentException.class, () -> manager.routeForSignature(user, "req-1"));

		verifyNoInteractions(mockDocuSignClient);
	}

	@Test
	public void testRouteForSignatureWithNoTemplateId() {
		Request request = buildValidRequest();
		UserInfo user = new UserInfo(false, 100L, DEFAULT_REALM_ID);
		ManagedACTAccessRequirement ar = buildValidAccessRequirement();
		ar.setEDucTemplateId(null);
		when(mockRequestDao.get("req-1")).thenReturn(request);
		when(mockAccessRequirementDao.get("ar-1")).thenReturn(ar);

		// call under test
		assertThrows(IllegalArgumentException.class, () -> manager.routeForSignature(user, "req-1"));

		verifyNoInteractions(mockDocuSignClient);
	}

	@Test
	public void testRouteForSignatureWithNoCollaborators() {
		Request request = buildValidRequest();
		request.setAccessorChanges(Collections.emptyList());
		UserInfo user = new UserInfo(false, 100L, DEFAULT_REALM_ID);
		when(mockRequestDao.get("req-1")).thenReturn(request);
		when(mockAccessRequirementDao.get("ar-1")).thenReturn(buildValidAccessRequirement());
		when(mockPrincipalAliasDao.getUserName(200L)).thenReturn("drjones");
		when(mockDocuSignClient.createAndSendEnvelope(eq("tpl-abc"), any(), any())).thenReturn("env-no-collabs");
		when(mockRequestDao.update(any())).thenAnswer(i -> i.getArgument(0));

		// call under test
		RequestInterface result = manager.routeForSignature(user, "req-1");

		assertEquals("env-no-collabs", result.getEDucSignatureEnvelopeId());

		@SuppressWarnings("unchecked")
		ArgumentCaptor<Map<String, String>> emailsCaptor = ArgumentCaptor.forClass(Map.class);
		verify(mockDocuSignClient).createAndSendEnvelope(eq("tpl-abc"), emailsCaptor.capture(), any());
		assertEquals(2, emailsCaptor.getValue().size());
	}

	@Test
	public void testBuildFullName() {
		assertEquals("Alice Smith", EDucManager.buildFullName("Alice", "Smith"));
		assertEquals("Alice", EDucManager.buildFullName("Alice", null));
		assertEquals("Smith", EDucManager.buildFullName(null, "Smith"));
		assertNull(EDucManager.buildFullName(null, null));
	}
}
