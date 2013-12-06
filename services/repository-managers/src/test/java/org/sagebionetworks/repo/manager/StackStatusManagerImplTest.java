package org.sagebionetworks.repo.manager;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import java.util.UUID;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.sagebionetworks.repo.model.AuthorizationConstants;
import org.sagebionetworks.repo.model.DatastoreException;
import org.sagebionetworks.repo.model.Principal;
import org.sagebionetworks.repo.model.UnauthorizedException;
import org.sagebionetworks.repo.model.UserInfo;
import org.sagebionetworks.repo.model.auth.NewUser;
import org.sagebionetworks.repo.model.status.StackStatus;
import org.sagebionetworks.repo.model.status.StatusEnum;
import org.sagebionetworks.repo.web.NotFoundException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;

@RunWith(SpringJUnit4ClassRunner.class)
@ContextConfiguration(locations = { "classpath:test-context.xml" })
public class StackStatusManagerImplTest {
	
	@Autowired
	private StackStatusManager stackStatusManager;
	
	@Autowired
	public UserManager userManager;
	
	private Long nonAdminUserId;
	
	@Before
	public void before(){
		// Create a non admin user
		NewUser nu = new NewUser();
		nu.setEmail(UUID.randomUUID().toString()+"@test.com");
		nu.setPrincipalName(UUID.randomUUID().toString());
		Principal p = userManager.createUser(nu);
		nonAdminUserId = Long.parseLong(p.getId());
	}
	
	@After
	public void after(){
		try {
			userManager.delete(nonAdminUserId.toString());
		} catch (Exception e) {} 
	}
	@Test
	public void testGetCurrent(){
		StackStatus status = stackStatusManager.getCurrentStatus();
		assertNotNull(status);
		assertEquals(StatusEnum.READ_WRITE, status.getStatus());
	}
	
	@Test (expected=UnauthorizedException.class)
	public void testNonAdminUpdate() throws Exception {
		// Only an admin can change the status
		StackStatus status = stackStatusManager.getCurrentStatus();
		stackStatusManager.updateStatus(userManager.getUserInfo(nonAdminUserId), status);
	}
	
	@Test 
	public void testAdminUpdate() throws Exception {
		UserInfo adminUserInfo = userManager.getUserInfo(AuthorizationConstants.ADMIN_USER_ID);
		// Only an admin can change the status
		StackStatus status = stackStatusManager.getCurrentStatus();
		status.setPendingMaintenanceMessage("Pending the completion of this test");
		StackStatus updated = stackStatusManager.updateStatus(adminUserInfo, status);
		assertEquals(status, updated);
		// Clear the message
		status = stackStatusManager.getCurrentStatus();
		status.setPendingMaintenanceMessage(null);
		updated = stackStatusManager.updateStatus(adminUserInfo, status);
		assertEquals(status, updated);
	}
	

}
