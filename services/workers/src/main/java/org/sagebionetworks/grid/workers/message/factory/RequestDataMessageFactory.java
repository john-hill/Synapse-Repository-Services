package org.sagebionetworks.grid.workers.message.factory;

import org.json.JSONArray;
import org.sagebionetworks.grid.workers.message.PatchDataRequest;
import org.sagebionetworks.grid.workers.message.RequestDataMessage;
import org.sagebionetworks.repo.model.grid.EventContext;
import org.sagebionetworks.repo.model.grid.event.JsonRxMessageType;
import org.springframework.stereotype.Component;

@Component
public class RequestDataMessageFactory implements JsonRxMessageFactory<RequestDataMessage> {

	@Override
	public JsonRxMessageType type() {
		return JsonRxMessageType.RequestData;
	}

	@Override
	public RequestDataMessage createMessage(EventContext context, Integer id, String method, Object body) {
		if ("patch".equals(method)) {
			if (!(body instanceof JSONArray)) {
				throw new IllegalArgumentException("patch body must be a JSON array.");
			}
			return new PatchDataRequest(context, id, (JSONArray) body);
		}
		throw new IllegalArgumentException("Unknown notification message with method: " + method);
	}

}
