package org.sagebionetworks.markdown;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.apache.http.client.ClientProtocolException;
import org.sagebionetworks.aws.v2.AwsCredentialsProviderV2;
import org.sagebionetworks.simpleHttpClient.SimpleHttpClient;
import org.sagebionetworks.simpleHttpClient.SimpleHttpClientImpl;
import org.sagebionetworks.simpleHttpClient.SimpleHttpRequest;
import org.sagebionetworks.simpleHttpClient.SimpleHttpResponse;

import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.http.ContentStreamProvider;
import software.amazon.awssdk.http.SdkHttpMethod;
import software.amazon.awssdk.http.SdkHttpRequest;
import software.amazon.awssdk.http.auth.aws.signer.AwsV4HttpSigner;
import software.amazon.awssdk.http.auth.spi.signer.SignRequest;
import software.amazon.awssdk.http.auth.spi.signer.SignedRequest;
import software.amazon.awssdk.regions.Region;

public class MarkdownClient {

	private SimpleHttpClient simpleHttpClient;
	private String markdownServiceEndpoint;
	private AwsCredentialsProvider awsCredentialsProvider;
	private AwsV4HttpSigner signer;
	private static final Map<String, String> DEFAULT_REQUEST_HEADERS;

	static {
		Map<String, String> requestHeaders = new HashMap<String, String>();
		requestHeaders.put("Content-Type", "application/json");
		DEFAULT_REQUEST_HEADERS = Collections.unmodifiableMap(requestHeaders);
	}

	public void _init() {
		if (simpleHttpClient == null) {
			simpleHttpClient = new SimpleHttpClientImpl();
		}
		if (awsCredentialsProvider == null) {
			awsCredentialsProvider = AwsCredentialsProviderV2.PROVIDER_CHAIN;
		}
		if (signer == null) {
			signer = AwsV4HttpSigner.create();
		}
	}

	/**
	 * Takes a json string requestContent (ex. {"markdown":"## a heading"})
	 * Makes a call to the markdown server to convert the raw markdown to html
	 * Return the json string representation of the response (ex. {"result":"<h2 toc=\"true\">a heading</h2>\n"})
	 *
	 * @param requestContent
	 * @return
	 * @throws ClientProtocolException
	 * @throws IOException
	 * @throws MarkdownClientException
	 */
	public String requestMarkdownConversion(String requestContent) throws MarkdownClientException {
		SimpleHttpRequest request = new SimpleHttpRequest();
		request.setUri(markdownServiceEndpoint);

		SdkHttpRequest httpRequest = SdkHttpRequest.builder()
				.uri(URI.create(markdownServiceEndpoint))
				.method(SdkHttpMethod.POST)
				.build();

		SignedRequest signedRequest = signer.sign(SignRequest.builder(awsCredentialsProvider.resolveCredentials())
				.request(httpRequest)
				.payload(ContentStreamProvider.fromByteArray(requestContent.getBytes(StandardCharsets.UTF_8)))
				.putProperty(AwsV4HttpSigner.SERVICE_SIGNING_NAME, "execute-api")
				.putProperty(AwsV4HttpSigner.REGION_NAME, Region.US_EAST_1.toString())
				.build());

		Map<String, String> headers = new HashMap<>(DEFAULT_REQUEST_HEADERS);
		signedRequest.request().forEachHeader((name, values) -> {
			// Skip Host — Apache HttpClient regenerates it from the URI; a duplicate would break signature verification
			if (!"Host".equalsIgnoreCase(name)) {
				headers.put(name, String.join(",", values));
			}
		});
		request.setHeaders(headers);

		try {
			SimpleHttpResponse response = simpleHttpClient.post(request, requestContent);
			if (response.getStatusCode() == 200) {
				return response.getContent();
			} else {
				String message = "Fail to request markdown conversion for request: "+requestContent;
				throw new MarkdownClientException(response.getStatusCode(), message);
			}
		} catch (IOException  e) {
			throw new MarkdownClientException(e);
		}
	}

	public String getMarkdownServiceEndpoint() {
		return markdownServiceEndpoint;
	}
	public void setMarkdownServiceEndpoint(String markdownServiceEndpoint) {
		this.markdownServiceEndpoint = markdownServiceEndpoint;
	}

	public AwsCredentialsProvider getAwsCredentialsProvider() { return this.awsCredentialsProvider; }
	public void setAwsCredentialsProvider(AwsCredentialsProvider awsCredentialsProvider) {
		this.awsCredentialsProvider = awsCredentialsProvider;
	}

	public AwsV4HttpSigner getSigner() { return this.signer; }
	public void setSigner(AwsV4HttpSigner signer) {
		this.signer = signer;
	}

	public SimpleHttpClient getSimpleHttpClient() { return this.simpleHttpClient; }
	public void setSimpleHttpClient(SimpleHttpClient client) {
		this.simpleHttpClient = client;
	}
}
