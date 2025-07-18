package org.sagebionetworks.repo.manager.grid.response;

import org.sagebionetworks.repo.model.grid.EventContext;
import org.sagebionetworks.repo.model.grid.event.JsonRxMessageType;
import org.sagebionetworks.schema.adapter.JSONEntity;

/**
 * This handler is used by internal replicas to send messages to the "hub".
 */
public interface InternalReplicaToHubEventPublisher {

	/**
	 * Publish an internal event after the current transaction commits.
	 * 
	 * @param context
	 * @param event
	 */
	void publishEventAfterCommit(EventContext context, String event);

	/**
	 * Publish an internal event after the current transaction commits.
	 * 
	 * @param <T>
	 * @param context
	 * @param type
	 * @param payload
	 */
	<T extends JSONEntity> void publishEventAfterCommit(EventContext context, JsonRxMessageType type, String method,
			T payload);
}
