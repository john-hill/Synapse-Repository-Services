package org.sagebionetworks.repo.manager.grid;

import org.sagebionetworks.repo.model.grid.EventContext;

public interface GridEventPublisher {
	
	/**
	 * Publish an event.
	 * @param context
	 * @param event
	 * @return
	 */
	boolean publishEvent(EventContext context, String event);

}
