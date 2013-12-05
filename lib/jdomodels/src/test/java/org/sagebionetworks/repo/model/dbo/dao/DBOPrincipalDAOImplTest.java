package org.sagebionetworks.repo.model.dbo.dao;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.sagebionetworks.repo.model.AuthorizationConstants;
import org.sagebionetworks.repo.model.DatastoreException;
import org.sagebionetworks.repo.model.Principal;
import org.sagebionetworks.repo.model.PrincipalDAO;
import org.sagebionetworks.repo.web.NotFoundException;
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
		Principal ug = userGroupDAO.findPrincipalWithPrincipalName(GROUP_NAME, false);
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
	public void testFindUserWithEmail() throws DatastoreException, NotFoundException{
		Principal user = new Principal();
		user.setPrincipalName("foo.Bar-123");
		String email = "Foo@Bar.com";
		String emailLower = email.toLowerCase();
		user.setEmail(email);
		user.setIsIndividual(true);
		String userId = userGroupDAO.create(user);
		assertNotNull(userId);
		groupsToDelete.add(userId);
		user = userGroupDAO.get(userId);
		// Find the user with the email
		Principal lookup = userGroupDAO.findUserWithEmail(emailLower);
		assertNotNull(lookup);
		assertEquals(user, lookup);
		// Now use the original email
		lookup = userGroupDAO.findUserWithEmail(email);
		assertNotNull(lookup);
		assertEquals(user, lookup);
	}
	
	@Test
	public void testFindPrincipalWithPrincipalNameUser() throws DatastoreException, NotFoundException{
		Principal user = new Principal();
		String principalName = "This.is.a.great.Name";
		String nameUnique = PrincipalUtils.getUniquePrincipalName(principalName);
		user.setPrincipalName("This.is.a.great.Name");
		String email = "Foo@Bar.com";
		user.setEmail(email);
		user.setIsIndividual(true);
		String userId = userGroupDAO.create(user);
		assertNotNull(userId);
		groupsToDelete.add(userId);
		user = userGroupDAO.get(userId);
		// Find the user with original name
		Principal lookup = userGroupDAO.findPrincipalWithPrincipalName(principalName, true);
		assertNotNull(lookup);
		assertEquals(user, lookup);
		// Now find the user with their unique name
		lookup = userGroupDAO.findPrincipalWithPrincipalName(nameUnique, true);
		assertNotNull(lookup);
		assertEquals(user, lookup);
		try{
			 userGroupDAO.findPrincipalWithPrincipalName(nameUnique, false);
			 fail("This is a user so they should only be found with isIndividual=true");
		} catch(NotFoundException e){
			// expected
		}
	}
	
	@Test
	public void testFindPrincipalWithPrincipalNameTeam() throws DatastoreException, NotFoundException{
		Principal team = new Principal();
		String principalName = "This.is.a.great. team Name";
		String nameUnique = PrincipalUtils.getUniquePrincipalName(principalName);
		team.setPrincipalName("This.is.a.great.Name");
		team.setIsIndividual(false);
		String teamId = userGroupDAO.create(team);
		assertNotNull(teamId);
		groupsToDelete.add(teamId);
		team = userGroupDAO.get(teamId);
		// Find the user with original name
		Principal lookup = userGroupDAO.findPrincipalWithPrincipalName(principalName, false);
		assertNotNull(lookup);
		assertEquals(team, lookup);
		// Now find the user with their unique name
		lookup = userGroupDAO.findPrincipalWithPrincipalName(nameUnique, false);
		assertNotNull(lookup);
		assertEquals(team, lookup);
		try{
			 userGroupDAO.findPrincipalWithPrincipalName(nameUnique, true);
			 fail("This is a team so they should only be found with isIndividual=false");
		} catch(NotFoundException e){
			// expected
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
		assertNotNull(userGroupDAO.findUserWithEmail(AuthorizationConstants.ANONYMOUS_USER_EMAIL));
	}
	
	@Test
	public void doesPrincipalExistWithPrincipalName() throws Exception {
		Principal group = new Principal();
		String dispalyPrincipalName = "This is our Group Name";
		String uniqueName = PrincipalUtils.getUniquePrincipalName(dispalyPrincipalName);
		group.setPrincipalName(dispalyPrincipalName);
		String groupId = userGroupDAO.create(group);
		assertNotNull(groupId);
		groupsToDelete.add(groupId);
		assertTrue(userGroupDAO.doesPrincipalExistWithPrincipalName(dispalyPrincipalName));
		// This group should also exist with its unqiue name
		assertTrue(userGroupDAO.doesPrincipalExistWithPrincipalName(uniqueName));
		assertFalse(userGroupDAO.doesPrincipalExistWithPrincipalName(""+(new Random()).nextLong()));
	}
	
	@Test
	public void doesPrincipalExistWithEmail() throws Exception {
		Principal user = new Principal();
		user.setPrincipalName("someUserName");
		String originalEmail = "CaseInsensitive@foo.bar";
		String upperEmail = originalEmail.toUpperCase();
		user.setEmail("Foo@gmail.coM");
		String userId = userGroupDAO.create(user);
		assertNotNull(userId);
		groupsToDelete.add(userId);
		assertTrue(userGroupDAO.doesPrincipalExistWithEmail(originalEmail));
		assertTrue(userGroupDAO.doesPrincipalExistWithEmail(upperEmail));
		assertFalse(userGroupDAO.doesPrincipalExistWithPrincipalName(""+(new Random()).nextLong()));
	}

	
	@Test
	public void testBootstrapUsers() throws DatastoreException, NotFoundException{
		List<Principal> boots = this.userGroupDAO.getBootstrapUsers();
		assertNotNull(boots);
		assertTrue(boots.size() >0);
		// Each should exist
		for(Principal bootUg: boots){
			assertTrue(userGroupDAO.doesPrincipalExistWithPrincipalName(bootUg.getPrincipalName()));
			Principal ug = userGroupDAO.get(bootUg.getId());
			assertEquals(bootUg.getId(), ug.getId());
			assertEquals(bootUg.getPrincipalName(), ug.getPrincipalName());
		}
	}

}
