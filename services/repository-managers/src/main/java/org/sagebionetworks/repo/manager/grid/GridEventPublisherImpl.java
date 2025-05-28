package org.sagebionetworks.repo.manager.grid;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.sagebionetworks.repo.model.grid.EventContext;
import org.sagebionetworks.repo.model.grid.EventSource;
import org.springframework.stereotype.Service;

@Service
public class GridEventPublisherImpl implements GridEventPublisher {
	
	private Map<EventSource, GridEventPublishHandler> handlerMap;
	
	public GridEventPublisherImpl(List<GridEventPublishHandler> handlers) {
		handlerMap = handlers.stream()
				.collect(Collectors.toMap(GridEventPublishHandler::getEventSource, handler -> handler));
	}
	
	@Override
	public boolean publishEvent(EventContext context, String event) {
		return handlerMap.get(context.eventSource()).publishEvent(context, event);
	}

}
