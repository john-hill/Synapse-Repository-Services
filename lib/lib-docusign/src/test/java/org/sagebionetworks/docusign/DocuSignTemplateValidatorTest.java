package org.sagebionetworks.docusign;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.docusign.esign.model.DateSigned;
import com.docusign.esign.model.Email;
import com.docusign.esign.model.EnvelopeTemplate;
import com.docusign.esign.model.FullName;
import com.docusign.esign.model.Recipients;
import com.docusign.esign.model.SignHere;
import com.docusign.esign.model.Signer;
import com.docusign.esign.model.Tabs;
import com.docusign.esign.model.Text;
import com.docusign.esign.model.Title;

public class DocuSignTemplateValidatorTest {

	@Test
	public void testValidateWithValidTemplate() {
		EnvelopeTemplate template = buildValidTemplate(2);

		// call under test
		assertDoesNotThrow(() -> DocuSignTemplateValidator.validate(template));
	}

	@Test
	public void testValidateWithNoCollaborators() {
		EnvelopeTemplate template = buildValidTemplate(0);

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
		EnvelopeTemplate template = buildValidTemplate(1);
		template.getRecipients().getSigners().removeIf(s -> "signing_official".equals(s.getRoleName()));

		// call under test
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				() -> DocuSignTemplateValidator.validate(template));
		assertTrue(ex.getMessage().contains("signing_official"));
	}

	@Test
	public void testValidateWithMissingPrincipalInvestigatorRole() {
		EnvelopeTemplate template = buildValidTemplate(1);
		template.getRecipients().getSigners().removeIf(s -> "principal_investigator".equals(s.getRoleName()));

		// call under test
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				() -> DocuSignTemplateValidator.validate(template));
		assertTrue(ex.getMessage().contains("principal_investigator"));
	}

	@Test
	public void testValidateWithMissingSigningOfficialTab() {
		EnvelopeTemplate template = buildValidTemplate(0);
		Signer so = findSigner(template, "signing_official");
		so.getTabs().setTitleTabs(null);

		// call under test
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				() -> DocuSignTemplateValidator.validate(template));
		assertTrue(ex.getMessage().contains("signing_official_title"));
		assertTrue(ex.getMessage().contains("TITLE"));
	}

	@Test
	public void testValidateWithMissingPrincipalInvestigatorTab() {
		EnvelopeTemplate template = buildValidTemplate(0);
		Signer pi = findSigner(template, "principal_investigator");
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
		EnvelopeTemplate template = buildValidTemplate(0);
		Recipients recipients = template.getRecipients();
		recipients.getSigners().add(buildCollaboratorSigner(1));
		recipients.getSigners().add(buildCollaboratorSigner(3));

		// call under test
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				() -> DocuSignTemplateValidator.validate(template));
		assertTrue(ex.getMessage().contains("collaborator_2"));
	}

	@Test
	public void testValidateWithCollaboratorIndexTooLarge() {
		EnvelopeTemplate template = buildValidTemplate(0);
		Recipients recipients = template.getRecipients();
		recipients.getSigners().add(buildCollaboratorSigner(99));

		// call under test
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				() -> DocuSignTemplateValidator.validate(template));
		assertTrue(ex.getMessage().contains("exceeds maximum"));
	}

	@Test
	public void testValidateWithCollaboratorMissingTab() {
		EnvelopeTemplate template = buildValidTemplate(1);
		Signer collab = findSigner(template, "collaborator_1");
		collab.getTabs().setSignHereTabs(null);

		// call under test
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				() -> DocuSignTemplateValidator.validate(template));
		assertTrue(ex.getMessage().contains("collaborator_1_signature"));
		assertTrue(ex.getMessage().contains("SIGN_HERE"));
	}

	@Test
	public void testValidateWithExtraTabsAllowed() {
		EnvelopeTemplate template = buildValidTemplate(1);
		Signer so = findSigner(template, "signing_official");
		Text extra = new Text();
		extra.setTabLabel("some_extra_tab");
		so.getTabs().setTextTabs(List.of(extra));

		// call under test
		assertDoesNotThrow(() -> DocuSignTemplateValidator.validate(template));
	}

	@Test
	public void testValidateWithEmailInEmailAddressTabs() {
		EnvelopeTemplate template = buildValidTemplate(0);
		Signer so = findSigner(template, "signing_official");
		so.getTabs().setEmailTabs(null);
		com.docusign.esign.model.EmailAddress ea = new com.docusign.esign.model.EmailAddress();
		ea.setTabLabel("signing_official_email");
		so.getTabs().setEmailAddressTabs(List.of(ea));

		// call under test
		assertDoesNotThrow(() -> DocuSignTemplateValidator.validate(template));
	}

	private static EnvelopeTemplate buildValidTemplate(int numCollaborators) {
		EnvelopeTemplate template = new EnvelopeTemplate();
		Recipients recipients = new Recipients();
		List<Signer> signers = new java.util.ArrayList<>();
		signers.add(buildSigningOfficialSigner());
		signers.add(buildPrincipalInvestigatorSigner());
		for (int i = 1; i <= numCollaborators; i++) {
			signers.add(buildCollaboratorSigner(i));
		}
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

	private static Signer findSigner(EnvelopeTemplate template, String roleName) {
		return template.getRecipients().getSigners().stream()
				.filter(s -> roleName.equals(s.getRoleName()))
				.findFirst()
				.orElseThrow();
	}
}
