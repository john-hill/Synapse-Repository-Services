package org.sagebionetworks.repo.manager.grid.internal.replica.change;

import org.apache.commons.codec.digest.DigestUtils;
import org.sagebionetworks.StackConfiguration;
import org.springframework.stereotype.Service;

import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

@Service
public class PatchBuilderPublisherImpl implements PatchBuilderPublisher {

	private final SqsClient sqsClient;
	private final String queueUrl;

	public PatchBuilderPublisherImpl(SqsClient sqsClient, StackConfiguration config) {
		super();
		this.sqsClient = sqsClient;
		this.queueUrl = sqsClient.getQueueUrl(
				GetQueueUrlRequest.builder().queueName(config.getQueueName("GRID_REPLICA_PATCH_BUILDER.fifo")).build())
				.queueUrl();
	}

	@Override
	public void sendChangesToPatchBuilder(IntendedChangeSet changeSet) {
		String body = IntendedChangeSerializable.serialize(changeSet).toString();
		String groupId = String.format("%s-%d-%s", changeSet.getSessionId(), changeSet.getReplicaId(),
				changeSet.getConnectionId());
		sqsClient.sendMessage(SendMessageRequest.builder().queueUrl(queueUrl)
				.messageDeduplicationId(DigestUtils.sha256Hex(body)).messageGroupId(groupId).messageBody(body).build());

	}

}
