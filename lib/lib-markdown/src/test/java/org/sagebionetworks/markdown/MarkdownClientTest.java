package org.sagebionetworks.markdown;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.io.IOException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.sagebionetworks.simpleHttpClient.SimpleHttpClient;
import org.sagebionetworks.simpleHttpClient.SimpleHttpRequest;
import org.sagebionetworks.simpleHttpClient.SimpleHttpResponse;
import org.springframework.test.util.ReflectionTestUtils;

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
		ReflectionTestUtils.setField(markdownClient, "simpleHttpClient", mockHttpClient);
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

}
