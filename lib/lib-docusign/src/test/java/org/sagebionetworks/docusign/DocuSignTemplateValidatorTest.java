package org.sagebionetworks.docusign;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.docusign.esign.model.EnvelopeTemplate;
import com.docusign.esign.model.Recipients;
import com.docusign.esign.model.Signer;
import com.docusign.esign.model.Text;

public class DocuSignTemplateValidatorTest {

	@Test
	public void testValidateWithValidTemplate() {
		EnvelopeTemplate template = TestTemplateHelper.buildValidTemplate(2);

		// call under test
		assertDoesNotThrow(() -> DocuSignTemplateValidator.validate(template));
	}

	@Test
	public void testValidateWithNoCollaborators() {
		EnvelopeTemplate template = TestTemplateHelper.buildValidTemplate(0);

		// call under test
		assertDoesNotThrow(() -> DocuSignTemplateValidator.validate(template));
	}

	@Test
	public void testValidateWithNullRecipients() {
		EnvelopeTemplate template = new EnvelopeTemplate();
		template.setRecipients(null);

		// call under test
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				() -> DocuSignTemplateValidator.validate(template));
		assertTrue(ex.getMessage().contains("no signer roles"));
	}

	@Test
	public void testValidateWithMissingSigningOfficialRole() {
		EnvelopeTemplate template = TestTemplateHelper.buildValidTemplate(1);
		template.getRecipients().getSigners().removeIf(s -> "signing_official".equals(s.getRoleName()));

		// call under test
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				() -> DocuSignTemplateValidator.validate(template));
		assertTrue(ex.getMessage().contains("signing_official"));
	}

	@Test
	public void testValidateWithMissingPrincipalInvestigatorRole() {
		EnvelopeTemplate template = TestTemplateHelper.buildValidTemplate(1);
		template.getRecipients().getSigners().removeIf(s -> "principal_investigator".equals(s.getRoleName()));

		// call under test
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				() -> DocuSignTemplateValidator.validate(template));
		assertTrue(ex.getMessage().contains("principal_investigator"));
	}

	@Test
	public void testValidateWithMissingSigningOfficialTab() {
		EnvelopeTemplate template = TestTemplateHelper.buildValidTemplate(0);
		Signer so = TestTemplateHelper.findSigner(template, "signing_official");
		so.getTabs().setTitleTabs(null);

		// call under test
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				() -> DocuSignTemplateValidator.validate(template));
		assertTrue(ex.getMessage().contains("signing_official_title"));
		assertTrue(ex.getMessage().contains("TITLE"));
	}

	@Test
	public void testValidateWithMissingPrincipalInvestigatorTab() {
		EnvelopeTemplate template = TestTemplateHelper.buildValidTemplate(0);
		Signer pi = TestTemplateHelper.findSigner(template, "principal_investigator");
		Text userName = new Text();
		userName.setTabLabel("principal_investigator_user_name");
		pi.getTabs().setTextTabs(List.of(userName));

		// call under test
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				() -> DocuSignTemplateValidator.validate(template));
		assertTrue(ex.getMessage().contains("principal_investigator_institution"));
		assertTrue(ex.getMessage().contains("TEXT"));
	}

	@Test
	public void testValidateWithNonSequentialCollaborators() {
		EnvelopeTemplate template = TestTemplateHelper.buildValidTemplate(0);
		Recipients recipients = template.getRecipients();
		recipients.getSigners().add(TestTemplateHelper.buildCollaboratorSigner(1));
		recipients.getSigners().add(TestTemplateHelper.buildCollaboratorSigner(3));

		// call under test
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				() -> DocuSignTemplateValidator.validate(template));
		assertTrue(ex.getMessage().contains("collaborator_2"));
	}

	@Test
	public void testValidateWithCollaboratorIndexTooLarge() {
		EnvelopeTemplate template = TestTemplateHelper.buildValidTemplate(0);
		Recipients recipients = template.getRecipients();
		recipients.getSigners().add(TestTemplateHelper.buildCollaboratorSigner(99));

		// call under test
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				() -> DocuSignTemplateValidator.validate(template));
		assertTrue(ex.getMessage().contains("exceeds maximum"));
	}

	@Test
	public void testValidateWithCollaboratorMissingTab() {
		EnvelopeTemplate template = TestTemplateHelper.buildValidTemplate(1);
		Signer collab = TestTemplateHelper.findSigner(template, "collaborator_1");
		collab.getTabs().setSignHereTabs(null);

		// call under test
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				() -> DocuSignTemplateValidator.validate(template));
		assertTrue(ex.getMessage().contains("collaborator_1_signature"));
		assertTrue(ex.getMessage().contains("SIGN_HERE"));
	}

	@Test
	public void testValidateWithExtraTabsAllowed() {
		EnvelopeTemplate template = TestTemplateHelper.buildValidTemplate(1);
		Signer so = TestTemplateHelper.findSigner(template, "signing_official");
		Text extra = new Text();
		extra.setTabLabel("some_extra_tab");
		so.getTabs().setTextTabs(List.of(extra));

		// call under test
		assertDoesNotThrow(() -> DocuSignTemplateValidator.validate(template));
	}

	@Test
	public void testValidateWithEmailInEmailAddressTabs() {
		EnvelopeTemplate template = TestTemplateHelper.buildValidTemplate(0);
		Signer so = TestTemplateHelper.findSigner(template, "signing_official");
		so.getTabs().setEmailTabs(null);
		com.docusign.esign.model.EmailAddress ea = new com.docusign.esign.model.EmailAddress();
		ea.setTabLabel("signing_official_email");
		so.getTabs().setEmailAddressTabs(List.of(ea));

		// call under test
		assertDoesNotThrow(() -> DocuSignTemplateValidator.validate(template));
	}
}
