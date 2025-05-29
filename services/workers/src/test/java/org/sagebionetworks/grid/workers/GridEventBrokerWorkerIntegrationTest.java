package org.sagebionetworks.grid.workers;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.net.http.WebSocket.Listener;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.json.JSONArray;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.sagebionetworks.AsynchronousJobWorkerHelper;
import org.sagebionetworks.repo.manager.UserManager;
import org.sagebionetworks.repo.model.AsynchJobFailedException;
import org.sagebionetworks.repo.model.AuthorizationConstants.BOOTSTRAP_PRINCIPAL;
import org.sagebionetworks.repo.model.UserInfo;
import org.sagebionetworks.repo.model.grid.CreateGridPresignedUrlRequest;
import org.sagebionetworks.repo.model.grid.CreateGridRequest;
import org.sagebionetworks.repo.model.grid.CreateGridResponse;
import org.sagebionetworks.repo.model.grid.CreateReplicaRequest;
import org.sagebionetworks.repo.model.grid.GridReplica;
import org.sagebionetworks.repo.model.grid.GridSession;
import org.sagebionetworks.repo.service.GridService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(locations = { "classpath:test-context.xml" })
public class GridEventBrokerWorkerIntegrationTest {

	public static final long MAX_WAIT_MS = 120_000;

	@Autowired
	private GridService gridServie;

	@Autowired
	private UserManager userManager;

	@Autowired
	private AsynchronousJobWorkerHelper asynchronousJobWorkerHelper;

	private UserInfo admin;

	@BeforeEach
	public void before() {
		admin = userManager.getUserInfo(BOOTSTRAP_PRINCIPAL.THE_ADMIN_USER.getPrincipalId());
	}

	@Test
	public void testPingGrid()
			throws InterruptedException, AssertionError, AsynchJobFailedException, URISyntaxException {

		// Create a grid session.
		GridSession session = asynchronousJobWorkerHelper
				.assertJobResponse(admin, new CreateGridRequest(), (CreateGridResponse response) -> {
					assertNotNull(response);
					assertNotNull(response.getGridSession());
				}, MAX_WAIT_MS).getResponse().getGridSession();

		// Create a replica
		GridReplica replica = gridServie
				.createReplica(admin.getId(), new CreateReplicaRequest().setGridSessionId(session.getSessionId()))
				.getReplica();

		String presignedUrl = gridServie
				.createPresignedUrl(admin.getId(), new CreateGridPresignedUrlRequest()
						.setGridSessionId(session.getSessionId()).setReplicaId(replica.getReplicaId()))
				.getPresignedUrl();
		assertNotNull(presignedUrl);

		BlockingQueue<String> incomingMessages = new LinkedBlockingQueue<>();
		WebSocket ws = createConnection(presignedUrl, incomingMessages);

		// send a ping
		long start = System.currentTimeMillis();
		ws.sendText(new JSONArray("[8,\"ping\"]").toString(), true).join();
		assertTrue(waitForMessage(8, "pong", incomingMessages));
		long end = System.currentTimeMillis();
		System.out.println("Ping: " + (end - start) + " ms");
		ws.sendClose(4999, "closing").join();
	}
	
	/**
	 * Wait for the given message to appear on the queue.
	 * @param code
	 * @param key
	 * @param incomingMessages
	 * @return
	 * @throws InterruptedException
	 */
	boolean waitForMessage(int code, String key, BlockingQueue<String> incomingMessages) throws InterruptedException {
		String message = null;
		do {
			message = incomingMessages.poll(10, TimeUnit.SECONDS);
			JSONArray response = new JSONArray(message);
			if(response.length() > 1){
				if(response.getInt(0) == 8) {
					if(key.equals(response.getString(1))) {
						return true;
					}
				}
			}
		} while (message != null);
		return false;
	}
	
	

	/**
	 * Create a websocket connection that will post all received messages to the
	 * passed queue.
	 * 
	 * @param presignedUrl
	 * @param incomingMessages
	 * @return
	 * @throws URISyntaxException
	 */
	public WebSocket createConnection(String presignedUrl, BlockingQueue<String> incomingMessages)
			throws URISyntaxException {
		HttpClient client = HttpClient.newHttpClient();
		return client.newWebSocketBuilder().buildAsync(new URI(presignedUrl), new Listener() {

			@Override
			public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
				try {
					incomingMessages.put(data.toString());
				} catch (InterruptedException e) {
					webSocket.sendClose(4999, "closing");
					throw new RuntimeException(e);
				}
				webSocket.request(1);
				return null;
			}
		}).join();
	}

}
