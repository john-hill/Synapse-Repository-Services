package org.sagebionetworks.docusign;

import java.io.IOException;
import java.time.Instant;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

import org.sagebionetworks.repo.model.UnauthorizedException;
import org.sagebionetworks.repo.model.educ.EDucTemplate;
import org.sagebionetworks.repo.model.educ.EDucTemplatePage;
import org.sagebionetworks.repo.web.NotFoundException;
import org.sagebionetworks.repo.web.ServiceUnavailableException;
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
 * exchanged for an access token, which is cached in memory until just before
 * its expiry.
 *
 * <p>This is a Spring singleton; concurrent callers share the cached access
 * token via volatile reads plus double-checked locking on refresh.
 */
@Service
public class DocuSignClient {

	static final long JWT_EXPIRES_IN_SECONDS = 3600L;
	static final long TOKEN_EXPIRY_BUFFER_MILLIS = 60_000L;
	static final List<String> SCOPES = Arrays.asList("signature", "impersonation");

	private final DocuSignClientConfig config;
	private final TemplatesApiFactory templatesApiFactory;

	private volatile String cachedAccessToken;
	private volatile long cachedAccessTokenExpiryMillis;
	private final Object tokenLock = new Object();

	@Autowired
	public DocuSignClient(DocuSignClientConfig config) {
		this(config, new DefaultTemplatesApiFactory());
	}

	DocuSignClient(DocuSignClientConfig config, TemplatesApiFactory templatesApiFactory) {
		this.config = config;
		this.templatesApiFactory = templatesApiFactory;
	}

	/**
	 * List a page of templates from the configured DocuSign account.
	 *
	 * @param startPosition 0-based offset into the DocuSign template set
	 * @param count page size to request from DocuSign
	 * @return a page of templates; the {@code nextPageToken} field is left null
	 *         (callers are responsible for assembling the Synapse-style token)
	 */
	public EDucTemplatePage listTemplates(int startPosition, int count) throws Exception {
		try {
			return listTemplatesOnce(startPosition, count, getAccessToken());
		} catch (UnauthorizedException e) {
			// The cached token was rejected; force a refresh and retry once.
			invalidateAccessToken();
			return listTemplatesOnce(startPosition, count, getAccessToken());
		}
	}

	private EDucTemplatePage listTemplatesOnce(int startPosition, int count, String accessToken) throws Exception {
		TemplatesApi templatesApi = templatesApiFactory.create(config.getBasePath(), accessToken);
		TemplatesApi.ListTemplatesOptions options = templatesApi.new ListTemplatesOptions();
		options.setStartPosition(String.valueOf(startPosition));
		options.setCount(String.valueOf(count));

		try {
			EnvelopeTemplateResults results = templatesApi.listTemplates(config.getAccountId(), options);
			return toEDucTemplatePage(results);
		} catch (ApiException e) {
			throw convertApiException(e);
		}
	}

	private String getAccessToken() throws ServiceUnavailableException {
		long now = System.currentTimeMillis();
		String token = cachedAccessToken;
		if (token != null && now + TOKEN_EXPIRY_BUFFER_MILLIS < cachedAccessTokenExpiryMillis) {
			return token;
		}
		synchronized (tokenLock) {
			token = cachedAccessToken;
			now = System.currentTimeMillis();
			if (token != null && now + TOKEN_EXPIRY_BUFFER_MILLIS < cachedAccessTokenExpiryMillis) {
				return token;
			}
			OAuth.OAuthToken fresh = requestJwtUserToken();
			cachedAccessToken = fresh.getAccessToken();
			cachedAccessTokenExpiryMillis = System.currentTimeMillis() + (fresh.getExpiresIn() * 1000L);
			return cachedAccessToken;
		}
	}

	private void invalidateAccessToken() {
		synchronized (tokenLock) {
			cachedAccessToken = null;
			cachedAccessTokenExpiryMillis = 0L;
		}
	}

	/**
	 * Exchanges a signed JWT assertion for an access token. Package-private so unit
	 * tests can spy/override without driving real DocuSign HTTP traffic.
	 */
	OAuth.OAuthToken requestJwtUserToken() throws ServiceUnavailableException {
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
			throw new ServiceUnavailableException("Failed to obtain DocuSign access token.", e);
		} catch (IOException e) {
			throw new ServiceUnavailableException("Failed to read DocuSign private key.", e);
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

	static Exception convertApiException(ApiException e) throws ServiceUnavailableException {
		int code = e.getCode();
		switch (code) {
			case 401:
				return new UnauthorizedException("DocuSign rejected the access token.", e);
			case 403:
				return new UnauthorizedException("DocuSign denied access for the configured user.", e);
			case 404:
				return new NotFoundException("DocuSign resource not found.", e);
			default:
				return new ServiceUnavailableException(
						"Error " + code + " communicating with DocuSign.", e);
		}
	}

	private static final class DefaultTemplatesApiFactory implements TemplatesApiFactory {
		@Override
		public TemplatesApi create(String basePath, String accessToken) {
			ApiClient apiClient = new ApiClient(basePath);
			apiClient.addDefaultHeader("Authorization", "Bearer " + accessToken);
			return new TemplatesApi(apiClient);
		}
	}
}
