package org.sagebionetworks.grid.workers.message.builder;

import java.util.Optional;

import org.sagebionetworks.repo.model.grid.EventContext;
import org.sagebionetworks.repo.model.grid.event.JsonRxMessageType;
import org.springframework.stereotype.Component;

@Component
public class PingMessageBuilder implements JsonRxMessageBuilder {

	@Override
	public JsonRxMessageType type() {
		return JsonRxMessageType.Notification;
	}

	@Override
	public Optional<String> method() {
		return Optional.of("ping");
	}

	@Override
	public PingMessage build(EventContext context, Integer id, Object body) {
		return new PingMessage(context, id, body);
	}

	public static class PingMessage extends AbstractJsonRxMessage {

		public PingMessage(EventContext context, Integer id, Object body) {
			super(context, id, body);
		}
	}
}
