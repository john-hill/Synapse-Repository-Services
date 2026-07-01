package org.sagebionetworks.docusign;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.sagebionetworks.repo.model.educ.EDucTemplate;
import org.sagebionetworks.repo.model.educ.EDucTemplatePage;
import org.sagebionetworks.util.ValidateArgument;
import org.springframework.stereotype.Service;

import org.sagebionetworks.docusign.DocuSignTemplateValidator.TabType;

import com.docusign.esign.client.ApiException;
import com.docusign.esign.model.Email;
import com.docusign.esign.model.EnvelopeDefinition;
import com.docusign.esign.model.EnvelopeSummary;
import com.docusign.esign.model.EnvelopeTemplate;
import com.docusign.esign.model.EnvelopeTemplateResults;
import com.docusign.esign.model.FullName;
import com.docusign.esign.model.Signer;
import com.docusign.esign.model.Tabs;
import com.docusign.esign.model.TemplateRole;
import com.docusign.esign.model.Text;
import com.docusign.esign.model.Title;

/**
 * Client for the DocuSign REST API. Authenticates headlessly via the JWT Bearer
 * Grant: a JWT assertion signed with the configured RSA private key is
 * exchanged for an access token, which is cached until just before its expiry.
 */
@Service
public class DocuSignClient {

	private final DocuSignClientConfig config;
	private final DocuSignTemplatesApi templatesApi;
	private final DocuSignEnvelopesApi envelopesApi;
	private final DocuSignAccessTokenProvider accessTokenProvider;

	DocuSignClient(DocuSignClientConfig config, DocuSignTemplatesApi templatesApi,
			DocuSignEnvelopesApi envelopesApi, DocuSignAccessTokenProvider accessTokenProvider) {
		this.config = config;
		this.templatesApi = templatesApi;
		this.envelopesApi = envelopesApi;
		this.accessTokenProvider = accessTokenProvider;
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
		return executeWithRetry(token -> listTemplatesOnce(startPosition, count, token));
	}

	/**
	 * Validates that the given template has the required signer roles and tabs.
	 * Throws IllegalArgumentException if the template does not meet requirements.
	 */
	public void validateTemplate(String templateId) {
		ValidateArgument.required(templateId, "templateId");
		executeWithRetry(token -> {
			validateTemplateOnce(templateId, token);
			return null;
		});
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
			Map<RoleTabKey, String> tabValues) {
		ValidateArgument.required(templateId, "templateId");
		ValidateArgument.required(roleEmails, "roleEmails");
		ValidateArgument.required(tabValues, "tabValues");
		return executeWithRetry(token -> createAndSendEnvelopeOnce(templateId, roleEmails, tabValues, token));
	}

	private <T> T executeWithRetry(Function<String, T> action) {
		try {
			return action.apply(accessTokenProvider.getAccessToken());
		} catch (DocuSignUnauthorizedException e) {
			accessTokenProvider.invalidateAccessToken();
			return action.apply(accessTokenProvider.getAccessToken());
		}
	}

	private void validateTemplateOnce(String templateId, String accessToken) {
		try {
			EnvelopeTemplate template = templatesApi.getTemplate(
					config.getBasePath(), accessToken, config.getAccountId(), templateId);
			DocuSignTemplateValidator.validate(template);
		} catch (ApiException e) {
			throw convertApiException(e);
		}
	}

	private String createAndSendEnvelopeOnce(String templateId, Map<String, String> roleEmails,
			Map<RoleTabKey, String> tabValues, String accessToken) {
		try {
			EnvelopeTemplate template = templatesApi.getTemplate(
					config.getBasePath(), accessToken, config.getAccountId(), templateId);
			DocuSignTemplateValidator.validate(template);

			Map<RoleTabKey, TabType> tabTypeMap = buildTabTypeMap(template);
			List<TemplateRole> templateRoles = buildTemplateRoles(roleEmails, tabValues, tabTypeMap);
			EnvelopeDefinition envelopeDefinition = new EnvelopeDefinition();
			envelopeDefinition.setTemplateId(templateId);
			envelopeDefinition.setTemplateRoles(templateRoles);
			envelopeDefinition.setStatus("sent");

			EnvelopeSummary summary = envelopesApi.createEnvelope(
					config.getBasePath(), accessToken, config.getAccountId(), envelopeDefinition);
			return summary.getEnvelopeId();
		} catch (ApiException e) {
			throw convertApiException(e);
		}
	}

	static Map<RoleTabKey, TabType> buildTabTypeMap(EnvelopeTemplate template) {
		Map<RoleTabKey, TabType> map = new java.util.HashMap<>();
		if (template.getRecipients() == null || template.getRecipients().getSigners() == null) {
			return map;
		}
		for (Signer signer : template.getRecipients().getSigners()) {
			String roleName = signer.getRoleName();
			Tabs tabs = signer.getTabs();
			if (tabs == null) {
				continue;
			}
			if (tabs.getTextTabs() != null) {
				for (Text t : tabs.getTextTabs()) {
					if (t.getTabLabel() != null) {
						map.put(new RoleTabKey(roleName, t.getTabLabel()), TabType.TEXT);
					}
				}
			}
			if (tabs.getFullNameTabs() != null) {
				for (FullName f : tabs.getFullNameTabs()) {
					if (f.getTabLabel() != null) {
						map.put(new RoleTabKey(roleName, f.getTabLabel()), TabType.FULL_NAME);
					}
				}
			}
			if (tabs.getTitleTabs() != null) {
				for (Title t : tabs.getTitleTabs()) {
					if (t.getTabLabel() != null) {
						map.put(new RoleTabKey(roleName, t.getTabLabel()), TabType.TITLE);
					}
				}
			}
			if (tabs.getEmailTabs() != null) {
				for (Email e : tabs.getEmailTabs()) {
					if (e.getTabLabel() != null) {
						map.put(new RoleTabKey(roleName, e.getTabLabel()), TabType.EMAIL);
					}
				}
			}
			if (tabs.getEmailAddressTabs() != null) {
				for (com.docusign.esign.model.EmailAddress e : tabs.getEmailAddressTabs()) {
					if (e.getTabLabel() != null) {
						map.put(new RoleTabKey(roleName, e.getTabLabel()), TabType.EMAIL);
					}
				}
			}
		}
		return map;
	}

	static List<TemplateRole> buildTemplateRoles(Map<String, String> roleEmails,
			Map<RoleTabKey, String> tabValues, Map<RoleTabKey, TabType> tabTypeMap) {
		List<TemplateRole> roles = new ArrayList<>();
		for (Map.Entry<String, String> entry : roleEmails.entrySet()) {
			String roleName = entry.getKey();
			String email = entry.getValue();

			TemplateRole role = new TemplateRole();
			role.setRoleName(roleName);
			role.setEmail(email);

			List<Text> textTabs = new ArrayList<>();
			List<FullName> fullNameTabs = new ArrayList<>();
			List<Title> titleTabs = new ArrayList<>();
			List<Email> emailTabs = new ArrayList<>();

			for (Map.Entry<RoleTabKey, String> tabEntry : tabValues.entrySet()) {
				if (!tabEntry.getKey().roleName().equals(roleName)) {
					continue;
				}
				String tabLabel = tabEntry.getKey().tabLabel();
				String value = tabEntry.getValue();
				TabType type = tabTypeMap.getOrDefault(tabEntry.getKey(), TabType.TEXT);

				switch (type) {
					case FULL_NAME -> {
						FullName fn = new FullName();
						fn.setTabLabel(tabLabel);
						fn.setValue(value);
						fullNameTabs.add(fn);
						role.setName(value);
					}
					case TITLE -> {
						Title t = new Title();
						t.setTabLabel(tabLabel);
						t.setValue(value);
						titleTabs.add(t);
					}
					case EMAIL -> {
						Email e = new Email();
						e.setTabLabel(tabLabel);
						e.setValue(value);
						emailTabs.add(e);
					}
					default -> {
						Text text = new Text();
						text.setTabLabel(tabLabel);
						text.setValue(value);
						textTabs.add(text);
					}
				}
			}

			Tabs tabs = new Tabs();
			boolean hasTabs = false;
			if (!textTabs.isEmpty()) {
				tabs.setTextTabs(textTabs);
				hasTabs = true;
			}
			if (!fullNameTabs.isEmpty()) {
				tabs.setFullNameTabs(fullNameTabs);
				hasTabs = true;
			}
			if (!titleTabs.isEmpty()) {
				tabs.setTitleTabs(titleTabs);
				hasTabs = true;
			}
			if (!emailTabs.isEmpty()) {
				tabs.setEmailTabs(emailTabs);
				hasTabs = true;
			}
			if (hasTabs) {
				role.setTabs(tabs);
			}

			roles.add(role);
		}
		return roles;
	}

	private EDucTemplatePage listTemplatesOnce(int startPosition, int count, String accessToken) {
		try {
			EnvelopeTemplateResults results = templatesApi.listTemplates(
					config.getBasePath(), accessToken, config.getAccountId(),
					String.valueOf(startPosition), String.valueOf(count));
			return toEDucTemplatePage(results);
		} catch (ApiException e) {
			throw convertApiException(e);
		}
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

	static Date parseDate(String iso8601) {
		if (iso8601 == null || iso8601.isEmpty()) {
			return null;
		}
		return Date.from(Instant.parse(iso8601));
	}

	static RuntimeException convertApiException(ApiException e) {
		int code = e.getCode();
		if (code == 401) {
			return new DocuSignUnauthorizedException("DocuSign rejected the access token.", e);
		}
		return new IllegalStateException("DocuSign API error " + code + ".", e);
	}
}
