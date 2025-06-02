package org.sagebionetworks.grid.workers.message;

import org.sagebionetworks.repo.model.grid.EventContext;

public class ConnectionMessage extends AbstractJsonRxMessage implements NotificationMessage {

	public ConnectionMessage(EventContext context, Integer id, Object body) {
		super(context, id, body);
	}
}