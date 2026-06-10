package org.sagebionetworks.docusign;

import java.io.IOException;
import java.time.Instant;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

import org.sagebionetworks.repo.model.educ.EDucTemplate;
import org.sagebionetworks.repo.model.educ.EDucTemplatePage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.docusign.esign.api.TemplatesApi;
import com.docusign.esign.client.ApiClient;
import com.docusign.esign.client.ApiException;
import com.docusign.esign.client.auth.OAuth;
import com.docusign.esign.model.EnvelopeTemplate;
import com.docusign.esign.model.EnvelopeTemplateResults;

/**
 * Client for the DocuSign REST API. Authenticates headlessly via the JWT Bearer
 * Grant: a JWT assertion signed with the configured RSA private key is
 * exchanged for an access token, which is cached until just before its expiry.
 */
@Service
public class DocuSignClient {

	static final long JWT_EXPIRES_IN_SECONDS = 3600L;
	static final long TOKEN_EXPIRY_BUFFER_MILLIS = 60_000L;
	static final List<String> SCOPES = Arrays.asList("signature", "impersonation");

	private final DocuSignClientConfig config;
	private final TemplatesApiFactory templatesApiFactory;
	private final DocuSignAccessTokenProvider accessTokenProvider;

	@Autowired
	public DocuSignClient(DocuSignClientConfig config) {
		this(config, new DefaultTemplatesApiFactory());
	}

	DocuSignClient(DocuSignClientConfig config, TemplatesApiFactory templatesApiFactory) {
		this(config, templatesApiFactory, null);
	}

	DocuSignClient(DocuSignClientConfig config, TemplatesApiFactory templatesApiFactory,
			DocuSignAccessTokenProvider accessTokenProvider) {
		this.config = config;
		this.templatesApiFactory = templatesApiFactory;
		this.accessTokenProvider = accessTokenProvider != null ? accessTokenProvider : new DocuSignAccessTokenProvider(
				() -> {
					OAuth.OAuthToken token = requestJwtUserToken();
					return new DocuSignAccessTokenProvider.TokenResult(token.getAccessToken(), token.getExpiresIn());
				},
				TOKEN_EXPIRY_BUFFER_MILLIS
		);
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
		try {
			return listTemplatesOnce(startPosition, count, accessTokenProvider.getAccessToken());
		} catch (DocuSignUnauthorizedException e) {
			// The cached token was rejected; force a refresh and retry once.
			accessTokenProvider.invalidateAccessToken();
			return listTemplatesOnce(startPosition, count, accessTokenProvider.getAccessToken());
		}
	}

	private EDucTemplatePage listTemplatesOnce(int startPosition, int count, String accessToken) {
		try {
			EnvelopeTemplateResults results = templatesApiFactory.listTemplates(
					config.getBasePath(), accessToken, config.getAccountId(),
					String.valueOf(startPosition), String.valueOf(count));
			return toEDucTemplatePage(results);
		} catch (ApiException e) {
			throw convertApiException(e);
		}
	}

	/**
	 * Exchanges a signed JWT assertion for an access token. Package-private so unit
	 * tests can spy/override without driving real DocuSign HTTP traffic.
	 */
	OAuth.OAuthToken requestJwtUserToken() {
		ApiClient apiClient = new ApiClient(config.getBasePath());
		apiClient.setOAuthBasePath(config.getOAuthBasePath());
		try {
			return apiClient.requestJWTUserToken(
					config.getIntegrationKey(),
					config.getUserId(),
					SCOPES,
					config.getPrivateKeyBytes(),
					JWT_EXPIRES_IN_SECONDS);
		} catch (ApiException e) {
			throw new IllegalStateException("Failed to obtain DocuSign access token.", e);
		} catch (IOException e) {
			throw new IllegalStateException("Failed to read DocuSign private key.", e);
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

	private static final class DefaultTemplatesApiFactory implements TemplatesApiFactory {
		@Override
		public EnvelopeTemplateResults listTemplates(String basePath, String accessToken,
				String accountId, String startPosition, String count) throws ApiException {
			ApiClient apiClient = new ApiClient(basePath);
			apiClient.addDefaultHeader("Authorization", "Bearer " + accessToken);
			TemplatesApi templatesApi = new TemplatesApi(apiClient);
			TemplatesApi.ListTemplatesOptions options = templatesApi.new ListTemplatesOptions();
			options.setStartPosition(startPosition);
			options.setCount(count);
			return templatesApi.listTemplates(accountId, options);
		}
	}
}
