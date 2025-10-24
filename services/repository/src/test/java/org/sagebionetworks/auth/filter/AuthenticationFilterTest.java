package org.sagebionetworks.auth.filter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.PrintWriter;
import java.util.Collections;
import java.util.Enumeration;

import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletContext;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.sagebionetworks.repo.manager.UserManager;
import org.sagebionetworks.repo.manager.oauth.OIDCTokenManager;
import org.sagebionetworks.repo.manager.oauth.OpenIDConnectManager;
import org.sagebionetworks.repo.model.AuthenticationMethod;
import org.sagebionetworks.repo.model.AuthorizationConstants;
import org.sagebionetworks.repo.model.AuthorizationConstants.BOOTSTRAP_PRINCIPAL;
import org.sagebionetworks.repo.model.principal.PrincipalAlias;
import org.sagebionetworks.repo.service.auth.AuthenticationService;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Header;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.impl.DefaultClaims;

@ExtendWith({MockitoExtension.class})
public class AuthenticationFilterTest {
	
	@Mock
	private HttpServletRequest mockHttpRequest;
	
	@Mock
	private HttpServletResponse mockHttpResponse;
	
	@Mock
	private PrintWriter mockPrintWriter;
	
	@Mock
	private FilterChain mockFilterChain;
	
	@Mock
	private OIDCTokenManager oidcTokenManager;
	
	@Mock
	private OpenIDConnectManager mockOidcManager;
	
	@Captor
	private ArgumentCaptor<HttpServletRequest> requestCaptor;

	@Mock
	private AuthenticationService mockAuthService;
	
	@Mock
	private UserManager mockUserManager;
	
	@InjectMocks
	private AuthenticationFilter filter;

	private static final Long userId = 123456789L;
	private static final String BEARER_TOKEN;
	private PrincipalAlias pa;
	
	static {
		Claims claims = new DefaultClaims();
		claims.setSubject("userId");
		BEARER_TOKEN = Jwts.builder().setClaims(claims).
				setHeaderParam(Header.TYPE, Header.JWT_TYPE).compact() + "signature";
	}
	
	
	@BeforeEach
	public void setupFilter() throws Exception {
		pa = new PrincipalAlias();
		pa.setPrincipalId(userId);

		filter.init(new FilterConfig() {
			public String getFilterName() { 
				return ""; 
			}
			
			public String getInitParameter(String name) {
				return null;
			}
			
			public Enumeration<String> getInitParameterNames() {
				return Collections.emptyEnumeration();
			}
			
			public ServletContext getServletContext() {
				return null;
			}
		});
	}
	
	@Test
	public void testAnonymous() throws Exception {
		// A request with no information provided
		MockHttpServletRequest request = new MockHttpServletRequest();
		MockHttpServletResponse response = new MockHttpServletResponse();
		MockFilterChain filterChain = new MockFilterChain();
		
		filter.doFilter(request, response, filterChain);
		
		// Should default to anonymous
		HttpServletRequest modRequest = (HttpServletRequest) filterChain.getRequest();
		assertNotNull(modRequest);
		String anonymous = modRequest.getParameter(AuthorizationConstants.USER_ID_PARAM);
		assertEquals(BOOTSTRAP_PRINCIPAL.ANONYMOUS_USER.getPrincipalId().toString(), anonymous);
		assertNull(modRequest.getHeader(AuthorizationConstants.SYNAPSE_AUTHENTICATION_METHOD_HEADER_NAME));
	}
	
	/**
	 * Test that we treat an empty session token the same an a null session token.
	 * @throws Exception
	 */
	@Test
	public void testPLFM_2422() throws Exception {
		// A request with no information provided
		MockHttpServletRequest request = new MockHttpServletRequest();
		request.addHeader(AuthorizationConstants.SESSION_TOKEN_PARAM, "");
		MockHttpServletResponse response = new MockHttpServletResponse();
		MockFilterChain filterChain = new MockFilterChain();

		filter.doFilter(request, response, filterChain);
		
		// Should default to anonymous
		HttpServletRequest modRequest = (HttpServletRequest) filterChain.getRequest();
		assertNotNull(modRequest);
		String anonymous = modRequest.getParameter(AuthorizationConstants.USER_ID_PARAM);
		assertTrue(BOOTSTRAP_PRINCIPAL.ANONYMOUS_USER.getPrincipalId().toString().equals(anonymous));
		assertNull(modRequest.getHeader(AuthorizationConstants.SYNAPSE_AUTHENTICATION_METHOD_HEADER_NAME));
	}
	
	
	@Test
	public void testSessionToken_isNull() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest();
		request.addParameter(AuthorizationConstants.SESSION_TOKEN_PARAM, (String) null);
		MockHttpServletResponse response = new MockHttpServletResponse();
		MockFilterChain filterChain = new MockFilterChain();
		
		filter.doFilter(request, response, filterChain);

		// Session token should not be recognized
		HttpServletRequest modRequest = (HttpServletRequest) filterChain.getRequest();
		assertNotNull(modRequest);
		String sessionUsername = modRequest.getParameter(AuthorizationConstants.USER_ID_PARAM);
		assertEquals(BOOTSTRAP_PRINCIPAL.ANONYMOUS_USER.getPrincipalId().toString(), sessionUsername);
		assertNull(modRequest.getHeader(AuthorizationConstants.SYNAPSE_AUTHENTICATION_METHOD_HEADER_NAME));
	}
	
	@Test
	public void testFilter_AccessTokenPassedAsSessionToken() throws Exception {
		when(mockHttpRequest.getHeader(AuthorizationConstants.SESSION_TOKEN_PARAM)).thenReturn(BEARER_TOKEN);
		when(mockHttpRequest.getHeaderNames()).thenReturn(Collections.enumeration(Collections.singletonList("sessionToken")));
		when(mockOidcManager.validateAccessToken(anyString())).thenReturn(""+userId);

		// method under test
		filter.doFilter(mockHttpRequest, mockHttpResponse, mockFilterChain);
		
		verify(mockOidcManager).validateAccessToken(BEARER_TOKEN);
		verify(mockFilterChain).doFilter(requestCaptor.capture(), (ServletResponse)any());
		
		assertEquals(""+userId, requestCaptor.getValue().getParameter(AuthorizationConstants.USER_ID_PARAM));
		assertEquals("Bearer "+BEARER_TOKEN, requestCaptor.getValue().getHeader(AuthorizationConstants.SYNAPSE_AUTHORIZATION_HEADER_NAME));
		assertEquals(AuthenticationMethod.SESSIONTOKEN.name(), requestCaptor.getValue().getHeader(AuthorizationConstants.SYNAPSE_AUTHENTICATION_METHOD_HEADER_NAME));
	}
}
