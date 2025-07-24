package org.sagebionetworks.repo.manager.grid.internal.replica;

import org.sagebionetworks.repo.model.grid.GridConnectionInfo;
import org.sagebionetworks.repo.model.grid.message.JsonRxMessage;
import org.sagebionetworks.repo.model.grid.message.JsonRxMessageType;

public interface InternalMessgeHandler {

	/**
	 * The type of messages that this handler will handle.
	 * 
	 * @return
	 */
	JsonRxMessageType getType();

	/**
	 * Handle the provided message.
	 * @param connection
	 * @param message
	 */
	void handleMessage(GridConnectionInfo connection, JsonRxMessage message);

}
