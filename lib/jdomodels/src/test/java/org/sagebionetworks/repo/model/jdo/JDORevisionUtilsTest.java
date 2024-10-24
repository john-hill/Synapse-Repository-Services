package org.sagebionetworks.repo.model.jdo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.Random;
import java.util.Set;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.sagebionetworks.repo.model.AuthorizationConstants;
import org.sagebionetworks.repo.model.DatastoreException;
import org.sagebionetworks.repo.model.NamedAnnotations;
import org.sagebionetworks.repo.model.NodeRevisionBackup;
import org.sagebionetworks.repo.model.Reference;
import org.sagebionetworks.repo.model.UserGroupDAO;
import org.sagebionetworks.repo.model.dbo.persistence.DBONode;
import org.sagebionetworks.repo.model.dbo.persistence.DBORevision;
import org.sagebionetworks.repo.model.util.RandomAnnotationsUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;

@RunWith(SpringJUnit4ClassRunner.class)
@ContextConfiguration(locations = { "classpath:bootstrap-entites-spb.xml","classpath:jdomodels-test-context.xml" })
public class JDORevisionUtilsTest {
	
	@Autowired
	private UserGroupDAO userGroupDAO;

	@Test
	public void testMakeCopyForNewVersion() throws Exception {
		String createdById = userGroupDAO.findGroup(AuthorizationConstants.BOOTSTRAP_USER_GROUP_NAME, false).getId();
		// Create a random blob for this revision's annotatoins
		Random rand = new Random(565);
		int size = rand.nextInt(100);
		// Exclude zero
		size++;
		byte[] blob = new byte[size];
		rand.nextBytes(blob);
		
		DBONode owner = new DBONode();
		owner.setId(12l);
		DBORevision original = new DBORevision();
		original.setOwner(owner.getId());
		original.setRevisionNumber(2L);
		original.setAnnotations(blob);
		original.setLabel("0.3.9");
		original.setModifiedBy(Long.parseLong(createdById));
		original.setModifiedOn(3123l);
		// Now make a copy
		DBORevision copy = JDORevisionUtils.makeCopyForNewVersion(original);
		assertNotNull(copy);
		// The copy should not equal the original since it will have an incremented version number.
		assertFalse(original.equals(copy));
		// Make sure the version number is incremented
		assertEquals(new Long(original.getRevisionNumber()+1), copy.getRevisionNumber());
		// We do not copy over the label or the comment
		assertEquals(null, copy.getLabel());
		assertEquals(null, copy.getComment());
		// We do make a copy of the annotations blob
		// but it should be a copy and not the original
		assertTrue(original.getAnnotations() != copy.getAnnotations());
		assertTrue(Arrays.equals(original.getAnnotations(), copy.getAnnotations()));
		
	}
	
	@Test
	public void testRoundTrip() throws IOException, DatastoreException{
		Long createdById = Long.parseLong(userGroupDAO.findGroup(AuthorizationConstants.BOOTSTRAP_USER_GROUP_NAME, false).getId());
		NodeRevisionBackup dto = new NodeRevisionBackup();
		dto.setNodeId(KeyFactory.keyToString(123L));
		dto.setRevisionNumber(new Long(3));
		dto.setComment("I comment therefore I am!");
		dto.setLabel("1.0.1");
		dto.setModifiedByPrincipalId(createdById);
		dto.setModifiedOn(new Date());
		dto.setNamedAnnotations(new NamedAnnotations());
		dto.getNamedAnnotations().put("someRandomName-space", RandomAnnotationsUtil.generateRandom(123, 4));
		dto.setReferences(new HashMap<String, Set<Reference>>());
		// Now create the JDO object
		DBORevision jdo = new DBORevision();
		DBONode owner = new DBONode();
		owner.setId(new Long(123));
		JDORevisionUtils.updateJdoFromDto(dto, jdo);
		// Now go back
		NodeRevisionBackup clone = JDORevisionUtils.createDtoFromJdo(jdo);
		assertEquals(dto, clone);
	}

}
