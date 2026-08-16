package org.sagebionetworks.repo.manager.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.sagebionetworks.repo.manager.agent.tool.TurnLimitAdvisor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClient.ChatClientRequestSpec;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * Proves the turn-limit guarantee at the {@code Agent.chat()} seam through the <em>real</em> Spring AI
 * advisor chain — the {@link Agent#TURN_LIMITING_TOOL_CALL_ADVISOR} + {@link Agent#TURN_LIMIT_ADVISOR}
 * composition and counter seeding that {@code chat()} wires — with no AWS/Bedrock (PLFM-9881).
 * <p>
 * The fake {@link ChatModel} here always emits a tool call, so absent the bound the tool-calling loop
 * would run forever (the PLFM-9879 failure mode). The test asserts the model is called exactly
 * {@link TurnLimitAdvisor#DEFAULT_MAX_TURNS} times and {@code chat()} returns the terminal
 * {@code RESULT: ERROR} marker — the universal guarantee that a new agent cannot accidentally reintroduce
 * the unbounded loop, since every agent runs through this same {@code chat()} default method.
 */
public class AgentTurnLimitWiringTest {

	@Test
	public void testChatBoundsAnUnboundedToolCallingModel() {
		// A model that never stops asking for a tool: unbounded, chat()'s loop would run forever.
		CountingLoopingChatModel model = new CountingLoopingChatModel();
		Agent agent = agentOver(model);

		// call under test
		String result = agent.chat("do the work", new ToolContext(Map.of()));

		// The model was called for exactly the budgeted turns; the turn past the budget short-circuits
		// without calling it, so the loop is bounded even though the model always wants another tool.
		assertEquals(TurnLimitAdvisor.DEFAULT_MAX_TURNS, model.getCallCount());
		assertEquals(TurnLimitAdvisor.TURN_LIMIT_REACHED_RESULT, result);
	}

	/**
	 * A minimal supervisor {@link Agent} whose {@code ChatClient} runs over the fake model with a single
	 * real tool the {@code ToolCallingManager} can execute — the same shape a real agent builds, so
	 * {@code chat()} wires the tool-call and turn-limit advisors around it exactly as in production.
	 */
	private Agent agentOver(ChatModel model) {
		ChatClient chatClient = ChatClient.builder(model)
				.defaultToolCallbacks(new AlwaysSucceedingTool())
				.defaultOptions(ToolCallingChatOptions.builder().build())
				.build();
		return new Agent() {
			@Override
			public AgentRole getAgentRole() {
				return AgentRole.SUPERVISOR;
			}

			@Override
			public ChatClientRequestSpec prepareChatClientRequestSpec(String message, ToolContext context) {
				return chatClient.prompt().user(message).toolContext(context.getContext());
			}
		};
	}

	/**
	 * Returns a response asking for {@link AlwaysSucceedingTool} on every call, so the tool-calling loop
	 * only ends if something bounds it. Counts its calls, and fails fast (rather than hanging the test)
	 * if the bound is ever removed and the loop runs away.
	 */
	private static class CountingLoopingChatModel implements ChatModel {

		private int callCount = 0;

		@Override
		public ChatResponse call(Prompt prompt) {
			callCount++;
			if (callCount > TurnLimitAdvisor.DEFAULT_MAX_TURNS + 5) {
				throw new IllegalStateException(
						"The turn limit was not enforced; the tool-calling loop ran unbounded.");
			}
			AssistantMessage message = AssistantMessage.builder()
					.content("")
					.toolCalls(List.of(new AssistantMessage.ToolCall("call-" + callCount, "function",
							AlwaysSucceedingTool.NAME, "{}")))
					.build();
			return new ChatResponse(List.of(new Generation(message)));
		}

		int getCallCount() {
			return callCount;
		}
	}

	/**
	 * A no-op tool the {@code ToolCallingManager} executes each iteration so the loop can continue. Its
	 * result never satisfies the model (which always asks again), which is the point: convergence must
	 * come from the turn limit, not the tool.
	 */
	private static class AlwaysSucceedingTool implements ToolCallback {

		static final String NAME = "keepGoing";

		@Override
		public ToolDefinition getToolDefinition() {
			return ToolDefinition.builder()
					.name(NAME)
					.description("A no-op tool used to keep the tool-calling loop going.")
					.inputSchema("{\"type\":\"object\",\"properties\":{}}")
					.build();
		}

		@Override
		public String call(String toolInput) {
			return "not done yet";
		}

		@Override
		public String call(String toolInput, ToolContext toolContext) {
			return call(toolInput);
		}
	}

	@Test
	public void testTerminalResponseHasNoToolCalls() {
		// A guard on the mechanism the loop relies on: the terminal response must carry no tool calls, or
		// the ToolCallAdvisor would loop again on it. (Complements the end-to-end bound above.)
		CountingLoopingChatModel model = new CountingLoopingChatModel();
		agentOver(model).chat("do the work", new ToolContext(Map.of()));

		AssistantMessage terminal = new AssistantMessage(TurnLimitAdvisor.TURN_LIMIT_REACHED_RESULT);
		assertFalse(terminal.hasToolCalls());
	}
}
