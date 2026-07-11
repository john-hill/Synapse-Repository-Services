package org.sagebionetworks.docusign;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

import org.sagebionetworks.repo.model.educ.EDucTemplate;
import org.sagebionetworks.repo.model.educ.EDucTemplatePage;
import org.sagebionetworks.util.ValidateArgument;
import org.springframework.stereotype.Service;

import com.docusign.esign.model.Envelope;
import com.docusign.esign.model.EnvelopeDefinition;
import com.docusign.esign.model.EnvelopeSummary;
import com.docusign.esign.model.EnvelopeTemplate;
import com.docusign.esign.model.EnvelopeTemplateResults;
import com.docusign.esign.model.Tabs;
import com.docusign.esign.model.TemplateRole;

/**
 * Client for the DocuSign REST API. Authenticates headlessly via the JWT Bearer
 * Grant: a JWT assertion signed with the configured RSA private key is
 * exchanged for an access token, which is cached until just before its expiry.
 */
@Service
public class DocuSignClient {

	private final DocuSignTemplatesApi templatesApi;
	private final DocuSignEnvelopesApi envelopesApi;

	DocuSignClient(DocuSignTemplatesApi templatesApi, DocuSignEnvelopesApi envelopesApi) {
		this.templatesApi = templatesApi;
		this.envelopesApi = envelopesApi;
	}

	/**
	 * List a page of templates from the configured DocuSign account.
	 *
	 * @param startPosition 0-based offset into the DocuSign template set
	 * @param count page size to request from DocuSign
	 * @return a page of templates; the {@code nextPageToken} field is left null
	 *         (callers are responsible for assembling the Synapse-style token)
	 */
	public EDucTemplatePage listTemplates(int startPosition, int count) {
		EnvelopeTemplateResults results = templatesApi.listTemplates(
				String.valueOf(startPosition), String.valueOf(count));
		return toEDucTemplatePage(results);
	}

	/**
	 * Validates that the given template has the required signer roles and tabs.
	 * Throws IllegalArgumentException if the template does not meet requirements.
	 */
	public void validateTemplate(String templateId) {
		ValidateArgument.required(templateId, "templateId");
		EnvelopeTemplate template = templatesApi.getTemplate(templateId);
		DocuSignTemplateValidator.validate(template);
	}

	/**
	 * Creates and immediately sends an envelope from the specified template.
	 *
	 * @param templateId the DocuSign template ID
	 * @param roleEmails map from role name to the signer's email address
	 * @param tabValues map from (roleName, tabLabel) to the text value to pre-fill
	 * @return the envelope ID of the created envelope
	 */
	public String createAndSendEnvelope(String templateId, Map<String, String> roleEmails,
			Map<RoleLabelKey, String> tabValues) {
		ValidateArgument.required(templateId, "templateId");
		ValidateArgument.required(roleEmails, "roleEmails");
		ValidateArgument.required(tabValues, "tabValues");

		EnvelopeTemplate template = templatesApi.getTemplate(templateId);
		DocuSignTemplateValidator.validate(template);

		List<TemplateRole> templateRoles = buildTemplateRoles(roleEmails, tabValues);
		EnvelopeDefinition envelopeDefinition = new EnvelopeDefinition();
		envelopeDefinition.setTemplateId(templateId);
		envelopeDefinition.setTemplateRoles(templateRoles);
		envelopeDefinition.setStatus("sent");

		EnvelopeSummary summary = envelopesApi.createEnvelope(envelopeDefinition);
		return summary.getEnvelopeId();
	}

	static List<TemplateRole> buildTemplateRoles(Map<String, String> roleEmails,
			Map<RoleLabelKey, String> tabValues) {
		List<TemplateRole> roles = new ArrayList<>();
		for (Map.Entry<String, String> entry : roleEmails.entrySet()) {
			String roleName = entry.getKey();
			String email = entry.getValue();

			TemplateRole role = new TemplateRole();
			role.setRoleName(roleName);
			role.setEmail(email);

			Tabs tabs = new Tabs();
			role.setTabs(tabs);

			for (Map.Entry<RoleLabelKey, String> tabEntry : tabValues.entrySet()) {
				if (!tabEntry.getKey().roleName().equals(roleName)) {
					continue;
				}
				String tabLabel = tabEntry.getKey().tabLabel();
				String value = tabEntry.getValue();
				TabType type = DocuSignTemplateValidator.typeforRoleAndLabel(roleName, tabLabel);
				type.fillInTabValue(tabs, tabLabel, value);
				if (TabType.FULL_NAME.equals(type)) {
					role.setName(value);
				}
			}

			roles.add(role);
		}
		return roles;
	}

	public Envelope getEnvelope(String envelopeId) {
		ValidateArgument.required(envelopeId, "envelopeId");
		return envelopesApi.getEnvelope(envelopeId);
	}

	static EDucTemplatePage toEDucTemplatePage(EnvelopeTemplateResults results) {
		EDucTemplatePage page = new EDucTemplatePage();
		List<EnvelopeTemplate> templates = results == null ? null : results.getEnvelopeTemplates();
		if (templates == null) {
			page.setResults(java.util.Collections.emptyList());
			return page;
		}
		java.util.List<EDucTemplate> mapped = new java.util.ArrayList<>(templates.size());
		for (EnvelopeTemplate t : templates) {
			mapped.add(toEDucTemplate(t));
		}
		page.setResults(mapped);
		return page;
	}

	static EDucTemplate toEDucTemplate(EnvelopeTemplate t) {
		EDucTemplate out = new EDucTemplate();
		out.setTemplateId(t.getTemplateId());
		out.setName(t.getName());
		out.setDescription(t.getDescription());
		out.setCreatedOn(parseDate(t.getCreatedDateTime()));
		out.setModifiedOn(parseDate(t.getLastModifiedDateTime()));
		return out;
	}

	public static Date parseDate(String iso8601) {
		if (iso8601 == null || iso8601.isEmpty()) {
			return null;
		}
		return Date.from(Instant.parse(iso8601));
	}

}
