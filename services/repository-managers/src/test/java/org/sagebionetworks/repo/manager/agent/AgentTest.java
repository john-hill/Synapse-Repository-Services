package org.sagebionetworks.repo.manager.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.when;

import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient.CallResponseSpec;
import org.springframework.ai.chat.client.ChatClient.ChatClientRequestSpec;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.model.ToolContext;

@ExtendWith(MockitoExtension.class)
public class AgentTest {

	@Mock
	private ChatClientRequestSpec requestSpec;
	@Mock
	private CallResponseSpec responseSpec;
	@Mock
	private ChatResponse chatResponse;

	private ToolContext context = new ToolContext(java.util.Map.of());

	/**
	 * Minimal {@link Agent} whose only job is to hand back the mocked request spec, so the default
	 * {@code chat()} finish-reason handling can be exercised in isolation.
	 */
	private Agent agentReturning(ChatClientRequestSpec spec) {
		return (message, ctx) -> spec;
	}

	@Test
	public void testChatWithNormalCompletion() {
		when(requestSpec.call()).thenReturn(responseSpec);
		when(responseSpec.chatResponse()).thenReturn(chatResponse);
		when(chatResponse.hasFinishReasons(Set.of(Agent.MAX_TOKENS_FINISH_REASON))).thenReturn(false);
		when(chatResponse.getResult()).thenReturn(new Generation(new AssistantMessage("all done")));

		// call under test
		String result = agentReturning(requestSpec).chat("do the work", context);

		assertEquals("all done", result);
	}

	@Test
	public void testChatWithMaxTokensTruncation() {
		when(requestSpec.call()).thenReturn(responseSpec);
		when(responseSpec.chatResponse()).thenReturn(chatResponse);
		when(chatResponse.hasFinishReasons(Set.of(Agent.MAX_TOKENS_FINISH_REASON))).thenReturn(true);

		// call under test — a truncated turn returns the corrective message instead of the narration
		String result = agentReturning(requestSpec).chat("apply a huge batch", context);

		assertEquals(Agent.TRUNCATED_RESPONSE_MESSAGE, result);
	}

	@Test
	public void testChatWithNullChatResponse() {
		when(requestSpec.call()).thenReturn(responseSpec);
		when(responseSpec.chatResponse()).thenReturn(null);

		// call under test
		String result = agentReturning(requestSpec).chat("do the work", context);

		assertNull(result);
	}

	@Test
	public void testChatWithNullGeneration() {
		when(requestSpec.call()).thenReturn(responseSpec);
		when(responseSpec.chatResponse()).thenReturn(chatResponse);
		when(chatResponse.hasFinishReasons(Set.of(Agent.MAX_TOKENS_FINISH_REASON))).thenReturn(false);
		when(chatResponse.getResult()).thenReturn(null);

		// call under test
		String result = agentReturning(requestSpec).chat("do the work", context);

		assertNull(result);
	}
}
