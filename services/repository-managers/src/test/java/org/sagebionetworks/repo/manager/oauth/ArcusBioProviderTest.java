package org.sagebionetworks.repo.manager.oauth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sagebionetworks.repo.manager.http.HttpClientImpl;
import org.sagebionetworks.simpleHttpClient.SimpleHttpClient;
import static org.junit.jupiter.api.Assertions.*;

class ArcusBioProviderTest {
	
	private static String CLIENT_ID="client-id";
	private static String CLIENT_SECRET="client-secret";
	private static String REDIRECT_URL="https://dev.accounts.synapse.org/?provider=ARCUS_BIOSCIENCES";
	private static String ARCUS_BIO_DISCOVERY_DOCUMENT_URL="https://arcusbio.okta.com/oauth2/default/.well-known/oauth-authorization-server";
	private static String AUTHORIZATION_CODE="auth-code";
	
	private SimpleHttpClient client;
	private ArcusBioProvider provider;
	
	@BeforeEach
	void setUp() {
		this.client= new HttpClientImpl();
		this.provider = new ArcusBioProvider(CLIENT_ID, CLIENT_SECRET,
				new OIDCConfig(client, ARCUS_BIO_DISCOVERY_DOCUMENT_URL));
	}
	
	@Test
	public void testConstructor() {
		assertEquals("https://arcusbio.okta.com/oauth2/default/v1/token", provider.tokenUrl);
		assertEquals("https://arcusbio.okta.com/oauth2/default/v1/userinfo", provider.userInfoUrl);
	}
//	@Test
//	void testValidateUserWithProvider() {
//		provider.validateUserWithProvider(AUTHORIZATION_CODE, REDIRECT_URL);
//	}

}
