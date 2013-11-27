package org.sagebionetworks.repo.model.dbo.dao;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.sagebionetworks.repo.model.AuthorizationConstants;
import org.sagebionetworks.repo.model.Principal;
import org.sagebionetworks.repo.model.PrincipalDAO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;


@RunWith(SpringJUnit4ClassRunner.class)
@ContextConfiguration(locations = { "classpath:jdomodels-test-context.xml" })
public class DBOPrincipalDAOImplTest {
	
	@Autowired
	private PrincipalDAO userGroupDAO;

		
	private List<String> groupsToDelete;
	
	private static final String GROUP_NAME = "DBOUserGroup_TestGroup";

	@Before
	public void setUp() throws Exception {
		groupsToDelete = new ArrayList<String>();
		Principal ug = userGroupDAO.findGroup(GROUP_NAME, false);
		if (ug != null) {
			userGroupDAO.delete(ug.getId());
		}
	}

	@After
	public void tearDown() throws Exception {
		for (String todelete: groupsToDelete) {
			userGroupDAO.delete(todelete);
		}
	}
	
	@Test
	public void testTeamRoundTrip() throws Exception {
		Principal group = new Principal();
		group.setPrincipalName(GROUP_NAME);
		group.setIsIndividual(false);
		long initialCount = userGroupDAO.getCount();
		String groupId = userGroupDAO.create(group);
		assertNotNull(groupId);
		groupsToDelete.add(groupId);
		Principal clone = userGroupDAO.get(groupId);
		assertEquals(groupId, clone.getId());
		assertEquals(GROUP_NAME, clone.getPrincipalName());
		assertEquals(group.getIsIndividual(), clone.getIsIndividual());
		assertEquals(1+initialCount, userGroupDAO.getCount());
	}
	
	@Test
	public void testUserRoundTrip() throws Exception {
		Principal user = new Principal();
		user.setPrincipalName("jamesBond007");
		user.setEmail("Foo@Bar.org");
		user.setIsIndividual(true);
		long initialCount = userGroupDAO.getCount();
		String userId = userGroupDAO.create(user);
		assertNotNull(userId);
		groupsToDelete.add(userId);
		Principal clone = userGroupDAO.get(userId);
		assertEquals(userId, clone.getId());
		assertEquals(user.getPrincipalName(), clone.getPrincipalName());
		assertEquals(true, clone.getIsIndividual());
		assertEquals(1+initialCount, userGroupDAO.getCount());
	}
	
	
	@Test
	public void findAnonymousUser() throws Exception {
		assertNotNull(userGroupDAO.findGroup(AuthorizationConstants.ANONYMOUS_USER_ID, true));
	}
	@Test
	public void testDoesPrincipalExist() throws Exception {
		Principal group = new Principal();
		group.setPrincipalName(GROUP_NAME);
		String groupId = userGroupDAO.create(group);
		assertNotNull(groupId);
		groupsToDelete.add(groupId);
		
		assertTrue(userGroupDAO.doesPrincipalExist(GROUP_NAME));
		
		assertFalse(userGroupDAO.doesPrincipalExist(""+(new Random()).nextLong()));
	}
	@Test
	public void testGetGroupsByNamesEmptySet()  throws Exception {

		Collection<String> groupNames = new HashSet<String>();
		Map<String,Principal> map =  userGroupDAO.getGroupsByNames(groupNames);
		assertTrue(map.isEmpty());
	}

	@Test
	public void testGetGroupsByNames() throws Exception {
		Collection<Principal> allGroups = null; 
		allGroups = userGroupDAO.getAll();
		int startingCount =  allGroups.size();
	
		Collection<String> groupNames = new HashSet<String>();
		groupNames.add(GROUP_NAME);
		Map<String,Principal> map = null;
		map = userGroupDAO.getGroupsByNames(groupNames);
		assertFalse("initial groups: "+allGroups+"  getGroupsByNames("+GROUP_NAME+") returned "+map.keySet(), map.containsKey(GROUP_NAME));
//		assertFalse(map.containsKey(GROUP_NAME));
			
		Principal group = new Principal();
		group.setPrincipalName(GROUP_NAME);
		String groupId = userGroupDAO.create(group);
		assertNotNull(groupId);
		groupsToDelete.add(groupId);
		allGroups = userGroupDAO.getAll();
		assertEquals(allGroups.toString(), (startingCount+1), allGroups.size()); // now the new group should be there
			
		groupNames.clear();
		groupNames.add(GROUP_NAME);	
		map = userGroupDAO.getGroupsByNames(groupNames);
		assertTrue(groupNames.toString()+" -> "+map.toString(), map.containsKey(GROUP_NAME));
		
		
		groupNames.clear(); 
		// Add one of the default groups
		groupNames.add(AuthorizationConstants.DEFAULT_GROUPS.AUTHENTICATED_USERS.name());
		map = userGroupDAO.getGroupsByNames(groupNames);
		assertTrue(map.toString(), map.containsKey(AuthorizationConstants.DEFAULT_GROUPS.AUTHENTICATED_USERS.name()));

		// try the paginated call
		List<Principal> groups = userGroupDAO.getInRange(0, startingCount+100, false);
		List<String> omit = new ArrayList<String>();
		omit.add(AuthorizationConstants.DEFAULT_GROUPS.AUTHENTICATED_USERS.name());
		List<Principal> groupsButOne = userGroupDAO.getInRangeExcept(0, startingCount+100, false, omit);
		assertEquals(groups.size(), groupsButOne.size()+1);
	}
	
//	@Test
//	public void testBootstrapUsers() throws DatastoreException, NotFoundException{
//		List<Principal> boots = this.userGroupDAO.getBootstrapUsers();
//		assertNotNull(boots);
//		assertTrue(boots.size() >0);
//		// Each should exist
//		for(Principal bootUg: boots){
//			assertTrue(userGroupDAO.doesPrincipalExist(bootUg.getName()));
//			UserGroup ug = userGroupDAO.get(bootUg.getId());
//			assertEquals(bootUg.getId(), ug.getId());
//			assertEquals(bootUg.getName(), ug.getName());
//		}
//	}

}
