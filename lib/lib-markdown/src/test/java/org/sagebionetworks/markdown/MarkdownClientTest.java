package org.sagebionetworks.markdown;

import static org.junit.Assert.assertNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.sagebionetworks.simpleHttpClient.SimpleHttpClient;
import org.sagebionetworks.simpleHttpClient.SimpleHttpRequest;
import org.sagebionetworks.simpleHttpClient.SimpleHttpResponse;
import org.springframework.test.util.ReflectionTestUtils;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.auth.aws.signer.AwsV4HttpSigner;

public class MarkdownClientTest {
	@Mock
	private SimpleHttpClient mockHttpClient;
	@Mock
	private SimpleHttpResponse mockResponse;
	private MarkdownClient markdownClient;

	@BeforeEach
	public void before() {
		MockitoAnnotations.initMocks(this);
		markdownClient = new MarkdownClient();
//		ReflectionTestUtils.setField(markdownClient, "simpleHttpClient", mockHttpClient);
//		ReflectionTestUtils.setField(markdownClient, "markdownServiceEndpoint",
//				"https://abc123.execute-api.us-east-1.amazonaws.com/prod/markdown");
//		ReflectionTestUtils.setField(markdownClient, "awsCredentialsProvider",
//				StaticCredentialsProvider.create(AwsBasicCredentials.create("akid", "secret")));
//		ReflectionTestUtils.setField(markdownClient, "signer", AwsV4HttpSigner.create());
		markdownClient.setSimpleHttpClient(mockHttpClient);
		markdownClient.setMarkdownServiceEndpoint("https://abc123.execute-api.us-east-1.amazonaws.com/prod/markdown");
		markdownClient.setSigner(AwsV4HttpSigner.create());
		markdownClient.setAwsCredentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("akid", "secret")));
	}

	@Test
	public void testRequestMarkdownConversionFailure() throws Exception {
		String request = "{\"markdown\":\"## a heading\"}";
		String response = "{\"error\":\"Service unavailable\"}";
		when(mockResponse.getStatusCode()).thenReturn(500);
		when(mockResponse.getContent()).thenReturn(response);
		when(mockHttpClient.post(any(SimpleHttpRequest.class), eq(request))).thenReturn(mockResponse);
		MarkdownClientException e = assertThrows(MarkdownClientException.class, ()->{
			// call under test
			markdownClient.requestMarkdownConversion(request);
		});
		assertEquals(500, e.getStatusCode());
		assertEquals("Fail to request markdown conversion for request: "+request, e.getMessage());
	}

	@Test
	public void testRequestMarkdownConversionIOException() throws Exception {
		String request = "{\"markdown\":\"## a heading\"}";
		IOException io = new IOException("some kind of connection problem");
		when(mockHttpClient.post(any(SimpleHttpRequest.class), eq(request))).thenThrow(io);
		MarkdownClientException e = assertThrows(MarkdownClientException.class, ()->{
			// call under test
			markdownClient.requestMarkdownConversion(request);
		});
		assertEquals(-1, e.getStatusCode());
		assertEquals(io, e.getCause());
	}

	@Test
	public void testRequestMarkdownConversionSuccess() throws Exception {
		String request = "{\"markdown\":\"## a heading\"}";
		String response = "{\"html\":\"<h2 toc=\\\"true\\\">a heading</h2>\\n\"}";
		when(mockResponse.getStatusCode()).thenReturn(200);
		when(mockResponse.getContent()).thenReturn(response);
		when(mockHttpClient.post(any(SimpleHttpRequest.class), eq(request))).thenReturn(mockResponse);
		assertEquals(response, markdownClient.requestMarkdownConversion(request));
	}

	@Test
	public void testRequestMarkdownConversionWithIamSigning() throws Exception {
		String request = "{\"markdown\":\"## a heading\"}";
		String response = "{\"html\":\"<h2 toc=\\\"true\\\">a heading</h2>\\n\"}";
		when(mockResponse.getStatusCode()).thenReturn(200);
		when(mockResponse.getContent()).thenReturn(response);

		ArgumentCaptor<SimpleHttpRequest> requestCaptor = ArgumentCaptor.forClass(SimpleHttpRequest.class);
		when(mockHttpClient.post(requestCaptor.capture(), eq(request))).thenReturn(mockResponse);

		// call under test
		markdownClient.requestMarkdownConversion(request);

		Map<String, String> headers = requestCaptor.getValue().getHeaders();

		String authHeader = headers.get("Authorization");
		assertNotNull(authHeader);
		assertTrue(authHeader.startsWith("AWS4-HMAC-SHA256 Credential=akid/"));

		assertNotNull(headers.get("X-Amz-Date"));
		assertEquals("application/json", headers.get("Content-Type"));
		assertFalse(headers.containsKey("Host"));
	}

	@Test
	public void testInit() throws Exception {
		MarkdownClient mdc = new MarkdownClient();
		mdc._init();
		assertNotNull(mdc.getSimpleHttpClient());
		assertNotNull(mdc.getAwsCredentialsProvider());
		assertNotNull(mdc.getSigner());
	}

	@Test
	public void testInitWithAlreadyConfigured() throws Exception {
		// markdownClient already has simpleHttpClient, awsCredentialsProvider, and signer set from before()
		// call under test - pre-set fields should not be replaced
		markdownClient._init();
		assertEquals(mockHttpClient, markdownClient.getSimpleHttpClient());
	}

	@Test
	public void testGetSet() throws Exception {
		MarkdownClient mdc = new MarkdownClient();
		assertNull(mdc.getMarkdownServiceEndpoint());
		assertNull(mdc.getAwsCredentialsProvider());
		assertNull(mdc.getSimpleHttpClient());
		assertNull(mdc.getSigner());
		mdc.setMarkdownServiceEndpoint("https://service.emdpoint.org");
		assertEquals("https://service.emdpoint.org", mdc.getMarkdownServiceEndpoint());
		AwsV4HttpSigner signer = AwsV4HttpSigner.create();
		mdc.setSigner(signer);
		assertEquals(signer, mdc.getSigner());
	}
}