package org.sagebionetworks.repo.web;

import static org.mockito.Matchers.anyObject;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.apache.commons.logging.Log;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;
import org.sagebionetworks.repo.model.AccessControlList;
import org.sagebionetworks.repo.model.auth.ChangePasswordRequest;
import org.sagebionetworks.repo.model.auth.LoginCredentials;
import org.sagebionetworks.repo.model.auth.SecretKey;
import org.sagebionetworks.repo.model.auth.Session;
import org.sagebionetworks.schema.adapter.JSONObjectAdapterException;
import org.sagebionetworks.schema.adapter.org.json.EntityFactory;


public class RequestBodyLoggerTest {

	Log mockLog;
	LogProvider mockProvider;
	
	RequestBodyLogger bodyLogger;
	
	@Before
	public void before(){
		mockLog = Mockito.mock(Log.class);
		mockProvider = Mockito.mock(LogProvider.class);
		when(mockProvider.getLog()).thenReturn(mockLog);
		bodyLogger = new RequestBodyLogger();
		bodyLogger.setLogProvider(mockProvider);
	}
	
	@Test
	public void testNull(){
		bodyLogger.inspectRequestBody(null);
		verify(mockLog, never()).info(anyObject());
	}
	
	@Test
	public void testLogACL() throws JSONObjectAdapterException{
		AccessControlList acl = new AccessControlList();
		acl.setId("12345");
		bodyLogger.inspectRequestBody(acl);
		String aclJSON = EntityFactory.createJSONStringForEntity(acl);
		verify(mockLog).info(aclJSON);
	}
	
	/**
	 * Make sure we do not log passwords
	 */
	@Test
	public void testLogLoginCredentials(){
		LoginCredentials creds = new LoginCredentials();
		creds.setEmail("abc@123.org");
		creds.setPassword("secret");
		bodyLogger.inspectRequestBody(creds);
		verify(mockLog, never()).info(anyObject());
	}
	
	/**
	 * Make sure we do not log passwords
	 */
	@Test
	public void testLogChangePassword(){
		ChangePasswordRequest cpr = new ChangePasswordRequest();
		cpr.setPassword("new password");
		cpr.setSessionToken("a token");
		bodyLogger.inspectRequestBody(cpr);
		verify(mockLog, never()).info(anyObject());
	}
	
	
	/**
	 * Make sure we do not log keys
	 */
	@Test
	public void testLogSecretKey(){
		SecretKey key = new SecretKey();
		key.setSecretKey("my key");
		bodyLogger.inspectRequestBody(key);
		verify(mockLog, never()).info(anyObject());
	}
	
	/**
	 * Make sure we do not log session tokens
	 */
	@Test
	public void testLogSession(){
		Session session = new Session();
		session.setAcceptsTermsOfUse(true);
		session.setSessionToken("a token");
		bodyLogger.inspectRequestBody(session);
		verify(mockLog, never()).info(anyObject());
	}
}
