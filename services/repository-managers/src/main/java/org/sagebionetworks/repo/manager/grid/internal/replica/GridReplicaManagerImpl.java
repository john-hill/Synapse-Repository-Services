package org.sagebionetworks.repo.manager.grid.internal.replica;

import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.sagebionetworks.grid.db.GridIndexManager;
import org.sagebionetworks.grid.db.MessageChain;
import org.sagebionetworks.repo.manager.grid.response.InternalReplicaToHubEventPublisher;
import org.sagebionetworks.repo.model.grid.EventContext;
import org.sagebionetworks.repo.model.grid.EventSource;
import org.sagebionetworks.repo.model.grid.EventType;
import org.sagebionetworks.repo.model.grid.GridConnectionInfo;
import org.sagebionetworks.repo.model.grid.message.JsonRxMessage;
import org.sagebionetworks.repo.model.grid.message.JsonRxMessageType;
import org.sagebionetworks.repo.model.grid.node.IndexType;
import org.sagebionetworks.repo.model.grid.patch.LogicalTimestamp;
import org.sagebionetworks.repo.model.grid.patch.Patch;
import org.sagebionetworks.repo.model.grid.patch.compact.LogicalTimestampCompactSerializable;
import org.sagebionetworks.util.ValidateArgument;
import org.sagebionetworks.util.progress.ProgressCallback;
import org.springframework.stereotype.Component;

import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.PublishRequest;

@Component
public class GridReplicaManagerImpl implements GridReplicaManager {

	private static final Logger log = LogManager.getLogger(GridReplicaManagerImpl.class);

	private final GridIndexManager gridIndexManager;
	private final InternalReplicaToHubEventPublisher publisher;
	private final SnsClient snsClient;
	private final String topicArn;
	private final HttpClient httpClient;

	public GridReplicaManagerImpl(GridIndexManager gridIndexManager, InternalReplicaToHubEventPublisher publisher,
			SnsClient snsClient, String gridReplicaChangeTopicArn, HttpClient httpClient) {
		this.gridIndexManager = gridIndexManager;
		this.publisher = publisher;
		this.snsClient = snsClient;
		this.topicArn = gridReplicaChangeTopicArn;
		this.httpClient = httpClient;

	}

	void synchronizeClock(ProgressCallback callback, GridConnectionInfo connection) {
		// Ignore this request if an active 'synchronize-clock' is in progress.
		Optional<MessageChain> mo = gridIndexManager.getNonExpiredMessageChain(connection.getSessionId(),
				connection.getReplicaId(), SYNCHRONIZE_CLOCK);
		if (mo.isPresent()) {
			log.info("Non-expired message chain already exists for session: {} replica: {} method: {}",
					connection.getSessionId(), connection.getReplicaId(), SYNCHRONIZE_CLOCK);
			return;
		}
		MessageChain chain = gridIndexManager.startMessageChain(connection.getSessionId(), connection.getReplicaId(),
				SYNCHRONIZE_CLOCK);
		List<LogicalTimestamp> clock = gridIndexManager.getClock(connection.getSessionId(), connection.getReplicaId());
		sendClockMessage(chain.getId(), connection.getConnectionId(), clock);
	}

	void sendClockMessage(Integer methodId, String connectionId, List<LogicalTimestamp> clock) {
		publisher.publishEvent(new EventContext(EventType.MESSAGE, EventSource.INTERNAL, connectionId),
				new JsonRxMessage(JsonRxMessageType.RequestData).setMethod(SYNCHRONIZE_CLOCK).setId(methodId)
						.setBody(LogicalTimestampCompactSerializable.serializeClock(clock)));
	}

	@Override
	public void onResponseComplete(ProgressCallback callback, GridConnectionInfo connection, Integer methodId) {
		gridIndexManager.completeMessageChain(connection.getSessionId(), connection.getReplicaId(), methodId);
	}

	@Override
	public void onApplyPatch(ProgressCallback callback, GridConnectionInfo connection, Integer messageId, Patch patch) {
		gridIndexManager.refreshMessageChain(connection.getSessionId(), connection.getReplicaId(), messageId);
		Map<IndexType, Set<LogicalTimestamp>> changes = gridIndexManager.applyPatch(connection.getSessionId(),
				connection.getReplicaId(), patch);
		List<LogicalTimestamp> clock = gridIndexManager.getClock(connection.getSessionId(), connection.getReplicaId());
		sendClockMessage(messageId, connection.getConnectionId(), clock);
		sendChangesToTopic(ReplicaChangeSet.fromPatch(connection, patch.getPatchId(), changes));
	}

	@Override
	public void onApplySnapshot(ProgressCallback callback, GridConnectionInfo connection, Integer messageId, URL snapshotPresignedUrl) {
		gridIndexManager.refreshMessageChain(connection.getSessionId(), connection.getReplicaId(), messageId);

		Path snapshotFile = null;
		try {
			snapshotFile = downloadSnapshotFile(snapshotPresignedUrl);
			// if applySnapshot/applyPatch throws a recoverable error, then we can send the clock again.
			// but we do not want to put the message on the queue!
			gridIndexManager.applySnapshot(connection.getSessionId(), connection.getReplicaId(), snapshotFile);
		} finally {
			if (snapshotFile != null) {
				try {
					Files.deleteIfExists(snapshotFile);
				} catch (IOException e) {
					log.warn("Failed to delete temp file: {}", snapshotFile, e);
				}
			}
		}
		List<LogicalTimestamp> clock = gridIndexManager.getClock(connection.getSessionId(), connection.getReplicaId());
		sendClockMessage(messageId, connection.getConnectionId(), clock);
		sendChangesToTopic(ReplicaChangeSet.fromSnapshot(connection));
	}

	Path downloadSnapshotFile(URL snapshotPresignedUrl) {
		ValidateArgument.required(snapshotPresignedUrl, "snapshotPresignedUrl");
		Path tempFile;
		try {
			tempFile = Files.createTempFile("grid-snapshot-", ".cbor");

			HttpRequest request = HttpRequest.newBuilder()
					.uri(snapshotPresignedUrl.toURI())
					.GET()
					.build();

			HttpResponse<Path> response = httpClient.send(request, HttpResponse.BodyHandlers.ofFile(tempFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE));

			if (response.statusCode() != 200) {
				throw new RuntimeException("Failed to download snapshot. Status: " + response.statusCode());
			}

			return response.body();
		} catch (IOException e) {
			throw new RuntimeException("Failed to download snapshot from: " + snapshotPresignedUrl, e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new RuntimeException("Interrupted while downloading snapshot from: " + snapshotPresignedUrl, e);
		} catch (URISyntaxException e) {
			throw new IllegalArgumentException("Invalid snapshot URL: " + snapshotPresignedUrl, e);
		}
	}


	void sendChangesToTopic(ReplicaChangeSet changeSet) {
		log.info("Publishing replica change set to topic: {} changeSet: {}", topicArn, changeSet);
		snsClient.publish(PublishRequest.builder().targetArn(topicArn).message(changeSet.toJson()).build());
	}

	@Override
	public void onConnected(ProgressCallback callback, GridConnectionInfo connection) {
		synchronizeClock(callback, connection);
	}

	@Override
	public void onNewPatch(ProgressCallback callback, GridConnectionInfo connection) {
		synchronizeClock(callback, connection);
	}
}
