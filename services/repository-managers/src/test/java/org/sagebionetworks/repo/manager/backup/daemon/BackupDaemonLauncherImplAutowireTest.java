package org.sagebionetworks.repo.manager.backup.daemon;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.sagebionetworks.repo.manager.NodeManager;
import org.sagebionetworks.repo.manager.UserManager;
import org.sagebionetworks.repo.model.AuthorizationConstants;
import org.sagebionetworks.repo.model.DatastoreException;
import org.sagebionetworks.repo.model.Principal;
import org.sagebionetworks.repo.model.UnauthorizedException;
import org.sagebionetworks.repo.model.UserInfo;
import org.sagebionetworks.repo.model.auth.NewUser;
import org.sagebionetworks.repo.web.NotFoundException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;

@RunWith(SpringJUnit4ClassRunner.class)
@ContextConfiguration(locations = { "classpath:test-context.xml" })
public class BackupDaemonLauncherImplAutowireTest {

	@Autowired
	private BackupDaemonLauncher backupDaemonLauncher;
	
	@Autowired
	private UserManager userManager;
	
	@Autowired
	public NodeManager nodeManager;
	
	private List<String> nodesToDelete = null;
	private UserInfo adminUserInfo;
	private UserInfo nonAdminInfo;
	
	@Before
	public void before() throws Exception {
		nodesToDelete = new ArrayList<String>();
		adminUserInfo = userManager.getUserInfo(AuthorizationConstants.ADMIN_USER_ID);
		
		// Create a non admin user
		NewUser nu = new NewUser();
		nu.setEmail(UUID.randomUUID().toString()+"@test.com");
		nu.setPrincipalName(UUID.randomUUID().toString());
		Principal p = userManager.createUser(nu);
		
		nonAdminInfo = userManager.getUserInfo(Long.parseLong(p.getId()));
	}
	
	@After
	public void after() throws Exception {
		for (String id: nodesToDelete) {
			nodeManager.delete(adminUserInfo, id);
		}
		if(nonAdminInfo != null){
			try {
				userManager.delete(nonAdminInfo.getIndividualGroup().getId());
			} catch (Exception e) {} 
		}
	}
	
	@Test (expected=UnauthorizedException.class)
	public void testNonAdminUserGetStatus() throws UnauthorizedException, DatastoreException, NotFoundException{
		// A non-admin should not be able to start the daemon
		backupDaemonLauncher.getStatus(nonAdminInfo, "123");
	}
}
