package org.sagebionetworks.docusign;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
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

import com.docusign.esign.model.EnvelopeDefinition;
import com.docusign.esign.model.EnvelopeSummary;
import com.docusign.esign.model.EnvelopeTemplate;
import com.docusign.esign.model.EnvelopeTemplateResults;
import com.docusign.esign.model.TemplateRole;

@ExtendWith(MockitoExtension.class)
public class DocuSignClientTest {

	@Mock
	private DocuSignTemplatesApi mockDocuSignTemplatesApi;
	@Mock
	private DocuSignEnvelopesApi mockDocuSignEnvelopesApi;

	@InjectMocks
	private DocuSignClient client;

	@Test
	public void testListTemplatesSuccess() {
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
		when(mockDocuSignTemplatesApi.listTemplates("0", "51")).thenReturn(results);

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

		verify(mockDocuSignTemplatesApi).listTemplates("0", "51");
	}

	@Test
	public void testListTemplatesWithEmptyResults() {
		EnvelopeTemplateResults results = new EnvelopeTemplateResults();
		results.setEnvelopeTemplates(null);
		when(mockDocuSignTemplatesApi.listTemplates(any(), any())).thenReturn(results);

		// call under test
		EDucTemplatePage page = client.listTemplates(0, 51);

		assertNotNull(page);
		assertEquals(Collections.emptyList(), page.getResults());
	}

	@Test
	public void testValidateTemplateSuccess() {
		EnvelopeTemplate template = TestTemplateHelper.buildValidTemplate(1);
		when(mockDocuSignTemplatesApi.getTemplate("tpl-1")).thenReturn(template);

		// call under test
		client.validateTemplate("tpl-1");

		verify(mockDocuSignTemplatesApi).getTemplate("tpl-1");
	}

	@Test
	public void testValidateTemplateWithInvalidTemplate() {
		EnvelopeTemplate template = new EnvelopeTemplate();
		template.setRecipients(null);
		when(mockDocuSignTemplatesApi.getTemplate("tpl-1")).thenReturn(template);

		// call under test
		assertThrows(IllegalArgumentException.class, () -> client.validateTemplate("tpl-1"));
	}

	@Test
	public void testCreateAndSendEnvelopeSuccess() {
		EnvelopeTemplate template = TestTemplateHelper.buildValidTemplate(1);
		when(mockDocuSignTemplatesApi.getTemplate("tpl-1")).thenReturn(template);
		EnvelopeSummary summary = new EnvelopeSummary();
		summary.setEnvelopeId("env-123");
		when(mockDocuSignEnvelopesApi.createEnvelope(any())).thenReturn(summary);

		Map<String, String> roleEmails = Map.of(
				"signing_official", "so@example.com",
				"principal_investigator", "pi@example.com"
		);
		Map<RoleLabelKey, String> tabValues = Map.of(
				new RoleLabelKey("signing_official", "signing_official_name"), "Dr. Smith",
				new RoleLabelKey("principal_investigator", "principal_investigator_name"), "Dr. Jones"
		);

		// call under test
		String envelopeId = client.createAndSendEnvelope("tpl-1", roleEmails, tabValues);

		assertEquals("env-123", envelopeId);
		ArgumentCaptor<EnvelopeDefinition> captor = ArgumentCaptor.forClass(EnvelopeDefinition.class);
		verify(mockDocuSignEnvelopesApi).createEnvelope(captor.capture());
		EnvelopeDefinition captured = captor.getValue();
		assertEquals("tpl-1", captured.getTemplateId());
		assertEquals("sent", captured.getStatus());
		assertEquals(2, captured.getTemplateRoles().size());
	}

	@Test
	public void testCreateAndSendEnvelopeValidationFailure() {
		EnvelopeTemplate template = new EnvelopeTemplate();
		template.setRecipients(null);
		when(mockDocuSignTemplatesApi.getTemplate("tpl-1")).thenReturn(template);

		Map<String, String> roleEmails = Map.of("signing_official", "so@example.com");
		Map<RoleLabelKey, String> tabValues = Map.of();

		// call under test
		assertThrows(IllegalArgumentException.class,
				() -> client.createAndSendEnvelope("tpl-1", roleEmails, tabValues));

		verifyNoInteractions(mockDocuSignEnvelopesApi);
	}

	@Test
	public void testBuildTemplateRolesWithCorrectTabTypes() {
		Map<String, String> roleEmails = Map.of(
				"signing_official", "so@example.com",
				"principal_investigator", "pi@example.com"
		);
		Map<RoleLabelKey, String> tabValues = Map.of(
				new RoleLabelKey("signing_official", "signing_official_name"), "Dr. Smith",
				new RoleLabelKey("signing_official", "signing_official_title"), "Director",
				new RoleLabelKey("signing_official", "signing_official_email"), "so@example.com",
				new RoleLabelKey("principal_investigator", "principal_investigator_institution"), "MIT"
		);

		// call under test
		List<TemplateRole> roles = DocuSignClient.buildTemplateRoles(roleEmails, tabValues);

		assertEquals(2, roles.size());
		TemplateRole soRole = roles.stream()
				.filter(r -> "signing_official".equals(r.getRoleName())).findFirst().orElseThrow();
		assertEquals("so@example.com", soRole.getEmail());
		assertEquals("Dr. Smith", soRole.getName());
		assertEquals(1, soRole.getTabs().getFullNameTabs().size());
		assertEquals("signing_official_name", soRole.getTabs().getFullNameTabs().get(0).getTabLabel());
		assertEquals(1, soRole.getTabs().getTitleTabs().size());
		assertEquals("Director", soRole.getTabs().getTitleTabs().get(0).getValue());
		assertEquals(1, soRole.getTabs().getEmailTabs().size());
		assertEquals("so@example.com", soRole.getTabs().getEmailTabs().get(0).getValue());

		TemplateRole piRole = roles.stream()
				.filter(r -> "principal_investigator".equals(r.getRoleName())).findFirst().orElseThrow();
		assertEquals(1, piRole.getTabs().getTextTabs().size());
		assertEquals("MIT", piRole.getTabs().getTextTabs().get(0).getValue());
	}

	@Test
	public void testHandleApiExceptionMapping() {
		assertEquals(DocuSignUnauthorizedException.class,
				DocuSignApiRetryHelper.convertApiException(new ApiException(401, "x")).getClass());
		assertEquals(IllegalStateException.class,
				DocuSignApiRetryHelper.convertApiException(new ApiException(403, "x")).getClass());
		assertEquals(IllegalStateException.class,
				DocuSignApiRetryHelper.convertApiException(new ApiException(404, "x")).getClass());
		assertEquals(IllegalStateException.class,
				DocuSignApiRetryHelper.convertApiException(new ApiException(500, "x")).getClass());
		assertEquals(IllegalStateException.class,
				DocuSignApiRetryHelper.convertApiException(new ApiException(429, "x")).getClass());
	}
}
