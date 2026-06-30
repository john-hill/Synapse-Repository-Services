package org.sagebionetworks.markdown;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.when;
import static org.sagebionetworks.markdown.MarkdownDaoImpl.MARKDOWN;
import static org.sagebionetworks.markdown.MarkdownDaoImpl.OUTPUT;

import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

public class MarkdownDaoImplTest {
	@Mock
	MarkdownClient mockMarkdownClient;

	private MarkdownDaoImpl dao;

	@Before
	public void before() {
		MockitoAnnotations.initMocks(this);
		dao = new MarkdownDaoImpl(mockMarkdownClient, "https://synapse.org");
	}

	@Test (expected = IllegalArgumentException.class)
	public void testConvertMarkdownWithNullMarkdown() throws Exception {
		dao.convertMarkdown(null, null);
	}

	@Test (expected = MarkdownClientException.class)
	public void testConvertMarkdownWithNullResponse() throws Exception {
		String rawMarkdown = "## a heading";
		JSONObject request = new JSONObject();
		request.put(MARKDOWN, rawMarkdown);
		request.put(MarkdownDaoImpl.BASE_URL, "https://synapse.org");
		when(mockMarkdownClient.requestMarkdownConversion(request.toString())).thenThrow(new MarkdownClientException(500,""));
		dao.convertMarkdown(rawMarkdown, null);
	}

	@Test
	public void testConvertMarkdown() throws Exception {
		String rawMarkdown = "## a heading";
		String outputType = "html";
		JSONObject request = new JSONObject();
		request.put(MARKDOWN, rawMarkdown);
		request.put(MarkdownDaoImpl.BASE_URL, "https://synapse.org");
		request.put(OUTPUT, outputType);
		String result = "<h2 toc=\"true\">a heading</h2>\n";
		String response = "{\"result\":\"<h2 toc=\\\"true\\\">a heading</h2>\\n\"}";
		when(mockMarkdownClient.requestMarkdownConversion(request.toString())).thenReturn(response);
		assertEquals(result, dao.convertMarkdown(rawMarkdown, outputType));
	}

	@Test
	public void testGetSynapseBaseUrl() throws Exception {
		assertEquals("https://synapse.org", dao.getSynapseBaseUrl());
	}
}
