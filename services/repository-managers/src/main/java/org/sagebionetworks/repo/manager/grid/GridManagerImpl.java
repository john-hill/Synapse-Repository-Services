package org.sagebionetworks.repo.manager.grid;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.sagebionetworks.repo.manager.config.WebsocketApi;
import org.sagebionetworks.repo.model.UserInfo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.http.SdkHttpMethod;
import software.amazon.awssdk.http.SdkHttpRequest;
import software.amazon.awssdk.http.auth.aws.signer.AwsV4FamilyHttpSigner.AuthLocation;
import software.amazon.awssdk.http.auth.aws.signer.AwsV4HttpSigner;
import software.amazon.awssdk.http.auth.spi.signer.SignRequest;
import software.amazon.awssdk.http.auth.spi.signer.SignedRequest;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.apigatewaymanagementapi.ApiGatewayManagementApiClient;
import software.amazon.awssdk.services.apigatewaymanagementapi.model.PostToConnectionRequest;

@Service
public class GridManagerImpl implements GridManager {

	private final AwsCredentialsProvider awsCredentialsProvider;
	private final ApiGatewayManagementApiClient apiGatewayManagmentClient;
	private final WebsocketApi websocketApi;

	@Autowired
	public GridManagerImpl(AwsCredentialsProvider awsCredentialsProvider,
			ApiGatewayManagementApiClient apiGatewayManagmentClient, WebsocketApi websocketApi) {
		super();
		this.awsCredentialsProvider = awsCredentialsProvider;
		this.apiGatewayManagmentClient = apiGatewayManagmentClient;
		this.websocketApi = websocketApi;
	}

	@Override
	public void sendMessage(String connectionId, String message) {
		apiGatewayManagmentClient.postToConnection(
				PostToConnectionRequest.builder().data(SdkBytes.fromByteArray(message.getBytes(StandardCharsets.UTF_8)))
						.connectionId(connectionId).build());

	}


	@Override
	public String createWebsocketPresignedUrl(String gridSessionId, int replicaId, UserInfo userer) {
		AwsV4HttpSigner signer = AwsV4HttpSigner.create();
		String startUrl = String.format(
				"https://%s.execute-api.us-east-1.amazonaws.com/%s/?gridSessionId=%s&replicaId=%d&userId=%d",
				websocketApi.getApiId(), websocketApi.getStageName(), gridSessionId, replicaId, userer.getId());
		SdkHttpRequest httpRequest = SdkHttpRequest.builder().uri(startUrl).method(SdkHttpMethod.GET).build();

		SignedRequest signedRequest = signer.sign(SignRequest.builder(awsCredentialsProvider.resolveCredentials())
				.request(httpRequest)
				.putProperty(AwsV4HttpSigner.SERVICE_SIGNING_NAME, "execute-api")
				.putProperty(AwsV4HttpSigner.REGION_NAME, Region.US_EAST_1.toString())
				.putProperty(AwsV4HttpSigner.AUTH_LOCATION, AuthLocation.QUERY_STRING)
				.putProperty(AwsV4HttpSigner.EXPIRATION_DURATION, Duration.ofMinutes(15))
				.build());

		String url = signedRequest.request().getUri().toString();
		StringBuilder builder = new StringBuilder("wss").append(url.substring(5, url.length()));
		return builder.toString();
	}

}
