package org.sagebionetworks.agentcore.tool;

import org.sagebionetworks.repo.manager.schema.JsonSchemaManager;
import org.sagebionetworks.repo.model.schema.JsonSchema;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Tool that allows agents to fetch Synapse JSON schemas by their $id.
 *
 * This tool calls JsonSchemaManager.getSchema() and returns the schema as a JSON string.
 * Agents should not automatically follow $ref unless needed to answer the caller's question.
 */
@Component
public class GetJsonSchemaById implements ToolCallback {

	private static final String TOOL_NAME = "getJsonSchema";
	private static final String TOOL_DESCRIPTION = """
			Fetches a Synapse JSON schema by its $id and returns it as a JSON string.
			Use this tool when you need to examine a schema's structure, properties, or validation rules.
			Do not automatically follow $ref references unless explicitly needed to answer the question.

			Input: The schema $id (e.g., "org.sagebionetworks.repo.model.FileEntity-1.0.0")
			Output: The JSON schema as a formatted string
			""";

	private final JsonSchemaManager schemaManager;
	private final ObjectMapper objectMapper;

	public GetJsonSchemaById(JsonSchemaManager schemaManager) {
		this.schemaManager = schemaManager;
		this.objectMapper = new ObjectMapper();
	}

	@Override
	public ToolDefinition getToolDefinition() {
		return ToolDefinition.builder()
			.name(TOOL_NAME)
			.description(TOOL_DESCRIPTION)
			.build();
	}

	@Override
	public String call(String toolInput) {
		try {
			GetSchemaRequest request = objectMapper.readValue(toolInput, GetSchemaRequest.class);

			JsonSchema schema = schemaManager.getSchema(request.schemaId(), true);

			if (schema == null) {
				return "Schema not found for $id: " + request.schemaId();
			}

			return objectMapper.writerWithDefaultPrettyPrinter()
				.writeValueAsString(schema);

		} catch (JsonProcessingException e) {
			return "Error processing request: " + e.getMessage();
		} catch (Exception e) {
			return "Error fetching schema: " + e.getMessage();
		}
	}

	/**
	 * Request object for the getJsonSchema tool.
	 */
	public record GetSchemaRequest(String schemaId) {}
}
