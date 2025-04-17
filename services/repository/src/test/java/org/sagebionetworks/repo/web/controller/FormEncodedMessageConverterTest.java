package org.sagebionetworks.repo.web.controller;

import static org.junit.Assert.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.charset.Charset;

import org.json.JSONException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.sagebionetworks.repo.model.Project;
import org.sagebionetworks.schema.adapter.JSONEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.MediaType;

@ExtendWith(MockitoExtension.class)
class FormEncodedMessageConverterTest {
	private static final Charset CHARSET = Charset.forName("ISO-8859-1");
	
	@Mock
	HttpInputMessage mockInMessage;
	
	@Mock
	HttpHeaders mockHeaders;
	
	FormEncodedMessageConverter converter;
	
	Project project;
	

	@BeforeEach
	void setUp() throws Exception {
		project = new Project();
		project.setName("foo-bar");		
		converter = new FormEncodedMessageConverter();
	}

	@Test
	public void testConvertFormEncodedDataToJSONString_HappyCase() throws JSONException, UnsupportedEncodingException {
		assertEquals("{\"foo\":\"bar\",\"bar\":\"baz\"}", FormEncodedMessageConverter.convertFormEncodedDataToJSONString("foo=bar&bar=baz", CHARSET));
	}
	
	@Test
	public void testConvertFormEncodedDataToJSONString_EmptyString() throws JSONException, UnsupportedEncodingException {
		assertEquals("{}", FormEncodedMessageConverter.convertFormEncodedDataToJSONString("", CHARSET));
	}
	
	@Test
	public void testConvertFormEncodedDataToJSONString_InvalidInput() {
		assertThrows(IllegalArgumentException.class, () -> {
			FormEncodedMessageConverter.convertFormEncodedDataToJSONString("garbage", CHARSET);
		});
	}

	
	@Test
	public void testRoundTripWithFormencodedMediaType() throws IOException  {
		String keyValueParams = "name=foo-bar&concreteType=org.sagebionetworks.repo.model.Project";
		ByteArrayInputStream in  = new ByteArrayInputStream(keyValueParams.getBytes("ISO-8859-1"));
		Mockito.when(mockInMessage.getBody()).thenReturn(in);
		Mockito.when(mockInMessage.getHeaders()).thenReturn(mockHeaders);
		Mockito.when(mockHeaders.getContentType()).thenReturn(MediaType.APPLICATION_FORM_URLENCODED);

		// method under test
		JSONEntity results = converter.read(Project.class, mockInMessage);
		
		assertEquals(project, results);
	}
	

}
