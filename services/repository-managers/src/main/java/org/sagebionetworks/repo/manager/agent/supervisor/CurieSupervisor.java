package org.sagebionetworks.repo.manager.agent.supervisor;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.sagebionetworks.StackConfiguration;
import org.sagebionetworks.repo.manager.agent.CodeInterpreterTools;
import org.sagebionetworks.repo.model.UserInfo;
import org.sagebionetworks.repo.model.agent.GridAgentSessionContext;
import org.sagebionetworks.util.ValidateArgument;
import org.springframework.ai.bedrock.converse.BedrockChatOptions;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;

/**
 * A conversational supervisor agent (Curie) that orchestrates the specialist agents to curate
 * grid data. It runs on a stronger model than the Haiku specialists because it must plan a
 * multi-step workflow and delegate focused sub-tasks. It delegates through a focused subset of
 * specialist tools (JSON schema + grid query + grid update) and can also run Python directly on
 * the shared code interpreter session via {@link CodeInterpreterTools}. Curie is invoked from
 * asynchronous chat jobs, so consecutive turns of one conversation may run on different worker
 * machines. Its chat memory is therefore backed by a durable {@link ChatMemoryRepository} (Bedrock
 * AgentCore Memory) rather than an in-JVM store, keyed by a conversation id derived from the user
 * and the durable chat session id so any worker resolves the same conversation.
 */
public class CurieSupervisor {

	private final ChatClient chatClient;

	CurieSupervisor(ChatModel chatModel, StackConfiguration stackConfig, List<ToolCallback> specialistTools,
			CodeInterpreterTools codeInterpreterTools, ChatMemoryRepository memoryRepository, String systemPrompt) {
		ChatMemory memory = MessageWindowChatMemory.builder()
				.chatMemoryRepository(memoryRepository)
				.maxMessages(40)
				.build();
		this.chatClient = ChatClient.builder(chatModel)
				.defaultSystem(systemPrompt)
				.defaultToolCallbacks(specialistTools)
				.defaultToolCallbacks(codeInterpreterTools.getToolCallbacks())
				.defaultAdvisors(MessageChatMemoryAdvisor.builder(memory).build())
				.defaultOptions(BedrockChatOptions.builder()
						.model(stackConfig.getModelIdClaudeSonnet())
						.maxTokens(8192)
						.build())
				.build();
	}

	/**
	 * Send a message to this supervisor and get a response. Conversation context is durable and
	 * cross-machine: it is keyed by a conversation id derived from the user and the durable chat
	 * {@code sessionId}, so a later turn on a different worker continues the same conversation. Both
	 * {@code user} and {@code sessionId} are required for this reason. The trusted
	 * {@link GridAgentSessionContext} is forwarded to the grid specialists via the agent-immutable
	 * tool context, so they operate against the user's replica in the current grid session.
	 */
	public String chat(String message, UserInfo user, String sessionId, GridAgentSessionContext gridContext) {
		ValidateArgument.required(user, "user");
		ValidateArgument.required(user.getId(), "user.getId()");
		ValidateArgument.requiredNotBlank(sessionId, "sessionId");
		// AgentCore Memory parses this as actorId:sessionId — the user is the actor, the durable
		// Synapse chat session is the session.
		String conversationId = user.getId() + ":" + sessionId;
		Map<String, Object> context = new HashMap<>();
		context.put("userInfo", user);
		context.put("sessionId", sessionId);
		context.put("gridAgentSessionContext", gridContext);
		return chatClient.prompt()
				.user(message)
				.toolContext(context)
				.advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
				.call()
				.content();
	}
}
