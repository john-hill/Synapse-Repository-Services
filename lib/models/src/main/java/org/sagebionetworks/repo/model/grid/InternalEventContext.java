package org.sagebionetworks.repo.model.grid;

import java.util.Objects;

import org.sagebionetworks.util.ValidateArgument;

/**
 * Represents an internal event context.
 */
public class InternalEventContext implements EventContext {

	private final EventType eventType;
	private final String queueName;

	public InternalEventContext(EventType eventType, String queueName) {
		super();
		ValidateArgument.required(eventType, "eventType");
		ValidateArgument.required(queueName, "queueName");
		this.eventType = eventType;
		this.queueName = queueName;
	}

	@Override
	public EventType eventType() {
		return eventType;
	}

	@Override
	public EventSource eventSource() {
		return EventSource.INTERNAL;
	}

	public String getQueueName() {
		return queueName;
	}

	@Override
	public int hashCode() {
		return Objects.hash(eventType, queueName);
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (getClass() != obj.getClass())
			return false;
		InternalEventContext other = (InternalEventContext) obj;
		return eventType == other.eventType && Objects.equals(queueName, other.queueName);
	}

	@Override
	public String toString() {
		return "InternalEventContext [eventType=" + eventType + ", queueName=" + queueName + "]";
	}

}
