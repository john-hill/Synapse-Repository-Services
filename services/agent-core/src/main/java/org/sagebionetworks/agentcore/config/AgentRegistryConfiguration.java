package org.sagebionetworks.agentcore.config;

import org.sagebionetworks.agentcore.tool.GetJsonSchemaById;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AgentRegistryConfiguration {

	@Bean
	public ChatClient getJsonSchemaSpecialist(ChatClient.Builder builder, GetJsonSchemaById getJsonSchemaById) {
		return builder.defaultSystem("You are a logistical assistant. Track packages and evaluate delivery dates.")
				.defaultTools(getJsonSchemaById).build();
	}
}
