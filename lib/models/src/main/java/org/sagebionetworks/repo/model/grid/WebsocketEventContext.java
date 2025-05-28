package org.sagebionetworks.repo.model.grid;

import java.util.Objects;

import org.sagebionetworks.util.ValidateArgument;

public class WebsocketEventContext implements EventContext {

	private final EventType eventType;
	private final String connectionId;

	public WebsocketEventContext(EventType eventType, String connectionId) {
		super();
		ValidateArgument.required(eventType, "eventType");
		ValidateArgument.required(connectionId, "connectionId");
		this.eventType = eventType;
		this.connectionId = connectionId;
	}

	@Override
	public EventType eventType() {
		return eventType;
	}

	@Override
	public EventSource eventSource() {
		return EventSource.WEBSOCKET;
	}
	
	public String getConnectionId() {
		return connectionId;
	}

	@Override
	public int hashCode() {
		return Objects.hash(connectionId, eventType);
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (getClass() != obj.getClass())
			return false;
		WebsocketEventContext other = (WebsocketEventContext) obj;
		return Objects.equals(connectionId, other.connectionId) && eventType == other.eventType;
	}

	@Override
	public String toString() {
		return "WebsocketEventContext [eventType=" + eventType + ", connectionId=" + connectionId + "]";
	}

}
