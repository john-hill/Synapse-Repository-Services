package org.sagebionetworks.auth.filter;

import java.io.IOException;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.sagebionetworks.auth.HttpAuthUtil;
import org.sagebionetworks.repo.manager.feature.FeatureManager;
import org.sagebionetworks.repo.model.AuthorizationConstants;
import org.sagebionetworks.repo.model.AuthorizationConstants.BOOTSTRAP_PRINCIPAL;
import org.sagebionetworks.repo.model.auth.AuthenticationDAO;
import org.sagebionetworks.repo.model.feature.Feature;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * A filter that checks that two factor authentication is enabled for any user performing requests against the APIs
 */
@Component("twoFactorAuthFilter")
public class TwoFactorAuthFilter extends OncePerRequestFilter {

	private AuthenticationDAO authDao;
	private FeatureManager featureManager;
	
	public TwoFactorAuthFilter(AuthenticationDAO authDao, FeatureManager featureManager) {
		this.authDao = authDao;
		this.featureManager = featureManager;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest httpRequest, HttpServletResponse httpResponse, FilterChain filterChain) throws ServletException, IOException {
		// The Authentication filter will always inject this parameter with the current user id
		Long userId = Long.parseLong(httpRequest.getParameter(AuthorizationConstants.USER_ID_PARAM));
		
		// The anonymous user is not a conventional user and cannot enable 2fa
		if (!BOOTSTRAP_PRINCIPAL.ANONYMOUS_USER.getPrincipalId().equals(userId) && featureManager.isFeatureEnabled(Feature.ENFORCE_TWO_FA)) {
			if (!authDao.isTwoFactorAuthEnabled(userId)) {
				HttpAuthUtil.rejectWithErrorResponse(httpResponse, "You must enable two factor authentication.", HttpStatus.UNAUTHORIZED);
				return;
			}
		}

		filterChain.doFilter(httpRequest, httpResponse);
		
	}

}
