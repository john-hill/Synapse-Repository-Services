package org.sagebionetworks.repo.web.controller;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.sagebionetworks.evaluation.dao.EvaluationDAO;
import org.sagebionetworks.evaluation.dao.ParticipantDAO;
import org.sagebionetworks.evaluation.dao.SubmissionDAO;
import org.sagebionetworks.evaluation.dao.SubmissionStatusDAO;
import org.sagebionetworks.evaluation.manager.EvaluationPermissionsManager;
import org.sagebionetworks.evaluation.model.Evaluation;
import org.sagebionetworks.evaluation.model.EvaluationStatus;
import org.sagebionetworks.evaluation.model.Participant;
import org.sagebionetworks.evaluation.model.Submission;
import org.sagebionetworks.evaluation.model.SubmissionStatus;
import org.sagebionetworks.evaluation.model.SubmissionStatusEnum;
import org.sagebionetworks.evaluation.model.UserEvaluationPermissions;
import org.sagebionetworks.repo.manager.NodeManager;
import org.sagebionetworks.repo.manager.UserManager;
import org.sagebionetworks.repo.model.ACCESS_TYPE;
import org.sagebionetworks.repo.model.AccessControlList;
import org.sagebionetworks.repo.model.AuthorizationConstants;
import org.sagebionetworks.repo.model.AuthorizationConstants.DEFAULT_GROUPS;
import org.sagebionetworks.repo.model.DatastoreException;
import org.sagebionetworks.repo.model.EntityType;
import org.sagebionetworks.repo.model.Node;
import org.sagebionetworks.repo.model.NodeDAO;
import org.sagebionetworks.repo.model.PaginatedResults;
import org.sagebionetworks.repo.model.Principal;
import org.sagebionetworks.repo.model.ResourceAccess;
import org.sagebionetworks.repo.model.UnauthorizedException;
import org.sagebionetworks.repo.model.UserInfo;
import org.sagebionetworks.repo.model.auth.NewUser;
import org.sagebionetworks.repo.model.jdo.KeyFactory;
import org.sagebionetworks.repo.web.NotFoundException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;

@RunWith(SpringJUnit4ClassRunner.class)
@ContextConfiguration(locations = { "classpath:test-context.xml" })
public class EvaluationControllerAutowiredTest {
	
	@Autowired
	private EntityServletTestHelper entityServletHelper;
	@Autowired
	private UserManager userManager;
	@Autowired
	private NodeManager nodeManager;
	@Autowired
	private EvaluationPermissionsManager evalPermissionsManager;
	@Autowired
	private EvaluationDAO evaluationDAO;
	@Autowired
	private ParticipantDAO participantDAO;
	@Autowired
	private SubmissionDAO submissionDAO;
	@Autowired
	private SubmissionStatusDAO submissionStatusDAO;
	@Autowired
	private NodeDAO nodeDAO;
	
	private Long ownerId;
	private Long userId;
	
	private Evaluation eval1;
	private Evaluation eval2;
	private Participant part1;
	private Participant part2;
	private Submission sub1;
	private Submission sub2;
	
	private List<String> evaluationsToDelete;
	private List<Participant> participantsToDelete;
	private List<String> submissionsToDelete;
	private List<String> nodesToDelete;
	
	@Before
	public void before() throws DatastoreException, NotFoundException {
		evaluationsToDelete = new ArrayList<String>();
		participantsToDelete = new ArrayList<Participant>();
		submissionsToDelete = new ArrayList<String>();
		nodesToDelete = new ArrayList<String>();
		
		// get user IDs
		ownerId = AuthorizationConstants.ADMIN_USER_ID;
		// Create a non admin user
		NewUser nu = new NewUser();
		nu.setEmail(UUID.randomUUID().toString()+"@test.com");
		nu.setPrincipalName(UUID.randomUUID().toString());
		Principal p = userManager.createUser(nu);
		userId = Long.parseLong(p.getId());
		
		// initialize Evaluations
		eval1 = new Evaluation();
		eval1.setName("name");
		eval1.setDescription("description");
        eval1.setContentSource(KeyFactory.SYN_ROOT_ID);
        eval1.setStatus(EvaluationStatus.PLANNED);
        eval2 = new Evaluation();
		eval2.setName("name2");
		eval2.setDescription("description");
        eval2.setContentSource(KeyFactory.SYN_ROOT_ID);
        eval2.setStatus(EvaluationStatus.PLANNED);
        
        // initialize Participants
        part1 = new Participant();
        part1.setUserId(ownerId.toString());
        part2 = new Participant();
        part2.setUserId(userId.toString());		
        
        // initialize Submissions
        sub1 = new Submission();
        sub1.setName("submission1");
        sub1.setVersionNumber(1L);
        sub1.setSubmitterAlias("Team Awesome!");
        sub2 = new Submission();
        sub2.setName("submission2");
        sub2.setVersionNumber(1L);
        sub2.setSubmitterAlias("Team Even Better!");
	}
	
	@After
	public void after() {
		// clean up submissions
		for (String id : submissionsToDelete) {
			try {
				submissionStatusDAO.delete(id);
			} catch (Exception e) {}
			try {
				submissionDAO.delete(id);
			} catch (Exception e) {}
		}
		
		// clean up participants
		for (Participant part : participantsToDelete) {
			try {
				participantDAO.delete(part.getUserId(), part.getEvaluationId());
			} catch (Exception e) {}
		}
		
		// clean up evaluations
		for (String id : evaluationsToDelete) {
			try {
				UserInfo admin = userManager.getUserInfo(AuthorizationConstants.ADMIN_USER_ID);
				evalPermissionsManager.deleteAcl(admin, id);
				evaluationDAO.delete(id);
			} catch (Exception e) {}
		}
		
		// clean up nodes
		for (String id : nodesToDelete) {
			try {
				nodeDAO.delete(id);
			} catch (Exception e) {}
		}
		

		if(userId != null){
			try {
				userManager.delete(userId.toString());
			} catch (Exception e) {} 
		}
	}
	
	@Test
	public void testEvaluationRoundTrip() throws Exception {
		long initialCount = entityServletHelper.getEvaluationCount(ownerId);
		
		// Create
		eval1 = entityServletHelper.createEvaluation(eval1, ownerId);		
		assertNotNull(eval1.getEtag());
		assertNotNull(eval1.getId());
		evaluationsToDelete.add(eval1.getId());
		
		//can read
		Boolean canRead = entityServletHelper.canAccess(ownerId, eval1.getId(), ACCESS_TYPE.READ);
		assertTrue(canRead);
		//test user cannot read
		canRead = entityServletHelper.canAccess(userId, eval1.getId(), ACCESS_TYPE.READ);
		assertFalse(canRead);
		
		// Read
		Evaluation fetched = entityServletHelper.getEvaluation(ownerId, eval1.getId());
		assertEquals(eval1, fetched);
		try {
			fetched = entityServletHelper.getEvaluation(userId, eval1.getId());
			fail();
		} catch (UnauthorizedException e) {
			// Expected
		}

		// Find
		fetched = entityServletHelper.findEvaluation(ownerId, eval1.getName());
		assertEquals(eval1, fetched);
		try {
			fetched = entityServletHelper.findEvaluation(userId, eval1.getName());
			assertEquals(eval1, fetched);
			fail();
		} catch (NotFoundException e) {
			// Expected
		}
		
		//can update
		Boolean canUpdate = entityServletHelper.canAccess(ownerId, eval1.getId(), ACCESS_TYPE.UPDATE);
		assertTrue(canUpdate);
		//test user can't update
		canUpdate = entityServletHelper.canAccess(userId, eval1.getId(), ACCESS_TYPE.UPDATE);
		assertFalse(canUpdate);
		
		// Update
		fetched.setDescription(eval1.getDescription() + " (modified)");
		Evaluation updated = entityServletHelper.updateEvaluation(fetched, ownerId);
		assertFalse("eTag was not updated", updated.getEtag().equals(fetched.getEtag()));
		fetched.setEtag(updated.getEtag());
		assertEquals(fetched, updated);		
		assertEquals(initialCount + 1, entityServletHelper.getEvaluationCount(ownerId));
		
		// Delete
		entityServletHelper.deleteEvaluation(eval1.getId(), ownerId);
		try {
			entityServletHelper.getEvaluation(ownerId, eval1.getId());
			fail("Delete failed");
		} catch (NotFoundException e) {
			// expected
		}
		assertEquals(initialCount, entityServletHelper.getEvaluationCount(ownerId));
	}
	
	@Test
	public void testParticipantRoundTrip() throws Exception {
		eval1.setStatus(EvaluationStatus.OPEN);
		eval1 = entityServletHelper.createEvaluation(eval1, ownerId);
		evaluationsToDelete.add(eval1.getId());
		
		// create -- can't join yet
		try {
			part1 = entityServletHelper.createParticipant(userId, eval1.getId());
			fail();
		} catch (UnauthorizedException e) {
			// Expected
		}
		
		// open the evaluation to join
		AccessControlList acl = entityServletHelper.getEvaluationAcl(ownerId, eval1.getId());
		{
			Set<ACCESS_TYPE> accessSet = new HashSet<ACCESS_TYPE>(2);
			accessSet.add(ACCESS_TYPE.PARTICIPATE);
			accessSet.add(ACCESS_TYPE.READ);
			ResourceAccess ra = new ResourceAccess();
			ra.setAccessType(accessSet);
			String userId = userManager.getDefaultUserGroup(DEFAULT_GROUPS.AUTHENTICATED_USERS).getId();
			ra.setPrincipalId(Long.parseLong(userId));
			acl.getResourceAccess().add(ra);
		}
		{
			// this is the new way to add a participant
			Set<ACCESS_TYPE> accessSet = new HashSet<ACCESS_TYPE>(1);
			accessSet.add(ACCESS_TYPE.SUBMIT);
			ResourceAccess ra = new ResourceAccess();
			ra.setAccessType(accessSet);
			assertNotNull(userId);
			ra.setPrincipalId(userId);
			acl.getResourceAccess().add(ra);
		}
		acl = entityServletHelper.updateEvaluationAcl(ownerId, acl);
		assertNotNull(acl);

		// create
		long initialCount = entityServletHelper.getParticipantCount(ownerId, eval1.getId());
		part1 = entityServletHelper.createParticipant(userId, eval1.getId());
		assertNotNull(part1.getCreatedOn());
		participantsToDelete.add(part1);
		assertEquals(initialCount + 1, entityServletHelper.getParticipantCount(ownerId, eval1.getId()));

		// query, just checking basic wiring
		PaginatedResults<Evaluation> pr = entityServletHelper.getAvailableEvaluations(userId);
		assertEquals(1L, pr.getTotalNumberOfResults());
		// get the new etag (changed when participant was added?)
		eval1 = entityServletHelper.getEvaluation(userId, eval1.getId());
		assertEquals(eval1, pr.getResults().iterator().next());
		
		// read
		Participant clone = entityServletHelper.getParticipant(userId, userId, eval1.getId());
		assertEquals(part1, clone);
		
		// delete
		entityServletHelper.deleteParticipant(ownerId, userId, eval1.getId());
		try {
			entityServletHelper.getParticipant(ownerId, userId, eval1.getId());
			fail("Failed to delete Participant " + part1.toString());
		} catch (NotFoundException e) {
			// expected
		}
		assertEquals(initialCount, entityServletHelper.getParticipantCount(ownerId, eval1.getId()));
	}
	
	@Test
	public void testSubmissionRoundTrip() throws Exception {
		eval1.setStatus(EvaluationStatus.OPEN);
		eval1 = entityServletHelper.createEvaluation(eval1, ownerId);
		evaluationsToDelete.add(eval1.getId());
		
		// open the evaluation to join
		Set<ACCESS_TYPE> accessSet = new HashSet<ACCESS_TYPE>(12);
		accessSet.add(ACCESS_TYPE.PARTICIPATE);
		accessSet.add(ACCESS_TYPE.SUBMIT);
		accessSet.add(ACCESS_TYPE.READ);
		ResourceAccess ra = new ResourceAccess();
		ra.setAccessType(accessSet);
		ra.setPrincipalId(userId);
		AccessControlList acl = entityServletHelper.getEvaluationAcl(ownerId, eval1.getId());
		acl.getResourceAccess().add(ra);
		acl = entityServletHelper.updateEvaluationAcl(ownerId, acl);
		assertNotNull(acl);
		
		// join
		part1 = entityServletHelper.createParticipant(userId, eval1.getId());
		participantsToDelete.add(part1);
		UserInfo userInfo = userManager.getUserInfo(userId);
		String nodeId = createNode("An entity", userInfo);
		assertNotNull(nodeId);
		nodesToDelete.add(nodeId);
		
		long initialCount = entityServletHelper.getSubmissionCount(ownerId, eval1.getId());
		
		// create
		Node node = nodeManager.get(userInfo, nodeId);
		sub1.setEvaluationId(eval1.getId());
		sub1.setEntityId(nodeId);
		sub1 = entityServletHelper.createSubmission(sub1, userId, node.getETag());
		assertNotNull(sub1.getId());
		submissionsToDelete.add(sub1.getId());
		assertEquals(initialCount + 1, entityServletHelper.getSubmissionCount(ownerId, eval1.getId()));
		
		// read
		Submission clone = entityServletHelper.getSubmission(ownerId, sub1.getId());
		assertEquals(sub1, clone);
		SubmissionStatus status = entityServletHelper.getSubmissionStatus(ownerId, sub1.getId());
		assertNotNull(status);
		assertEquals(sub1.getId(), status.getId());
		assertEquals(SubmissionStatusEnum.RECEIVED, status.getStatus());
		
		// update
		Thread.sleep(1L);
		status.setScore(0.5);
		status.setStatus(SubmissionStatusEnum.SCORED);
		SubmissionStatus statusClone = entityServletHelper.updateSubmissionStatus(status, ownerId);
		assertFalse("Modified date was not updated", status.getModifiedOn().equals(statusClone.getModifiedOn()));
		status.setModifiedOn(statusClone.getModifiedOn());
		assertFalse("Etag was not updated", status.getEtag().equals(statusClone.getEtag()));
		status.setEtag(statusClone.getEtag());
		assertEquals(status, statusClone);
		assertEquals(initialCount + 1, entityServletHelper.getSubmissionCount(ownerId, eval1.getId()));
		
		// delete
		entityServletHelper.deleteSubmission(sub1.getId(), ownerId);
		try {
			entityServletHelper.deleteSubmission(sub1.getId(), ownerId);
			fail("Failed to delete Submission " + sub1.toString());
		} catch (NotFoundException e) {
			// expected
		}
		assertEquals(initialCount, entityServletHelper.getSubmissionCount(ownerId, eval1.getId()));
	}
	

	
	@Test(expected=UnauthorizedException.class)
	public void testSubmissionUnauthorized() throws Exception {		
		eval1.setStatus(EvaluationStatus.OPEN);
		eval1 = entityServletHelper.createEvaluation(eval1, ownerId);
		evaluationsToDelete.add(eval1.getId());
		part1 = entityServletHelper.createParticipant(userId, eval1.getId());
		participantsToDelete.add(part1);
		UserInfo ownerInfo = userManager.getUserInfo(ownerId);
		String nodeId = createNode("An entity", ownerInfo);
		assertNotNull(nodeId);
		nodesToDelete.add(nodeId);
		Node node = nodeManager.get(ownerInfo, nodeId);
		
		// create
		sub1.setEvaluationId(eval1.getId());
		sub1.setEntityId(nodeId);
		sub1 = entityServletHelper.createSubmission(sub1, userId, node.getETag());
	}
	
	@Test
	public void testPaginated() throws Exception {
		// create objects
		eval1.setStatus(EvaluationStatus.OPEN);
		eval1 = entityServletHelper.createEvaluation(eval1, ownerId);
		assertNotNull(eval1.getId());
		evaluationsToDelete.add(eval1.getId());
		eval2 = entityServletHelper.createEvaluation(eval2, ownerId);
		assertNotNull(eval2.getId());
		evaluationsToDelete.add(eval2.getId());
		
		part1 = entityServletHelper.createParticipant(ownerId, eval1.getId());
		assertNotNull(part1);
		participantsToDelete.add(part1);
		// open the evaluation to join
		Set<ACCESS_TYPE> accessSet = new HashSet<ACCESS_TYPE>(12);
		accessSet.add(ACCESS_TYPE.PARTICIPATE);
		accessSet.add(ACCESS_TYPE.READ);
		ResourceAccess ra = new ResourceAccess();
		ra.setAccessType(accessSet);
		ra.setPrincipalId(userId);
		AccessControlList acl = entityServletHelper.getEvaluationAcl(ownerId, eval1.getId());
		acl.getResourceAccess().add(ra);
		acl = entityServletHelper.updateEvaluationAcl(ownerId, acl);
		assertNotNull(acl);
		part2 = entityServletHelper.createParticipant(userId, eval1.getId());
		assertNotNull(part2);
		participantsToDelete.add(part2);
		
		// fetch eval1 and verify that eTag has been updated
		String oldEtag = eval1.getEtag();
		eval1 = entityServletHelper.getEvaluation(userId, eval1.getId());
		assertFalse("Etag was not updated", oldEtag.equals(eval1.getEtag()));
		
		UserInfo userInfo = userManager.getUserInfo(userId);
		String node1 = createNode("entity1", userInfo);
		assertNotNull(node1);
		String etag1 = nodeManager.get(userInfo, node1).getETag();
		nodesToDelete.add(node1);
		String node2 = createNode("entity2", userInfo);
		String etag2 = nodeManager.get(userInfo, node2).getETag();
		assertNotNull(node2);
		nodesToDelete.add(node2);
		
		sub1.setEvaluationId(eval1.getId());
		sub1.setEntityId(node1);
		sub1.setVersionNumber(1L);
		sub1.setUserId(ownerId.toString());
		sub1 = entityServletHelper.createSubmission(sub1, ownerId, etag1);
		assertNotNull(sub1.getId());
		submissionsToDelete.add(sub1.getId());		
		sub2.setEvaluationId(eval1.getId());
		sub2.setEntityId(node2);
		sub2.setVersionNumber(1L);
		sub2.setUserId(ownerId.toString());
		sub2 = entityServletHelper.createSubmission(sub2, ownerId, etag2);
		assertNotNull(sub2.getId());
		submissionsToDelete.add(sub2.getId());
		
		// paginated evaluations
		PaginatedResults<Evaluation> evals = entityServletHelper.getEvaluationsPaginated(ownerId, 10, 0);
		assertEquals(2, evals.getTotalNumberOfResults());
		for (Evaluation c : evals.getResults()) {
			assertTrue("Unknown Evaluation returned: " + c.toString(), c.equals(eval1) || c.equals(eval2));
		}
		
		// paginated evaluations by content source
		evals = entityServletHelper.getEvaluationsByContentSourcePaginated(ownerId, KeyFactory.SYN_ROOT_ID, 10, 0);
		assertEquals(2, evals.getTotalNumberOfResults());
		for (Evaluation c : evals.getResults()) {
			assertTrue("Unknown Evaluation returned: " + c.toString(), c.equals(eval1) || c.equals(eval2));
		}
		
		// paginated participants
		PaginatedResults<Participant> parts = entityServletHelper.getAllParticipants(ownerId, eval1.getId());
		assertEquals(2, parts.getTotalNumberOfResults());
		for (Participant p : parts.getResults()) {
			assertTrue("Unknown Participant returned: " + p.toString(), p.equals(part1) || p.equals(part2));
		}
		
		parts = entityServletHelper.getAllParticipants(ownerId, eval2.getId());
		assertEquals(0, parts.getTotalNumberOfResults());
		
		// paginated submissions
		PaginatedResults<Submission> subs = entityServletHelper.getAllSubmissions(ownerId, eval1.getId(), null);
		assertEquals(2, subs.getTotalNumberOfResults());
		for (Submission s : subs.getResults()) {
			assertTrue("Unknown Submission returned: " + s.toString(), s.equals(sub1) || s.equals(sub2));
		}
		
		subs = entityServletHelper.getAllSubmissions(ownerId, eval1.getId(), SubmissionStatusEnum.SCORED);
		assertEquals(0, subs.getTotalNumberOfResults());
		
		subs = entityServletHelper.getAllSubmissions(ownerId, eval2.getId(), null);
		assertEquals(0, subs.getTotalNumberOfResults());
	}

	@Test
	public void testAclRoundTrip() throws Exception {

		// Create the entity first
		UserInfo userInfo = userManager.getUserInfo(userId);
		String nodeId = createNode("EvaluationControllerAutowiredTest.testAclRoundTrip()", userInfo);
		assertNotNull(nodeId);
		nodesToDelete.add(nodeId);

		// Create the evaluation (which should also creates the ACL)
		eval1.setContentSource(nodeId);
		eval1 = entityServletHelper.createEvaluation(eval1, userId);		
		assertNotNull(eval1.getEtag());
		assertNotNull(eval1.getId());
		assertEquals(nodeId, eval1.getContentSource());
		evaluationsToDelete.add(eval1.getId());

		// Get the ACL
		AccessControlList aclReturned = entityServletHelper.getEvaluationAcl(userId, eval1.getId());
		assertNotNull(aclReturned);
		assertEquals(eval1.getId(), aclReturned.getId());
		assertNotNull(aclReturned.getResourceAccess());

		// Update the ACL
		ResourceAccess ra = new ResourceAccess();
		Set<ACCESS_TYPE> accessType = new HashSet<ACCESS_TYPE>();
		accessType.add(ACCESS_TYPE.CHANGE_PERMISSIONS);
		accessType.add(ACCESS_TYPE.PARTICIPATE);
		accessType.add(ACCESS_TYPE.READ);
		ra.setAccessType(accessType);
		ra.setPrincipalId(userId);
		aclReturned.getResourceAccess().add(ra);

		aclReturned = entityServletHelper.updateEvaluationAcl(userId, aclReturned);
		assertNotNull(aclReturned);
		assertEquals(eval1.getId(), aclReturned.getId());

		// getAcl()
		aclReturned = entityServletHelper.getEvaluationAcl(userId, eval1.getId());
		assertNotNull(aclReturned);
		assertEquals(eval1.getId(), aclReturned.getId());

		// getUserEvaluationPermissions()
		UserEvaluationPermissions uepReturned = entityServletHelper.getEvaluationPermissions(userId, eval1.getId());
		assertNotNull(uepReturned);
	}

	private String createNode(String name, UserInfo userInfo) throws Exception {
		Node toCreate = new Node();
		toCreate.setName(name);
		String ownerId = userInfo.getIndividualGroup().getId();
		toCreate.setCreatedByPrincipalId(Long.parseLong(ownerId));
		toCreate.setModifiedByPrincipalId(Long.parseLong(ownerId));
		toCreate.setCreatedOn(new Date(System.currentTimeMillis()));
		toCreate.setModifiedOn(toCreate.getCreatedOn());
		toCreate.setNodeType(EntityType.project.name());
    	toCreate.setVersionComment("This is the first version of the first node ever!");
    	toCreate.setVersionLabel("1");
    	String id = nodeManager.createNewNode(toCreate, userInfo);
    	nodesToDelete.add(KeyFactory.stringToKey(id).toString());
    	return id;
	}

}
