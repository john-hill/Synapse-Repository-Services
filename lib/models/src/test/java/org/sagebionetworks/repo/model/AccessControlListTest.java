package org.sagebionetworks.repo.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.sagebionetworks.repo.model.util.AccessControlListUtil;

/**
 * Test for the ACL DTO object
 * @author jmhill
 *
 */
public class AccessControlListTest {

	@Test
	public void testGrantAll(){
		String nodeId = "123";
		UserInfo info = new UserInfo(false);
		Principal userGroup = new Principal();
		userGroup.setId("123");
		userGroup.setPrincipalName("one");
		userGroup.setIsIndividual(false);
		User user = new User();
		user.setId("33");
		user.setUserId("someUser@somedomain.net");
		info.setUser(user);
		info.setIndividualGroup(userGroup);
		AccessControlList acl = AccessControlListUtil.createACLToGrantAll(nodeId, info);
		assertNotNull(acl);
		assertEquals(acl.getId(), nodeId);
		assertNotNull(acl.getCreationDate());
		assertNotNull(acl.getResourceAccess());
		assertEquals(1, acl.getResourceAccess().size());
		ResourceAccess ra = acl.getResourceAccess().iterator().next();
		assertNotNull(ra);
		assertEquals(userGroup.getId(), ra.getPrincipalId().toString());
		assertNotNull(ra.getAccessType());
		// There should be one for each type
		ACCESS_TYPE[] array = ACCESS_TYPE.values();
		assertEquals(array.length, ra.getAccessType().size());
		// check each type
		for(ACCESS_TYPE type: array){
			assertTrue(ra.getAccessType().contains(type));
		}
	}
}
