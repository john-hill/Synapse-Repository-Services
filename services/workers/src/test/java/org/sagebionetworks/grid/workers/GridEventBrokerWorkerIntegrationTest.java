package org.sagebionetworks.grid.workers;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.net.http.WebSocket.Listener;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

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
import org.sagebionetworks.repo.model.grid.patch.LogicalTimestamp;
import org.sagebionetworks.repo.model.grid.patch.compact.PatchCompactSerializable;
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

		waitForConnected(incomingMessages);
		// send a ping
		ws.sendText(new JSONArray("[8,\"ping\"]").toString(), true).join();
		assertTrue(waitForMessage((a) -> a.optInt(0) == 8 && "pong".equals(a.optString(1)), incomingMessages));
		ws.sendClose(4999, "closing").join();

	}

	void waitForConnected(BlockingQueue<String> incomingMessages) throws InterruptedException {
		assertTrue(waitForMessage((a) -> a.optInt(0) == 8 && "connected".equals(a.optString(1)), incomingMessages));
	}

	@Test
	public void testPatch() throws AssertionError, AsynchJobFailedException, URISyntaxException, InterruptedException {
		// Create a grid session.
		GridSession session = asynchronousJobWorkerHelper
				.assertJobResponse(admin, new CreateGridRequest(), (CreateGridResponse response) -> {
					assertNotNull(response);
					assertNotNull(response.getGridSession());
				}, MAX_WAIT_MS).getResponse().getGridSession();

		// Create replica One
		GridReplica replicaOne = gridServie
				.createReplica(admin.getId(), new CreateReplicaRequest().setGridSessionId(session.getSessionId()))
				.getReplica();

		String urlOne = gridServie
				.createPresignedUrl(admin.getId(), new CreateGridPresignedUrlRequest()
						.setGridSessionId(session.getSessionId()).setReplicaId(replicaOne.getReplicaId()))
				.getPresignedUrl();
		assertNotNull(urlOne);

		BlockingQueue<String> incomingMessagesOne = new LinkedBlockingQueue<>();
		WebSocket wsOne = createConnection(urlOne, incomingMessagesOne);
		waitForConnected(incomingMessagesOne);

		// Create replica two.
		GridReplica replicaTwo = gridServie
				.createReplica(admin.getId(), new CreateReplicaRequest().setGridSessionId(session.getSessionId()))
				.getReplica();

		String urlTwo = gridServie
				.createPresignedUrl(admin.getId(), new CreateGridPresignedUrlRequest()
						.setGridSessionId(session.getSessionId()).setReplicaId(replicaTwo.getReplicaId()))
				.getPresignedUrl();
		assertNotNull(urlOne);

		BlockingQueue<String> incomingMessagesTwo = new LinkedBlockingQueue<>();
		WebSocket wsTwo = createConnection(urlTwo, incomingMessagesTwo);
		waitForConnected(incomingMessagesTwo);

		// Replica one sends a patch.
		String patchBody = String.format("[[[%d,1]],[0]]", replicaOne.getReplicaId());
		String patchRequest = String.format("[1,101,\"patch\", %s]", patchBody);
		wsOne.sendText(patchRequest, true).join();

		// Wait for response complete: [5,101]
		assertTrue(waitForMessage((a) -> a.optInt(0) == 5 && a.optInt(1) == 101, incomingMessagesOne));

		// send a second patch;
		patchBody = String.format("[[[%d,4]],[0]]", replicaOne.getReplicaId());
		patchRequest = String.format("[1,102,\"patch\", %s]", patchBody);
		wsOne.sendText(patchRequest, true).join();

		// Wait for response complete: [5,102]
		assertTrue(waitForMessage((a) -> a.optInt(0) == 5 && a.optInt(1) == 102, incomingMessagesOne));

		// The second replica should be notified of two patches
		assertTrue(waitForMessage((a) -> a.optInt(0) == 8 && "new-patch".equals(a.optString(1)), incomingMessagesTwo));
		assertTrue(waitForMessage((a) -> a.optInt(0) == 8 && "new-patch".equals(a.optString(1)), incomingMessagesTwo));

		// Two's clock is currently empty so start a synchronize.
		wsTwo.sendText("[1,99,\"synchronize-clock\",[]]", true).join();

		List<LogicalTimestamp> patchIds = new ArrayList<>();
		assertTrue(waitForMessage((a) -> {
			if (a.optInt(0) == 4 && a.optInt(1) == 99) {
				patchIds.add(PatchCompactSerializable.peekPatchId(a.getJSONArray(2)));
				return true;
			} else {
				return false;
			}
		}, incomingMessagesTwo));

		// after applying the patch update the clock and synchronize again.
		String newClock = PatchCompactSerializable.serializeClock(patchIds).toString();
		wsTwo.sendText(String.format("[1,99,\"synchronize-clock\",%s]", newClock), true).join();

		patchIds.clear();
		assertTrue(waitForMessage((a) -> {
			if (a.optInt(0) == 4 && a.optInt(1) == 99) {
				patchIds.add(PatchCompactSerializable.peekPatchId(a.getJSONArray(2)));
				return true;
			} else {
				return false;
			}
		}, incomingMessagesTwo));

		// after the second snych, replica two should be up-to-date.
		newClock = PatchCompactSerializable.serializeClock(patchIds).toString();
		wsTwo.sendText(String.format("[1,99,\"synchronize-clock\",%s]", newClock), true).join();
		
		assertTrue(waitForMessage((a) -> a.optInt(0) == 5 && a.optInt(1) == 99, incomingMessagesTwo));

	}

	/**
	 * Wait for the given message to appear on the queue.
	 * 
	 * @param code
	 * @param key
	 * @param incomingMessages
	 * @return
	 * @throws InterruptedException
	 */
	boolean waitForMessage(Predicate<JSONArray> handler, BlockingQueue<String> incomingMessages)
			throws InterruptedException {
		String message = null;
		do {
			message = incomingMessages.poll(10, TimeUnit.SECONDS);
			System.out.println(message);
			if (message == null) {
				return false;
			}
			JSONArray array = new JSONArray(message);
			if (handler.test(array)) {
				return true;
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
