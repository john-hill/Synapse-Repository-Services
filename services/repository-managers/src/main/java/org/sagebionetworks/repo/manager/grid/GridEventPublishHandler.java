package org.sagebionetworks.repo.manager.grid;

import org.sagebionetworks.repo.model.grid.EventContext;
import org.sagebionetworks.repo.model.grid.EventSource;

public interface GridEventPublishHandler {
	
	/**
	 * The source to handle.
	 * @return
	 */
	EventSource getEventSource();
	
	/**
	 * Publish an event.
	 * @param context
	 * @param event
	 * @return
	 */
	boolean publishEvent(EventContext context, String event);

}
