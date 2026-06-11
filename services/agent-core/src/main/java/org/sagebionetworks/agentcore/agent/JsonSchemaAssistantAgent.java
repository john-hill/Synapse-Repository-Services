package org.sagebionetworks.agentcore.agent;

import org.sagebionetworks.agentcore.tool.GetJsonSchemaById;
import org.springaicommunity.agentcore.annotation.AgentCoreInvocation;
import org.springaicommunity.agentcore.context.AgentCoreContext;
import org.springaicommunity.agentcore.context.AgentCoreHeaders;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import reactor.core.publisher.Flux;

/**
 * Specialized agent for helping other agents understand Synapse JSON schemas.
 *
 * When asked about a schema, the agent uses the getJsonSchema tool to fetch schemas by $id
 * and explains their structure, validation rules, and usage patterns.
 *
 * The agent does NOT automatically follow $ref unless explicitly needed to answer the question.
 */
@Service
public class JsonSchemaAssistantAgent {

	private static final String SYSTEM_PROMPT = """
			You are a JSON Schema expert assistant for the Synapse platform.
			Your role is to help other agents and developers understand JSON schemas.

			When asked about a schema:
			1. Use the getJsonSchema tool to fetch the schema by its $id
			2. Explain the schema's structure, properties, and validation rules clearly
			3. Do NOT automatically follow $ref references unless they are needed to answer the specific question
			4. If the user asks about a referenced schema ($ref), fetch that schema separately using the tool
			5. Focus on the schema's purpose, required fields, types, and constraints
			6. Provide examples when helpful

			Be concise but thorough. Format your responses in markdown when appropriate.
			""";

	private final ChatClient chatClient;

	public JsonSchemaAssistantAgent(
			ChatClient.Builder chatClientBuilder,
			GetJsonSchemaById getJsonSchemaTool) {
		this.chatClient = chatClientBuilder
			.defaultSystem(SYSTEM_PROMPT)
			.defaultToolCallbacks(getJsonSchemaTool)
			.build();
	}

	@AgentCoreInvocation
	public Flux<String> chat(SchemaAssistantRequest request, AgentCoreContext context) {
		String sessionId = context.getHeader(AgentCoreHeaders.SESSION_ID);

		return chatClient.prompt()
			.user(request.prompt())
			.stream()
			.content();
	}

	/**
	 * Request for the JSON Schema Assistant agent.
	 *
	 * @param prompt The question or request about a JSON schema
	 */
	public record SchemaAssistantRequest(String prompt) {}
}
