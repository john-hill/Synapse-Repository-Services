package org.sagebionetworks.agentcore.config;

import java.time.Duration;

import org.sagebionetworks.agentcore.tool.GetJsonSchemaById;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.model.bedrock.autoconfigure.BedrockAwsConnectionProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration
public class AgentRegistryConfiguration {

	@Bean
	@Primary
	public BedrockAwsConnectionProperties getBedrockAwsConnectionProperties() {
		BedrockAwsConnectionProperties props = new BedrockAwsConnectionProperties();
		props.setRegion("us-east-1");
		props.setTimeout(Duration.ofMinutes(10));
		props.setSocketTimeout(Duration.ofMinutes(10));
		props.setConnectionAcquisitionTimeout(Duration.ofSeconds(10));
		props.setAsyncReadTimeout(Duration.ofMinutes(10));
		return props;
	}


//	@Bean
//	public ChatClient getJsonSchemaSpecialist(ChatClient.Builder builder, GetJsonSchemaById getJsonSchemaById) {
//		return builder.defaultSystem("You are a logistical assistant. Track packages and evaluate delivery dates.")
//				.defaultTools(getJsonSchemaById).build();
//	}
}
