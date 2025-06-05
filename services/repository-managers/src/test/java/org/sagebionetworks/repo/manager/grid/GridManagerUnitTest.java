package org.sagebionetworks.repo.manager.grid;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.sagebionetworks.repo.manager.config.WebsocketApi;
import org.sagebionetworks.repo.model.AuthorizationConstants.BOOTSTRAP_PRINCIPAL;
import org.sagebionetworks.repo.model.UnauthorizedException;
import org.sagebionetworks.repo.model.UserInfo;
import org.sagebionetworks.repo.model.dbo.grid.GridDao;
import org.sagebionetworks.repo.model.grid.CreateGridPresignedUrlRequest;
import org.sagebionetworks.repo.model.grid.CreateGridPresignedUrlResponse;
import org.sagebionetworks.repo.model.grid.CreateGridRequest;
import org.sagebionetworks.repo.model.grid.CreateGridResponse;
import org.sagebionetworks.repo.model.grid.CreateReplicaRequest;
import org.sagebionetworks.repo.model.grid.CreateReplicaResponse;
import org.sagebionetworks.repo.model.grid.EventContext;
import org.sagebionetworks.repo.model.grid.EventSource;
import org.sagebionetworks.repo.model.grid.EventType;
import org.sagebionetworks.repo.model.grid.GridConnectionInfo;
import org.sagebionetworks.repo.model.grid.GridReplica;
import org.sagebionetworks.repo.model.grid.GridSession;
import org.sagebionetworks.repo.model.grid.GridUtils;
import org.sagebionetworks.repo.model.grid.internal.Connection;
import org.sagebionetworks.repo.web.NotFoundException;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;

@ExtendWith(MockitoExtension.class)
public class GridManagerUnitTest {

	@Mock
	private GridDao mockGridDao;

	@Mock
	private AwsCredentialsProvider mockCredentialsProvider;

	@Mock
	private WebsocketApi mockWebsocketApi;

	@Mock
	private UserInfo mockUser;

	@Spy
	@InjectMocks
	private GridManagerImpl gridManager;

	private Long userId;
	private String gridSessionId;
	private Long gridSessionIdLong;
	private EventSource eventSource;
	private boolean isAgent;
	private CreateReplicaRequest createReplicaRequest;
	private Long replicaId;
	private GridReplica replica;
	private CreateGridPresignedUrlRequest createGridPresignedUrlRequest;
	private EventContext eventContext;
	private String connectionId;

	@BeforeEach
	public void before() {
		userId = 123L;
		gridSessionIdLong = 456L;
		gridSessionId = GridUtils.gridSessionIdAsString(gridSessionIdLong);
		eventSource = EventSource.WEBSOCKET;
		isAgent = false;
		createReplicaRequest = new CreateReplicaRequest().setGridSessionId(gridSessionId);
		replicaId = 88L;
		replica = new GridReplica().setReplicaId(replicaId);
		createGridPresignedUrlRequest = new CreateGridPresignedUrlRequest().setGridSessionId(gridSessionId)
				.setReplicaId(replicaId);
		connectionId = "con444=";
		eventContext = new EventContext(EventType.CONNECT, eventSource, connectionId);
	}

	@Test
	public void testCreateGrid() {
		when(mockUser.getId()).thenReturn(userId);
		CreateGridRequest request = new CreateGridRequest();

		GridSession expected = new GridSession().setSessionId("gs123");
		when(mockGridDao.createGridSession(userId)).thenReturn(expected);
		// call under test
		CreateGridResponse result = gridManager.createGrid(mockUser, request);
		assertNotNull(result);
		assertEquals(expected, result.getGridSession());
	}

	@Test
	public void testCreateGridWithAnonymous() {
		userId = BOOTSTRAP_PRINCIPAL.ANONYMOUS_USER.getPrincipalId();
		when(mockUser.getId()).thenReturn(userId);
		CreateGridRequest request = new CreateGridRequest();

		String message = assertThrows(UnauthorizedException.class, () -> {

			// call under test
			gridManager.createGrid(mockUser, request);

		}).getMessage();
		assertEquals("Must login to perform this action", message);
		verifyZeroInteractions(mockGridDao);
	}

	@Test
	public void testCreateGridWithNullUser() {
		CreateGridRequest request = new CreateGridRequest();

		String message = assertThrows(IllegalArgumentException.class, () -> {

			// call under test
			gridManager.createGrid(null, request);

		}).getMessage();
		assertEquals("user is required.", message);
		verifyZeroInteractions(mockGridDao);
	}

	@Test
	public void testCreateGridWithNullRequest() {
		CreateGridRequest request = null;

		String message = assertThrows(IllegalArgumentException.class, () -> {

			// call under test
			gridManager.createGrid(mockUser, request);

		}).getMessage();
		assertEquals("request is required.", message);
		verifyZeroInteractions(mockGridDao);
	}

	@Test
	public void testValidGridSessionAccess() {
		when(mockUser.getId()).thenReturn(userId);
		when(mockGridDao.getGridSessionStartedBy(gridSessionId)).thenReturn(Optional.of(userId));
		// call under test
		gridManager.validGridSessionAccess(mockUser, gridSessionId);
	}

	@Test
	public void testValidGridSessionAccessWithOtherUserNonAdmin() {
		when(mockUser.getId()).thenReturn(userId);
		when(mockUser.isAdmin()).thenReturn(false);
		when(mockGridDao.getGridSessionStartedBy(gridSessionId)).thenReturn(Optional.of(456L));
		String message = assertThrows(UnauthorizedException.class, () -> {
			// call under test
			gridManager.validGridSessionAccess(mockUser, gridSessionId);
		}).getMessage();
		assertEquals("You are not authorized to access this resource.", message);
	}

	@Test
	public void testValidGridSessionAccessWithOtherAsAdmin() {
		when(mockUser.isAdmin()).thenReturn(true);

		String gridSessionId = "gs123";
		when(mockGridDao.getGridSessionStartedBy(gridSessionId)).thenReturn(Optional.of(456L));
		// call under test
		gridManager.validGridSessionAccess(mockUser, gridSessionId);
	}

	@Test
	public void testValidGridSessionAccessWithNotFound() {
		when(mockGridDao.getGridSessionStartedBy(gridSessionId)).thenReturn(Optional.empty());
		String message = assertThrows(NotFoundException.class, () -> {
			// call under test
			gridManager.validGridSessionAccess(mockUser, gridSessionId);
		}).getMessage();
		assertEquals("Grid session not found.", message);
	}

	@Test
	public void testValidGridSessionAccessWithNullUser() {
		mockUser = null;

		String message = assertThrows(IllegalArgumentException.class, () -> {
			// call under test
			gridManager.validGridSessionAccess(mockUser, gridSessionId);
		}).getMessage();
		assertEquals("user is required.", message);
		verifyZeroInteractions(mockGridDao);
	}

	@Test
	public void testValidGridSessionAccessWithNulSessionId() {
		String gridSessionId = null;

		String message = assertThrows(IllegalArgumentException.class, () -> {
			// call under test
			gridManager.validGridSessionAccess(mockUser, gridSessionId);
		}).getMessage();
		assertEquals("gridSessionId is required.", message);
		verifyZeroInteractions(mockGridDao);
	}

	@Test
	public void testGetGridSession() {

		doNothing().when(gridManager).validGridSessionAccess(mockUser, gridSessionId);

		GridSession expected = new GridSession().setSessionId("gs123").setStartedBy(userId.toString());
		when(mockGridDao.geGridSession(gridSessionId)).thenReturn(Optional.of(expected));

		// call under test
		GridSession session = gridManager.getGridSession(mockUser, gridSessionId);
		assertEquals(expected, session);
	}

	@Test
	public void testGetGridSessionNotFound() {

		doNothing().when(gridManager).validGridSessionAccess(mockUser, gridSessionId);

		when(mockGridDao.geGridSession(gridSessionId)).thenReturn(Optional.empty());

		String message = assertThrows(NotFoundException.class, () -> {
			// call under test
			gridManager.getGridSession(mockUser, gridSessionId);
		}).getMessage();
		assertEquals("Grid session not found.", message);
	}

	@Test
	public void testCreateReplica() {
		when(mockUser.getId()).thenReturn(userId);
		// must have access to create a replica.
		doNothing().when(gridManager).validGridSessionAccess(mockUser, gridSessionId);
		GridReplica replica = new GridReplica().setReplicaId(333L);
		when(mockGridDao.createReplica(userId, gridSessionId, isAgent, eventSource)).thenReturn(replica);

		// call under test
		CreateReplicaResponse response = gridManager.createReplica(mockUser, gridSessionId, isAgent, eventSource);
		assertNotNull(response);
		assertEquals(replica, response.getReplica());
	}

	@Test
	public void testCreateReplicaWithNullUser() {
		mockUser = null;

		String message = assertThrows(IllegalArgumentException.class, () -> {
			// call under test
			gridManager.createReplica(mockUser, gridSessionId, isAgent, eventSource);
		}).getMessage();
		assertEquals("user is required.", message);

		verify(gridManager, never()).validGridSessionAccess(any(), any());
	}

	@Test
	public void testCreateReplicaWithNullSessionId() {
		gridSessionId = null;

		String message = assertThrows(IllegalArgumentException.class, () -> {
			// call under test
			gridManager.createReplica(mockUser, gridSessionId, isAgent, eventSource);
		}).getMessage();
		assertEquals("gridSessionId is required.", message);

		verify(gridManager, never()).validGridSessionAccess(any(), any());
	}

	@Test
	public void testCreateReplicaWithNullEventSource() {
		eventSource = null;

		String message = assertThrows(IllegalArgumentException.class, () -> {
			// call under test
			gridManager.createReplica(mockUser, gridSessionId, isAgent, eventSource);
		}).getMessage();
		assertEquals("source is required.", message);

		verify(gridManager, never()).validGridSessionAccess(any(), any());
	}

	@Test
	public void testCreateReplicaWithRequest() {

		doReturn(new CreateReplicaResponse().setReplica(replica)).when(gridManager).createReplica(mockUser,
				gridSessionId, false, EventSource.WEBSOCKET);

		// call under test
		CreateReplicaResponse response = gridManager.createReplica(mockUser, createReplicaRequest);
		assertNotNull(response);
		assertEquals(replica, response.getReplica());
	}

	@Test
	public void testCreateReplicaWithRequestNullRequest() {
		createReplicaRequest = null;

		String message = assertThrows(IllegalArgumentException.class, () -> {
			// call under test
			gridManager.createReplica(mockUser, createReplicaRequest);
		}).getMessage();
		assertEquals("request is required.", message);

		verify(gridManager, never()).createReplica(any(), any(), any(Boolean.class), any());
	}

	@Test
	public void testGetReplica() {
		// must have access to create a replica.
		doNothing().when(gridManager).validGridSessionAccess(mockUser, gridSessionId);
		when(mockGridDao.getGridReplica(gridSessionId, replicaId)).thenReturn(Optional.of(replica));

		// call under test
		GridReplica result = gridManager.getReplica(mockUser, gridSessionId, replicaId);
		assertEquals(replica, result);
	}

	@Test
	public void testGetReplicaWithNotFound() {
		// must have access to create a replica.
		doNothing().when(gridManager).validGridSessionAccess(mockUser, gridSessionId);
		when(mockGridDao.getGridReplica(gridSessionId, replicaId)).thenReturn(Optional.empty());

		String message = assertThrows(NotFoundException.class, () -> {
			// call under test
			gridManager.getReplica(mockUser, gridSessionId, replicaId);
		}).getMessage();
		assertEquals("Grid replica not found.", message);
	}

	@Test
	public void testGetReplicaWithNullReplicaId() {
		replicaId = null;
		String message = assertThrows(IllegalArgumentException.class, () -> {
			// call under test
			gridManager.getReplica(mockUser, gridSessionId, replicaId);
		}).getMessage();
		assertEquals("replicaId is required.", message);
	}

	@Test
	public void testValidateReplicaOwnerWithOwner() {
		when(mockUser.getId()).thenReturn(userId);
		when(mockUser.isAdmin()).thenReturn(false);
		when(mockGridDao.getReplicaCreatedBy(gridSessionId, replicaId, false)).thenReturn(Optional.of(userId));
		// call under test
		gridManager.validateRepicaOwner(mockUser, gridSessionId, replicaId);
	}

	@Test
	public void testValidateReplicaOwnerWithOther() {
		when(mockUser.getId()).thenReturn(userId);
		when(mockUser.isAdmin()).thenReturn(false);
		when(mockGridDao.getReplicaCreatedBy(gridSessionId, replicaId, false)).thenReturn(Optional.of(77777L));

		String message = assertThrows(UnauthorizedException.class, () -> {
			// call under test
			gridManager.validateRepicaOwner(mockUser, gridSessionId, replicaId);
		}).getMessage();
		assertEquals("You are not authorized to access this resource.", message);
	}

	@Test
	public void testValidateReplicaOwnerWithOtherAdmin() {
		when(mockUser.isAdmin()).thenReturn(true);
		when(mockGridDao.getReplicaCreatedBy(gridSessionId, replicaId, false)).thenReturn(Optional.of(555L));
		// call under test
		gridManager.validateRepicaOwner(mockUser, gridSessionId, replicaId);
	}

	@Test
	public void testValidateReplicaOwnerWithNotFound() {
		when(mockGridDao.getReplicaCreatedBy(gridSessionId, replicaId, false)).thenReturn(Optional.empty());
		String message = assertThrows(NotFoundException.class, () -> {
			// call under test
			gridManager.validateRepicaOwner(mockUser, gridSessionId, replicaId);
		}).getMessage();
		assertEquals("Grid replica not found.", message);
		verifyZeroInteractions(mockGridDao);
	}

	@Test
	public void testValidateReplicaOwnerWithNullUser() {
		mockUser = null;
		String message = assertThrows(IllegalArgumentException.class, () -> {
			// call under test
			gridManager.validateRepicaOwner(mockUser, gridSessionId, replicaId);
		}).getMessage();
		assertEquals("user is required.", message);
		verifyZeroInteractions(mockGridDao);
	}

	@Test
	public void testValidateReplicaOwnerWithNullSessionId() {
		gridSessionId = null;
		String message = assertThrows(IllegalArgumentException.class, () -> {
			// call under test
			gridManager.validateRepicaOwner(mockUser, gridSessionId, replicaId);
		}).getMessage();
		assertEquals("gridSessionId is required.", message);
		verifyZeroInteractions(mockGridDao);
	}

	@Test
	public void testValidateReplicaOwnerWithNullReplicaId() {
		replicaId = null;
		String message = assertThrows(IllegalArgumentException.class, () -> {
			// call under test
			gridManager.validateRepicaOwner(mockUser, gridSessionId, replicaId);
		}).getMessage();
		assertEquals("replicaId is required.", message);
		verifyZeroInteractions(mockGridDao);
	}

	@Test
	public void testCreateWebsocketPresignedUrl() {
		when(mockUser.getId()).thenReturn(userId);
		when(mockWebsocketApi.getApiId()).thenReturn("abcde");
		when(mockWebsocketApi.getStageName()).thenReturn("stage");
		AwsBasicCredentials creds = AwsBasicCredentials.builder().accessKeyId("accessKey").secretAccessKey("secret")
				.build();
		when(mockCredentialsProvider.resolveCredentials()).thenReturn(creds);
		doNothing().when(gridManager).validateRepicaOwner(mockUser, gridSessionId, replicaId);

		// call under test
		CreateGridPresignedUrlResponse response = gridManager.createWebsocketPresignedUrl(mockUser,
				createGridPresignedUrlRequest);
		String presigned = response.getPresignedUrl();
		assertTrue(presigned.startsWith(
				"wss://abcde.execute-api.us-east-1.amazonaws.com/stage/?gridSessionId=456&replicaId=88&userId=123"));
		// note the date and signature change with each run.
		assertTrue(presigned.contains("X-Amz-Algorithm=AWS4-HMAC-SHA256"));
		assertTrue(presigned.contains("X-Amz-Date"));
		assertTrue(presigned.contains("X-Amz-SignedHeaders=host"));
		assertTrue(presigned.contains("X-Amz-Credential=accessKey"));
		assertTrue(presigned.contains("X-Amz-Expires"));
		assertTrue(presigned.contains("X-Amz-Signature"));

	}

	@Test
	public void testCreateWebsocketPresignedUrlWithNullRequest() {
		createGridPresignedUrlRequest = null;
		String message = assertThrows(IllegalArgumentException.class, () -> {
			// call under test
			gridManager.createWebsocketPresignedUrl(mockUser, createGridPresignedUrlRequest);
		}).getMessage();
		assertEquals("request is required.", message);
		verify(gridManager, never()).validateRepicaOwner(any(), any(), any());

	}

	@Test
	public void testCreateReplicaConnection() {
		when(mockUser.getId()).thenReturn(userId);
		doNothing().when(gridManager).validateRepicaOwner(mockUser, gridSessionId, replicaId);

		// call under test
		gridManager.createReplicaConnection(mockUser, eventContext,
				new Connection().setGridSessionId(gridSessionIdLong).setReplicaId(replicaId).setUserId(userId));

		verify(mockGridDao)
				.createConnection(new GridConnectionInfo().setConnectionId(connectionId).setSessionId(gridSessionId)
						.setReplicaId(replicaId).setCreatedBy(userId).setSource(EventSource.WEBSOCKET));
	}

	@Test
	public void testCreateReplicaConnectionWithNonConnectionType() {
		// Only connection type is allowed.
		eventContext = new EventContext(EventType.MESSAGE, EventSource.WEBSOCKET, connectionId);
		String message = assertThrows(UnauthorizedException.class, () -> {
			// call under test
			gridManager.createReplicaConnection(mockUser, eventContext,
					new Connection().setGridSessionId(gridSessionIdLong).setReplicaId(replicaId).setUserId(userId));
		}).getMessage();
		assertEquals("Invalid request", message);

		verifyZeroInteractions(mockGridDao);
	}

	@Test
	public void testCreateReplicaConnectionWithNullUser() {
		mockUser = null;
		String message = assertThrows(IllegalArgumentException.class, () -> {
			// call under test
			gridManager.createReplicaConnection(mockUser, eventContext,
					new Connection().setGridSessionId(gridSessionIdLong).setReplicaId(replicaId).setUserId(userId));
		}).getMessage();
		assertEquals("user is required.", message);

		verifyZeroInteractions(mockGridDao);
	}

	@Test
	public void testCreateReplicaConnectionWithNullContext() {
		eventContext = null;
		String message = assertThrows(IllegalArgumentException.class, () -> {
			// call under test
			gridManager.createReplicaConnection(mockUser, eventContext,
					new Connection().setGridSessionId(gridSessionIdLong).setReplicaId(replicaId).setUserId(userId));
		}).getMessage();
		assertEquals("context is required.", message);

		verifyZeroInteractions(mockGridDao);
	}

	@Test
	public void testCreateReplicaConnectionWithNullConnection() {
		String message = assertThrows(IllegalArgumentException.class, () -> {
			// call under test
			gridManager.createReplicaConnection(mockUser, eventContext, null);
		}).getMessage();
		assertEquals("connection is required.", message);

		verifyZeroInteractions(mockGridDao);
	}

	@Test
	public void testRemoveReplicaConnection() {
		// call under test
		gridManager.removeReplicatConnection(EventType.DISCONNECT, connectionId);
		verify(mockGridDao).removeConnection(connectionId);
	}

	@Test
	public void testRemoveReplicaConnectionWithNonDisconnect() {
		String message = assertThrows(UnauthorizedException.class, () -> {
			// call under test
			gridManager.removeReplicatConnection(EventType.MESSAGE, connectionId);
		}).getMessage();
		assertEquals("Invalid request", message);
		verifyZeroInteractions(mockGridDao);
	}

	@Test
	public void testRemoveReplicaConnectionWithNullType() {
		String message = assertThrows(IllegalArgumentException.class, () -> {
			// call under test
			gridManager.removeReplicatConnection(null, connectionId);
		}).getMessage();
		assertEquals("type is required.", message);
		verifyZeroInteractions(mockGridDao);
	}

	@Test
	public void testRemoveReplicaConnectionWithNullConnectionId() {
		connectionId = null;
		String message = assertThrows(IllegalArgumentException.class, () -> {
			// call under test
			gridManager.removeReplicatConnection(EventType.DISCONNECT, connectionId);
		}).getMessage();
		assertEquals("connectionId is required.", message);
		verifyZeroInteractions(mockGridDao);
	}

	@Test
	public void testRemoveReplicaConnectionInternal() {
		// call under test
		gridManager.removeReplicaConnection(connectionId);
		verify(mockGridDao).removeConnection(connectionId);
	}

	@Test
	public void testRemoveReplicaConnectionInternalWithNullId() {
		connectionId = null;
		String message = assertThrows(IllegalArgumentException.class, () -> {
			// call under test
			gridManager.removeReplicaConnection(connectionId);
		}).getMessage();
		assertEquals("connectionId is required.", message);
		verifyZeroInteractions(mockGridDao);
	}
}
