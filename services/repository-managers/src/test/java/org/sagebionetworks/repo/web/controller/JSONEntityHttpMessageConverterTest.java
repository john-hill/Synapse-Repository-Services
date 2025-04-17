package org.sagebionetworks.repo.web.controller;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.HashSet;
import java.util.Set;

import org.apache.commons.io.IOUtils;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;
import org.sagebionetworks.repo.model.Entity;
import org.sagebionetworks.repo.model.ErrorResponse;
import org.sagebionetworks.repo.model.Project;
import org.sagebionetworks.repo.model.schema.CreateSchemaRequest;
import org.sagebionetworks.schema.adapter.JSONEntity;
import org.sagebionetworks.schema.adapter.JSONObjectAdapter;
import org.sagebionetworks.schema.adapter.org.json.JSONObjectAdapterImpl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.HttpOutputMessage;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageNotWritableException;

import com.amazonaws.util.StringInputStream;


public class JSONEntityHttpMessageConverterTest {
	Project project;
	
	HttpOutputMessage mockOutMessage;
	HttpInputMessage mockInMessage;
	ByteArrayOutputStream outStream;
	HttpHeaders mockHeaders;
	JSONEntityHttpMessageConverter converter;

	@Before
	public void before() throws IOException{	
		project = new Project();
		project.setName("foo-bar");
		
		// Create the mocks
		outStream = new ByteArrayOutputStream();
		mockOutMessage = Mockito.mock(HttpOutputMessage.class);
		Mockito.when(mockOutMessage.getBody()).thenReturn(outStream);
		mockInMessage = Mockito.mock(HttpInputMessage.class);
		mockHeaders = Mockito.mock(HttpHeaders.class);
		
		Mockito.when(mockInMessage.getHeaders()).thenReturn(mockHeaders);
		Mockito.when(mockOutMessage.getHeaders()).thenReturn(mockHeaders);
		Mockito.when(mockHeaders.getContentType()).thenReturn(MediaType.APPLICATION_JSON);
		
		Set<Class <? extends JSONEntity>> set = new HashSet<>();
		set.add(CreateSchemaRequest.class);
		converter = new JSONEntityHttpMessageConverter(set);
		
	}

	@Test
	public void testCanRead(){
		assertTrue(converter.canRead(Project.class, MediaType.APPLICATION_JSON));
		assertFalse(converter.canRead(Object.class, MediaType.APPLICATION_JSON));
		assertTrue(converter.canRead(Project.class, new MediaType("application","json", Charset.forName("ISO-8859-1"))));
	}
	
	@Test
	public void testCanWrite(){
		assertTrue(converter.canWrite(Project.class, MediaType.APPLICATION_JSON));
		assertFalse(converter.canWrite(Object.class, MediaType.APPLICATION_JSON));
	}
	
	@Test
	public void testRoundTrip() throws HttpMessageNotWritableException, IOException{
		// Write it out.
		converter.write(project, MediaType.APPLICATION_JSON, mockOutMessage);
		
		ByteArrayInputStream in  = new ByteArrayInputStream(outStream.toByteArray());
		Mockito.when(mockInMessage.getBody()).thenReturn(in);
		// Make sure we can read it back
		JSONEntity results = converter.read(Project.class, mockInMessage);
		assertEquals(project, results);
	}
	
	@Test
	public void testRoundTripWithPlainTextMediaType() throws HttpMessageNotWritableException, IOException{
		// Write it out.
		converter.write(project, MediaType.TEXT_PLAIN, mockOutMessage);
		
		ByteArrayInputStream in  = new ByteArrayInputStream(outStream.toByteArray());
		Mockito.when(mockInMessage.getBody()).thenReturn(in);
		// Make sure we can read it back
		JSONEntity results = converter.read(Project.class, mockInMessage);
		assertEquals(project, results);
	}
	
	@Test
	public void testRoundTripWithFormencodedMediaType() throws IOException  {
		String keyValueParams = "name=foo-bar&concreteType=org.sagebionetworks.repo.model.Project";
		ByteArrayInputStream in  = new ByteArrayInputStream(keyValueParams.getBytes("ISO-8859-1"));
		Mockito.when(mockInMessage.getBody()).thenReturn(in);
		
		Mockito.when(mockHeaders.getContentType()).thenReturn(MediaType.APPLICATION_FORM_URLENCODED);

		// method under test
		JSONEntity results = converter.read(Project.class, mockInMessage);
		
		assertEquals(project, results);
	}
	

	
	@Test
	public void testErrorResponseRoundTripWithPlainTextMediaType() throws HttpMessageNotWritableException, IOException{
		ErrorResponse error = new ErrorResponse();
		error.setReason("foo");
		// Write it out.
		converter.write(error, MediaType.TEXT_PLAIN, mockOutMessage);
		
		ByteArrayInputStream in  = new ByteArrayInputStream(outStream.toByteArray());
		assertEquals("foo", IOUtils.toString(in));
	}
	
	@Test
	public void testConvertEntityToPlainText() throws Exception {
		ErrorResponse error = new ErrorResponse();
		error.setReason("foo");
		assertEquals("foo", JSONEntityHttpMessageConverter.convertEntityToPlainText(error));
	}
	
	@Test
	public void testPLFM_2079() throws Exception{
		// In the past we used the "entityType" field to determine which implementation Entity to create when a caller passed an JSON string.
		// This was specific to Entity so when the JSON schema project tackled the same problem "concreteType" was used instead of entityType.
		// We then switch Entities to use concreteType but we did not want this to be a breaking API change.
		// So when a old client uses "entityType" it should not break.
		
		// Create some JSON using a project entity.
		Project project = new Project();
		project.setName("someProject");
		project.setParentId("syn123");
		project.setId("syn456");
		JSONObject jsonObject = new JSONObject();
		JSONObjectAdapter adapter = new JSONObjectAdapterImpl(jsonObject);
		project.writeToJSONObject(adapter);
		// Swap the concreteType field with entityType
		String type = jsonObject.getString("concreteType");
		jsonObject.remove("concreteType");
		// replace it with entity type
		jsonObject.put("entityType", type);
		String json = adapter.toJSONString();
		assertTrue(json.indexOf("entityType") > 0);
		assertFalse(json.indexOf("concreteType") > 0);
		// Now make sure we can parse the json
		Mockito.when(mockInMessage.getBody()).thenReturn(new StringInputStream(json));
		try{
			Project clone = (Project) converter.read(Entity.class, mockInMessage);
			assertNotNull(clone);
			// It should match the original
			assertEquals(project, clone);
		}catch(Exception e){
			throw new RuntimeException(json,e);
		}
	}
	
	@Test
	public void testReadWhereValidationOfInvalidSuccess() throws Exception {
		// PLFM-6320
		// Invalid element, and the entity is one in which we want to validate
		// setup no exception to be thrown
		JSONObjectAdapter schema = new JSONObjectAdapterImpl();
		schema.put("description", "Expect this to fail");
		schema.put("notPartOfSpecification", "random");
		JSONObjectAdapter adapter = new JSONObjectAdapterImpl();
		adapter.put("concreteType", "org.sagebionetworks.repo.model.schema.CreateSchemaRequest");
		adapter.put("schema", schema);
		String jsonString = adapter.toJSONString();
		Mockito.when(mockInMessage.getBody()).thenReturn(new StringInputStream(jsonString));
		// call under test
		String message = assertThrows(IllegalArgumentException.class, () -> {
			converter.read(CreateSchemaRequest.class, mockInMessage);
		}).getMessage();
		assertEquals(message, "JSON Element in Entity is Unsupported: notPartOfSpecification");
	}
	
	@Test
	public void testReadWhereEntityTypeNotToBeValidated() throws Exception {
		// PLFM-6320
		// empty set, not entities to validate
		Set<Class <? extends JSONEntity>> set = new HashSet<>();
		JSONEntityHttpMessageConverter nonValidatingConverter = new JSONEntityHttpMessageConverter(set);
		JSONObjectAdapter schema = new JSONObjectAdapterImpl();
		schema.put("description", "Expect this to fail");
		// unsupported element
		schema.put("notPartOfSpecification", "random");
		JSONObjectAdapter adapter = new JSONObjectAdapterImpl();
		adapter.put("concreteType", "org.sagebionetworks.repo.model.schema.CreateSchemaRequest");
		adapter.put("schema", schema);
		String jsonString = adapter.toJSONString();
		Mockito.when(mockInMessage.getBody()).thenReturn(new StringInputStream(jsonString));
		// call under test
		CreateSchemaRequest result = (CreateSchemaRequest)nonValidatingConverter.read(CreateSchemaRequest.class, mockInMessage);
		assertNotNull(result);
	}
}
