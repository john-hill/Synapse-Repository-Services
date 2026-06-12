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
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.sagebionetworks.repo.model.educ.EDucTemplate;
import org.sagebionetworks.repo.model.educ.EDucTemplatePage;

import com.docusign.esign.client.ApiException;
import com.docusign.esign.model.EnvelopeTemplate;
import com.docusign.esign.model.EnvelopeTemplateResults;

@ExtendWith(MockitoExtension.class)
public class DocuSignClientTest {

	@Mock
	private DocuSignClientConfig mockConfig;
	@Mock
	private TemplatesApiFactory mockTemplatesApiFactory;
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
		when(mockTemplatesApiFactory.listTemplates(eq(BASE_PATH), eq(ACCESS_TOKEN), eq(ACCOUNT_ID), any(), any()))
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

		verify(mockTemplatesApiFactory).listTemplates(BASE_PATH, ACCESS_TOKEN, ACCOUNT_ID, "0", "51");
	}

	@Test
	public void testListTemplatesWithEmptyResults() throws Exception {
		when(mockAccessTokenProvider.getAccessToken()).thenReturn(ACCESS_TOKEN);
		when(mockConfig.getBasePath()).thenReturn(BASE_PATH);
		when(mockConfig.getAccountId()).thenReturn(ACCOUNT_ID);
		EnvelopeTemplateResults results = new EnvelopeTemplateResults();
		results.setEnvelopeTemplates(null);
		when(mockTemplatesApiFactory.listTemplates(eq(BASE_PATH), eq(ACCESS_TOKEN), eq(ACCOUNT_ID), any(), any()))
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
		when(mockTemplatesApiFactory.listTemplates(eq(BASE_PATH), any(), eq(ACCOUNT_ID), any(), any()))
				.thenThrow(new ApiException(401, "Unauthorized"))
				.thenReturn(success);

		// call under test
		EDucTemplatePage page = client.listTemplates(0, 51);

		assertNotNull(page);
		verify(mockAccessTokenProvider).invalidateAccessToken();
		verify(mockAccessTokenProvider, times(2)).getAccessToken();
		verify(mockTemplatesApiFactory).listTemplates(BASE_PATH, "first-token", ACCOUNT_ID, "0", "51");
		verify(mockTemplatesApiFactory).listTemplates(BASE_PATH, "retry-token", ACCOUNT_ID, "0", "51");
	}

	@Test
	public void testListTemplatesPropagatesPersistent401() throws Exception {
		when(mockAccessTokenProvider.getAccessToken())
				.thenReturn("t1")
				.thenReturn("t2");
		when(mockConfig.getBasePath()).thenReturn(BASE_PATH);
		when(mockConfig.getAccountId()).thenReturn(ACCOUNT_ID);
		when(mockTemplatesApiFactory.listTemplates(eq(BASE_PATH), any(), eq(ACCOUNT_ID), any(), any()))
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
		when(mockTemplatesApiFactory.listTemplates(eq(BASE_PATH), eq(ACCESS_TOKEN), eq(ACCOUNT_ID), any(), any()))
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
}
