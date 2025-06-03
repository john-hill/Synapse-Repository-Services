package org.sagebionetworks.grid.workers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;

import org.json.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.sagebionetworks.grid.workers.message.ConnectionMessage;
import org.sagebionetworks.grid.workers.message.DisconnectedMessage;
import org.sagebionetworks.grid.workers.message.PingMessage;
import org.sagebionetworks.repo.manager.UserManager;
import org.sagebionetworks.repo.manager.grid.GridManager;
import org.sagebionetworks.repo.manager.grid.response.GridEventResponsePublisher;
import org.sagebionetworks.repo.model.UserInfo;
import org.sagebionetworks.repo.model.grid.EventContext;
import org.sagebionetworks.repo.model.grid.EventSource;
import org.sagebionetworks.repo.model.grid.EventType;
import org.sagebionetworks.repo.model.grid.internal.Connection;
import org.sagebionetworks.schema.adapter.JSONObjectAdapterException;
import org.sagebionetworks.schema.adapter.org.json.EntityFactory;

@ExtendWith(MockitoExtension.class)
public class GridEventListenerTest {

	@Mock
	private GridEventResponsePublisher mockPublisher;
	@Mock
	private UserManager mockUserManager;
	@Mock
	private GridManager mockManager;
	@Mock
	private UserInfo mockUser;

	@InjectMocks
	private GridEventListener listener;

	private EventType eventType;
	private EventSource eventSource;
	private String connectionId;
	private EventContext context;
	private PingMessage pingMessgae;
	private Connection connection;
	private ConnectionMessage connectionMessage;
	private Long userId;
	private DisconnectedMessage disconnectMessage;

	@BeforeEach
	public void before() throws JSONObjectAdapterException {
		userId = 987L;
		eventType = EventType.CONNECT;
		eventSource = EventSource.WEBSOCKET;
		connectionId = "con123";
		context = new EventContext(eventType, eventSource, connectionId);
		pingMessgae = new PingMessage(context, 0, null);
		connection = new Connection().setGridSessionId(123L).setUserId(userId);
		connectionMessage = new ConnectionMessage(context, EntityFactory.createJSONObjectForEntity(connection));
		disconnectMessage = new DisconnectedMessage(context, null, null);
	}

	@Test
	public void testOnPing() {
		// call under test
		listener.onPing(pingMessgae);
		verify(mockPublisher).publishEventResponse(context, "[8,\"pong\"]");

	}

	@Test
	public void testOnPingWithNullMessage() {
		pingMessgae = null;
		String message = assertThrows(IllegalArgumentException.class, () -> {
			// call under test
			listener.onPing(pingMessgae);
		}).getMessage();
		assertEquals("ping is required.", message);
	}

	@Test
	public void testOnConnection() {
		when(mockUserManager.getUserInfo(userId)).thenReturn(mockUser);
		// call under test
		listener.onConnection(connectionMessage);
		verify(mockManager).createReplicaConnection(mockUser, context, connection);
		verify(mockPublisher).publishEventResponse(context, "[8,\"connected\"]");
	}

	@Test
	public void testOnConnectionWithNullUser() {
		connectionMessage = null;

		String message = assertThrows(IllegalArgumentException.class, () -> {
			// call under test
			listener.onConnection(connectionMessage);
		}).getMessage();
		assertEquals("message is required.", message);

		verifyZeroInteractions(mockPublisher, mockUserManager, mockManager);
	}
	
	@Test
	public void testOnConnectionWithNullContext() throws JSONObjectAdapterException {
		connectionMessage = new ConnectionMessage(null, EntityFactory.createJSONObjectForEntity(connection));

		String message = assertThrows(IllegalArgumentException.class, () -> {
			// call under test
			listener.onConnection(connectionMessage);
		}).getMessage();
		assertEquals("message.context is required.", message);

		verifyZeroInteractions(mockPublisher, mockUserManager, mockManager);
	}
	
	@ParameterizedTest
	@EnumSource(EventType.class)
	public void testOnDisconnected(EventType type) {
		context = new EventContext(type, eventSource, connectionId);
		disconnectMessage = new DisconnectedMessage(context, null, null);
		// call under test
		listener.onDisconnected(disconnectMessage);
		verify(mockManager).removeReplicatConnection(type, connectionId);
	}
	
	@Test
	public void testOnDisconnectedWithNullMessage() throws JSONObjectAdapterException {
		disconnectMessage = null;

		String message = assertThrows(IllegalArgumentException.class, () -> {
			// call under test
			listener.onDisconnected(disconnectMessage );
		}).getMessage();
		assertEquals("message is required.", message);

		verifyZeroInteractions(mockPublisher, mockUserManager, mockManager);
	}
	
	@Test
	public void testOnDisconnectedWithContext() throws JSONObjectAdapterException {
		disconnectMessage = new DisconnectedMessage(null, null, null);;

		String message = assertThrows(IllegalArgumentException.class, () -> {
			// call under test
			listener.onDisconnected(disconnectMessage );
		}).getMessage();
		assertEquals("message.context is required.", message);

		verifyZeroInteractions(mockPublisher, mockUserManager, mockManager);
	}
}
