package org.sagebionetworks.repo.manager.grid.response;

import java.nio.charset.StandardCharsets;

import org.sagebionetworks.repo.model.grid.EventContext;
import org.sagebionetworks.repo.model.grid.EventSource;
import org.sagebionetworks.repo.model.grid.WebsocketEventContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.apigatewaymanagementapi.ApiGatewayManagementApiClient;
import software.amazon.awssdk.services.apigatewaymanagementapi.model.PostToConnectionRequest;

@Service
public class WebsocketGridEventPublishHandler implements GridEventResponsePublishHandler {

	private final ApiGatewayManagementApiClient apiGatewayManagmentClient;

	@Autowired
	public WebsocketGridEventPublishHandler(ApiGatewayManagementApiClient apiGatewayManagmentClient) {
		super();
		this.apiGatewayManagmentClient = apiGatewayManagmentClient;
	}

	@Override
	public EventSource getEventSource() {
		return EventSource.WEBSOCKET;
	}

	@Override
	public boolean publishEventResponse(EventContext context, String event) {
		WebsocketEventContext webContext = (WebsocketEventContext) context;
		apiGatewayManagmentClient.postToConnection(
				PostToConnectionRequest.builder().data(SdkBytes.fromByteArray(event.getBytes(StandardCharsets.UTF_8)))
						.connectionId(webContext.getConnectionId()).build());
		return true;
	}

}
