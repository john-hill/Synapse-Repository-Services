package org.sagebionetworks.repo.manager.agent.supervisor;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.sagebionetworks.StackConfiguration;
import org.sagebionetworks.repo.manager.agent.CodeInterpreterTools;
import org.sagebionetworks.repo.model.UserInfo;
import org.sagebionetworks.repo.model.agent.GridAgentSessionContext;
import org.springframework.ai.bedrock.converse.BedrockChatOptions;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;

/**
 * A conversational supervisor agent (Curie) that orchestrates the specialist agents to curate
 * grid data. It runs on a stronger model than the Haiku specialists because it must plan a
 * multi-step workflow and delegate focused sub-tasks. It delegates through a focused subset of
 * specialist tools (JSON schema + grid query + grid update) and can also run Python directly on
 * the shared code interpreter session via {@link CodeInterpreterTools}. Each instance maintains
 * its own chat memory and is intended for a single task delegation (multi-turn within that task,
 * but discarded after).
 */
public class CurieSupervisor {

	private final ChatClient chatClient;
	private final String conversationId;

	CurieSupervisor(ChatModel chatModel, StackConfiguration stackConfig, List<ToolCallback> specialistTools,
			CodeInterpreterTools codeInterpreterTools, String systemPrompt) {
		this.conversationId = UUID.randomUUID().toString();
		ChatMemory memory = MessageWindowChatMemory.builder().maxMessages(40).build();
		this.chatClient = ChatClient.builder(chatModel)
				.defaultSystem(systemPrompt)
				.defaultToolCallbacks(specialistTools)
				.defaultTools(codeInterpreterTools)
				.defaultAdvisors(MessageChatMemoryAdvisor.builder(memory).build())
				.defaultOptions(BedrockChatOptions.builder()
						.model(stackConfig.getModelIdClaudeSonnet())
						.maxTokens(8192)
						.build())
				.build();
	}

	/**
	 * Send a message to this supervisor and get a response. Maintains conversation context across
	 * multiple calls within the same supervisor instance. The trusted
	 * {@link GridAgentSessionContext} is forwarded to the grid specialists via the agent-immutable
	 * tool context, so they operate against the user's replica in the current grid session.
	 */
	public String chat(String message, UserInfo user, String sessionId, GridAgentSessionContext gridContext) {
		Map<String, Object> context = new HashMap<>();
		context.put("userInfo", user);
		if (sessionId != null) {
			context.put("sessionId", sessionId);
		}
		context.put("gridAgentSessionContext", gridContext);
		return chatClient.prompt()
				.user(message)
				.toolContext(context)
				.advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
				.call()
				.content();
	}
}
