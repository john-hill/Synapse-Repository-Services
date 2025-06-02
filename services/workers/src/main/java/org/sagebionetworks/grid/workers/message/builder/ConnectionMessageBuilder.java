package org.sagebionetworks.grid.workers.message.builder;

import java.util.Optional;

import org.sagebionetworks.repo.model.grid.EventContext;
import org.sagebionetworks.repo.model.grid.event.JsonRxMessageType;
import org.springframework.stereotype.Component;

@Component
public class ConnectionMessageBuilder implements JsonRxMessageBuilder {

	@Override
	public JsonRxMessageType type() {
		return JsonRxMessageType.Notification;
	}

	@Override
	public Optional<String> method() {
		return Optional.of("connection");
	}

	@Override
	public Object build(EventContext context, Integer id, Object body) {
		return new ConnectionMessage(context, id, body);
	}

	public static class ConnectionMessage extends AbstractJsonRxMessage {

		public ConnectionMessage(EventContext context, Integer id, Object body) {
			super(context, id, body);
		}
	}

}
