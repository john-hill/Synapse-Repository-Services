package org.sagebionetworks.schema.worker;

import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.io.InputStream;

import org.apache.commons.io.IOUtils;
import org.everit.json.schema.Schema;
import org.everit.json.schema.ValidationException;
import org.everit.json.schema.loader.SchemaLoader;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.sagebionetworks.repo.model.schema.JsonSchema;
import org.sagebionetworks.repo.model.schema.JsonSchemaConstants;

@ExtendWith(MockitoExtension.class)
public class SchemaValidationTest {
	
	/**
	 * Load a file from the classpath
	 * 
	 * @param name
	 * @return
	 * @throws IOException
	 */
	public String loadFromClassPath(String name) throws IOException {
		try (InputStream in = CreateJsonSchemaWorkerIntegrationTest.class.getClassLoader().getResourceAsStream(name);) {
			if (in == null) {
				throw new IllegalArgumentException("Cannot find: '" + name + "' on the classpath");
			}
			return IOUtils.toString(in, "UTF-8");
		}
	}

	@Test
	public void testValidation() throws IOException {
		String validationJson = loadFromClassPath("pets/ValidationSchema.json");
		JSONObject rawSchema = new JSONObject(validationJson);
		Schema schema = SchemaLoader.load(rawSchema);
		String jsonToValidate = loadFromClassPath("pets/Charity.json");
		schema.validate(new JSONObject(jsonToValidate));
		System.out.println("It worked!");
	}

	@Test
	public void testValidationTwo() throws IOException {
		String validationJson = loadFromClassPath("pets/ValidationTwo.json");
		JSONObject rawSchema = new JSONObject(validationJson);
		Schema schema = SchemaLoader.load(rawSchema);
		String jsonToValidate = loadFromClassPath("pets/Charity.json");
		schema.validate(new JSONObject(jsonToValidate));
		System.out.println("It worked!");
	}
	
	@Test
	public void testValidationTwoInvalid() throws IOException {
		String validationJson = loadFromClassPath("pets/ValidationTwo.json");
		JSONObject rawSchema = new JSONObject(validationJson);
		Schema schema = SchemaLoader.load(rawSchema);
		String jsonToValidate = loadFromClassPath("pets/CharityInvalid.json");
		assertThrows(ValidationException.class, ()->{
			schema.validate(new JSONObject(jsonToValidate));
		});
	}

	/**
	 * Helper to create a schema with the given $id.
	 * 
	 * @param $id
	 * @return
	 */
	public JsonSchema createSchema(String organizationName, String schemaName) {
		JsonSchema schema = new JsonSchema();
		schema.set$id(organizationName + JsonSchemaConstants.ID_DELIMITER + schemaName);
		return schema;
	}

}
