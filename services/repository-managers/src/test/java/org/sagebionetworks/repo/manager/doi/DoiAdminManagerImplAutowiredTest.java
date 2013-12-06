package org.sagebionetworks.repo.manager.doi;

import java.util.UUID;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.sagebionetworks.repo.manager.UserManager;
import org.sagebionetworks.repo.model.AuthorizationConstants;
import org.sagebionetworks.repo.model.Principal;
import org.sagebionetworks.repo.model.UnauthorizedException;
import org.sagebionetworks.repo.model.UserInfo;
import org.sagebionetworks.repo.model.auth.NewUser;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;

@RunWith(SpringJUnit4ClassRunner.class)
@ContextConfiguration(locations = { "classpath:test-context.xml" })
public class DoiAdminManagerImplAutowiredTest {

	@Autowired 
	private DoiAdminManager doiAdminManager;
	
	@Autowired
	private UserManager userManager;
	private UserInfo testAdminUserInfo;
	private UserInfo testUserInfo;

	@Before
	public void before() throws Exception {
		testAdminUserInfo = userManager.getUserInfo(AuthorizationConstants.ADMIN_USER_ID);
		// Create a non admin user
		NewUser nu = new NewUser();
		nu.setEmail(UUID.randomUUID().toString()+"@test.com");
		nu.setPrincipalName(UUID.randomUUID().toString());
		Principal p = userManager.createUser(nu);
		testUserInfo = userManager.getUserInfo(Long.parseLong(p.getId()));
	}
	
	@After
	public void after(){
		if(testUserInfo != null){
			try {
				userManager.delete(testUserInfo.getIndividualGroup().getId());
			} catch (Exception e) {} 
		}
	}

	@Test
	public void testAdmin() throws Exception {
		doiAdminManager.clear(Long.parseLong(testAdminUserInfo.getIndividualGroup().getPrincipalName()));
	}

	@Test(expected=UnauthorizedException.class)
	public void testNotAdmin() throws Exception {
		doiAdminManager.clear(Long.parseLong((testUserInfo.getIndividualGroup().getPrincipalName())));
	}
}
