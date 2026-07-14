package org.sagebionetworks.markdown;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.auth.aws.signer.AwsV4HttpSigner;

public class AwsV4RequestSignerTest {
	private AwsV4RequestSigner signer;

	@BeforeEach
	public void before() {
		signer = new AwsV4RequestSigner(
			StaticCredentialsProvider.create(AwsBasicCredentials.create("AKIAIOSFODNN7EXAMPLE", "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY")),
			AwsV4HttpSigner.create()
		);
	}

	@Test
	public void testSignReturnsSignedHeaders() throws Exception {
		String endpoint = "https://abc123.execute-api.us-east-1.amazonaws.com/prod/markdown";
		String payload = "{\"markdown\":\"## a heading\"}";
		byte[] payloadBytes = payload.getBytes(StandardCharsets.UTF_8);

		// call under test
		Map<String, String> signedHeaders = signer.sign(URI.create(endpoint), payloadBytes);

		assertNotNull(signedHeaders);
		assertFalse(signedHeaders.isEmpty());
	}

	@Test
	public void testSignIncludesAuthorizationHeader() throws Exception {
		String endpoint = "https://abc123.execute-api.us-east-1.amazonaws.com/prod/markdown";
		String payload = "{\"markdown\":\"## a heading\"}";
		byte[] payloadBytes = payload.getBytes(StandardCharsets.UTF_8);

		// call under test
		Map<String, String> signedHeaders = signer.sign(URI.create(endpoint), payloadBytes);

		String authHeader = signedHeaders.get("Authorization");
		assertNotNull(authHeader);
		assertTrue(authHeader.startsWith("AWS4-HMAC-SHA256 Credential=AKIAIOSFODNN7EXAMPLE/"));
		assertTrue(authHeader.contains("SignedHeaders="));
		assertTrue(authHeader.contains("Signature="));
	}

	@Test
	public void testSignIncludesAmzDateHeader() throws Exception {
		String endpoint = "https://abc123.execute-api.us-east-1.amazonaws.com/prod/markdown";
		String payload = "{\"markdown\":\"## a heading\"}";
		byte[] payloadBytes = payload.getBytes(StandardCharsets.UTF_8);

		// call under test
		Map<String, String> signedHeaders = signer.sign(URI.create(endpoint), payloadBytes);

		String amzDate = signedHeaders.get("X-Amz-Date");
		assertNotNull(amzDate);
		assertTrue(amzDate.matches("\\d{8}T\\d{6}Z"));
	}

	@Test
	public void testSignExcludesHostHeader() throws Exception {
		String endpoint = "https://abc123.execute-api.us-east-1.amazonaws.com/prod/markdown";
		String payload = "{\"markdown\":\"## a heading\"}";
		byte[] payloadBytes = payload.getBytes(StandardCharsets.UTF_8);

		// call under test
		Map<String, String> signedHeaders = signer.sign(URI.create(endpoint), payloadBytes);

		assertFalse(signedHeaders.containsKey("Host"));
		assertFalse(signedHeaders.containsKey("host"));
	}

	@Test
	public void testSignReturnsUnmodifiableMap() throws Exception {
		String endpoint = "https://abc123.execute-api.us-east-1.amazonaws.com/prod/markdown";
		String payload = "{\"markdown\":\"## a heading\"}";
		byte[] payloadBytes = payload.getBytes(StandardCharsets.UTF_8);

		// call under test
		Map<String, String> signedHeaders = signer.sign(URI.create(endpoint), payloadBytes);

		// Callers (e.g. MarkdownClient) must copy this map before mutating it
		assertThrows(UnsupportedOperationException.class, () -> signedHeaders.put("foo", "bar"));
	}

	@Test
	public void testSignWithEmptyPayload() throws Exception {
		String endpoint = "https://abc123.execute-api.us-east-1.amazonaws.com/prod/markdown";
		byte[] payloadBytes = new byte[0];

		// call under test
		Map<String, String> signedHeaders = signer.sign(URI.create(endpoint), payloadBytes);

		assertNotNull(signedHeaders);
		assertNotNull(signedHeaders.get("Authorization"));
		assertNotNull(signedHeaders.get("X-Amz-Date"));
	}

	@Test
	public void testSignWithDifferentEndpoints() throws Exception {
		String endpoint1 = "https://service1.execute-api.us-east-1.amazonaws.com/prod/markdown";
		String endpoint2 = "https://service2.execute-api.us-west-2.amazonaws.com/v1/markdown";
		String payload = "{\"markdown\":\"test\"}";
		byte[] payloadBytes = payload.getBytes(StandardCharsets.UTF_8);

		// call under test - both should produce valid signatures
		Map<String, String> headers1 = signer.sign(URI.create(endpoint1), payloadBytes);
		Map<String, String> headers2 = signer.sign(URI.create(endpoint2), payloadBytes);

		assertNotNull(headers1.get("Authorization"));
		assertNotNull(headers2.get("Authorization"));
		// Signatures should be different because endpoints are different
		assertFalse(headers1.get("Authorization").equals(headers2.get("Authorization")));
	}
}
