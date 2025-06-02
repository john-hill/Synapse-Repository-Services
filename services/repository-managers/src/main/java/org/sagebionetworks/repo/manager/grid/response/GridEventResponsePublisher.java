package org.sagebionetworks.repo.manager.grid.response;

import org.sagebionetworks.repo.model.grid.EventContext;

public interface GridEventResponsePublisher {
	
	/**
	 * Publish an event.
	 * @param context
	 * @param event
	 * @return
	 */
	boolean publishEventResponse(EventContext context, String event);

}
