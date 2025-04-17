package org.sagebionetworks.repo.web.controller;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.util.Collections;
import java.util.List;
import java.util.StringTokenizer;

import org.json.JSONException;
import org.json.JSONObject;
import org.sagebionetworks.repo.util.JSONEntityUtil;
import org.sagebionetworks.schema.adapter.JSONEntity;
import org.sagebionetworks.schema.adapter.JSONObjectAdapterException;
import org.sagebionetworks.schema.adapter.org.json.EntityFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.HttpOutputMessage;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.converter.HttpMessageNotWritableException;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.StringTokenizer;

import org.apache.commons.lang3.StringUtils;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.sagebionetworks.repo.model.Entity;
import org.sagebionetworks.repo.model.ErrorResponse;
import org.sagebionetworks.repo.util.JSONEntityUtil;
import org.sagebionetworks.schema.adapter.JSONArrayAdapter;
import org.sagebionetworks.schema.adapter.JSONEntity;
import org.sagebionetworks.schema.adapter.JSONObjectAdapter;
import org.sagebionetworks.schema.adapter.JSONObjectAdapterException;
import org.sagebionetworks.schema.adapter.org.json.EntityFactory;
import org.sagebionetworks.schema.adapter.org.json.JSONArrayAdapterImpl;
import org.sagebionetworks.schema.adapter.org.json.JSONObjectAdapterImpl;
import org.sagebionetworks.util.ValidateArgument;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.HttpOutputMessage;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.converter.HttpMessageNotWritableException;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.sagebionetworks.repo.model.schema.CreateSchemaRequest;
import org.sagebionetworks.repo.util.JSONEntityUtil;
import org.sagebionetworks.schema.adapter.JSONEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.HttpOutputMessage;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.converter.HttpMessageNotWritableException;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

/*
 * Class for reading application/x-www-form-urlencoded input into JSON entities
 * Note this message converter only reads and cannot write
 */
public class FormEncodedMessageConverter implements HttpMessageConverter<JSONEntity> {

	@Override
	public boolean canRead(Class<?> clazz, MediaType mediaType) {
		return JSONEntityUtil.isJSONEntity(clazz) && 
				MediaType.APPLICATION_FORM_URLENCODED.equalsTypeAndSubtype(mediaType);
	}

	@Override
	public boolean canWrite(Class<?> clazz, MediaType mediaType) {
		return false;
	}

	@Override
	public List<MediaType> getSupportedMediaTypes() {
		return Collections.singletonList(MediaType.APPLICATION_FORM_URLENCODED);
	}
	
	public static String convertFormEncodedDataToJSONString(String s, Charset charset) throws JSONException, UnsupportedEncodingException {
		JSONObject result = new JSONObject();
		// split by &
		StringTokenizer st=new StringTokenizer(s, "&");
		while (st.hasMoreTokens()) {
			String token = st.nextToken();
			String[] keyValuePair = token.split("=");
			if (keyValuePair.length!=2) throw new IllegalArgumentException("Expected key-value pairs separated by '= but found "+s);
			result.put(URLDecoder.decode(keyValuePair[0], charset.name()), URLDecoder.decode(keyValuePair[1], charset.name()));
		}
		return result.toString();
	}

	@Override
	public JSONEntity read(Class<? extends JSONEntity> clazz, HttpInputMessage inputMessage)
			throws IOException, HttpMessageNotReadableException {
		Charset charsetForDeSerializingBody = inputMessage.getHeaders().getContentType().getCharset();
		String formEncoded = JSONEntityHttpMessageConverterHelper.readToString(inputMessage.getBody(), charsetForDeSerializingBody);
		String jsonEncoded = convertFormEncodedDataToJSONString(formEncoded, charsetForDeSerializingBody);
		return JSONEntityHttpMessageConverterHelper.read(
				jsonEncoded, charsetForDeSerializingBody, clazz, Collections.EMPTY_SET);
	}

	@Override
	public void write(JSONEntity t, MediaType contentType, HttpOutputMessage outputMessage)
			throws IOException, HttpMessageNotWritableException {
		throw new IllegalStateException("Cannot write "+MediaType.APPLICATION_FORM_URLENCODED+" media type.");
	}

}
