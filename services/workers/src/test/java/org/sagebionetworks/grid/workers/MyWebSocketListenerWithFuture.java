package org.sagebionetworks.grid.workers;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class MyWebSocketListenerWithFuture implements WebSocket.Listener {

	private final CountDownLatch latch;
	private final CompletableFuture<String> receivedMessageFuture; // To hold the message

	public MyWebSocketListenerWithFuture(CountDownLatch latch, CompletableFuture<String> receivedMessageFuture) {
		this.latch = latch;
		this.receivedMessageFuture = receivedMessageFuture;
	}

	@Override
	public void onOpen(WebSocket webSocket) {
		System.out.println("WebSocket opened: " + webSocket);
		webSocket.sendText("Hello WebSocket Server from Client!", true); // Send a message
	}

	@Override
	public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
		String message = data.toString();
		System.out.println("Listener received text message: " + message);

		// Complete the CompletableFuture with the received message
		receivedMessageFuture.complete(message);

//		// You might want to close the connection or send more messages depending on
//		// your logic
//		// For this example, let's close after receiving the first message.
//		webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "Received message").thenRun(() -> {
//			System.out.println("Client initiated close after receiving message.");
//		});

		return null; // Let the listener handle the next messages if any, though we're closing.
	}

	@Override
	public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
		System.out.println("WebSocket closed with status " + statusCode + ", reason: " + reason);
		latch.countDown();
		return null;
	}

	@Override
	public void onError(WebSocket webSocket, Throwable error) {
		System.err.println("WebSocket error: " + error.getMessage());
		error.printStackTrace();
		receivedMessageFuture.completeExceptionally(error); // Signal error to the future
		latch.countDown();
	}

	public static void main(String[] args) throws InterruptedException {
		HttpClient client = HttpClient.newHttpClient();
		CountDownLatch latch = new CountDownLatch(1);
		CompletableFuture<String> messageFuture = new CompletableFuture<>(); // Create the future

		String webSocketUri = "ws://echo.websocket.org";

		System.out.println("Connecting to WebSocket server: " + webSocketUri);

		client.newWebSocketBuilder()
				.buildAsync(URI.create(webSocketUri), new MyWebSocketListenerWithFuture(latch, messageFuture))
				.thenAccept(webSocket -> {
					System.out.println("Connected to WebSocket.");
				}).exceptionally(throwable -> {
					System.err.println("Failed to connect to WebSocket: " + throwable.getMessage());
					messageFuture.completeExceptionally(throwable); // Signal connection error
					latch.countDown();
					return null;
				});

		// Wait for the message or for the WebSocket to close
		try {
			String receivedMessage = messageFuture.get(10, TimeUnit.SECONDS); // Wait for the message with a timeout
			System.out.println("Main method received message: " + receivedMessage);
		} catch (java.util.concurrent.TimeoutException e) {
			System.err.println("Timeout waiting for message.");
		} catch (java.util.concurrent.ExecutionException e) {
			System.err.println("Error while getting message from future: " + e.getCause().getMessage());
		} finally {
			// Ensure the latch is counted down if the future completed earlier than the
			// connection closing.
			// This is important if you close the connection from within onText as done in
			// this example.
			latch.await(); // Wait for the actual WebSocket close
		}

		System.out.println("WebSocket demonstration finished.");
	}
}
