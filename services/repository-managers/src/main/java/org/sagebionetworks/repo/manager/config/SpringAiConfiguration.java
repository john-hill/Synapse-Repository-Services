package org.sagebionetworks.repo.manager.config;

import java.time.Duration;

import org.sagebionetworks.StackConfiguration;
import org.springaicommunity.agentcore.codeinterpreter.AgentCoreCodeInterpreterClient;
import org.springaicommunity.agentcore.codeinterpreter.AgentCoreCodeInterpreterConfiguration;
import org.springframework.ai.bedrock.converse.BedrockChatOptions;
import org.springframework.ai.bedrock.converse.BedrockProxyChatModel;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrockagentcore.BedrockAgentCoreAsyncClient;
import software.amazon.awssdk.services.bedrockagentcore.BedrockAgentCoreClient;
import software.amazon.awssdk.services.bedrockagentcorecontrol.BedrockAgentCoreControlClient;
import software.amazon.awssdk.services.bedrockagentcorecontrol.model.CodeInterpreterSummary;
import software.amazon.awssdk.services.bedrockagentcorecontrol.model.ListCodeInterpretersRequest;
import software.amazon.awssdk.services.bedrockagentcorecontrol.model.ListCodeInterpretersResponse;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * Spring AI configuration for Bedrock Converse ChatModel and AgentCore services.
 */
@Configuration
public class SpringAiConfiguration {

	@Bean
	public ChatModel bedrockChatModel(AwsCredentialsProvider credentialProvider, StackConfiguration stackConfig) {
		return BedrockProxyChatModel.builder()
				.credentialsProvider(credentialProvider)
				.region(Region.of(stackConfig.getBedrockConverseRegion()))
				.timeout(Duration.ofMinutes(5))
				.defaultOptions(BedrockChatOptions.builder()
						.model(stackConfig.getModelIdClaudeHaiku())
						.build())
				.build();
	}

	@Bean
	public BedrockAgentCoreClient bedrockAgentCoreClient(AwsCredentialsProvider credentialProvider,
			StackConfiguration stackConfig) {
		return BedrockAgentCoreClient.builder()
				.credentialsProvider(credentialProvider)
				.region(Region.of(stackConfig.getBedrockConverseRegion()))
				.build();
	}

	@Bean
	public BedrockAgentCoreAsyncClient bedrockAgentCoreAsyncClient(AwsCredentialsProvider credentialProvider,
			StackConfiguration stackConfig) {
		return BedrockAgentCoreAsyncClient.builder()
				.credentialsProvider(credentialProvider)
				.region(Region.of(stackConfig.getBedrockConverseRegion()))
				.build();
	}

	@Bean
	public AgentCoreCodeInterpreterClient agentCoreCodeInterpreterClient(BedrockAgentCoreClient syncClient,
			BedrockAgentCoreAsyncClient asyncClient, AwsCredentialsProvider credentialProvider,
			StackConfiguration stackConfig) {
		String codeInterpreterIdentifier = lookupCodeInterpreterIdentifier(credentialProvider, stackConfig);
		return new AgentCoreCodeInterpreterClient(syncClient, asyncClient,
				new AgentCoreCodeInterpreterConfiguration(null, codeInterpreterIdentifier, null, null, null, null));
	}

	private String lookupCodeInterpreterIdentifier(AwsCredentialsProvider credentialProvider,
			StackConfiguration stackConfig) {
		String expectedName = stackConfig.getStack() + "_" + stackConfig.getStackInstance() + "_code_interpreter";
		try (BedrockAgentCoreControlClient controlClient = BedrockAgentCoreControlClient.builder()
				.credentialsProvider(credentialProvider)
				.region(Region.of(stackConfig.getBedrockConverseRegion()))
				.build()) {
			String nextToken = null;
			do {
				ListCodeInterpretersResponse response = controlClient.listCodeInterpreters(
						ListCodeInterpretersRequest.builder().nextToken(nextToken).build());
				for (CodeInterpreterSummary summary : response.codeInterpreterSummaries()) {
					if (expectedName.equals(summary.name())) {
						return summary.codeInterpreterId();
					}
				}
				nextToken = response.nextToken();
			} while (nextToken != null);
		}
		throw new IllegalStateException("Code interpreter not found with name: " + expectedName);
	}

	@Bean
	public S3Presigner s3Presigner(AwsCredentialsProvider credentialProvider) {
		return S3Presigner.builder()
				.credentialsProvider(credentialProvider)
				.region(Region.US_EAST_1)
				.build();
	}

	@Bean
	public ChatMemory agentCoreChatMemory() {
		return MessageWindowChatMemory.builder()
				.maxMessages(100)
				.build();
	}
}
