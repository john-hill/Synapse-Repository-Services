package org.sagebionetworks.repo.web;

import static org.sagebionetworks.repo.model.AuthorizationConstants.SYNAPSE_AUTHORIZATION_HEADER_NAME;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.sagebionetworks.auth.HttpAuthUtil;
import org.sagebionetworks.repo.manager.oauth.ClaimsJsonUtil;
import org.sagebionetworks.repo.manager.oauth.OIDCTokenHelper;
import org.sagebionetworks.repo.model.AuthorizationConstants;
import org.sagebionetworks.repo.model.oauth.OAuthScope;
import org.sagebionetworks.repo.web.controller.RequiredScope;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwsHeader;
import io.jsonwebtoken.Jwt;

/*
 * For requests which require authentication (have a 'userId' parameter) and are not anonymous, 
 * the scope in the access token is compared to the scope in the RequiredScope annotation 
 * (default to full scope) and 403 status is returned if the required scope is not present.
 */
public class OAuthScopeInterceptor implements HandlerInterceptor {

	/*
	 * If a handler is not annotated with RequiredScope then, by default, it requires the following
	 */
	private static final Set<OAuthScope> DEFAULT_SCOPES;
	static {
		DEFAULT_SCOPES = new HashSet<OAuthScope>(Arrays.asList(OAuthScope.values()));
		DEFAULT_SCOPES.remove(OAuthScope.openid);
	}

	private static final String ERROR_MESSAGE_PREFIX  = "Request lacks scope(s) required by this service: ";
	
	@Autowired
	private OIDCTokenHelper oidcTokenHelper;
	
	public static boolean hasUserIdParameterOrAccessTokenHeader(HandlerMethod handlerMethod) {
		for (MethodParameter methodParameter : handlerMethod.getMethodParameters()) {
			RequestParam requestParam = methodParameter.getParameterAnnotation(RequestParam.class);
			if (requestParam!=null && requestParam.value().equals(AuthorizationConstants.USER_ID_PARAM)) {
				return true;
			}
			RequestHeader requestHeader = methodParameter.getParameterAnnotation(RequestHeader.class);
			if (requestHeader!=null && requestHeader.value().equals(SYNAPSE_AUTHORIZATION_HEADER_NAME)) {
				return true;
			}
		}
		return false;
	}
	
	public static boolean isAnonymous(HttpServletRequest request) {
		String userIdRequestParameter = request.getParameter(AuthorizationConstants.USER_ID_PARAM);
		return userIdRequestParameter == null ||
				AuthorizationConstants.BOOTSTRAP_PRINCIPAL.ANONYMOUS_USER.getPrincipalId()
					.equals(Long.parseLong(userIdRequestParameter));
	}

	@Override
	public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
			throws Exception {
		
		// anonymous requests do not need to have scope checked, they have the same 
		// access that unauthenticated requests have
		if (isAnonymous(request)) {
			return true;
		}
		
		if (!(handler instanceof HandlerMethod)) {
			throw new IllegalStateException("Ths HandlerInterceptor should only be applied to HandlerMethods, but this handler is a "+handler.getClass());
		}
		
		HandlerMethod handlerMethod = (HandlerMethod) handler;

		// if no 'userId' parameter or access token header then this is 
		// not an authenticated request, and no scope is required
		if (!hasUserIdParameterOrAccessTokenHeader(handlerMethod)) {
			return true;
		}

		// if no scopes are specified by an annotation on the method, then we use these defaults
		Set<OAuthScope> requiredScopes = new TreeSet<OAuthScope>(DEFAULT_SCOPES);
		
		RequiredScope requiredScopeAnnotation = handlerMethod.getMethodAnnotation(RequiredScope.class);
		if (requiredScopeAnnotation != null) {
			requiredScopes = new HashSet<OAuthScope>(Arrays.asList(requiredScopeAnnotation.value()));
		}

		List<OAuthScope> requestScopes = Collections.EMPTY_LIST;
		String synapseAuthorizationHeader = request.getHeader(SYNAPSE_AUTHORIZATION_HEADER_NAME);
		String accessToken = HttpAuthUtil.getBearerTokenFromAuthorizationHeader(synapseAuthorizationHeader);
		if (accessToken!=null) {
			try {
				Jwt<JwsHeader, Claims> jwt = oidcTokenHelper.parseJWT(accessToken);
				requestScopes = ClaimsJsonUtil.getScopeFromClaims(jwt.getBody());
			} catch (IllegalArgumentException e) {
				HttpAuthUtil.reject(response, e.getMessage(), HttpStatus.UNAUTHORIZED);
				return false;
			}
		}

		requiredScopes.removeAll(requestScopes);
		if (requiredScopes.isEmpty()) {
			return true;
		}
		
		Set<String> missingScopes = new TreeSet<String>();
		for (OAuthScope scope: requiredScopes) {
			missingScopes.add(scope.name());
		}
		StringBuilder sb = new StringBuilder(ERROR_MESSAGE_PREFIX);
		sb.append(String.join(", ", missingScopes));

		HttpAuthUtil.reject(response, sb.toString(), HttpStatus.FORBIDDEN);
		
		return false;
	}

}