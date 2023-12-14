package org.sagebionetworks.translator;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.File;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.tools.DocumentationTool;
import javax.tools.ToolProvider;

import org.apache.commons.io.IOUtils;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

public class ControllerModelDocletTest {
	private static File sampleSourceFile;
	private static File classpathFile;
	private static File outputDirectory;
	private static JSONObject generatedOpenAPISpec;
	
	private static File findFileOnClasspath(String fileName){
		URL url = ControllerModelDocletTest.class.getClassLoader().getResource(fileName);
		assertNotNull(url, "Failed to find: " + fileName + " on the classpath");
		File file = new File(url.getFile().replaceAll("%20", " "));
		assertTrue(file.exists());
		return file;
	}
	
	@BeforeAll
	public static void before() throws Exception{
		// Lookup the test files.
		sampleSourceFile = findFileOnClasspath("controller/BasicExampleController.java");
		// Find the classpath file generated by the maven-dependency-plugin
		String propertyValue = System.getProperty("auto.generated.classpath");
		if(propertyValue == null){
			// this occurs when run in eclipse.
			propertyValue = "target/gen/auto-generated-classpath.txt";
		}
		classpathFile = new File(propertyValue);
		assertTrue(classpathFile.exists(), "Classpath files does not exist: " + classpathFile.getAbsolutePath());
		// Lookup the output directory.
		propertyValue = System.getProperty("test.javadoc.output.directory");
		if(propertyValue == null){
			// this occurs when run in eclipse.
			propertyValue = "target/test-classes";
		}
		outputDirectory = new File(propertyValue);
		
		// invoke the doclet
		startDoclet();
	}

	public static void startDoclet() throws Exception {
		final String serverSideFactoryPath = "org.sagebionetworks.openapi.server.ServerSideOnlyFactory";
		final String controllersPackageName = "controller";
		final String targetFilePath = outputDirectory + "/GeneratedOpenAPISpec.json";
		
		String[] docletArgs = new String[] {
			"-doclet", ControllerModelDoclet.class.getName(),
			"-docletpath", "@" + classpathFile.getAbsolutePath(),
			"-sourcepath", sampleSourceFile.getParentFile().getParent(),
			"--target-file", targetFilePath,
			"--factory-path", serverSideFactoryPath,
			"--should-run", "true",
			controllersPackageName
		};
		
		for (int i = 0; i < docletArgs.length - 1; i += 2) {
			System.out.println(docletArgs[i] + "  " + docletArgs[i + 1]);
		}
		System.out.println(docletArgs[docletArgs.length - 1]);

		DocumentationTool docTool = ToolProvider.getSystemDocumentationTool();
		docTool.run(System.in, System.out, System.err, docletArgs);
		
		// get the resulting json generated
		try (InputStream is = ControllerModelDoclet.class.getClassLoader().getResourceAsStream("GeneratedOpenAPISpec.json")) {
			assertNotNull(is);
			String jsonTxt = IOUtils.toString(is, StandardCharsets.UTF_8);
			generatedOpenAPISpec = new JSONObject(jsonTxt);
			System.out.println("generated open api spec " + generatedOpenAPISpec.toString(5));
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}
	
	@Test
	public void testComplexPetsControllerExists() {
		JSONArray array = generatedOpenAPISpec.getJSONArray("tags");
		boolean foundComplexPetsTag = false;
		for (int i = 0; i < array.length(); i++) {
			JSONObject obj = array.getJSONObject(i);
			String controllerName = obj.get("name").toString();
			String controllerDescription = obj.get("description").toString();
			if (controllerName.equals("ComplexPets")) {
				foundComplexPetsTag = true;
				assertEquals("This controller is used to test translating for complex types.", controllerDescription);
			}
		}
		assertTrue(foundComplexPetsTag);
	}
	
	@Test
	public void testRedirectedEndpointResponsesGenerateCorrectly() {
		JSONObject pathsObj = generatedOpenAPISpec.getJSONObject("paths");
		JSONObject pathObj = pathsObj.getJSONObject("/repo/v1/complex-pet/redirected");
		JSONObject operationObj = pathObj.getJSONObject("get");
		
		JSONObject responses = operationObj.getJSONObject("responses");
		
		JSONObject responseStatusCode200 = responses.getJSONObject("200");
		assertEquals("Status 200 will be returned if the 'redirect' boolean param is false", responseStatusCode200.getString("description"));
		JSONObject content = responseStatusCode200.getJSONObject("content");
		assertNotNull(content.getJSONObject("text/plain"));
		
		JSONObject responseStatusCode307 = responses.getJSONObject("307");
		assertEquals("Status 307 will be returned if the 'redirect' boolean param is true or null", responseStatusCode307.getString("description"));
	}
	
	@Test
	public void testTagsIsCorrectInPath() {
		JSONObject pathsObj = generatedOpenAPISpec.getJSONObject("paths");
		JSONObject pathObj = pathsObj.getJSONObject("/repo/v1/complex-pet/{petName}");
		JSONObject operationObj = pathObj.getJSONObject("get");
		
		// test tags is correct
		JSONArray tags = operationObj.getJSONArray("tags");
		boolean foundComplexPetsTag = false;
		for (int i = 0; i < tags.length(); i++) {
			String tag = tags.getString(i);
			if (tag.equals("ComplexPets")) {
				foundComplexPetsTag = true;
			}
		}
		assertTrue(foundComplexPetsTag);
	}
	
	@Test
	public void testOperationIdIsCorrectInPath() {
		String fullPath = "/repo/v1/complex-pet/{petName}";
		JSONObject pathsObj = generatedOpenAPISpec.getJSONObject("paths");
		JSONObject pathObj = pathsObj.getJSONObject(fullPath);
		JSONObject operationObj = pathObj.getJSONObject("get");
		
		// test operationId is correct
		assertEquals("get-/repo/v1/complex-pet/{petName}", operationObj.getString("operationId"));
	}
	
	@Test
	public void testParametersAreCorrectInPath() {
		JSONObject pathsObj = generatedOpenAPISpec.getJSONObject("paths");
		JSONObject pathObj = pathsObj.getJSONObject("/repo/v1/complex-pet/{petName}");
		JSONObject operationObj = pathObj.getJSONObject("get");
		
		// test parameters are correct
		JSONArray parameters = operationObj.getJSONArray("parameters");
		boolean foundPetNameParam = false;
		for (int i = 0; i < parameters.length(); i++) {
			JSONObject param = parameters.getJSONObject(i);
			if (param.getString("name").equals("petName")) {
				assertEquals("path", param.getString("in"));
				assertEquals(true, param.getBoolean("required"));
				JSONObject schema = param.getJSONObject("schema");
				assertEquals("string", schema.getString("type"));
				foundPetNameParam = true;
			}
		}
		assertTrue(foundPetNameParam);
	}
	
	@Test
	public void testResponseObjectIsCorrectInPath() {
		JSONObject pathsObj = generatedOpenAPISpec.getJSONObject("paths");
		JSONObject pathObj = pathsObj.getJSONObject("/repo/v1/complex-pet/{petName}");
		JSONObject operationObj = pathObj.getJSONObject("get");
		
		// test response object is correct
		JSONObject response = operationObj.getJSONObject("responses");
		JSONObject responseStatusCode = response.getJSONObject("200");
		assertEquals("the Pet associated with 'name'.", responseStatusCode.getString("description"));
		JSONObject content = responseStatusCode.getJSONObject("content");
		JSONObject contentType = content.getJSONObject("application/json");
		JSONObject responseSchema = contentType.getJSONObject("schema");

		// test to see if Pet interface is represented correctly as the return type.
		assertEquals("#/components/schemas/org.sagebionetworks.openapi.pet.Pet", responseSchema.getString("$ref"));
	}
	
	@Test
	public void testRequestBodyIsCorrectInPath() {
		JSONObject pathsObj = generatedOpenAPISpec.getJSONObject("paths");
		JSONObject pathObj = pathsObj.getJSONObject("/repo/v1/complex-pet/cat/{name}");
		JSONObject operationObj = pathObj.getJSONObject("post");

		JSONObject requestBody = operationObj.getJSONObject("requestBody");
		assertEquals(true, requestBody.getBoolean("required"));
		JSONObject content = requestBody.getJSONObject("content");
		JSONObject contentType = content.getJSONObject("application/json");
		JSONObject schema = contentType.getJSONObject("schema");
		
		assertEquals("#/components/schemas/org.sagebionetworks.openapi.pet.Cat", schema.getString("$ref"));
	}
	
	@Test
	public void testSchemasGeneratedCorrectlyInComponents() {
		JSONObject componentsObj = generatedOpenAPISpec.getJSONObject("components");
		JSONObject schemasObj = componentsObj.getJSONObject("schemas");
		JSONObject petInterface = schemasObj.getJSONObject("org.sagebionetworks.openapi.pet.Pet");
		
		assertEquals("object", petInterface.getString("type"));
		assertEquals("This interface represents a pet.", petInterface.getString("description"));
		JSONObject petInterfaceProperties = petInterface.getJSONObject("properties");
		JSONObject petInterfaceNameProperty = petInterfaceProperties.getJSONObject("name");
		assertEquals("string", petInterfaceNameProperty.getString("type"));
		JSONObject responseSchemaHasTailProperty = petInterfaceProperties.getJSONObject("hasTail");
		assertEquals("boolean", responseSchemaHasTailProperty.getString("type"));
		
		// test to see if the oneof property is being set correctly.
		JSONArray oneOf = petInterface.getJSONArray("oneOf");
		assertTrue(oneOf.length() == 5);
		Set<String> references = new HashSet<>();
		for (int i = 0; i < oneOf.length(); i++) {
			JSONObject reference = oneOf.getJSONObject(i);
			String ref = reference.getString("$ref");
			references.add(ref);
		}
		assertTrue(references.contains("#/components/schemas/org.sagebionetworks.openapi.pet.Husky"));
		assertTrue(references.contains("#/components/schemas/org.sagebionetworks.openapi.pet.Poodle"));
		assertTrue(references.contains("#/components/schemas/org.sagebionetworks.openapi.pet.Cat"));
	}
	
	@Test
	public void testComplexTypesInPropertiesAreGeneratedAsReferences() {
		JSONObject componentsObj = generatedOpenAPISpec.getJSONObject("components");
		JSONObject schemasObj = componentsObj.getJSONObject("schemas");
		JSONObject poodleObj = schemasObj.getJSONObject("org.sagebionetworks.openapi.pet.Poodle");
		JSONObject propertiesObj = poodleObj.getJSONObject("properties");
		JSONObject ownerProperty = propertiesObj.getJSONObject("owner");
		
		// Should be a reference to the place the complex type lives in the "components" section
		assertEquals("#/components/schemas/org.sagebionetworks.openapi.pet.Owner", ownerProperty.getString("$ref"));
	}

	@Test
	public void testDescriptionandContentForVoidReturnMethodWithNoRedirect() {
		JSONObject pathsObj = generatedOpenAPISpec.getJSONObject("paths");
		JSONObject pathObj = pathsObj.getJSONObject("/repo/v1/complex-pet/voidreturnnoredirect/{name}");
		JSONObject deleteObj = pathObj.getJSONObject("delete");
		JSONObject responsesObj = deleteObj.getJSONObject("responses");
		JSONObject okObj = responsesObj.getJSONObject("200");

		// Should be a reference to the place the complex type lives in the "components" section
		assertEquals("Void", okObj.getString("description"));
		assertTrue(okObj.isNull("content"));
	}

	@Test
	public void testDescriptionForMethodWithNoReturnComment() {
		JSONObject pathsObj = generatedOpenAPISpec.getJSONObject("paths");
		JSONObject pathObj = pathsObj.getJSONObject("/repo/v1/complex-pet/noreturndescription/{name}");
		JSONObject getObj = pathObj.getJSONObject("get");
		JSONObject responsesObj = getObj.getJSONObject("responses");
		JSONObject okObj = responsesObj.getJSONObject("200");
		JSONObject contentObj = okObj.getJSONObject("content");
		JSONObject contentType = contentObj.getJSONObject("application/json");
		JSONObject responseSchema = contentType.getJSONObject("schema");

		// Should be a reference to the place the complex type lives in the "components" section
		assertEquals("Auto-generated description", okObj.getString("description"));
		assertEquals("#/components/schemas/org.sagebionetworks.openapi.pet.Pet", responseSchema.getString("$ref"));
	}

	@Test
	public void testHttpServletResponseNotIncludedAsParameter() {
		JSONObject pathsObj = generatedOpenAPISpec.getJSONObject("paths");
		JSONObject pathObj = pathsObj.getJSONObject("/repo/v1/complex-pet/file/{fileId}/url/httpservletresponse");
		JSONObject getObj = pathObj.getJSONObject("get");
		JSONArray parametersObj = getObj.getJSONArray("parameters");

		for (int i = 0; i < parametersObj.length(); i++) {
			JSONObject parameterObj = parametersObj.getJSONObject(i);
			JSONObject schemaObj = parameterObj.getJSONObject("schema");
			assertTrue(schemaObj.isNull("$ref"));
		}
	}

	@Test
	public void testHttpServletRequestNotIncludedAsParameter() {
		JSONObject pathsObj = generatedOpenAPISpec.getJSONObject("paths");
		JSONObject pathObj = pathsObj.getJSONObject("/repo/v1/complex-pet/dog/{name}/httpservletrequest");
		JSONObject deleteObj = pathObj.getJSONObject("delete");
		JSONArray parametersObj = deleteObj.getJSONArray("parameters");

		for (int i = 0; i < parametersObj.length(); i++) {
			JSONObject parameterObj = parametersObj.getJSONObject(i);
			JSONObject schemaObj = parameterObj.getJSONObject("schema");
			assertTrue(schemaObj.isNull("$ref"));
		}
	}

	@Test
	public void testUriComponentsBuilderNotIncludedAsParameter() {
		JSONObject pathsObj = generatedOpenAPISpec.getJSONObject("paths");
		JSONObject pathObj = pathsObj.getJSONObject("/repo/v1/complex-pet/account/uricomponentsbuilder");
		JSONObject postObj = pathObj.getJSONObject("post");
		JSONArray parametersObj = postObj.getJSONArray("parameters");

		for (int i = 0; i < parametersObj.length(); i++) {
			JSONObject parameterObj = parametersObj.getJSONObject(i);
			JSONObject schemaObj = parameterObj.getJSONObject("schema");
			assertTrue(schemaObj.isNull("$ref"));
		}
	}

	@Test
	public void testRequestHeaderAnnotationMappedToParameterLocation() {
		JSONObject pathsObj = generatedOpenAPISpec.getJSONObject("paths");
		JSONObject pathObj = pathsObj.getJSONObject("/repo/v1/complex-pet/requestheader");
		JSONObject postObj = pathObj.getJSONObject("post");
		JSONArray parametersObj = postObj.getJSONArray("parameters");
		JSONObject parameterObj = parametersObj.getJSONObject(0);
		String in = parameterObj.getString("in");

		assertEquals("header", in);
	}

	@Test
	public void testResponseStatusNoContent() {
		JSONObject pathsObj = generatedOpenAPISpec.getJSONObject("paths");
		JSONObject pathObj = pathsObj.getJSONObject("/repo/v1/complex-pet/nocontentresponsestatus");
		JSONObject postObj = pathObj.getJSONObject("post");
		JSONObject responsesObj = postObj.getJSONObject("responses");

		JSONObject responseObj = responsesObj.getJSONObject("204");
		assertEquals("Void", responseObj.getString("description"));
	}

	@Test
	public void testResponseStatusAccepted() {
		JSONObject pathsObj = generatedOpenAPISpec.getJSONObject("paths");
		JSONObject pathObj = pathsObj.getJSONObject("/repo/v1/complex-pet/acceptedresponsestatus");
		JSONObject postObj = pathObj.getJSONObject("post");
		JSONObject responsesObj = postObj.getJSONObject("responses");

		JSONObject responseObj = responsesObj.getJSONObject("202");
		assertEquals("the name that was added", responseObj.getString("description"));
	}

	@Test
	public void testResponseStatusGone() {
		JSONObject pathsObj = generatedOpenAPISpec.getJSONObject("paths");
		JSONObject pathObj = pathsObj.getJSONObject("/repo/v1/complex-pet/goneresponsestatus");
		JSONObject postObj = pathObj.getJSONObject("post");
		JSONObject responsesObj = postObj.getJSONObject("responses");

		JSONObject responseObj = responsesObj.getJSONObject("410");
		assertEquals("the name that was added", responseObj.getString("description"));
	}

	@Test
	public void testNoResponseStatus() {
		JSONObject pathsObj = generatedOpenAPISpec.getJSONObject("paths");
		JSONObject pathObj = pathsObj.getJSONObject("/repo/v1/complex-pet/noresponsestatus");
		JSONObject postObj = pathObj.getJSONObject("post");
		JSONObject responsesObj = postObj.getJSONObject("responses");

		JSONObject responseObj = responsesObj.getJSONObject("200");
		assertEquals("the name that was added", responseObj.getString("description"));
	}

	@Test
	public void testDeprecatedMethod() {
		JSONObject pathsObj = generatedOpenAPISpec.getJSONObject("paths");
		assertTrue(pathsObj.isNull("/repo/v1/complex-pet/deprecated"));
	}

	@Test
	public void testNoControllerComment() {
		JSONArray array = generatedOpenAPISpec.getJSONArray("tags");
		for (int i = 0; i < array.length(); i++) {
			JSONObject obj = array.getJSONObject(i);
			String controllerName = obj.get("name").toString();
			if (controllerName.equals("NoComment")) {
				assertEquals("Auto-generated description", obj.get("description").toString());
			}
		}
	}

	@Test
	public void testDifferentPathAndMethodParameterNames() {
		JSONObject pathsObj = generatedOpenAPISpec.getJSONObject("paths");
		JSONObject pathObj = pathsObj.getJSONObject("/repo/v1/complex-pet/differentpathandmethodparameternames/{petName}");
		JSONObject postObj = pathObj.getJSONObject("get");
		JSONArray parametersObj = postObj.getJSONArray("parameters");
		JSONObject parameterObj = parametersObj.getJSONObject(0);
		assertEquals("petName", parameterObj.getString("name"));
		assertEquals("the name of the pet", parameterObj.getString("description"));
	}

	@Test
	public void testDifferentHeaderAndMethodParameterNames() {
		JSONObject pathsObj = generatedOpenAPISpec.getJSONObject("paths");
		JSONObject pathObj = pathsObj.getJSONObject("/repo/v1/complex-pet/differentheaderandmethodparameternames");
		JSONObject getObj = pathObj.getJSONObject("get");
		JSONArray paramsObj = getObj.getJSONArray("parameters");
		JSONObject paramObj = (JSONObject) paramsObj.get(0);


		assertEquals("annotationValue", paramObj.getString("name"));
	}

	@Test
	public void testDifferentRequestParameterAndMethodParameterNames() {
		JSONObject pathsObj = generatedOpenAPISpec.getJSONObject("paths");
		JSONObject pathObj = pathsObj.getJSONObject("/repo/v1/complex-pet/differentrequestparameterandmethodparameternames");
		JSONObject getObj = pathObj.getJSONObject("get");
		JSONArray paramsObj = getObj.getJSONArray("parameters");
		JSONObject paramObj = (JSONObject) paramsObj.get(0);


		assertEquals("annotationValue", paramObj.getString("name"));
	}

	@Test
	public void testRegularExpressionInPathParameter() {
		JSONObject pathsObj = generatedOpenAPISpec.getJSONObject("paths");
		JSONObject pathObj = pathsObj.getJSONObject("/repo/v1/complex-pet/regularexpression/{id}/test");
		JSONObject getObj = pathObj.getJSONObject("get");
		assertEquals("get-/repo/v1/complex-pet/regularexpression/{id}/test", getObj.getString("operationId"));
	}

	@Test
	public void testStringPrimitive() {
		JSONObject pathsObj = generatedOpenAPISpec.getJSONObject("paths");
		JSONObject pathObj = pathsObj.getJSONObject("/repo/v1/complex-pet/string/{testString}");
		JSONObject getObj = pathObj.getJSONObject("get");
		JSONArray paramsObj = getObj.getJSONArray("parameters");
		JSONObject paramObj = paramsObj.getJSONObject(0);
		JSONObject paramSchemaObj = paramObj.getJSONObject("schema");

		JSONObject responsesObj = getObj.getJSONObject("responses");
		JSONObject okObj = responsesObj.getJSONObject("200");
		JSONObject contentObj = okObj.getJSONObject("content");
		JSONObject mediaObj = contentObj.getJSONObject("application/json");
		JSONObject responseSchemaObj = mediaObj.getJSONObject("schema");

		assertEquals("string", paramSchemaObj.getString("type"));
		assertEquals("string", responseSchemaObj.getString("type"));
	}

	@Test
	public void testIntegerClass() {
		JSONObject pathsObj = generatedOpenAPISpec.getJSONObject("paths");
		JSONObject pathObj = pathsObj.getJSONObject("/repo/v1/complex-pet/integerclass/{testIntegerClass}");
		JSONObject getObj = pathObj.getJSONObject("get");
		JSONArray paramsObj = getObj.getJSONArray("parameters");
		JSONObject paramObj = paramsObj.getJSONObject(0);
		JSONObject paramSchemaObj = paramObj.getJSONObject("schema");

		JSONObject responsesObj = getObj.getJSONObject("responses");
		JSONObject okObj = responsesObj.getJSONObject("200");
		JSONObject contentObj = okObj.getJSONObject("content");
		JSONObject mediaObj = contentObj.getJSONObject("application/json");
		JSONObject responseSchemaObj = mediaObj.getJSONObject("schema");

		assertEquals("integer", paramSchemaObj.getString("type"));
		assertEquals("integer", responseSchemaObj.getString("type"));
	}

	@Test
	public void testBooleanClass() {
		JSONObject pathsObj = generatedOpenAPISpec.getJSONObject("paths");
		JSONObject pathObj = pathsObj.getJSONObject("/repo/v1/complex-pet/booleanclass/{testBooleanClass}");
		JSONObject getObj = pathObj.getJSONObject("get");
		JSONArray paramsObj = getObj.getJSONArray("parameters");
		JSONObject paramObj = paramsObj.getJSONObject(0);
		JSONObject paramSchemaObj = paramObj.getJSONObject("schema");

		JSONObject responsesObj = getObj.getJSONObject("responses");
		JSONObject okObj = responsesObj.getJSONObject("200");
		JSONObject contentObj = okObj.getJSONObject("content");
		JSONObject mediaObj = contentObj.getJSONObject("application/json");
		JSONObject responseSchemaObj = mediaObj.getJSONObject("schema");

		assertEquals("boolean", paramSchemaObj.getString("type"));
		assertEquals("boolean", responseSchemaObj.getString("type"));
	}

	@Test
	public void testLongClass() {
		JSONObject pathsObj = generatedOpenAPISpec.getJSONObject("paths");
		JSONObject pathObj = pathsObj.getJSONObject("/repo/v1/complex-pet/longclass/{testLongClass}");
		JSONObject getObj = pathObj.getJSONObject("get");
		JSONArray paramsObj = getObj.getJSONArray("parameters");
		JSONObject paramObj = paramsObj.getJSONObject(0);
		JSONObject paramSchemaObj = paramObj.getJSONObject("schema");

		JSONObject responsesObj = getObj.getJSONObject("responses");
		JSONObject okObj = responsesObj.getJSONObject("200");
		JSONObject contentObj = okObj.getJSONObject("content");
		JSONObject mediaObj = contentObj.getJSONObject("application/json");
		JSONObject responseSchemaObj = mediaObj.getJSONObject("schema");

		assertEquals("number", paramSchemaObj.getString("type"));
		assertEquals("number", responseSchemaObj.getString("type"));
	}

	@Test
	public void testObjectClass() {
		JSONObject pathsObj = generatedOpenAPISpec.getJSONObject("paths");
		JSONObject pathObj = pathsObj.getJSONObject("/repo/v1/complex-pet/objectclass/{testObject}");
		JSONObject getObj = pathObj.getJSONObject("get");
		JSONArray paramsObj = getObj.getJSONArray("parameters");
		JSONObject paramObj = paramsObj.getJSONObject(0);
		JSONObject paramSchemaObj = paramObj.getJSONObject("schema");

		JSONObject responsesObj = getObj.getJSONObject("responses");
		JSONObject okObj = responsesObj.getJSONObject("200");
		JSONObject contentObj = okObj.getJSONObject("content");
		JSONObject mediaObj = contentObj.getJSONObject("application/json");
		JSONObject responseSchemaObj = mediaObj.getJSONObject("schema");

		assertEquals("object", paramSchemaObj.getString("type"));
		assertEquals("object", responseSchemaObj.getString("type"));
	}

	@Test
	public void testBooleanPrimitive() {
		JSONObject pathsObj = generatedOpenAPISpec.getJSONObject("paths");
		JSONObject pathObj = pathsObj.getJSONObject("/repo/v1/complex-pet/booleanprimitive/{testBooleanPrimitive}");
		JSONObject getObj = pathObj.getJSONObject("get");
		JSONArray paramsObj = getObj.getJSONArray("parameters");
		JSONObject paramObj = paramsObj.getJSONObject(0);
		JSONObject paramSchemaObj = paramObj.getJSONObject("schema");

		JSONObject responsesObj = getObj.getJSONObject("responses");
		JSONObject okObj = responsesObj.getJSONObject("200");
		JSONObject contentObj = okObj.getJSONObject("content");
		JSONObject mediaObj = contentObj.getJSONObject("application/json");
		JSONObject responseSchemaObj = mediaObj.getJSONObject("schema");

		assertEquals("boolean", paramSchemaObj.getString("type"));
		assertEquals("boolean", responseSchemaObj.getString("type"));
	}

	@Test
	public void testIntPrimitive() {
		JSONObject pathsObj = generatedOpenAPISpec.getJSONObject("paths");
		JSONObject pathObj = pathsObj.getJSONObject("/repo/v1/complex-pet/intprimitive/{testIntPrimitive}");
		JSONObject getObj = pathObj.getJSONObject("get");
		JSONArray paramsObj = getObj.getJSONArray("parameters");
		JSONObject paramObj = paramsObj.getJSONObject(0);
		JSONObject paramSchemaObj = paramObj.getJSONObject("schema");

		JSONObject responsesObj = getObj.getJSONObject("responses");
		JSONObject okObj = responsesObj.getJSONObject("200");
		JSONObject contentObj = okObj.getJSONObject("content");
		JSONObject mediaObj = contentObj.getJSONObject("application/json");
		JSONObject responseSchemaObj = mediaObj.getJSONObject("schema");

		assertEquals("integer", paramSchemaObj.getString("type"));
		assertEquals("integer", responseSchemaObj.getString("type"));
	}

	@Test
	public void testLongPrimitive() {
		JSONObject pathsObj = generatedOpenAPISpec.getJSONObject("paths");
		JSONObject pathObj = pathsObj.getJSONObject("/repo/v1/complex-pet/longprimitive/{testLongPrimitive}");
		JSONObject getObj = pathObj.getJSONObject("get");
		JSONArray paramsObj = getObj.getJSONArray("parameters");
		JSONObject paramObj = paramsObj.getJSONObject(0);
		JSONObject paramSchemaObj = paramObj.getJSONObject("schema");

		JSONObject responsesObj = getObj.getJSONObject("responses");
		JSONObject okObj = responsesObj.getJSONObject("200");
		JSONObject contentObj = okObj.getJSONObject("content");
		JSONObject mediaObj = contentObj.getJSONObject("application/json");
		JSONObject responseSchemaObj = mediaObj.getJSONObject("schema");

		assertEquals("number", paramSchemaObj.getString("type"));
		assertEquals("number", responseSchemaObj.getString("type"));
	}

	@Test
	public void testBooleanResult() {
		JSONObject pathsObj = generatedOpenAPISpec.getJSONObject("paths");
		JSONObject pathObj = pathsObj.getJSONObject("/repo/v1/complex-pet/booleanresult");
		JSONObject getObj = pathObj.getJSONObject("get");
		JSONObject responsesObj = getObj.getJSONObject("responses");
		JSONObject okObj = responsesObj.getJSONObject("200");
		JSONObject contentObj = okObj.getJSONObject("content");
		JSONObject mediaObj = contentObj.getJSONObject("application/json");
		JSONObject responseSchemaObj = mediaObj.getJSONObject("schema");

		assertEquals("boolean", responseSchemaObj.getString("type"));
	}

	@Test
	public void testJsonObject() {
		JSONObject pathsObj = generatedOpenAPISpec.getJSONObject("paths");
		JSONObject pathObj = pathsObj.getJSONObject("/repo/v1/complex-pet/jsonobject/{testJsonObject}");
		JSONObject getObj = pathObj.getJSONObject("get");
		JSONArray paramsObj = getObj.getJSONArray("parameters");
		JSONObject paramObj = paramsObj.getJSONObject(0);
		JSONObject paramSchemaObj = paramObj.getJSONObject("schema");

		JSONObject responsesObj = getObj.getJSONObject("responses");
		JSONObject okObj = responsesObj.getJSONObject("200");
		JSONObject contentObj = okObj.getJSONObject("content");
		JSONObject mediaObj = contentObj.getJSONObject("application/json");
		JSONObject responseSchemaObj = mediaObj.getJSONObject("schema");

		assertEquals("object", paramSchemaObj.getString("type"));
		assertEquals("object", responseSchemaObj.getString("type"));
	}

	@Test
	public void testObjectSchema() {
		JSONObject pathsObj = generatedOpenAPISpec.getJSONObject("paths");
		JSONObject pathObj = pathsObj.getJSONObject("/repo/v1/complex-pet/objectschema");
		JSONObject getObj = pathObj.getJSONObject("get");
		JSONObject responsesObj = getObj.getJSONObject("responses");
		JSONObject okObj = responsesObj.getJSONObject("200");
		JSONObject contentObj = okObj.getJSONObject("content");
		JSONObject mediaObj = contentObj.getJSONObject("application/json");
		JSONObject responseSchemaObj = mediaObj.getJSONObject("schema");

		assertEquals("object", responseSchemaObj.getString("type"));
	}

	@Test
	public void testGenericPaginatedResultsClassWithCustomClassArgument() {
		JSONObject pathsObj = generatedOpenAPISpec.getJSONObject("paths");
		JSONObject pathObj = pathsObj.getJSONObject("/repo/v1/complex-pet/paginatedresultsofclass");
		JSONObject getObj = pathObj.getJSONObject("get");

		JSONObject responsesObj = getObj.getJSONObject("responses");
		JSONObject okObj = responsesObj.getJSONObject("200");
		JSONObject contentObj = okObj.getJSONObject("content");
		JSONObject mediaObj = contentObj.getJSONObject("application/json");
		JSONObject responseSchemaObj = mediaObj.getJSONObject("schema");

		assertEquals("#/components/schemas/PaginatedResultsOfPug", responseSchemaObj.getString("$ref"));

		JSONObject componentsObj = generatedOpenAPISpec.getJSONObject("components");
		JSONObject schemasObj = componentsObj.getJSONObject("schemas");
		JSONObject schemaObj = schemasObj.getJSONObject("PaginatedResultsOfPug");
		JSONObject propertiesObj = schemaObj.getJSONObject("properties");
		JSONObject resultsObj = propertiesObj.getJSONObject("results");
		JSONObject itemsObj = resultsObj.getJSONObject("items");
		JSONObject numberOfResultsObj = propertiesObj.getJSONObject("totalNumberOfResults");

		assertEquals("object", schemaObj.getString("type"));
		assertEquals("array", resultsObj.getString("type"));
		assertEquals("#/components/schemas/org.sagebionetworks.openapi.pet.Pug", itemsObj.getString("$ref"));
		assertEquals("integer", numberOfResultsObj.getString("type"));
	}

	@Test
	public void testGenericListWrapperClassWithCustomClassArgument() {
		JSONObject pathsObj = generatedOpenAPISpec.getJSONObject("paths");
		JSONObject pathObj = pathsObj.getJSONObject("/repo/v1/complex-pet/listwrapperofclass");
		JSONObject getObj = pathObj.getJSONObject("get");

		JSONObject responsesObj = getObj.getJSONObject("responses");
		JSONObject okObj = responsesObj.getJSONObject("200");
		JSONObject contentObj = okObj.getJSONObject("content");
		JSONObject mediaObj = contentObj.getJSONObject("application/json");
		JSONObject responseSchemaObj = mediaObj.getJSONObject("schema");

		assertEquals("#/components/schemas/ListWrapperOfCat", responseSchemaObj.getString("$ref"));

		JSONObject requestBodyObj = getObj.getJSONObject("requestBody");
		JSONObject requestBodyContentObj = requestBodyObj.getJSONObject("content");
		JSONObject requestBodyMediaObj = requestBodyContentObj.getJSONObject("application/json");
		JSONObject responseBodySchemaObj = requestBodyMediaObj.getJSONObject("schema");

		assertEquals("#/components/schemas/ListWrapperOfPet", responseBodySchemaObj.getString("$ref"));

		JSONObject componentsObj = generatedOpenAPISpec.getJSONObject("components");
		JSONObject schemasObj = componentsObj.getJSONObject("schemas");
		JSONObject schemaObj = schemasObj.getJSONObject("ListWrapperOfCat");
		JSONObject propertiesObj = schemaObj.getJSONObject("properties");
		JSONObject listObj = propertiesObj.getJSONObject("list");
		JSONObject itemsObj = listObj.getJSONObject("items");

		assertEquals("object", schemaObj.getString("type"));
		assertEquals("array", listObj.getString("type"));
		assertEquals("#/components/schemas/org.sagebionetworks.openapi.pet.Cat", itemsObj.getString("$ref"));
	}

	@Test
	public void testGenericListClassWithCustomClassArgument() {
		JSONObject pathsObj = generatedOpenAPISpec.getJSONObject("paths");
		JSONObject pathObj = pathsObj.getJSONObject("/repo/v1/complex-pet/listofclass");
		JSONObject getObj = pathObj.getJSONObject("get");

		JSONObject paramObj = (JSONObject) getObj.getJSONArray("parameters").get(0);
		JSONObject paramSchemaObj = paramObj.getJSONObject("schema");

		assertEquals("#/components/schemas/ListOfTerrier", paramSchemaObj.getString("$ref"));

		JSONObject componentsObj = generatedOpenAPISpec.getJSONObject("components");
		JSONObject schemasObj = componentsObj.getJSONObject("schemas");
		JSONObject componentsSchemaObj = schemasObj.getJSONObject("ListOfTerrier");
		JSONObject itemsObj = componentsSchemaObj.getJSONObject("items");

		assertEquals("array", componentsSchemaObj.getString("type"));
		assertEquals("#/components/schemas/org.sagebionetworks.openapi.pet.Terrier", itemsObj.getString("$ref"));
	}

	@Test
	public void testGenericListClassWithStringArgument() {
		JSONObject pathsObj = generatedOpenAPISpec.getJSONObject("paths");
		JSONObject pathObj = pathsObj.getJSONObject("/repo/v1/complex-pet/listofstring");
		JSONObject getObj = pathObj.getJSONObject("get");

		JSONObject paramObj = (JSONObject) getObj.getJSONArray("parameters").get(0);
		JSONObject paramSchemaObj = paramObj.getJSONObject("schema");

		assertEquals("#/components/schemas/ListOfString", paramSchemaObj.getString("$ref"));

		JSONObject componentsObj = generatedOpenAPISpec.getJSONObject("components");
		JSONObject schemasObj = componentsObj.getJSONObject("schemas");
		JSONObject schemaObj = schemasObj.getJSONObject("ListOfString");
		JSONObject itemsObj = schemaObj.getJSONObject("items");

		assertEquals("array", schemaObj.getString("type"));
		assertEquals("string", itemsObj.getString("type"));
	}

	@Test
	public void testHttpHeadersClassNotTranslated() {
		JSONObject pathsObj = generatedOpenAPISpec.getJSONObject("paths");
		JSONObject pathObj = pathsObj.getJSONObject("/repo/v1/complex-pet/httpheaders");
		JSONObject getObj = pathObj.getJSONObject("get");
		JSONArray paramsObj = getObj.getJSONArray("parameters");

		assertEquals(0, paramsObj.length());
	}

	@Test
	public void testRequiredFalse() {
		JSONObject pathsObj = generatedOpenAPISpec.getJSONObject("paths");
		JSONObject pathObj = pathsObj.getJSONObject("/repo/v1/complex-pet/requiredfalse");
		JSONObject getObj = pathObj.getJSONObject("get");
		JSONArray paramsObj = getObj.getJSONArray("parameters");
		JSONObject paramObj = (JSONObject) paramsObj.get(0);

		assertEquals("false", paramObj.getString("required"));

		JSONObject requestBodyObj = getObj.getJSONObject("requestBody");

		assertEquals("false", requestBodyObj.getString("required"));
	}

	@Test
	public void testRequiredTrue() {
		JSONObject pathsObj = generatedOpenAPISpec.getJSONObject("paths");
		JSONObject pathObj = pathsObj.getJSONObject("/repo/v1/complex-pet/requiredtrue");
		JSONObject getObj = pathObj.getJSONObject("get");
		JSONArray paramsObj = getObj.getJSONArray("parameters");
		JSONObject paramObj = (JSONObject) paramsObj.get(0);

		assertEquals("true", paramObj.getString("required"));

		JSONObject requestBodyObj = getObj.getJSONObject("requestBody");

		assertEquals("true", requestBodyObj.getString("required"));
	}

	@Test
	public void testEnum() {
		JSONObject componentsObj = generatedOpenAPISpec.getJSONObject("components");
		JSONObject schemasObj = componentsObj.getJSONObject("schemas");

		JSONObject statusEnumSchemaObj = schemasObj.getJSONObject("org.sagebionetworks.repo.model.status.StatusEnum");
		JSONArray statusEnumExpected = new JSONArray(List.of("READ_WRITE", "READ_ONLY", "DOWN"));
		assertEquals(statusEnumExpected.toString(), statusEnumSchemaObj.getJSONArray("enum").toString());

		JSONObject aliasEnumSchemaObj = schemasObj.getJSONObject("org.sagebionetworks.repo.model.principal.AliasEnum");
		JSONArray aliasEnumExpected = new JSONArray(List.of("USER_NAME", "TEAM_NAME", "USER_EMAIL", "USER_OPEN_ID", "USER_ORCID"));
		assertEquals(aliasEnumExpected.toString(), aliasEnumSchemaObj.getJSONArray("enum").toString());

		JSONObject stateEnumSchemaObj = schemasObj.getJSONObject("org.sagebionetworks.repo.model.form.StateEnum");
		JSONArray stateEnumExpected = new JSONArray(List.of("WAITING_FOR_SUBMISSION", "SUBMITTED_WAITING_FOR_REVIEW", "ACCEPTED", "REJECTED"));
		assertEquals(stateEnumExpected.toString(), stateEnumSchemaObj.getJSONArray("enum").toString());
	}

	@Test
	public void testUserId() {
		JSONObject pathsObj = generatedOpenAPISpec.getJSONObject("paths");
		JSONObject pathObj = pathsObj.getJSONObject("/repo/v1/complex-pet/userid");
		JSONObject getObj = pathObj.getJSONObject("get");
		JSONArray paramsObj = getObj.getJSONArray("parameters");

		assertEquals(0, paramsObj.length());
	}

	@Test
	public void testAuthorization() {
		JSONObject pathsObj = generatedOpenAPISpec.getJSONObject("paths");
		JSONObject pathObj = pathsObj.getJSONObject("/repo/v1/complex-pet/authorization");
		JSONObject getObj = pathObj.getJSONObject("get");
		JSONArray securityObj = getObj.getJSONArray("security");
		JSONObject bearerObj = securityObj.getJSONObject(0);

		assertEquals(new JSONArray().toString(), bearerObj.getJSONArray("bearerAuth").toString());

		JSONObject componentsObj = generatedOpenAPISpec.getJSONObject("components");
		JSONObject securitySchemesObj = componentsObj.getJSONObject("securitySchemes");
		JSONObject bearerAuthObj = securitySchemesObj.getJSONObject("bearerAuth");

		assertEquals("http", bearerAuthObj.getString("type"));
		assertEquals("bearer", bearerAuthObj.getString("scheme"));
	}

	@Test
	public void testNoAuthorization() {
		JSONObject pathsObj = generatedOpenAPISpec.getJSONObject("paths");
		JSONObject pathObj = pathsObj.getJSONObject("/repo/v1/complex-pet/noauthorization");
		JSONObject getObj = pathObj.getJSONObject("get");
		assertTrue(pathObj.isNull("security"));
	}
}