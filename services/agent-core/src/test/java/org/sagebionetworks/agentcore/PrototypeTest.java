package org.sagebionetworks.agentcore;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public class PrototypeTest {

	@Autowired
    private WebTestClient webTestClient;

    @Test
    void testLiveLlmToToolExecutionLoop() {
        // We ask a specific question that FORCES the LLM to use our local tool.
        // For example, asking about internal system data or real-time data.
        String agentCorePayload = """
            {
               "prompt": "Check the status of order 98765 and tell me when it ships.",
               "conversationId": "live-test-session"
            }
            """;

        // This calls your actual POST /invocations endpoint
        webTestClient.post()
            .uri("/invocations")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(agentCorePayload)
            .exchange()
            .expectStatus().isOk()
            .expectBody(String.class)
            .consumeWith(result -> {
                String response = result.getResponseBody();
                System.out.println(response);
                
            });
    }
}
