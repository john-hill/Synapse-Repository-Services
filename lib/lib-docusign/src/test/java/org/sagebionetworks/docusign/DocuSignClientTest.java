package org.sagebionetworks.docusign;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.sagebionetworks.repo.model.UnauthorizedException;
import org.sagebionetworks.repo.model.educ.EDucTemplate;
import org.sagebionetworks.repo.model.educ.EDucTemplatePage;
import org.sagebionetworks.repo.web.NotFoundException;
import org.sagebionetworks.repo.web.ServiceUnavailableException;

import com.docusign.esign.api.TemplatesApi;
import com.docusign.esign.client.ApiException;
import com.docusign.esign.client.auth.OAuth;
import com.docusign.esign.model.EnvelopeTemplate;
import com.docusign.esign.model.EnvelopeTemplateResults;

@ExtendWith(MockitoExtension.class)
public class DocuSignClientTest {

	@Mock
	private DocuSignClientConfig mockConfig;
	@Mock
	private TemplatesApiFactory mockTemplatesApiFactory;
	@Mock
	private TemplatesApi mockTemplatesApi;

	private DocuSignClient client;

	private static final String BASE_PATH = "https://demo.docusign.net/restapi";
	private static final String ACCOUNT_ID = "account-guid";
	private static final String ACCESS_TOKEN = "access-token";

	@BeforeEach
	public void before() {
		client = spy(new DocuSignClient(mockConfig, mockTemplatesApiFactory));
	}

	private OAuth.OAuthToken oAuthToken(String accessToken, long expiresIn) {
		OAuth.OAuthToken token = new OAuth.OAuthToken();
		token.setAccessToken(accessToken);
		token.setExpiresIn(expiresIn);
		return token;
	}

	@Test
	public void testListTemplatesSuccess() throws Exception {
		// call under test setup
		doReturn(oAuthToken(ACCESS_TOKEN, 3600L)).when(client).requestJwtUserToken();
		when(mockConfig.getBasePath()).thenReturn(BASE_PATH);
		when(mockConfig.getAccountId()).thenReturn(ACCOUNT_ID);
		when(mockTemplatesApiFactory.create(BASE_PATH, ACCESS_TOKEN)).thenReturn(mockTemplatesApi);

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
		when(mockTemplatesApi.listTemplates(eq(ACCOUNT_ID), any())).thenReturn(results);

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

		// Verify the pagination params reach the SDK as strings
		ArgumentCaptor<TemplatesApi.ListTemplatesOptions> optionsCaptor =
				ArgumentCaptor.forClass(TemplatesApi.ListTemplatesOptions.class);
		verify(mockTemplatesApi).listTemplates(eq(ACCOUNT_ID), optionsCaptor.capture());
		assertEquals("0", optionsCaptor.getValue().getStartPosition());
		assertEquals("51", optionsCaptor.getValue().getCount());
	}

	@Test
	public void testListTemplatesWithEmptyResults() throws Exception {
		doReturn(oAuthToken(ACCESS_TOKEN, 3600L)).when(client).requestJwtUserToken();
		when(mockConfig.getBasePath()).thenReturn(BASE_PATH);
		when(mockConfig.getAccountId()).thenReturn(ACCOUNT_ID);
		when(mockTemplatesApiFactory.create(BASE_PATH, ACCESS_TOKEN)).thenReturn(mockTemplatesApi);
		EnvelopeTemplateResults results = new EnvelopeTemplateResults();
		results.setEnvelopeTemplates(null);
		when(mockTemplatesApi.listTemplates(eq(ACCOUNT_ID), any())).thenReturn(results);

		// call under test
		EDucTemplatePage page = client.listTemplates(0, 51);

		assertNotNull(page);
		assertEquals(Collections.emptyList(), page.getResults());
	}

	@Test
	public void testListTemplatesCachesAccessToken() throws Exception {
		doReturn(oAuthToken(ACCESS_TOKEN, 3600L)).when(client).requestJwtUserToken();
		when(mockConfig.getBasePath()).thenReturn(BASE_PATH);
		when(mockConfig.getAccountId()).thenReturn(ACCOUNT_ID);
		when(mockTemplatesApiFactory.create(BASE_PATH, ACCESS_TOKEN)).thenReturn(mockTemplatesApi);
		when(mockTemplatesApi.listTemplates(eq(ACCOUNT_ID), any())).thenReturn(new EnvelopeTemplateResults());

		// call under test
		client.listTemplates(0, 51);
		client.listTemplates(51, 51);
		client.listTemplates(102, 51);

		// JWT exchange should have occurred only once thanks to caching
		verify(client, times(1)).requestJwtUserToken();
	}

	@Test
	public void testListTemplatesRefreshesExpiredToken() throws Exception {
		// First token expires in 0s (treated as expired by the buffer); second is valid
		doReturn(oAuthToken("old-token", 0L), oAuthToken("new-token", 3600L))
				.when(client).requestJwtUserToken();
		when(mockConfig.getBasePath()).thenReturn(BASE_PATH);
		when(mockConfig.getAccountId()).thenReturn(ACCOUNT_ID);
		when(mockTemplatesApiFactory.create(eq(BASE_PATH), any())).thenReturn(mockTemplatesApi);
		when(mockTemplatesApi.listTemplates(eq(ACCOUNT_ID), any())).thenReturn(new EnvelopeTemplateResults());

		// call under test
		client.listTemplates(0, 51);
		client.listTemplates(51, 51);

		verify(client, times(2)).requestJwtUserToken();
		verify(mockTemplatesApiFactory).create(BASE_PATH, "old-token");
		verify(mockTemplatesApiFactory).create(BASE_PATH, "new-token");
	}

	@Test
	public void testListTemplatesInvalidatesCacheOn401AndRetries() throws Exception {
		doReturn(oAuthToken("first-token", 3600L), oAuthToken("retry-token", 3600L))
				.when(client).requestJwtUserToken();
		when(mockConfig.getBasePath()).thenReturn(BASE_PATH);
		when(mockConfig.getAccountId()).thenReturn(ACCOUNT_ID);
		when(mockTemplatesApiFactory.create(eq(BASE_PATH), any())).thenReturn(mockTemplatesApi);

		ApiException unauth = new ApiException(401, "Unauthorized");
		EnvelopeTemplateResults success = new EnvelopeTemplateResults();
		when(mockTemplatesApi.listTemplates(eq(ACCOUNT_ID), any()))
				.thenThrow(unauth)
				.thenReturn(success);

		// call under test
		EDucTemplatePage page = client.listTemplates(0, 51);

		assertNotNull(page);
		verify(client, times(2)).requestJwtUserToken();
		verify(mockTemplatesApiFactory).create(BASE_PATH, "first-token");
		verify(mockTemplatesApiFactory).create(BASE_PATH, "retry-token");
	}

	@Test
	public void testListTemplatesPropagatesPersistent401AsUnauthorized() throws Exception {
		doReturn(oAuthToken("t1", 3600L), oAuthToken("t2", 3600L))
				.when(client).requestJwtUserToken();
		when(mockConfig.getBasePath()).thenReturn(BASE_PATH);
		when(mockConfig.getAccountId()).thenReturn(ACCOUNT_ID);
		when(mockTemplatesApiFactory.create(eq(BASE_PATH), any())).thenReturn(mockTemplatesApi);
		when(mockTemplatesApi.listTemplates(eq(ACCOUNT_ID), any()))
				.thenThrow(new ApiException(401, "Unauthorized"));

		// call under test
		assertThrows(UnauthorizedException.class, () -> client.listTemplates(0, 51));

		verify(client, times(2)).requestJwtUserToken();
	}

	@Test
	public void testListTemplatesMapsServiceUnavailableException() throws Exception {
		doReturn(oAuthToken(ACCESS_TOKEN, 3600L)).when(client).requestJwtUserToken();
		when(mockConfig.getBasePath()).thenReturn(BASE_PATH);
		when(mockConfig.getAccountId()).thenReturn(ACCOUNT_ID);
		when(mockTemplatesApiFactory.create(BASE_PATH, ACCESS_TOKEN)).thenReturn(mockTemplatesApi);
		when(mockTemplatesApi.listTemplates(eq(ACCOUNT_ID), any()))
				.thenThrow(new ApiException(500, "Server error"));

		// call under test
		ServiceUnavailableException ex = assertThrows(ServiceUnavailableException.class,
				() -> client.listTemplates(0, 51));
		assertTrue(ex.getMessage().contains("500"));
	}

	@Test
	public void testHandleApiExceptionMapping() {
		assertThrows(UnauthorizedException.class,
				() -> DocuSignClient.handleApiException(new ApiException(401, "x")));
		assertThrows(UnauthorizedException.class,
				() -> DocuSignClient.handleApiException(new ApiException(403, "x")));
		assertThrows(NotFoundException.class,
				() -> DocuSignClient.handleApiException(new ApiException(404, "x")));
		assertThrows(ServiceUnavailableException.class,
				() -> DocuSignClient.handleApiException(new ApiException(500, "x")));
		assertThrows(ServiceUnavailableException.class,
				() -> DocuSignClient.handleApiException(new ApiException(429, "x")));
	}

	@Test
	public void testRequestJwtUserTokenTranslatesApiException() throws Exception {
		// Use a non-spied client so we exercise the real requestJwtUserToken
		DocuSignClient realClient = new DocuSignClient(mockConfig, mockTemplatesApiFactory) {
			@Override
			OAuth.OAuthToken requestJwtUserToken() throws ServiceUnavailableException {
				throw new ServiceUnavailableException("boom");
			}
		};
		assertThrows(ServiceUnavailableException.class, () -> realClient.listTemplates(0, 51));
	}
}
