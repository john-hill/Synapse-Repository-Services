package org.sagebionetworks.repo.manager.grid;

import java.time.Duration;

import org.sagebionetworks.repo.manager.config.WebsocketApi;
import org.sagebionetworks.repo.model.AuthorizationUtils;
import org.sagebionetworks.repo.model.UnauthorizedException;
import org.sagebionetworks.repo.model.UserInfo;
import org.sagebionetworks.repo.model.dbo.grid.GridDao;
import org.sagebionetworks.repo.model.grid.ConnectionInfo;
import org.sagebionetworks.repo.model.grid.CreateGridPresignedUrlRequest;
import org.sagebionetworks.repo.model.grid.CreateGridPresignedUrlResponse;
import org.sagebionetworks.repo.model.grid.CreateGridRequest;
import org.sagebionetworks.repo.model.grid.CreateGridResponse;
import org.sagebionetworks.repo.model.grid.CreateReplicaRequest;
import org.sagebionetworks.repo.model.grid.CreateReplicaResponse;
import org.sagebionetworks.repo.model.grid.EventContext;
import org.sagebionetworks.repo.model.grid.EventSource;
import org.sagebionetworks.repo.model.grid.EventType;
import org.sagebionetworks.repo.model.grid.GridReplica;
import org.sagebionetworks.repo.model.grid.GridSession;
import org.sagebionetworks.repo.model.grid.GridUtils;
import org.sagebionetworks.repo.model.grid.internal.Connection;
import org.sagebionetworks.repo.transactions.WriteTransaction;
import org.sagebionetworks.repo.web.NotFoundException;
import org.sagebionetworks.util.ValidateArgument;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.http.SdkHttpMethod;
import software.amazon.awssdk.http.SdkHttpRequest;
import software.amazon.awssdk.http.auth.aws.signer.AwsV4FamilyHttpSigner.AuthLocation;
import software.amazon.awssdk.http.auth.aws.signer.AwsV4HttpSigner;
import software.amazon.awssdk.http.auth.spi.signer.SignRequest;
import software.amazon.awssdk.http.auth.spi.signer.SignedRequest;
import software.amazon.awssdk.regions.Region;

@Service
public class GridManagerImpl implements GridManager {

	public static final String GRID_REPLICA_NOT_FOUND = "Grid replica not found.";
	public static final String GRID_SESSION_NOT_FOUND = "Grid session not found.";
	private final AwsCredentialsProvider awsCredentialsProvider;
	private final WebsocketApi websocketApi;
	private final GridDao gridDao;

	@Autowired
	public GridManagerImpl(AwsCredentialsProvider awsCredentialsProvider, WebsocketApi websocketApi, GridDao gridDao) {
		super();
		this.awsCredentialsProvider = awsCredentialsProvider;
		this.websocketApi = websocketApi;
		this.gridDao = gridDao;
	}

	@WriteTransaction
	@Override
	public CreateGridResponse createGrid(UserInfo user, CreateGridRequest request) {
		ValidateArgument.required(user, "user");
		ValidateArgument.required(request, "request");

		// Must authenticate to create a grid session.
		AuthorizationUtils.disallowAnonymous(user);

		GridSession session = gridDao.createGridSession(user.getId());

		return new CreateGridResponse().setGridSession(session);
	}

	/**
	 * Currently, only the user that started the session may access it.
	 * 
	 * @param user
	 * @param gridSessionId
	 */
	void validGridSessionAccess(UserInfo user, String gridSessionId) {
		ValidateArgument.required(user, "user");
		ValidateArgument.required(gridSessionId, "gridSessionId");

		Long startedBy = gridDao.getGridSessionStartedBy(gridSessionId)
				.orElseThrow(() -> new NotFoundException(GRID_SESSION_NOT_FOUND));
		if (!AuthorizationUtils.isUserCreatorOrAdmin(user, startedBy.toString())) {
			throw new UnauthorizedException("You are not authorized to access this resource.");
		}
	}

	@Override
	public GridSession getGridSession(UserInfo user, String gridSessionId) {
		validGridSessionAccess(user, gridSessionId);
		return gridDao.geGridSession(gridSessionId).orElseThrow(() -> new NotFoundException(GRID_SESSION_NOT_FOUND));
	}

	@WriteTransaction
	@Override
	public CreateReplicaResponse createReplica(UserInfo user, CreateReplicaRequest request) {
		ValidateArgument.required(user, "user");
		ValidateArgument.required(request, "request");
		boolean isAgent = false;
		EventSource soure = EventSource.WEBSOCKET;
		return createReplica(user, request.getGridSessionId(), isAgent, soure);
	}

	@WriteTransaction
	@Override
	public CreateReplicaResponse createReplica(UserInfo user, String gridSessionId, boolean isAgent,
			EventSource source) {
		ValidateArgument.required(user, "user");
		ValidateArgument.required(gridSessionId, "gridSessionId");
		ValidateArgument.required(source, "source");
		// User must have access to the session in order to create a replica
		validGridSessionAccess(user, gridSessionId);
		GridReplica replia = gridDao.createReplica(user.getId(), gridSessionId, isAgent, source);
		return new CreateReplicaResponse().setReplica(replia);
	}

	@Override
	public GridReplica getReplica(UserInfo user, String sessionId, Long replicaId) {
		ValidateArgument.required(replicaId, "replicaId");
		// User must have access to the session in order to create a replica
		validGridSessionAccess(user, sessionId);
		return gridDao.getGridReplica(sessionId, replicaId)
				.orElseThrow(() -> new NotFoundException(GRID_REPLICA_NOT_FOUND));
	}

	/**
	 * Validate that the user created the replica.
	 * 
	 * @param user
	 * @param sesisonId
	 * @param replicaId
	 */
	void validateRepicaOwner(UserInfo user, String sesisonId, Long replicaId) {
		ValidateArgument.required(user, "user");
		ValidateArgument.required(sesisonId, "gridSessionId");
		ValidateArgument.required(replicaId, "replicaId");

		boolean isAgentReplica = false;
		Long replicaCreatedBy = gridDao.getReplicaCreatedBy(sesisonId, replicaId, isAgentReplica)
				.orElseThrow(() -> new NotFoundException(GRID_REPLICA_NOT_FOUND));
		if (!AuthorizationUtils.isUserCreatorOrAdmin(user, replicaCreatedBy.toString())) {
			throw new UnauthorizedException("You are not authorized to access this resource.");
		}
	}

	@Override
	public CreateGridPresignedUrlResponse createWebsocketPresignedUrl(UserInfo user,
			CreateGridPresignedUrlRequest request) {
		ValidateArgument.required(request, "request");

		validateRepicaOwner(user, request.getGridSessionId(), request.getReplicaId());

		Long longSessionId = GridUtils.gridSessionIdAsLong(request.getGridSessionId());

		AwsV4HttpSigner signer = AwsV4HttpSigner.create();
		String startUrl = String.format(
				"https://%s.execute-api.us-east-1.amazonaws.com/%s/?gridSessionId=%s&replicaId=%d&userId=%d",
				websocketApi.getApiId(), websocketApi.getStageName(), longSessionId, request.getReplicaId(),
				user.getId());
		SdkHttpRequest httpRequest = SdkHttpRequest.builder().uri(startUrl).method(SdkHttpMethod.GET).build();

		SignedRequest signedRequest = signer.sign(SignRequest.builder(awsCredentialsProvider.resolveCredentials())
				.request(httpRequest).putProperty(AwsV4HttpSigner.SERVICE_SIGNING_NAME, "execute-api")
				.putProperty(AwsV4HttpSigner.REGION_NAME, Region.US_EAST_1.toString())
				.putProperty(AwsV4HttpSigner.AUTH_LOCATION, AuthLocation.QUERY_STRING)
				.putProperty(AwsV4HttpSigner.EXPIRATION_DURATION, Duration.ofMinutes(15)).build());

		String url = signedRequest.request().getUri().toString();
		StringBuilder builder = new StringBuilder("wss").append(url.substring(5, url.length()));
		return new CreateGridPresignedUrlResponse().setPresignedUrl(builder.toString());
	}

	@WriteTransaction
	@Override
	public void createReplicaConnection(UserInfo user, EventContext context, Connection connection) {
		ValidateArgument.required(user, "user");
		ValidateArgument.required(context, "context");
		ValidateArgument.required(connection, "connection");

		if (!EventType.CONNECT.equals(context.getEventType())) {
			throw new UnauthorizedException("The 'connected' event is not allowed in this context");
		}
		String sessionIdAsString = GridUtils.gridSessionIdAsString(connection.getGridSessionId());
		validateRepicaOwner(user, sessionIdAsString, connection.getReplicaId());
		gridDao.createConnection(new ConnectionInfo().setConnectionId(context.getConnectionId())
				.setCreatedBy(user.getId()).setReplciaId(connection.getReplicaId()).setSessionId(sessionIdAsString)
				.setSource(context.getEventSource()));
	}

	@Override
	public void removeReplicatConnection(EventType type, String connectionId) {
		ValidateArgument.required(type, "type");
		if (!EventType.DISCONNECT.equals(type)) {
			throw new UnauthorizedException("The 'disconnected' event is not allowed in this context");
		}
		removeReplicatConnection(connectionId);
	}

	@Override
	public void removeReplicatConnection(String connectionId) {
		ValidateArgument.required(connectionId, "connectionId");
		gridDao.removeConnection(connectionId);
	}

}
