package org.sagebionetworks.grid.workers;

import org.sagebionetworks.grid.workers.message.ConnectionMessage;
import org.sagebionetworks.grid.workers.message.DisconnectedMessage;
import org.sagebionetworks.grid.workers.message.PingMessage;
import org.sagebionetworks.repo.manager.UserManager;
import org.sagebionetworks.repo.manager.grid.GridManager;
import org.sagebionetworks.repo.manager.grid.response.GridEventResponsePublisher;
import org.sagebionetworks.repo.model.UserInfo;
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

}
