package org.sagebionetworks.repo.manager;


import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.sagebionetworks.repo.model.AuthorizationConstants;
import org.sagebionetworks.repo.model.AuthorizationConstants.DEFAULT_GROUPS;
import org.sagebionetworks.repo.model.DatastoreException;
import org.sagebionetworks.repo.model.Principal;
import org.sagebionetworks.repo.model.PrincipalDAO;
import org.sagebionetworks.repo.model.UserInfo;
import org.sagebionetworks.repo.model.auth.NewUser;
import org.sagebionetworks.repo.model.dbo.dao.AuthorizationUtils;
import org.sagebionetworks.repo.web.NotFoundException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;

@RunWith(SpringJUnit4ClassRunner.class)
@ContextConfiguration(locations = { "classpath:test-context.xml" })
public class UserManagerImplTest {
	
	@Autowired
	private UserManager userManager;
	
	@Autowired
	private PrincipalDAO userGroupDAO;
	
	private List<String> groupsToDeleteIds = null;
	
	private Principal newUser;
	
	@Before
	public void setUp() throws Exception {
		groupsToDeleteIds = new ArrayList<String>();
		
		// Create a non admin user
		NewUser nu = new NewUser();
		nu.setEmail(UUID.randomUUID().toString()+"@test.com");
		nu.setPrincipalName(UUID.randomUUID().toString());
		newUser = userManager.createUser(nu);
	}

	@After
	public void tearDown() throws Exception {
		for(String groupId: groupsToDeleteIds){
			try {
				userManager.delete(groupId);
			} catch (Exception e) {
				e.printStackTrace();
			}
		}
		
		if(newUser != null){
			try {
				userManager.delete(newUser.getId());
			} catch (Exception e) {} 
		}
	}
	
	@Test
	public void testGetDefaultGroup() throws DatastoreException, NotFoundException{
		// We should be able to get all default groups
		DEFAULT_GROUPS[] array = DEFAULT_GROUPS.values();
		for(DEFAULT_GROUPS group: array){
			Principal userGroup = userManager.getDefaultUserGroup(group);
			assertNotNull(userGroup);
		}
	}
	
	// invoke getUserInfo for Anonymous and check returned userInfo
	@Test
	public void testGetAnonymous() throws Exception {
		UserInfo ui = userManager.getUserInfo(AuthorizationConstants.ADMIN_USER_ID);
		assertTrue(AuthorizationUtils.isUserAnonymous(ui));
		assertTrue(AuthorizationUtils.isUserAnonymous(ui.getIndividualGroup()));
		assertTrue(AuthorizationUtils.isUserAnonymous(ui.getUser().getUserId()));
		assertNotNull(ui.getUser().getId());
		assertEquals(2, ui.getGroups().size());
		assertTrue(ui.getGroups().contains(ui.getIndividualGroup()));
		//assertEquals(ui.getIndividualGroup(), ui.getGroups().iterator().next());
		// They belong to the public group but not the authenticated user's group
		assertTrue(ui.getGroups().contains(userGroupDAO.findPrincipalWithPrincipalName(AuthorizationConstants.DEFAULT_GROUPS.PUBLIC.name(), false)));
		// Anonymous does not belong to the authenticated user's group.
		assertFalse(ui.getGroups().contains(userGroupDAO.findPrincipalWithPrincipalName(AuthorizationConstants.DEFAULT_GROUPS.AUTHENTICATED_USERS.name(), false)));
	}
	
	@Test
	public void testStandardUser() throws Exception {
		
		// Check that the UserInfo is populated
		UserInfo ui = userManager.getUserInfo(Long.parseLong(newUser.getId()));
		assertNotNull(ui.getIndividualGroup());
		assertNotNull(ui.getIndividualGroup().getId());
		assertNotNull(userGroupDAO.findPrincipalWithPrincipalName(newUser.getEmail(), true));
		
		// Should include Public and authenticated users' group.
		assertTrue(ui.getGroups().contains(userGroupDAO.findPrincipalWithPrincipalName(AuthorizationConstants.DEFAULT_GROUPS.PUBLIC.name(), false)));
		assertTrue(ui.getGroups().contains(userGroupDAO.findPrincipalWithPrincipalName(AuthorizationConstants.DEFAULT_GROUPS.AUTHENTICATED_USERS.name(), false)));
		
	}
		
	@Test
	public void testGetAnonymousUserInfo() throws Exception {
		userManager.getUserInfo(AuthorizationConstants.ANONYMOUS_USER_ID);
	}

	@Test
	public void testIdempotency() throws Exception {
		userManager.getUserInfo(AuthorizationConstants.ANONYMOUS_USER_ID);
		userManager.getUserInfo(AuthorizationConstants.ANONYMOUS_USER_ID);
	}
	
}
