package org.sagebionetworks.auth.controller;

import static org.sagebionetworks.repo.model.oauth.OAuthScope.authorize;
import static org.sagebionetworks.repo.model.oauth.OAuthScope.modify;
import static org.sagebionetworks.repo.model.oauth.OAuthScope.openid;
import static org.sagebionetworks.repo.model.oauth.OAuthScope.view;

import org.sagebionetworks.auth.HttpAuthUtil;
import org.sagebionetworks.repo.manager.oauth.OAuthClientNotVerifiedException;
import org.sagebionetworks.repo.model.AuthorizationConstants;
import org.sagebionetworks.repo.model.oauth.JsonWebKeySet;
import org.sagebionetworks.repo.model.oauth.OAuthAuthorizationResponse;
import org.sagebionetworks.repo.model.oauth.OAuthClient;
import org.sagebionetworks.repo.model.oauth.OAuthClientAuthorizationHistoryList;
import org.sagebionetworks.repo.model.oauth.OAuthClientIdAndSecret;
import org.sagebionetworks.repo.model.oauth.OAuthClientList;
import org.sagebionetworks.repo.model.oauth.OAuthConsentGrantedResponse;
import org.sagebionetworks.repo.model.oauth.OAuthGrantType;
import org.sagebionetworks.repo.model.oauth.OAuthRefreshTokenInformation;
import org.sagebionetworks.repo.model.oauth.OAuthRefreshTokenInformationList;
import org.sagebionetworks.repo.model.oauth.OAuthTokenRevocationRequest;
import org.sagebionetworks.repo.model.oauth.OIDCAuthorizationRequest;
import org.sagebionetworks.repo.model.oauth.OIDCAuthorizationRequestDescription;
import org.sagebionetworks.repo.model.oauth.OIDCTokenResponse;
import org.sagebionetworks.repo.model.oauth.OIDConnectConfiguration;
import org.sagebionetworks.repo.web.NotFoundException;
import org.sagebionetworks.repo.web.RequiredScope;
import org.sagebionetworks.repo.web.ServiceUnavailableException;
import org.sagebionetworks.repo.web.UrlHelpers;
import org.sagebionetworks.repo.web.rest.doc.ControllerInfo;
import org.sagebionetworks.repo.web.service.ServiceProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * 
The OpenID Connect (OIDC) services implement OAuth 2.0 with the OpenID identity extensions.
 *
 */
@Controller
@ControllerInfo(displayName="OpenID Connect Services", path="auth/v1")
@RequestMapping(UrlHelpers.AUTH_PATH)
public class OpenIDConnectController {
	@Autowired
	private ServiceProvider serviceProvider;
	
	/**
	 * Get the Open ID Configuration ("Discovery Document") for the Synapse OIDC service.
	 * @return
	 * @throws NotFoundException
	 */
	@RequiredScope({})
	@ResponseStatus(HttpStatus.OK)
	@RequestMapping(value = UrlHelpers.WELL_KNOWN_OPENID_CONFIGURATION, method = RequestMethod.GET)
	public @ResponseBody
	OIDConnectConfiguration getOIDCConfiguration(UriComponentsBuilder uriComponentsBuilder) throws NotFoundException {
		return serviceProvider.getOpenIDConnectService().
				getOIDCConfiguration(EndpointHelper.getEndpoint(uriComponentsBuilder));
	}
	
	/**
	 * Get the JSON Web Key Set for the Synapse OIDC service.  This is the set of public keys
	 * used to verify signed JSON Web tokens generated by Synapse.
	 * 
	 * @return the JSON Web Key Set
	 */
	@RequiredScope({})
	@ResponseStatus(HttpStatus.OK)
	@RequestMapping(value = UrlHelpers.OAUTH_2_JWKS, method = RequestMethod.GET)
	public @ResponseBody
	JsonWebKeySet getOIDCJsonWebKeySet() {
		return serviceProvider.getOpenIDConnectService().
				getOIDCJsonWebKeySet();
	}
	
	/**
	 * Create an OAuth 2.0 client.  Note:  After creating the client one must also set the client secret
	 * and have their client verified (See the <a href="https://docs.synapse.org/articles/using_synapse_as_an_oauth_server.html">Synapse OAuth Server Documentation</a>)
	 * 
	 * @param oauthClient the client metadata for the new client
	 * @return
	 * @throws NotFoundException
	 * @throws ServiceUnavailableException if a sector identifer URI is registered but the file cannot be read
	 */
	@RequiredScope({view,modify})
	@ResponseStatus(HttpStatus.CREATED)
	@RequestMapping(value = UrlHelpers.OAUTH_2_CLIENT, method = RequestMethod.POST)
	public @ResponseBody
	OAuthClient createOAuthClient(
			@RequestParam(value = AuthorizationConstants.USER_ID_PARAM) Long userId,
			@RequestBody OAuthClient oauthClient
			) throws NotFoundException, ServiceUnavailableException {
		return serviceProvider.getOpenIDConnectService().
				createOpenIDConnectClient(userId, oauthClient);
	}
	
	/**
	 * Get a secret credential to use when requesting an access token.  
	 * <br>
	 * See the <a href="https://openid.net/specs/openid-connect-core-1_0.html#ClientAuthentication">Open ID Connect specification for client authentication</a>
	 * <br>
	 * Synapse supports 'client_secret_basic' and 'client_secret_post'.
	 * <br>
	 * <em>NOTE:  This request will invalidate any previously issued secrets.</em>
	 * 
	 * @param clientId the ID of the client whose secret is to be generated
	 * @return
	 */
	@RequiredScope({view,modify})
	@ResponseStatus(HttpStatus.CREATED)
	@RequestMapping(value = UrlHelpers.OAUTH_2_CLIENT_SECRET, method = RequestMethod.POST)
	public @ResponseBody 
	OAuthClientIdAndSecret createOAuthClientSecret(
		@RequestParam(value = AuthorizationConstants.USER_ID_PARAM) Long userId,
		@PathVariable(value = UrlHelpers.ID_PATH_VARIABLE) String clientId) {
		return serviceProvider.getOpenIDConnectService().
				createOAuthClientSecret(userId, clientId);
	}
	
	/**
	 * Get an existing OAuth 2.0 client.  When retrieving one's own client,
	 * all metadata is returned.  It is permissible to retrieve a client anonymously
	 * or as a user other than the one who created the client, but only public fields
	 * (name, redirect URIs, and links to the client's site) are returned.
	 * 
	 * @param id the ID of the client to retrieve
	 * @return
	 * @throws NotFoundException
	 */
	@RequiredScope({view})
	@ResponseStatus(HttpStatus.OK)
	@RequestMapping(value = UrlHelpers.OAUTH_2_CLIENT_ID, method = RequestMethod.GET)
	public @ResponseBody
	OAuthClient getOAuthClient(
			@RequestParam(value = AuthorizationConstants.USER_ID_PARAM) Long userId,
			@PathVariable(value = UrlHelpers.ID_PATH_VARIABLE) String id
			) throws NotFoundException {
		return serviceProvider.getOpenIDConnectService().
				getOpenIDConnectClient(userId, id);
	}
	
	/**
	 * 
	 * List the OAuth 2.0 clients created by the current user.
	 * 
	 * @param userId
	 * @param nextPageToken returned along with a page of results, this is passed to 
	 * the server to retrieve the next page.
	 * @return
	 * @throws NotFoundException
	 */
	@RequiredScope({view})
	@ResponseStatus(HttpStatus.OK)
	@RequestMapping(value = UrlHelpers.OAUTH_2_CLIENT, method = RequestMethod.GET)
	public @ResponseBody
	OAuthClientList listOAuthClients(
			@RequestParam(value = AuthorizationConstants.USER_ID_PARAM) Long userId,
			@RequestParam(value = UrlHelpers.NEXT_PAGE_TOKEN_PARAM, required=false) String nextPageToken
			) throws NotFoundException {
		return serviceProvider.getOpenIDConnectService().
				listOpenIDConnectClients(userId, nextPageToken);
	}
	
	/**
	 * Update the metadata for an existing OAuth 2.0 client.
	 * Note, changing the redirect URIs will revert the 'verified' status of the client,
	 * necessitating re-verification.
	 * 
	 * @param oauthClient the client metadata to update
	 * @return
	 * @throws NotFoundException
	 * @throws ServiceUnavailableException 
	 */
	@RequiredScope({view,modify})
	@ResponseStatus(HttpStatus.OK)
	@RequestMapping(value = UrlHelpers.OAUTH_2_CLIENT_ID, method = RequestMethod.PUT)
	public @ResponseBody
	OAuthClient updateOAuthClient(
			@RequestParam(value = AuthorizationConstants.USER_ID_PARAM) Long userId,
			@RequestBody OAuthClient oauthClient
			) throws NotFoundException, ServiceUnavailableException {
		return serviceProvider.getOpenIDConnectService().
				updateOpenIDConnectClient(userId, oauthClient);
	}
	
	/**
	 * Delete OAuth 2.0 client
	 * 
	 * @param id the ID of the client to delete
	 * @throws NotFoundException
	 */
	@RequiredScope({view,modify})
	@ResponseStatus(HttpStatus.OK)
	@RequestMapping(value = UrlHelpers.OAUTH_2_CLIENT_ID, method = RequestMethod.DELETE)
	public void deleteOpenIDClient(
			@RequestParam(value = AuthorizationConstants.USER_ID_PARAM) Long userId,
			@PathVariable(value = UrlHelpers.ID_PATH_VARIABLE) String id
			) throws NotFoundException {
		serviceProvider.getOpenIDConnectService().
				deleteOpenIDConnectClient(userId, id);
	}
	
	/**
	 * Get a user-readable description of the authentication request.
	 * <br>
	 * This request does not need to be authenticated.
	 * 
	 * @param authorizationRequest The request to be described
	 * @return
	 */
	@RequiredScope({})
	@ResponseStatus(HttpStatus.OK)
	@RequestMapping(value = UrlHelpers.OAUTH_2_AUTH_REQUEST_DESCRIPTION, method = RequestMethod.POST)
	public @ResponseBody
	OIDCAuthorizationRequestDescription getAuthenticationRequestDescription(
			@RequestBody OIDCAuthorizationRequest authorizationRequest 
			) {
		return serviceProvider.getOpenIDConnectService().getAuthenticationRequestDescription(authorizationRequest);
	}
	
	/**
	 * Check whether user has already granted consent for the given OAuth client, scope, and claims.
	 * Consent persists for one year.
	 * 
	 * @param userId
	 * @param authorizationRequest The client, scope and claims for which the user may grant consent
	 * @return
	 */
	@RequiredScope({view})
	@ResponseStatus(HttpStatus.OK)
	@RequestMapping(value = UrlHelpers.OAUTH_2_CONSENT_CHECK, method = RequestMethod.POST)
	public @ResponseBody
	OAuthConsentGrantedResponse checkUserAuthorization(
			@RequestParam(value = AuthorizationConstants.USER_ID_PARAM) Long userId,
			@RequestBody OIDCAuthorizationRequest authorizationRequest 
			) {
		OAuthConsentGrantedResponse result = new OAuthConsentGrantedResponse();
		result.setGranted(serviceProvider.getOpenIDConnectService().hasUserGrantedConsent(userId, authorizationRequest));
		return result;
	}
	
	/**
	 * 
	 * Get authorization code for a given client, scopes, response type(s), and extra claim(s).
	 * <br/>
	 * See:
	 * <br/>
	 * <a href="https://openid.net/specs/openid-connect-core-1_0.html#Consent">Open ID Connect specification for consent</a>.
	 * <br/>
	 * <a href="https://openid.net/specs/openid-connect-core-1_0.html#AuthRequest">Open ID Connect specification for the authorization request</a>.
	 *
	 * @param authorizationRequest the request to be authorized
	 * @return
	 * @throws NotFoundException
	 * @throws OAuthClientNotVerifiedException if the client is not verified
	 */
	@RequiredScope({view,modify,authorize})
	@ResponseStatus(HttpStatus.CREATED)
	@RequestMapping(value = UrlHelpers.OAUTH_2_CONSENT, method = RequestMethod.POST)
	public @ResponseBody
	OAuthAuthorizationResponse authorizeClient(
			@RequestParam(value = AuthorizationConstants.USER_ID_PARAM) Long userId,
			@RequestBody OIDCAuthorizationRequest authorizationRequest 
			) throws NotFoundException, OAuthClientNotVerifiedException {
		return serviceProvider.getOpenIDConnectService().authorizeClient(userId, authorizationRequest);
	}
	
	/**
	 * 
	 *  Get access, refresh and id tokens, as per the 
	 *  <a href="https://openid.net/specs/openid-connect-core-1_0.html#TokenResponse">Open ID Connect specification for the token request</a>.
	 * <br/>
	 * <br/>
	 *  Request must include client ID and Secret in Basic Authentication header, i.e. the 'client_secret_basic' authentication method, as per the 
	 *  <a href="https://openid.net/specs/openid-connect-core-1_0.html#ClientAuthentication">Open ID Connect specification for client authentication</a>.
	 *
	 *
	 *  OAuth 2.0 refresh tokens are only issued when the "offline_access" scope is authorized. Refresh tokens issued by Synapse are single-use only,
	 *  and expire if unused for 180 days. Using the refresh_token grant type will cause Synapse to issue a new refresh token in the token response, and the old
	 *  refresh token will become invalid. Some token metadata, such as the unique refresh token ID and configurable token name, will not change when
	 *  a refresh token is rotated in this way.
	 *
	 *  Access tokens issued via a refresh token will also include a 'refresh_token_id' claim that can be used to identify the chain of refresh tokens that the
	 *  access token is related to.
	 *
	 * @param grant_type  authorization_code or refresh_token
	 * @param code required if grant_type is authorization_code
	 * @param redirectUri required if grant_type is authorization_code
	 * @param refresh_token required if grant_type is refresh_token
	 * @param scope only provided if grant_type is refresh_token
	 * @return
	 * @throws NotFoundException
	 * @throws OAuthClientNotVerifiedException if the client is not verified
	 */
	@RequiredScope({})
	@ResponseStatus(HttpStatus.OK)
	@RequestMapping(value = UrlHelpers.OAUTH_2_TOKEN, method = RequestMethod.POST)
	public @ResponseBody
	OIDCTokenResponse getTokenResponse(
			@RequestHeader(value = AuthorizationConstants.OAUTH_VERIFIED_CLIENT_ID_HEADER, required=true) String verifiedClientId,
			@RequestParam(value = AuthorizationConstants.OAUTH2_GRANT_TYPE_PARAM, required=true) OAuthGrantType grant_type,
			@RequestParam(value = AuthorizationConstants.OAUTH2_CODE_PARAM, required=false) String code,
			@RequestParam(value = AuthorizationConstants.OAUTH2_REDIRECT_URI_PARAM, required=false) String redirectUri,
			@RequestParam(value = AuthorizationConstants.OAUTH2_REFRESH_TOKEN_PARAM, required=false) String refresh_token,
			@RequestParam(value = AuthorizationConstants.OAUTH2_SCOPE_PARAM, required=false) String scope,
			UriComponentsBuilder uriComponentsBuilder
			)  throws NotFoundException, OAuthClientNotVerifiedException {
		return serviceProvider.getOpenIDConnectService().getTokenResponse(verifiedClientId, grant_type, code, redirectUri, refresh_token, scope, EndpointHelper.getEndpoint(uriComponentsBuilder));
	}
		
	/**
	 * The result is either a JSON Object or a JSON Web Token, depending on whether the client registered a
	 * signing algorithm in its userinfo_signed_response_alg field, as per the
	 * <a href="https://openid.net/specs/openid-connect-registration-1_0.html#ClientMetadata">Open ID Connect specification</a>.
	 * <br/>
	 * <br/>
	 * Authorization is via an OAuth access token passed as a Bearer token in the Authorization header.
	 * 
	 * @throws NotFoundException
	 * @throws OAuthClientNotVerifiedException if the client is not verified
	 */
	@RequiredScope({openid})
	@ResponseStatus(HttpStatus.OK)
	@RequestMapping(value = UrlHelpers.OAUTH_2_USER_INFO, method = {RequestMethod.GET})
	public @ResponseBody
	Object getUserInfoGET(
			@RequestHeader(value = AuthorizationConstants.SYNAPSE_AUTHORIZATION_HEADER_NAME, required=false) String authorizationHeader,
			UriComponentsBuilder uriComponentsBuilder
			)  throws NotFoundException, OAuthClientNotVerifiedException {
		String accessToken = HttpAuthUtil.getBearerTokenFromAuthorizationHeader(authorizationHeader);
		return serviceProvider.getOpenIDConnectService().getUserInfo(accessToken, EndpointHelper.getEndpoint(uriComponentsBuilder));
	}

	/**
	 * The result is either a JSON Object or a JSON Web Token, depending on whether the client registered a
	 * signing algorithm in its userinfo_signed_response_alg field, as per the
	 * <a href="https://openid.net/specs/openid-connect-registration-1_0.html#ClientMetadata">Open ID Connect specification</a>.
	 * <br/>
	 * <br/>
	 * Authorization is via an OAuth access token passed as a Bearer token in the Authorization header.
	 * 
	 * @throws OAuthClientNotVerifiedException if the client is not verified
	 */
	@RequiredScope({openid})
	@ResponseStatus(HttpStatus.OK)
	@RequestMapping(value = UrlHelpers.OAUTH_2_USER_INFO, method = {RequestMethod.POST})
	public @ResponseBody
	Object getUserInfoPOST(
			@RequestHeader(value = AuthorizationConstants.SYNAPSE_AUTHORIZATION_HEADER_NAME, required=false) String authorizationHeader,
			UriComponentsBuilder uriComponentsBuilder
			)  throws NotFoundException, OAuthClientNotVerifiedException {
		String accessToken = HttpAuthUtil.getBearerTokenFromAuthorizationHeader(authorizationHeader);
		return serviceProvider.getOpenIDConnectService().getUserInfo(accessToken, EndpointHelper.getEndpoint(uriComponentsBuilder));
	}

	/**
	 * Get a paginated list of the OAuth 2 clients that currently have active refresh tokens that grant access to the user's
	 * Synapse identity and/or resources. OAuth 2.0 clients that have no active refresh tokens will not appear in this list.
	 *
	 * @throws NotFoundException
	 * @throws OAuthClientNotVerifiedException if the client is not verified
	 */
	@RequiredScope({view})
	@ResponseStatus(HttpStatus.OK)
	@RequestMapping(value = UrlHelpers.OAUTH_2_AUDIT_CLIENTS, method = RequestMethod.GET)
	public @ResponseBody
	OAuthClientAuthorizationHistoryList getGrantedClientsForUser(
			@RequestParam(value = AuthorizationConstants.USER_ID_PARAM) Long userId,
			@RequestParam(value = UrlHelpers.NEXT_PAGE_TOKEN_PARAM, required=false) String nextPageToken
	) throws NotFoundException {
		return serviceProvider.getOpenIDConnectService().getClientAuthorizationHistory(userId, nextPageToken);
	}

	/**
	 * Get a paginated list of metadata about refresh tokens granted to a particular OAuth 2 client on
	 * behalf of the requesting user. The token itself may not be retrieved.
	 * Refresh tokens that have been revoked will not be included in this list.
	 *
	 * @throws NotFoundException
	 */
	@RequiredScope({view})
	@ResponseStatus(HttpStatus.OK)
	@RequestMapping(value = UrlHelpers.OAUTH_2_AUDIT_CLIENT_TOKENS, method = RequestMethod.GET)
	public @ResponseBody
	OAuthRefreshTokenInformationList getGrantedTokenMetadataForUserClientPair(
			@RequestParam(value = AuthorizationConstants.USER_ID_PARAM) Long userId,
			@PathVariable String clientId,
			@RequestParam(value = UrlHelpers.NEXT_PAGE_TOKEN_PARAM, required=false) String nextPageToken
	) throws NotFoundException {
		return serviceProvider.getOpenIDConnectService().getTokenMetadataForGrantedClient(userId, clientId, nextPageToken);
	}


	/**
	 * Retrieve the metadata for an OAuth 2.0 refresh token as an authenticated Synapse user.
	 *
	 * Clients that wish to retrieve OAuth 2.0 refresh token metadata should use
	 * <a href="${GET.oauth2.token.tokenId.metadata}">GET /oauth2/token/{tokenId}/metadata</a>
	 *
	 * @throws NotFoundException
	 */
	@RequiredScope({view})
	@ResponseStatus(HttpStatus.OK)
	@RequestMapping(value = UrlHelpers.OAUTH_2_AUDIT_TOKENS_ID_METADATA, method = RequestMethod.GET)
	public @ResponseBody
	OAuthRefreshTokenInformation getRefreshTokenMetadataAsUser(
			@RequestParam(value = AuthorizationConstants.USER_ID_PARAM) Long userId,
			@PathVariable String tokenId) throws NotFoundException {
		return serviceProvider.getOpenIDConnectService().getRefreshTokenMetadataAsUser(userId, tokenId);
	}

	/**
	 * Retrieve the metadata for an OAuth 2.0 refresh token. The request should be made as an OAuth 2.0 client using
	 * basic authentication.
	 *
	 * Users that wish to retrieve OAuth 2.0 refresh token metadata should use
	 * <a href="${GET.oauth2.audit.tokens.tokenId.metadata}">GET /oauth2/audit/tokens/{tokenId}/metadata</a>
	 *
	 * @throws NotFoundException
	 */
	@RequiredScope({})
	@ResponseStatus(HttpStatus.OK)
	@RequestMapping(value = UrlHelpers.OAUTH_2_TOKEN_ID_METADATA, method = RequestMethod.GET)
	public @ResponseBody
	OAuthRefreshTokenInformation getRefreshTokenMetadataAsClient(
			@RequestHeader(value = AuthorizationConstants.OAUTH_VERIFIED_CLIENT_ID_HEADER) String verifiedClientId,
			@PathVariable String tokenId) throws NotFoundException {
		return serviceProvider.getOpenIDConnectService().getRefreshTokenMetadataAsClient(verifiedClientId, tokenId);
	}

	/**
	 * Update the metadata for a refresh token. At this time, the only field that a user may set is the 'name' field.
	 *
	 * @throws NotFoundException
	 */
	@RequiredScope({view, modify})
	@ResponseStatus(HttpStatus.OK)
	@RequestMapping(value = UrlHelpers.OAUTH_2_AUDIT_TOKENS_ID_METADATA, method = RequestMethod.PUT)
	public @ResponseBody
	OAuthRefreshTokenInformation updateRefreshTokenMetadata(
			@RequestParam(value = AuthorizationConstants.USER_ID_PARAM) Long userId,
			@PathVariable String tokenId,
			@RequestBody OAuthRefreshTokenInformation metadata) throws NotFoundException {
		return serviceProvider.getOpenIDConnectService().updateRefreshTokenMetadata(userId, tokenId, metadata);
	}

	/**
	 * Revoke all refresh tokens and their related access tokens associated with a particular client and the requesting user.
	 * Note that access tokens that are not associated with refresh tokens cannot be revoked.
	 * Users that want to revoke one refresh token should use <a href="${POST.oauth2.audit.tokens.tokenId.revoke}">POST /oauth2/audit/tokens/{tokenId}/revoke</a>.
	 *
	 * Additionally, access tokens that are not associated with a refresh token cannot be revoked.
	 *
	 * OAuth 2.0 clients wishing to revoke a refresh token should use <a href="${POST.oauth2.revoke}">POST /oauth2/revoke</a>
	 */
	@RequiredScope({authorize})
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@RequestMapping(value = UrlHelpers.OAUTH_2_AUDIT_CLIENT_REVOKE, method = RequestMethod.POST)
	public void revokeRefreshTokensForUserClientPair(
			@RequestParam(value = AuthorizationConstants.USER_ID_PARAM) Long userId,
			@PathVariable String clientId) throws NotFoundException {
		serviceProvider.getOpenIDConnectService().revokeTokensForUserClientPair(userId, clientId);
	}

	/**
	 * Revoke a particular refresh token and all of its related access tokens using its unique ID. The caller must be the the user/resource owner associated with the refresh token.
	 * Note that a client may be in possession of more than one refresh token, so users wishing to revoke all access should use
	 * <a href="${POST.oauth2.audit.grantedClients.clientId.revoke}">POST /oauth2/audit/grantedClients/{clientId}/revoke</a>.
	 *
	 * Additionally, access tokens that are not associated with a refresh token cannot be revoked.
	 *
	 * OAuth 2.0 clients wishing to revoke a refresh token should use <a href="${POST.oauth2.revoke}">POST /oauth2/revoke</a>
	 */
	@RequiredScope({authorize})
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@RequestMapping(value = UrlHelpers.OAUTH_2_AUDIT_TOKENS_ID_REVOKE, method = RequestMethod.POST)
	public void revokeRefreshToken(
			@RequestParam(value = AuthorizationConstants.USER_ID_PARAM) Long userId,
			@PathVariable String tokenId) throws NotFoundException {
		serviceProvider.getOpenIDConnectService().revokeRefreshTokenAsUser(userId, tokenId);
	}

	/**
	 * Revoke a particular refresh token using the token itself, or an associated access token.
	 * The caller must be the the client associated with the refresh token, authenticated using basic authentication.
	 */
	@RequiredScope({})
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@RequestMapping(value = UrlHelpers.OAUTH_2_REVOKE, method = RequestMethod.POST)
	public void revokeToken(
			@RequestHeader(value = AuthorizationConstants.OAUTH_VERIFIED_CLIENT_ID_HEADER, required=true) String verifiedClientId,
			@RequestBody OAuthTokenRevocationRequest revokeRequest) throws NotFoundException {
		serviceProvider.getOpenIDConnectService().revokeToken(verifiedClientId, revokeRequest);
	}

}
