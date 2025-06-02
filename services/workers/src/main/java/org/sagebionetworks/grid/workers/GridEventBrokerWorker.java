package org.sagebionetworks.grid.workers;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.sagebionetworks.grid.workers.message.builder.JsonRxMessageBuilder;
import org.sagebionetworks.repo.manager.grid.response.GridEventResponsePublisher;
import org.sagebionetworks.repo.model.grid.ErrorEvent;
import org.sagebionetworks.repo.model.grid.ErrorType;
import org.sagebionetworks.repo.model.grid.EventContext;
import org.sagebionetworks.repo.model.grid.EventSource;
import org.sagebionetworks.repo.model.grid.EventType;
import org.sagebionetworks.repo.model.grid.InternalEventContext;
import org.sagebionetworks.repo.model.grid.NotificationError;
import org.sagebionetworks.repo.model.grid.WebsocketEventContext;
import org.sagebionetworks.repo.model.grid.event.JsonRxMessageType;
import org.sagebionetworks.util.ValidateArgument;
import org.sagebionetworks.util.progress.ProgressCallback;
import org.sagebionetworks.workers.util.aws.message.MessageDrivenRunner;
import org.sagebionetworks.workers.util.aws.message.RecoverableMessageException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import com.amazonaws.services.sqs.model.Message;
import com.amazonaws.services.sqs.model.MessageAttributeValue;

@Service
public class GridEventBrokerWorker implements MessageDrivenRunner {

	private static final Logger log = LogManager.getLogger(GridEventBrokerWorker.class);

	private final GridEventResponsePublisher publisher;
	private final ApplicationEventPublisher applicationEventPublisher;
	private final Map<String, JsonRxMessageBuilder> builderTypeMap;

	public GridEventBrokerWorker(GridEventResponsePublisher publisher,
			ApplicationEventPublisher applicationEventPublisher, List<JsonRxMessageBuilder> builders) {
		super();
		this.publisher = publisher;
		this.applicationEventPublisher = applicationEventPublisher;
		this.builderTypeMap = builders.stream()
				.collect(Collectors.toMap(JsonRxMessageBuilder::typeKey, handler -> handler));
	}

	@Override
	public void run(ProgressCallback progressCallback, Message message) throws RecoverableMessageException, Exception {
		log.info(message);
		try {
			EventContext context = buildEventContext(message);
			try {
				JSONArray eventBatch = new JSONArray(message.getBody());
				createEvents(context, eventBatch).forEach(e -> applicationEventPublisher.publishEvent(e));
			} catch (JSONException | IllegalArgumentException e) {
				// Invalid message
				publisher.publishEventResponse(context,
						new NotificationError()
								.setEvent(new ErrorEvent().setMessage(e.getMessage()).setError(ErrorType.BAD_REQUEST))
								.toString());
			}
		} catch (Exception e) {
			log.error("Failed to process message:", e);
		}
	}

	/**
	 * Create an event for each JSON-Rx message found in the provided array. If the
	 * array is not a batch, it will be treated as a single message.
	 * 
	 * @param context
	 * @param array
	 * @return
	 */
	List<Object> createEvents(EventContext context, JSONArray array) {
		JSONArray child = array.optJSONArray(0);
		if (child != null) {
			List<JSONArray> arrays = new ArrayList<>(array.length());
			// batch of messages
			for (int i = 0; i < array.length(); i++) {
				JSONArray c = array.optJSONArray(i);
				if (c == null) {
					throw new IllegalArgumentException(
							String.format("The message at index: %d is not a JSON array", i));
				}
				arrays.add(c);
			}
			return arrays.stream().map(a -> createEvent(context, a)).collect(Collectors.toList());
		}
		// Not a batch.
		return Collections.singletonList(createEvent(context, array));
	}

	/**
	 * Translate a single JSON-Rx message to an internal event.
	 * 
	 * @param context
	 * @param array
	 * @return
	 */
	Object createEvent(EventContext context, JSONArray array) {
		int zero = array.optInt(0, -1);
		if (zero < 1) {
			throw new IllegalArgumentException("Expected the fist element of the array to be a message code.");
		}
		Object one = array.opt(1);
		Object two = array.opt(2);
		Object three = array.opt(3);
		String method = one instanceof String ? (String) one : two instanceof String ? (String) two : null;
		Integer id = one instanceof Number ? ((Number) one).intValue() : null;
		JSONObject bodyObject = two instanceof JSONObject ? (JSONObject) two
				: three instanceof JSONObject ? (JSONObject) three : null;
		JSONArray bodyArray = two instanceof JSONArray ? (JSONArray) two
				: three instanceof JSONArray ? (JSONArray) three : null;
		Object body = bodyObject != null ? bodyObject : bodyArray;

		JsonRxMessageType type = JsonRxMessageType.fromCode(zero);
		String key = JsonRxMessageBuilder.createTypeKey(type, method);
		JsonRxMessageBuilder builder = builderTypeMap.get(key);
		if (builder != null) {
			return builder.build(context, id, body);
		}
		throw new IllegalArgumentException(String.format("Unknown message type -- code: %d and method: '%s'",
				type.getCode(), method != null ? method : ""));

	}

	/**
	 * Extract the message context from the message attributes.
	 * 
	 * @param message
	 * @return
	 */
	static EventContext buildEventContext(Message message) {
		try {
			ValidateArgument.required(message, "message");
			Map<String, MessageAttributeValue> attributes = message.getMessageAttributes();
			MessageAttributeValue eventTypeString = attributes.get("EventType");
			ValidateArgument.required(eventTypeString, "attribute.EventType");
			EventType eventType = EventType.valueOf(eventTypeString.getStringValue());
			MessageAttributeValue eventSourceString = attributes.get("EventSource");
			ValidateArgument.required(eventSourceString, "attribute.EventSource");
			EventSource eventSource = EventSource.valueOf(eventSourceString.getStringValue());

			switch (eventSource) {
			case INTERNAL:
				MessageAttributeValue queueValue = attributes.get("QueueName");
				ValidateArgument.required(queueValue, "attribute.QueueName");
				return new InternalEventContext(eventType, queueValue.getStringValue());
			case WEBSOCKET:
				MessageAttributeValue convalue = attributes.get("ConnectionId");
				ValidateArgument.required(convalue, "attribute.ConnectionId");
				return new WebsocketEventContext(eventType, convalue.getStringValue());
			default:
				throw new IllegalArgumentException("Unknown eventSource: " + eventSource);
			}
		} catch (IllegalArgumentException e) {
			// Any IllegalArgumentException in this context is a server-side issue.
			throw new IllegalStateException(e.getMessage());
		}
	}

	@Override
	public List<String> getMessageAttributeNames() {
		return Collections.singletonList(".*");
	}

}
