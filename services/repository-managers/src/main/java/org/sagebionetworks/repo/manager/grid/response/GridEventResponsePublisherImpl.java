package org.sagebionetworks.repo.manager.grid.response;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.sagebionetworks.repo.model.grid.EventContext;
import org.sagebionetworks.repo.model.grid.EventSource;
import org.springframework.stereotype.Service;

@Service
public class GridEventResponsePublisherImpl implements GridEventResponsePublisher {
	
	private Map<EventSource, GridEventResponsePublishHandler> handlerMap;
	
	public GridEventResponsePublisherImpl(List<GridEventResponsePublishHandler> handlers) {
		handlerMap = handlers.stream()
				.collect(Collectors.toMap(GridEventResponsePublishHandler::getEventSource, handler -> handler));
	}
	
	@Override
	public boolean publishEventResponse(EventContext context, String event) {
		return handlerMap.get(context.eventSource()).publishEventResponse(context, event);
	}

}
