package org.sagebionetworks.grid.workers.message.builder;

import java.util.Optional;

import org.sagebionetworks.repo.model.grid.EventContext;
import org.sagebionetworks.repo.model.grid.event.JsonRxMessageType;

public class DisconnectedMessageBuilder implements JsonRxMessageBuilder {

	@Override
	public JsonRxMessageType type() {
		return JsonRxMessageType.Notification;
	}

	@Override
	public Optional<String> method() {
		return Optional.of("disconnected");
	}

	@Override
	public Object build(EventContext context, Integer id, Object body) {
		return new DisconnectedMessage(context, id, body);
	}

	public static class DisconnectedMessage extends AbstractJsonRxMessage {

		public DisconnectedMessage(EventContext context, Integer id, Object body) {
			super(context, id, body);
		}
	}
}
