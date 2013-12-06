package org.sagebionetworks.repo.model.dbo.dao;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.sagebionetworks.StackConfiguration;
import org.sagebionetworks.ids.NamedIdGenerator;
import org.sagebionetworks.repo.model.AuthorizationConstants;
import org.sagebionetworks.repo.model.DatastoreException;
import org.sagebionetworks.repo.model.GroupMembersDAO;
import org.sagebionetworks.repo.model.Principal;
import org.sagebionetworks.repo.model.PrincipalDAO;
import org.sagebionetworks.repo.model.dbo.DBOBasicDao;
import org.sagebionetworks.repo.web.NotFoundException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;


@RunWith(SpringJUnit4ClassRunner.class)
@ContextConfiguration(locations = { "classpath:jdomodels-test-context.xml" })

public class DBOGroupMembersDAOImplTest {
	
	@Autowired
	private GroupMembersDAO groupMembersDAO;
	
	@Autowired
	private PrincipalDAO userGroupDAO;
	
	@Autowired
	private DBOBasicDao basicDAO;
	
	@Autowired
	private NamedIdGenerator idGenerator;
	
	private List<String> groupsToDelete;
	
	private Principal testGroup;
	private Principal testUserOne;
	private Principal testUserTwo;
	private Principal testUserThree;

	@Before
	public void setUp() throws Exception {
		groupsToDelete = new ArrayList<String>();
		
		testGroup = createTestGroup("" + UUID.randomUUID(), false);
		testUserOne = createTestGroup("" + UUID.randomUUID(), true);
		testUserTwo = createTestGroup("" + UUID.randomUUID(), true);
		testUserThree = createTestGroup("" + UUID.randomUUID(), true);
	}

	@After
	public void tearDown() throws Exception {
		for (String toDelete : groupsToDelete) {
			try {
				userGroupDAO.delete(toDelete);
			} catch (NotFoundException e) {
				// Good, not in DB
			}
		}
	}
	
	private Principal createTestGroup(String name, boolean isIndividual) throws Exception {		
		Principal group = new Principal();
		group.setPrincipalName(name);
		group.setIsIndividual(isIndividual);
		String id = null;
		try {
			id = userGroupDAO.create(group);
		} catch (DatastoreException e) {
			// Already exists
			id = userGroupDAO.findPrincipalWithPrincipalName(name, false).getId();
		}
		assertNotNull(id);
		groupsToDelete.add(id);
		return userGroupDAO.get(id);
	}
	
	@Test
	public void testGetters() throws Exception {
		List<Principal> members = groupMembersDAO.getMembers(testGroup.getId());
		assertEquals("No members initially", 0, members.size());
		
		List<Principal> groups = groupMembersDAO.getUsersGroups(testUserOne.getId());
		assertEquals("No groups initially", 0, groups.size());
	}
	
	@Test
	public void testAddMembers() throws Exception {
		// Add users to the test group
		List<String> adder = new ArrayList<String>();
		
		// Empty list should work
		groupMembersDAO.addMembers(testGroup.getId(), adder);
		
		// Repeated entries should work
		adder.add(testUserOne.getId());
		adder.add(testUserTwo.getId());
		adder.add(testUserThree.getId());
		adder.add(testUserThree.getId());
		adder.add(testUserThree.getId());
		
		// Insertion is idempotent
		groupMembersDAO.addMembers(testGroup.getId(), adder);
		groupMembersDAO.addMembers(testGroup.getId(), adder); 
		
		// Validate the addition worked
		List<Principal> newMembers = groupMembersDAO.getMembers(testGroup.getId());
		assertEquals("Number of users should match", 3, newMembers.size());
		
		// Each user should be present, with unaltered etags
		assertTrue("User one should be in the retrieved member list", newMembers.contains(testUserOne));
		assertTrue("User two should be in the retrieved member list", newMembers.contains(testUserTwo));
		assertTrue("User three should be in the retrieved member list", newMembers.contains(testUserThree));
		
		// Verify that the parent group's etag has changed
		Principal updatedTestGroup = userGroupDAO.get(testGroup.getId());
		assertTrue("Etag must have changed", !testGroup.getEtag().equals(updatedTestGroup.getEtag()));
	}
	
	@Test(expected=IllegalArgumentException.class)
	public void testAddGroupToGroup() throws Exception {
		List<String> adder = new ArrayList<String>();
		adder.add(testUserOne.getId());
		adder.add(testUserTwo.getId());
		adder.add(testUserThree.getId());
		
		// Try to sneak this one into the addition
		adder.add(testGroup.getId());
		
		groupMembersDAO.addMembers(testGroup.getId(), adder); 
	}
	
	@Test(expected=IllegalArgumentException.class)
	public void testAddMemberToMember() throws Exception {
		List<String> adder = new ArrayList<String>();
		adder.add(testUserOne.getId());
		adder.add(testUserTwo.getId());
		adder.add(testUserThree.getId());
		
		// Can't add members to individuals
		groupMembersDAO.addMembers(testUserOne.getId(), adder); 
	}
	
	@Test
	public void testRemoveMembers() throws Exception {
		// Setup the group
		List<String> adder = new ArrayList<String>();
		adder.add(testUserOne.getId());
		adder.add(testUserTwo.getId());
		adder.add(testUserThree.getId());

		groupMembersDAO.addMembers(testGroup.getId(), adder);
		List<Principal> newMembers = groupMembersDAO.getMembers(testGroup.getId());
		assertEquals("Number of users should match", 3, newMembers.size());
		
		// Verify that the parent group's etag has changed
		Principal updatedTestGroup = userGroupDAO.get(testGroup.getId());
		assertTrue("Etag must have changed", !testGroup.getEtag().equals(updatedTestGroup.getEtag()));
		
		// Remove all but one of the users from the group
		List<String> remover = new ArrayList<String>(adder);
		String antisocial = remover.remove(0);
		groupMembersDAO.removeMembers(testGroup.getId(), remover);
		List<Principal> fewerMembers = groupMembersDAO.getMembers(testGroup.getId());
		assertEquals("Number of users should match", 1, fewerMembers.size());
		fewerMembers.get(0).setCreationDate(null);
		fewerMembers.get(0).setEtag(null);
		assertEquals("Last member should match the one removed from the DTO", antisocial, fewerMembers.get(0).getId());
		
		// Verify that the parent group's etag has changed
		updatedTestGroup = userGroupDAO.get(testGroup.getId());
		assertTrue("Etag must have changed", !testGroup.getEtag().equals(updatedTestGroup.getEtag()));
		
		// Remove the last guy from the group
		remover.clear();
		remover.add(antisocial);
		groupMembersDAO.removeMembers(testGroup.getId(), remover);
		List<Principal> emptyGroup = groupMembersDAO.getMembers(testGroup.getId());
		assertEquals("Number of users should match", 0, emptyGroup.size());
		
		// Verify that the parent group's etag has changed
		updatedTestGroup = userGroupDAO.get(testGroup.getId());
		assertTrue("Etag must have changed", !testGroup.getEtag().equals(updatedTestGroup.getEtag()));
	}
	
	@Test
	public void testBootstrapGroups() throws Exception {
		String adminGroupId = userGroupDAO.findPrincipalWithPrincipalName(AuthorizationConstants.ADMIN_GROUP_NAME, false).getId();
		List<Principal> admins = groupMembersDAO.getMembers(adminGroupId);
		Set<String> adminNames = new HashSet<String>();
		for (Principal ug : admins) {
			adminNames.add(ug.getPrincipalName());
		}
		
		assertTrue(adminNames.contains(AuthorizationConstants.MIGRATION_USER_NAME));
	}
}
