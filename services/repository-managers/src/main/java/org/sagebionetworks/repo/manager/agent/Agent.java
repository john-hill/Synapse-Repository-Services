package org.sagebionetworks.repo.manager.agent;

import java.util.Set;

import org.springframework.ai.chat.client.ChatClient.CallResponseSpec;
import org.springframework.ai.chat.client.ChatClient.ChatClientRequestSpec;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.model.ToolContext;

/**
 * The delegation contract shared by every specialist and supervisor agent. Callers — {@code
 * SupervisorTools} delegating to a specialist, the curation sub-workers, and the interactive Curie
 * entry point — drive an agent only through this interface, never through a concrete agent class.
 * <p>
 * An implementation supplies {@link #prepareChatClientRequestSpec(String, ToolContext)} to describe a
 * single turn (its message, tools, system prompt, model, memory, and output-token budget). The default
 * {@link #chat(String, ToolContext)} runs that turn and normalizes the outcome — in particular it
 * converts an output-token truncation into corrective guidance rather than a misleading success
 * (PLFM-9868), so every caller gets that protection for free.
 */
public interface Agent {

	/**
	 * The Bedrock Converse stop reason reported when generation halts because the output-token limit
	 * ({@code maxTokens}) was reached. The tool-calling loop only executes a turn's tool calls when the
	 * finish reason is {@code tool_use} (see {@code BedrockProxyChatModel}); a turn that ends with this
	 * reason has any tool call it was emitting silently dropped, so the intended action never runs.
	 * Compared case-insensitively by {@link ChatResponse#hasFinishReasons(Set)}.
	 */
	static final String MAX_TOKENS_FINISH_REASON = "max_tokens";

	/**
	 * Returned in place of the model's text when a turn was truncated by the output-token limit. It is
	 * fed back to the calling agent (a delegating supervisor, or the end user for a top-level turn) as
	 * corrective guidance to retry the work in smaller pieces — the same self-correction path the tool
	 * layer uses for malformed arguments (PLFM-9868).
	 */
	public static final String TRUNCATED_RESPONSE_MESSAGE = "The response was cut off because it reached the "
			+ "output-token limit before completing, so any action it was in the middle of requesting was not "
			+ "performed. Retry the work as a series of smaller requests so each response stays within the limit.";
	
	/**
	 * Builds the request spec for a single turn: the user message, the tool context passed straight
	 * through, and the memory advisor bound to this instance's conversation. The implementation wires its
	 * own tools, system prompt, model, and output-token budget here (via its {@code ChatClient}).
	 *
	 * @param message the instruction for this turn
	 * @param context the tool context (acting user, session bindings) handed to every tool the turn invokes
	 * @return the prepared, not-yet-executed request spec
	 */
	ChatClientRequestSpec prepareChatClientRequestSpec(String message, ToolContext context);

	/**
	 * Runs one turn and returns the agent's textual response, or {@code null} when the model produced no
	 * response. When the turn is truncated at the output-token limit, returns
	 * {@link #TRUNCATED_RESPONSE_MESSAGE} instead of the model's (misleading) partial text, telling the
	 * caller to retry the work in smaller pieces.
	 *
	 * @param message the instruction for this turn
	 * @param context the tool context handed to every tool the turn invokes
	 * @return the response text, {@link #TRUNCATED_RESPONSE_MESSAGE} on an output-token truncation, or
	 *         {@code null} when there is no response
	 */
	default String chat(String message, ToolContext context) {
		ChatClientRequestSpec spec = prepareChatClientRequestSpec(message, context);
		CallResponseSpec response = spec.call();

		ChatResponse chatResponse = response.chatResponse();
		if (chatResponse == null) {
			return null;
		}
		// A turn that ends on max_tokens had the tool call it was emitting silently dropped, so report the
		// truncation to the caller rather than the model's "success" narration for an action that never ran.
		if (chatResponse.hasFinishReasons(Set.of(MAX_TOKENS_FINISH_REASON))) {
			return TRUNCATED_RESPONSE_MESSAGE;
		}
		Generation result = chatResponse.getResult();
		if (result == null || result.getOutput() == null) {
			return null;
		}
		return result.getOutput().getText();
	}

}
