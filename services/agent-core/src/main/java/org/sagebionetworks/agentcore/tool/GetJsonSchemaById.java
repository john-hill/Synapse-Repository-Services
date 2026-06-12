package org.sagebionetworks.agentcore.tool;

import org.sagebionetworks.repo.manager.schema.JsonSchemaManager;
import org.sagebionetworks.repo.model.schema.JsonSchema;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * Tool that allows agents to fetch Synapse JSON schemas by their $id.
 *
 * This tool calls JsonSchemaManager.getSchema() and returns the schema as a
 * JSON string. Agents should not automatically follow $ref unless needed to
 * answer the caller's question.
 */
@Component
public class GetJsonSchemaById {

	private static final String TOOL_NAME = "get_schema_by_id";
	private static final String TOOL_DESCRIPTION = """
			Fetches a Synapse JSON schema by its $id and returns it as a JSON string.
			Use this tool when you need to examine a schema's structure, properties, or validation rules.
			Do not automatically follow $ref references unless explicitly needed to answer the question.

			Input: The schema $id (e.g., "org.sagebionetworks.repo.model.FileEntity-1.0.0")
			Output: The JSON schema as a formatted string
			""";

	private final JsonSchemaManager schemaManager;

	public GetJsonSchemaById(JsonSchemaManager schemaManager) {
		this.schemaManager = schemaManager;
	}

	@Tool(description = TOOL_DESCRIPTION, resultConverter = JsonEntityResultconverter.class, name = TOOL_NAME)
	public JsonSchema getSchemaById(GetSchemaRequest request) {
		return schemaManager.getSchema(request.$id, true);
	}

	/**
	 * Request object for the getJsonSchema tool.
	 */
	public record GetSchemaRequest(
			@ToolParam(required = true, description = "The $id of a JSON schema that has been registered with Synapse.") String $id) {
	}
}
