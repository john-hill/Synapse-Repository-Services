package org.sagebionetworks.grid.workers;

import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONException;
import org.sagebionetworks.repo.manager.grid.GridEventPublisher;
import org.sagebionetworks.repo.model.grid.ErrorEvent;
import org.sagebionetworks.repo.model.grid.ErrorType;
import org.sagebionetworks.repo.model.grid.EventContext;
import org.sagebionetworks.repo.model.grid.EventSource;
import org.sagebionetworks.repo.model.grid.EventType;
import org.sagebionetworks.repo.model.grid.InternalEventContext;
import org.sagebionetworks.repo.model.grid.ResponseError;
import org.sagebionetworks.repo.model.grid.WebsocketEventContext;
import org.sagebionetworks.util.ValidateArgument;
import org.sagebionetworks.util.progress.ProgressCallback;
import org.sagebionetworks.workers.util.aws.message.MessageDrivenRunner;
import org.sagebionetworks.workers.util.aws.message.RecoverableMessageException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.amazonaws.services.sqs.model.Message;
import com.amazonaws.services.sqs.model.MessageAttributeValue;

@Service
public class GridEventBrokerWorker implements MessageDrivenRunner {

	private static final Logger LOG = LogManager.getLogger(GridEventBrokerWorker.class);

	private final GridEventPublisher publisher;

	@Autowired
	public GridEventBrokerWorker(GridEventPublisher publisher) {
		super();
		this.publisher = publisher;
	}

	@Override
	public void run(ProgressCallback progressCallback, Message message) throws RecoverableMessageException, Exception {

		try {
			EventContext context = buildEventContext(message);
			try {
				JSONArray event = new JSONArray(message.getBody());
				if(event.length() > 0) {
					Number first = event.getNumber(0);
					if(first.intValue() == 8) {
						if(event.length() > 1) {
							Object second = event.get(1);
							if("ping".equals(second.toString().toLowerCase())) {
								publisher.publishEvent(context, "[8,\"pong\"]");
							}
						}
					}
				}
				System.out.println(context.toString()+": "+event.toString());
			} catch (JSONException e) {
				// Invalid message
				publisher.publishEvent(context,
						new ResponseError()
								.setEvent(new ErrorEvent().setMessage(e.getMessage()).setError(ErrorType.BAD_REQUEST))
								.toString());
			}
		} catch (Exception e) {
			LOG.error("EventTypeFailed to process message", e);
		}
	}

	EventContext buildEventContext(Message message) {
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
	}

}
