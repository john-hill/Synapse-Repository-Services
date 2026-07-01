package org.sagebionetworks.docusign;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.sagebionetworks.repo.model.educ.EDucTemplate;
import org.sagebionetworks.repo.model.educ.EDucTemplatePage;

import com.docusign.esign.client.ApiException;
import com.docusign.esign.model.DateSigned;
import com.docusign.esign.model.Email;
import com.docusign.esign.model.EnvelopeDefinition;
import com.docusign.esign.model.EnvelopeSummary;
import com.docusign.esign.model.EnvelopeTemplate;
import com.docusign.esign.model.EnvelopeTemplateResults;
import com.docusign.esign.model.FullName;
import com.docusign.esign.model.Recipients;
import com.docusign.esign.model.SignHere;
import com.docusign.esign.model.Signer;
import com.docusign.esign.model.Tabs;
import com.docusign.esign.model.TemplateRole;
import com.docusign.esign.model.Text;
import com.docusign.esign.model.Title;

@ExtendWith(MockitoExtension.class)
public class DocuSignClientTest {

	@Mock
	private DocuSignClientConfig mockConfig;
	@Mock
	private DocuSignTemplatesApi mockDocuSignTemplatesApi;
	@Mock
	private DocuSignEnvelopesApi mockDocuSignEnvelopesApi;
	@Mock
	private DocuSignAccessTokenProvider mockAccessTokenProvider;

	@InjectMocks
	private DocuSignClient client;

	private static final String BASE_PATH = "https://demo.docusign.net/restapi";
	private static final String ACCOUNT_ID = "account-guid";
	private static final String ACCESS_TOKEN = "access-token";

	@Test
	public void testListTemplatesSuccess() throws Exception {
		when(mockAccessTokenProvider.getAccessToken()).thenReturn(ACCESS_TOKEN);
		when(mockConfig.getBasePath()).thenReturn(BASE_PATH);
		when(mockConfig.getAccountId()).thenReturn(ACCOUNT_ID);

		String createdIso = "2024-01-15T10:00:00.0000000Z";
		String modifiedIso = "2024-02-20T15:30:00.0000000Z";
		EnvelopeTemplate t1 = new EnvelopeTemplate();
		t1.setTemplateId("tpl-1");
		t1.setName("Consent Form");
		t1.setDescription("Standard consent form");
		t1.setCreatedDateTime(createdIso);
		t1.setLastModifiedDateTime(modifiedIso);
		EnvelopeTemplate t2 = new EnvelopeTemplate();
		t2.setTemplateId("tpl-2");
		t2.setName("Data Sharing Agreement");
		t2.setDescription("Data sharing agreement");
		EnvelopeTemplateResults results = new EnvelopeTemplateResults();
		results.setEnvelopeTemplates(Arrays.asList(t1, t2));
		when(mockDocuSignTemplatesApi.listTemplates(eq(BASE_PATH), eq(ACCESS_TOKEN), eq(ACCOUNT_ID), any(), any()))
				.thenReturn(results);

		// call under test
		EDucTemplatePage page = client.listTemplates(0, 51);

		assertNotNull(page);
		assertNull(page.getNextPageToken());
		assertEquals(2, page.getResults().size());

		EDucTemplate mapped1 = new EDucTemplate();
		mapped1.setTemplateId("tpl-1");
		mapped1.setName("Consent Form");
		mapped1.setDescription("Standard consent form");
		mapped1.setCreatedOn(Date.from(Instant.parse(createdIso)));
		mapped1.setModifiedOn(Date.from(Instant.parse(modifiedIso)));
		assertEquals(mapped1, page.getResults().get(0));

		EDucTemplate mapped2 = new EDucTemplate();
		mapped2.setTemplateId("tpl-2");
		mapped2.setName("Data Sharing Agreement");
		mapped2.setDescription("Data sharing agreement");
		assertEquals(mapped2, page.getResults().get(1));

		verify(mockDocuSignTemplatesApi).listTemplates(BASE_PATH, ACCESS_TOKEN, ACCOUNT_ID, "0", "51");
	}

	@Test
	public void testListTemplatesWithEmptyResults() throws Exception {
		when(mockAccessTokenProvider.getAccessToken()).thenReturn(ACCESS_TOKEN);
		when(mockConfig.getBasePath()).thenReturn(BASE_PATH);
		when(mockConfig.getAccountId()).thenReturn(ACCOUNT_ID);
		EnvelopeTemplateResults results = new EnvelopeTemplateResults();
		results.setEnvelopeTemplates(null);
		when(mockDocuSignTemplatesApi.listTemplates(eq(BASE_PATH), eq(ACCESS_TOKEN), eq(ACCOUNT_ID), any(), any()))
				.thenReturn(results);

		// call under test
		EDucTemplatePage page = client.listTemplates(0, 51);

		assertNotNull(page);
		assertEquals(Collections.emptyList(), page.getResults());
	}

	@Test
	public void testListTemplatesInvalidatesCacheOn401AndRetries() throws Exception {
		when(mockAccessTokenProvider.getAccessToken())
				.thenReturn("first-token")
				.thenReturn("retry-token");
		when(mockConfig.getBasePath()).thenReturn(BASE_PATH);
		when(mockConfig.getAccountId()).thenReturn(ACCOUNT_ID);

		EnvelopeTemplateResults success = new EnvelopeTemplateResults();
		when(mockDocuSignTemplatesApi.listTemplates(eq(BASE_PATH), any(), eq(ACCOUNT_ID), any(), any()))
				.thenThrow(new ApiException(401, "Unauthorized"))
				.thenReturn(success);

		// call under test
		EDucTemplatePage page = client.listTemplates(0, 51);

		assertNotNull(page);
		verify(mockAccessTokenProvider).invalidateAccessToken();
		verify(mockAccessTokenProvider, times(2)).getAccessToken();
		verify(mockDocuSignTemplatesApi).listTemplates(BASE_PATH, "first-token", ACCOUNT_ID, "0", "51");
		verify(mockDocuSignTemplatesApi).listTemplates(BASE_PATH, "retry-token", ACCOUNT_ID, "0", "51");
	}

	@Test
	public void testListTemplatesPropagatesPersistent401() throws Exception {
		when(mockAccessTokenProvider.getAccessToken())
				.thenReturn("t1")
				.thenReturn("t2");
		when(mockConfig.getBasePath()).thenReturn(BASE_PATH);
		when(mockConfig.getAccountId()).thenReturn(ACCOUNT_ID);
		when(mockDocuSignTemplatesApi.listTemplates(eq(BASE_PATH), any(), eq(ACCOUNT_ID), any(), any()))
				.thenThrow(new ApiException(401, "Unauthorized"));

		// call under test
		assertThrows(DocuSignUnauthorizedException.class, () -> client.listTemplates(0, 51));

		verify(mockAccessTokenProvider).invalidateAccessToken();
		verify(mockAccessTokenProvider, times(2)).getAccessToken();
	}

	@Test
	public void testListTemplatesWithServerError() throws Exception {
		when(mockAccessTokenProvider.getAccessToken()).thenReturn(ACCESS_TOKEN);
		when(mockConfig.getBasePath()).thenReturn(BASE_PATH);
		when(mockConfig.getAccountId()).thenReturn(ACCOUNT_ID);
		when(mockDocuSignTemplatesApi.listTemplates(eq(BASE_PATH), eq(ACCESS_TOKEN), eq(ACCOUNT_ID), any(), any()))
				.thenThrow(new ApiException(500, "Server error"));

		// call under test
		IllegalStateException ex = assertThrows(IllegalStateException.class,
				() -> client.listTemplates(0, 51));
		assertTrue(ex.getMessage().contains("500"));
	}

	@Test
	public void testHandleApiExceptionMapping() {
		assertEquals(DocuSignUnauthorizedException.class,
				DocuSignClient.convertApiException(new ApiException(401, "x")).getClass());
		assertEquals(IllegalStateException.class,
				DocuSignClient.convertApiException(new ApiException(403, "x")).getClass());
		assertEquals(IllegalStateException.class,
				DocuSignClient.convertApiException(new ApiException(404, "x")).getClass());
		assertEquals(IllegalStateException.class,
				DocuSignClient.convertApiException(new ApiException(500, "x")).getClass());
		assertEquals(IllegalStateException.class,
				DocuSignClient.convertApiException(new ApiException(429, "x")).getClass());
	}

	@Test
	public void testListTemplatesWithTokenProviderFailure() {
		when(mockAccessTokenProvider.getAccessToken()).thenThrow(new IllegalStateException("boom"));

		// call under test
		assertThrows(IllegalStateException.class, () -> client.listTemplates(0, 51));
	}

	@Test
	public void testValidateTemplateSuccess() throws Exception {
		when(mockAccessTokenProvider.getAccessToken()).thenReturn(ACCESS_TOKEN);
		when(mockConfig.getBasePath()).thenReturn(BASE_PATH);
		when(mockConfig.getAccountId()).thenReturn(ACCOUNT_ID);
		EnvelopeTemplate template = buildValidTemplate();
		when(mockDocuSignTemplatesApi.getTemplate(BASE_PATH, ACCESS_TOKEN, ACCOUNT_ID, "tpl-1"))
				.thenReturn(template);

		// call under test
		client.validateTemplate("tpl-1");

		verify(mockDocuSignTemplatesApi).getTemplate(BASE_PATH, ACCESS_TOKEN, ACCOUNT_ID, "tpl-1");
	}

	@Test
	public void testValidateTemplateWithInvalidTemplate() throws Exception {
		when(mockAccessTokenProvider.getAccessToken()).thenReturn(ACCESS_TOKEN);
		when(mockConfig.getBasePath()).thenReturn(BASE_PATH);
		when(mockConfig.getAccountId()).thenReturn(ACCOUNT_ID);
		EnvelopeTemplate template = new EnvelopeTemplate();
		template.setRecipients(null);
		when(mockDocuSignTemplatesApi.getTemplate(BASE_PATH, ACCESS_TOKEN, ACCOUNT_ID, "tpl-1"))
				.thenReturn(template);

		// call under test
		assertThrows(IllegalArgumentException.class, () -> client.validateTemplate("tpl-1"));
	}

	@Test
	public void testValidateTemplateRetryOn401() throws Exception {
		when(mockAccessTokenProvider.getAccessToken())
				.thenReturn("first-token")
				.thenReturn("retry-token");
		when(mockConfig.getBasePath()).thenReturn(BASE_PATH);
		when(mockConfig.getAccountId()).thenReturn(ACCOUNT_ID);
		EnvelopeTemplate template = buildValidTemplate();
		when(mockDocuSignTemplatesApi.getTemplate(eq(BASE_PATH), any(), eq(ACCOUNT_ID), eq("tpl-1")))
				.thenThrow(new ApiException(401, "Unauthorized"))
				.thenReturn(template);

		// call under test
		client.validateTemplate("tpl-1");

		verify(mockAccessTokenProvider).invalidateAccessToken();
		verify(mockAccessTokenProvider, times(2)).getAccessToken();
	}

	@Test
	public void testCreateAndSendEnvelopeSuccess() throws Exception {
		when(mockAccessTokenProvider.getAccessToken()).thenReturn(ACCESS_TOKEN);
		when(mockConfig.getBasePath()).thenReturn(BASE_PATH);
		when(mockConfig.getAccountId()).thenReturn(ACCOUNT_ID);
		EnvelopeTemplate template = buildValidTemplate();
		when(mockDocuSignTemplatesApi.getTemplate(BASE_PATH, ACCESS_TOKEN, ACCOUNT_ID, "tpl-1"))
				.thenReturn(template);
		EnvelopeSummary summary = new EnvelopeSummary();
		summary.setEnvelopeId("env-123");
		when(mockDocuSignEnvelopesApi.createEnvelope(eq(BASE_PATH), eq(ACCESS_TOKEN), eq(ACCOUNT_ID), any()))
				.thenReturn(summary);

		Map<String, String> roleEmails = Map.of(
				"signing_official", "so@example.com",
				"principal_investigator", "pi@example.com"
		);
		Map<RoleTabKey, String> tabValues = Map.of(
				new RoleTabKey("signing_official", "signing_official_name"), "Dr. Smith",
				new RoleTabKey("principal_investigator", "principal_investigator_name"), "Dr. Jones"
		);

		// call under test
		String envelopeId = client.createAndSendEnvelope("tpl-1", roleEmails, tabValues);

		assertEquals("env-123", envelopeId);
		ArgumentCaptor<EnvelopeDefinition> captor = ArgumentCaptor.forClass(EnvelopeDefinition.class);
		verify(mockDocuSignEnvelopesApi).createEnvelope(eq(BASE_PATH), eq(ACCESS_TOKEN), eq(ACCOUNT_ID), captor.capture());
		EnvelopeDefinition captured = captor.getValue();
		assertEquals("tpl-1", captured.getTemplateId());
		assertEquals("sent", captured.getStatus());
		assertEquals(2, captured.getTemplateRoles().size());
	}

	@Test
	public void testCreateAndSendEnvelopeRetryOn401() throws Exception {
		when(mockAccessTokenProvider.getAccessToken())
				.thenReturn("first-token")
				.thenReturn("retry-token");
		when(mockConfig.getBasePath()).thenReturn(BASE_PATH);
		when(mockConfig.getAccountId()).thenReturn(ACCOUNT_ID);
		EnvelopeTemplate template = buildValidTemplate();
		when(mockDocuSignTemplatesApi.getTemplate(eq(BASE_PATH), any(), eq(ACCOUNT_ID), eq("tpl-1")))
				.thenThrow(new ApiException(401, "Unauthorized"))
				.thenReturn(template);
		EnvelopeSummary summary = new EnvelopeSummary();
		summary.setEnvelopeId("env-456");
		when(mockDocuSignEnvelopesApi.createEnvelope(eq(BASE_PATH), any(), eq(ACCOUNT_ID), any()))
				.thenReturn(summary);

		Map<String, String> roleEmails = Map.of("signing_official", "so@example.com");
		Map<RoleTabKey, String> tabValues = Map.of();

		// call under test
		String envelopeId = client.createAndSendEnvelope("tpl-1", roleEmails, tabValues);

		assertEquals("env-456", envelopeId);
		verify(mockAccessTokenProvider).invalidateAccessToken();
	}

	@Test
	public void testCreateAndSendEnvelopeValidationFailure() throws Exception {
		when(mockAccessTokenProvider.getAccessToken()).thenReturn(ACCESS_TOKEN);
		when(mockConfig.getBasePath()).thenReturn(BASE_PATH);
		when(mockConfig.getAccountId()).thenReturn(ACCOUNT_ID);
		EnvelopeTemplate template = new EnvelopeTemplate();
		template.setRecipients(null);
		when(mockDocuSignTemplatesApi.getTemplate(BASE_PATH, ACCESS_TOKEN, ACCOUNT_ID, "tpl-1"))
				.thenReturn(template);

		Map<String, String> roleEmails = Map.of("signing_official", "so@example.com");
		Map<RoleTabKey, String> tabValues = Map.of();

		// call under test
		assertThrows(IllegalArgumentException.class,
				() -> client.createAndSendEnvelope("tpl-1", roleEmails, tabValues));

		verifyNoInteractions(mockDocuSignEnvelopesApi);
	}

	@Test
	public void testBuildTemplateRolesDerivesNameFromTabValues() {
		Map<String, String> roleEmails = Map.of("signing_official", "so@example.com");
		Map<RoleTabKey, String> tabValues = Map.of(
				new RoleTabKey("signing_official", "signing_official_name"), "Dr. Smith",
				new RoleTabKey("signing_official", "signing_official_title"), "Director"
		);

		// call under test
		List<TemplateRole> roles = DocuSignClient.buildTemplateRoles(roleEmails, tabValues);

		assertEquals(1, roles.size());
		TemplateRole role = roles.get(0);
		assertEquals("signing_official", role.getRoleName());
		assertEquals("so@example.com", role.getEmail());
		assertEquals("Dr. Smith", role.getName());
		assertEquals(2, role.getTabs().getTextTabs().size());
	}

	private static EnvelopeTemplate buildValidTemplate() {
		EnvelopeTemplate template = new EnvelopeTemplate();
		Recipients recipients = new Recipients();
		List<Signer> signers = new java.util.ArrayList<>();
		signers.add(buildSigningOfficialSigner());
		signers.add(buildPrincipalInvestigatorSigner());
		signers.add(buildCollaboratorSigner(1));
		recipients.setSigners(signers);
		template.setRecipients(recipients);
		return template;
	}

	private static Signer buildSigningOfficialSigner() {
		Signer signer = new Signer();
		signer.setRoleName("signing_official");
		Tabs tabs = new Tabs();
		FullName name = new FullName();
		name.setTabLabel("signing_official_name");
		tabs.setFullNameTabs(List.of(name));
		Title title = new Title();
		title.setTabLabel("signing_official_title");
		tabs.setTitleTabs(List.of(title));
		Email email = new Email();
		email.setTabLabel("signing_official_email");
		tabs.setEmailTabs(List.of(email));
		SignHere sig = new SignHere();
		sig.setTabLabel("signing_official_signature");
		tabs.setSignHereTabs(List.of(sig));
		DateSigned date = new DateSigned();
		date.setTabLabel("signing_official_date");
		tabs.setDateSignedTabs(List.of(date));
		signer.setTabs(tabs);
		return signer;
	}

	private static Signer buildPrincipalInvestigatorSigner() {
		Signer signer = new Signer();
		signer.setRoleName("principal_investigator");
		Tabs tabs = new Tabs();
		Text institution = new Text();
		institution.setTabLabel("principal_investigator_institution");
		Text userName = new Text();
		userName.setTabLabel("principal_investigator_user_name");
		tabs.setTextTabs(Arrays.asList(institution, userName));
		FullName name = new FullName();
		name.setTabLabel("principal_investigator_name");
		tabs.setFullNameTabs(List.of(name));
		Title title = new Title();
		title.setTabLabel("principal_investigator_title");
		tabs.setTitleTabs(List.of(title));
		Email email = new Email();
		email.setTabLabel("principal_investigator_email");
		tabs.setEmailTabs(List.of(email));
		SignHere sig = new SignHere();
		sig.setTabLabel("principal_investigator_signature");
		tabs.setSignHereTabs(List.of(sig));
		DateSigned date = new DateSigned();
		date.setTabLabel("principal_investigator_date");
		tabs.setDateSignedTabs(List.of(date));
		signer.setTabs(tabs);
		return signer;
	}

	private static Signer buildCollaboratorSigner(int index) {
		Signer signer = new Signer();
		signer.setRoleName("collaborator_" + index);
		String prefix = "collaborator_" + index + "_";
		Tabs tabs = new Tabs();
		Text userName = new Text();
		userName.setTabLabel(prefix + "user_name");
		tabs.setTextTabs(List.of(userName));
		FullName name = new FullName();
		name.setTabLabel(prefix + "name");
		tabs.setFullNameTabs(List.of(name));
		SignHere sig = new SignHere();
		sig.setTabLabel(prefix + "signature");
		tabs.setSignHereTabs(List.of(sig));
		DateSigned date = new DateSigned();
		date.setTabLabel(prefix + "date");
		tabs.setDateSignedTabs(List.of(date));
		signer.setTabs(tabs);
		return signer;
	}
}
