package org.sagebionetworks.repo.model.grid.event;

import org.sagebionetworks.repo.model.grid.EventContext;

/**
 * Abstraction for a builder of a JSON-Rx message.
 * @param <M> - The message type.
 * @param <B> - The message body type.
 */
public interface JsonRxMessageBuilder<M, B> {

	/**
	 * Set the context of the message.
	 * @param context
	 * @return
	 */
	JsonRxMessageBuilder<M, B> setContext(EventContext context);

	/**
	 * Set either a message ID or subscription ID of a message.
	 * @param id
	 * @return
	 */
	JsonRxMessageBuilder<M, B> setRequestOrSubscriptionId(int id);

	JsonRxMessageBuilder<M, B> setBody(B body);

	M build();

}
