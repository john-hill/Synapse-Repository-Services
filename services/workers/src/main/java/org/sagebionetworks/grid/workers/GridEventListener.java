package org.sagebionetworks.grid.workers;

import java.util.function.Predicate;

import org.sagebionetworks.grid.workers.message.ConnectionMessage;
import org.sagebionetworks.grid.workers.message.DisconnectedMessage;
import org.sagebionetworks.grid.workers.message.PatchDataRequest;
import org.sagebionetworks.grid.workers.message.PingMessage;
import org.sagebionetworks.repo.manager.UserManager;
import org.sagebionetworks.repo.manager.grid.GridManager;
import org.sagebionetworks.repo.manager.grid.response.GridEventResponsePublisher;
import org.sagebionetworks.repo.model.UserInfo;
import org.sagebionetworks.repo.model.grid.EventContext;
import org.sagebionetworks.repo.model.grid.EventType;
import org.sagebionetworks.repo.model.grid.event.JsonRxMessageType;
import org.sagebionetworks.util.ValidateArgument;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class GridEventListener {

	private final GridEventResponsePublisher publisher;
	private final UserManager userManager;
	private final GridManager manager;

	public GridEventListener(GridEventResponsePublisher publisher, UserManager userManager, GridManager manager) {
		super();
		this.publisher = publisher;
		this.userManager = userManager;
		this.manager = manager;
	}

	@EventListener
	public void onPing(PingMessage ping) {
		ValidateArgument.required(ping, "ping");
		publisher.publishEventResponse(ping.getContext(), "[8,\"pong\"]");
	}

	@EventListener
	public void onConnection(ConnectionMessage message) {
		ValidateArgument.required(message, "message");
		ValidateArgument.required(message.getConnection(), "message.connection");
		ValidateArgument.required(message.getContext(), "message.context");
		UserInfo user = userManager.getUserInfo(message.getConnection().getUserId());
		// Save the connection.
		manager.createReplicaConnection(user, message.getContext(), message.getConnection());
		// notify the call they are connected.
		publisher.publishEventResponse(message.getContext(), "[8,\"connected\"]");
	}

	@EventListener
	public void onDisconnected(DisconnectedMessage message) {
		ValidateArgument.required(message, "message");
		ValidateArgument.required(message.getContext(), "message.context");
		manager.removeReplicatConnection(message.getContext().getEventType(), message.getContext().getConnectionId());
	}

	@EventListener
	public void onPatchDataRequest(PatchDataRequest message) {
		ValidateArgument.required(message, "message");
		boolean isNew = manager.savePatch(message.getContext(), message.getPatchId(), message.getBody());
		String response = String.format("[%d,%d]", JsonRxMessageType.ResponseComplete.getCode(),
				message.getRequestId());
		publisher.publishEventResponse(message.getContext(), response);

		if (isNew) {
			/*
			 * This is a new patch that needs to be broadcast to all other connected
			 * replicas.
			 */
			String patchNotification = String.format("[%d,\"patch\",%s]", JsonRxMessageType.Notification.getCode(),
					message.getBody());
			manager.listActiveConnections(message.getContext().getConnectionId()).stream()
					// exclude the caller from the patch notification.
					.filter(Predicate.not(c -> message.getContext().getConnectionId().equals(c.getConnectionId())))
					.forEach(c -> {
						publisher.publishEventResponse(
								new EventContext(EventType.MESSAGE, c.getSource(), c.getConnectionId()),
								patchNotification);
					});
		}
	}

}
