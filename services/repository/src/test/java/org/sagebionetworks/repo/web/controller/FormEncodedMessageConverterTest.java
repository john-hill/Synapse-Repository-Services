package org.sagebionetworks.repo.web.controller;

import static org.junit.Assert.assertEquals;
import static org.junit.jupiter.api.Assertions.*;

import java.io.UnsupportedEncodingException;
import java.nio.charset.Charset;

import org.json.JSONException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class FormEncodedMessageConverterTest {
	private static final Charset CHARSET = Charset.forName("ISO-8859-1");

	@BeforeEach
	void setUp() throws Exception {
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

}
