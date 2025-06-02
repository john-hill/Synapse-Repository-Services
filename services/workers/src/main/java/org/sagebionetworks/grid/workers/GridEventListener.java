package org.sagebionetworks.grid.workers;

import org.sagebionetworks.grid.workers.message.ConnectionMessage;
import org.sagebionetworks.grid.workers.message.DisconnectedMessage;
import org.sagebionetworks.grid.workers.message.PingMessage;
import org.sagebionetworks.repo.manager.grid.response.GridEventResponsePublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class GridEventListener {

	private final GridEventResponsePublisher publisher;

	public GridEventListener(GridEventResponsePublisher publisher) {
		super();
		this.publisher = publisher;
	}

	@EventListener
	public void onPing(PingMessage ping) {
		publisher.publishEventResponse(ping.getContext(), "[8,\"pong\"]");
	}

	@EventListener
	public void onConnection(ConnectionMessage connection) {
		System.out.println("onConnection(): " + connection);
	}

	@EventListener
	public void onDisconnected(DisconnectedMessage disconnected) {
		System.out.println("onDisconnected(): " + disconnected);
	}

}
