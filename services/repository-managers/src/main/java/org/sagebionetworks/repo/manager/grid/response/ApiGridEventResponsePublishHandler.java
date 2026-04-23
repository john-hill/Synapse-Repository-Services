package org.sagebionetworks.repo.manager.grid.response;

import org.sagebionetworks.repo.model.grid.EventContext;
import org.sagebionetworks.repo.model.grid.EventSource;
import org.springframework.stereotype.Service;

/**
 * No-op publish handler for REST API connections. API clients poll for results
 * via async jobs and do not receive real-time push notifications, so no action
 * is needed when the hub broadcasts patch events to API-source connections.
 */
@Service
public class ApiGridEventResponsePublishHandler implements GridEventResponsePublishHandler {

	@Override
	public EventSource getEventSource() {
		return EventSource.API;
	}

	@Override
	public void publishEventResponse(EventContext context, String event) {
		// API clients poll for results — no real-time push needed.
	}

}
