package org.sagebionetworks.repo.manager.oauth;

import org.sagebionetworks.repo.model.UnauthorizedException;
import org.sagebionetworks.repo.model.principal.AliasType;
import org.sagebionetworks.util.ValidateArgument;
import org.scribe.exceptions.OAuthException;
import org.scribe.model.OAuthConfig;
import org.scribe.model.Verifier;

/**
 * OAuthProvider for Arcus Bio's OpenID Connect server
 * 
 */
public class ArcusBioProvider implements OAuthProviderBinding {

	private static final String AUTH_URL_DEFAULT_PARAMS = "?response_type=code&client_id=%s&redirect_uri=%s";
	
	/*
	 * To be OIDC compliant we need the openid scope.
	 */
	private static final String OIDC_SCOPES = "openid email";

	private String apiKey;
	private String apiSecret;
	private String authUrl;
	private String tokenUrl;
	
	/**
	 * Thread safe OAuth 2.0 provider.
	 * 
	 * @param apiKey Client ID provided by Arcus Bio.
	 * @param apiSecret Client Secret provided by Arcus Bio.
	 */
	public ArcusBioProvider(String apiKey, String apiSecret, OIDCConfig oidcConfig) {
		this.apiKey = apiKey;
		this.apiSecret = apiSecret;
		this.authUrl = oidcConfig.getAuthorizationEndpoint() + AUTH_URL_DEFAULT_PARAMS;
		this.tokenUrl = oidcConfig.getTokenEndpoint();
	}
	
	@Override
	public String getAuthorizationUrl(String redirectUrl) {
		return new OAuth2Api(authUrl, tokenUrl).
				getAuthorizationUrl(new OAuthConfig(apiKey, null, redirectUrl, null, OIDC_SCOPES, null));
	}

	@Override
	public ProvidedUserInfo validateUserWithProvider(String authorizationCode, String redirectUrl) {
		ValidateArgument.required(authorizationCode, "The authorizationCode");
		ValidateArgument.required(redirectUrl, "The redirectUrl");
		
		try {
			OAuth2Service service = (new OAuth2Api(authUrl, tokenUrl)).
					createService(new OAuthConfig(apiKey, apiSecret, redirectUrl, null, null, null));
			
			AccessTokenResponse accessTokenResponse = service.getAccessToken(null, new Verifier(authorizationCode));
						
			return accessTokenResponse.parseIdToken();
		} catch (OAuthException e) {
			throw new UnauthorizedException(e);
		}
	}

	@Override
	public AliasAndType retrieveProvidersId(String authorizationCode, String redirectUrl) {
		throw new IllegalArgumentException("Retrieving alias is not supported in Synapse for the Arcus Bio OAuth provider.");
	}

	@Override
	public AliasType getAliasType() {
		throw new IllegalArgumentException("Retrieving alias is not supported in Synapse for the Arcus Bio OAuth provider.");
	}
}
