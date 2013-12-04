package org.sagebionetworks.repo.manager;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import junit.framework.Assert;

import org.junit.Before;
import org.junit.Test;
import org.sagebionetworks.repo.model.AuthenticationDAO;
import org.sagebionetworks.repo.model.TermsOfUseException;
import org.sagebionetworks.repo.model.UnauthorizedException;
import org.sagebionetworks.repo.model.Principal;
import org.sagebionetworks.repo.model.PrincipalDAO;
import org.sagebionetworks.repo.model.auth.Session;
import org.sagebionetworks.securitytools.PBKDF2Utils;

public class AuthenticationManagerImplUnitTest {
	
	private AuthenticationManager authManager;
	private AuthenticationDAO authDAO;
	private PrincipalDAO userGroupDAO;
	
	final String userId = "12345";
	final String username = "AuthManager@test.org";
	final String password = "gro.tset@reganaMhtuA";
	final String sessionToken = "qwertyuiop";
	final byte[] salt = new byte[] { 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0 };
	
	@Before
	public void setUp() throws Exception {
		authDAO = mock(AuthenticationDAO.class);
		when(authDAO.getPasswordSalt(eq(username))).thenReturn(salt);
		when(authDAO.changeSessionToken(eq(userId), eq((String) null))).thenReturn(sessionToken);
		
		userGroupDAO = mock(PrincipalDAO.class);
		Principal ug = new Principal();
		ug.setId(userId);
		when(userGroupDAO.findUserWithEmail(eq(username))).thenReturn(ug);
		
		authManager = new AuthenticationManagerImpl(authDAO, userGroupDAO);
	}

	@Test
	public void testAuthenticateWithPassword() throws Exception {
		Session session = authManager.authenticate(username, password);
		assertEquals(sessionToken, session.getSessionToken());
		
		String passHash = PBKDF2Utils.hashPassword(password, salt);
		verify(authDAO, times(1)).getPasswordSalt(eq(username));
		verify(authDAO, times(1)).checkEmailAndPassword(eq(username), eq(passHash));
	}

	@Test
	public void testAuthenticateWithoutPassword() throws Exception {
		Session session = authManager.authenticate(username, null);
		Assert.assertEquals(sessionToken, session.getSessionToken());
		
		verify(authDAO, never()).getPasswordSalt(any(String.class));
		verify(authDAO, never()).checkEmailAndPassword(any(String.class), any(String.class));
	}

	@Test
	public void testGetSessionToken() throws Exception {
		Session session = authManager.getSessionToken(username);
		Assert.assertEquals(sessionToken, session.getSessionToken());
		
		verify(authDAO, times(1)).getSessionTokenIfValid(eq(username));
		verify(userGroupDAO, times(1)).findUserWithEmail(eq(username));
		verify(authDAO, times(1)).changeSessionToken(eq(userId), eq((String) null));
	}
	
	@Test
	public void testCheckSessionToken() throws Exception {
		when(authDAO.getPrincipalIfValid(eq(sessionToken))).thenReturn(Long.parseLong(userId));
		when(authDAO.getPrincipal(eq(sessionToken))).thenReturn(Long.parseLong(userId));
		when(authDAO.hasUserAcceptedToU(eq(userId))).thenReturn(true);
		String principalId = authManager.checkSessionToken(sessionToken, true).toString();
		Assert.assertEquals(userId, principalId);
		
		// Token matches, but terms haven't been signed
		when(authDAO.hasUserAcceptedToU(eq(userId))).thenReturn(false);
		try {
			authManager.checkSessionToken(sessionToken, true).toString();
			fail();
		} catch (TermsOfUseException e) { }

		// Nothing matches the token
		when(authDAO.getPrincipalIfValid(eq(sessionToken))).thenReturn(null);
		when(authDAO.getPrincipal(eq(sessionToken))).thenReturn(null);
		when(authDAO.hasUserAcceptedToU(eq(userId))).thenReturn(true);
		try {
			authManager.checkSessionToken(sessionToken, true).toString();
			fail();
		} catch (UnauthorizedException e) {
			assertTrue(e.getMessage().contains("invalid"));
		}
		
		// Token matches, but has expired
		when(authDAO.getPrincipal(eq(sessionToken))).thenReturn(Long.parseLong(userId));
		try {
			authManager.checkSessionToken(sessionToken, true).toString();
			fail();
		} catch (UnauthorizedException e) {
			assertTrue(e.getMessage().contains("expired"));
		}
	}
	
	@Test(expected=IllegalArgumentException.class) 
	public void testUnseeTermsOfUse() throws Exception {
		authManager.setTermsOfUseAcceptance(userId, null);
	}
}
