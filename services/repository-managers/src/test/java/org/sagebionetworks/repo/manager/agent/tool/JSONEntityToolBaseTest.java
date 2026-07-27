package org.sagebionetworks.repo.manager.agent.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.json.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.sagebionetworks.repo.manager.agent.specialist.ToolResponse;
import org.sagebionetworks.repo.model.Folder;
import org.sagebionetworks.repo.model.file.S3FileHandle;
import org.sagebionetworks.repo.model.jdo.JDOSecondaryPropertyUtils;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;

@ExtendWith(MockitoExtension.class)
public class JSONEntityToolBaseTest {

	private ExampleJSONEntityTool tool;

	@BeforeEach
	public void before() {
		tool = new ExampleJSONEntityTool();
	}

	private ToolCallback callback(String name) {
		return tool.getToolCallbacks().stream().filter(c -> name.equals(c.getToolDefinition().name())).findFirst()
				.orElseThrow();
	}

	@Test
	public void testGetToolCallbacks() {
		assertNotNull(tool.getToolCallbacks());
		assertEquals(3, tool.getToolCallbacks().size());
	}

	@Test
	public void testToolDefinitionUsesAnnotationName() {
		// The @JSONEntityTool name() overrides the method name when provided.
		ToolCallback callback = callback("get_entity_file_handle");
		assertEquals("This is the method description", callback.getToolDefinition().description());
		assertTrue(callback.getToolMetadata().returnDirect());
	}

	@Test
	public void testToolDefinitionFallsBackToMethodName() {
		// When name() is blank, the method name is used and returnDirect defaults to false.
		ToolCallback callback = callback("getRawPayload");
		assertNotNull(callback);
		assertFalse(callback.getToolMetadata().returnDirect());
	}

	@Test
	public void testInputSchemaGeneratedFromParameterType() {
		String inputSchema = callback("get_entity_file_handle").getToolDefinition().inputSchema();
		JSONObject schema = new JSONObject(inputSchema);

		// The schema is generated from the declared Entity parameter type, seeded with its concrete
		// implementers, so the polymorphic union appears as oneOf under $defs.
		assertTrue(schema.has("$defs"));
		assertTrue(inputSchema.contains("oneOf"));
		assertTrue(schema.getJSONObject("$defs").has("org.sagebionetworks.repo.model.Folder"));
		// The @JSONEntityToolParam description is surfaced on the root schema node the model reads.
		assertEquals("this is the parameter description", schema.getString("description"));
	}

	@Test
	public void testCallDeserializesTypedArgumentAndInjectsContext() {
		Folder folder = new Folder().setId("syn123").setName("my-folder");
		String toolInput = JDOSecondaryPropertyUtils.createJSONFromObject(folder);
		ToolContext context = new ToolContext(Map.of("key", "value"));

		// call under test
		String result = callback("get_entity_file_handle").call(toolInput, context);

		// The concreteType-aware path reconstructs the correct Entity subtype.
		assertEquals(folder, tool.getEntity());
		assertEquals(context, tool.getContext());
		// The ToolResponse is serialized via the JSONEntity path.
		assertEquals(JDOSecondaryPropertyUtils.createJSONFromObject(new ToolResponse<>(new S3FileHandle().setId("123"))),
				result);
	}

	@Test
	public void testCallWithRawStringSchemaTypePassesPayloadThrough() {
		Folder folder = new Folder().setId("syn999").setName("raw");
		String toolInput = JDOSecondaryPropertyUtils.createJSONFromObject(folder);

		// call under test — the raw payload reaches the tool untouched (not round-tripped through a POJO).
		String result = callback("getRawPayload").call(toolInput, null);

		assertEquals(toolInput, tool.getRawPayload());
		assertNull(tool.getEntity());
		assertEquals(JDOSecondaryPropertyUtils.createJSONFromObject(new ToolResponse<>(new S3FileHandle().setId("456"))),
				result);
	}

	@Test
	public void testRawStringSchemaTypeInputSchemaFromSchemaType() {
		// Even though the parameter is a raw String, the input schema is generated from schemaType=Entity.
		String inputSchema = callback("getRawPayload").getToolDefinition().inputSchema();
		JSONObject schema = new JSONObject(inputSchema);
		assertTrue(schema.has("$defs"));
		assertTrue(schema.getJSONObject("$defs").has("org.sagebionetworks.repo.model.Folder"));
	}

	@Test
	public void testCallWithJSONObjectParamParsesButPreservesRawTree() {
		// An omitted property must stay absent on the parsed JSONObject — distinct from an explicit null,
		// which a round-tripped POJO would collapse. Here 'name' is omitted entirely.
		String toolInput = "{\"concreteType\":\"org.sagebionetworks.repo.model.Folder\",\"id\":\"syn321\"}";

		// call under test — the base parses the payload into a JSONObject and passes the raw tree through.
		String result = callback("getRawObject").call(toolInput, null);

		assertEquals("syn321", tool.getRawObject().getString("id"));
		assertFalse(tool.getRawObject().has("name"), "An omitted property must remain absent on the raw tree");
		assertNull(tool.getEntity());
		assertEquals(JDOSecondaryPropertyUtils.createJSONFromObject(new ToolResponse<>(new S3FileHandle().setId("789"))),
				result);
	}

	@Test
	public void testCallWithJSONObjectParamAndMalformedJsonReturnsErrorString() {
		// A JSONObject parameter is validated by the base, so malformed input yields the same corrective
		// error string as a typed parameter — the tool body never runs.
		String result = callback("getRawObject").call("{ not valid json", null);

		assertTrue(result.contains("was not valid JSON for its input schema"));
		assertTrue(result.contains("Resubmit the call with a corrected argument."));
		assertNull(tool.getRawObject());
	}

	@Test
	public void testCallWithMalformedJsonReturnsErrorString() {
		// A typed parameter that cannot be parsed yields a corrective error string fed back to the
		// model — the tool method is never invoked and no exception escapes call(...).
		String result = callback("get_entity_file_handle").call("{ not valid json", new ToolContext(Map.of()));

		assertTrue(result.contains("was not valid JSON"), result);
		assertNull(tool.getEntity());
	}

	@Test
	public void testCallWithoutContext() {
		Folder folder = new Folder().setId("syn123").setName("my-folder");
		String toolInput = JDOSecondaryPropertyUtils.createJSONFromObject(folder);

		// call under test — the single-argument overload supplies a null context.
		String result = callback("get_entity_file_handle").call(toolInput);

		assertEquals(folder, tool.getEntity());
		assertNull(tool.getContext());
		assertNotNull(result);
	}
}
