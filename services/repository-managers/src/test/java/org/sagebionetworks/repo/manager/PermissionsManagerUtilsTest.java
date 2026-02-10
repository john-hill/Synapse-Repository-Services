package org.sagebionetworks.repo.manager;

import static org.junit.Assert.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sagebionetworks.repo.model.ACCESS_TYPE;
import org.sagebionetworks.repo.model.AccessControlList;
import org.sagebionetworks.repo.model.AuthorizationConstants;
import org.sagebionetworks.repo.model.InvalidModelException;
import org.sagebionetworks.repo.model.ResourceAccess;
import org.sagebionetworks.repo.model.UserInfo;
import org.sagebionetworks.repo.model.AuthorizationConstants.BOOTSTRAP_PRINCIPAL;

import com.google.common.collect.ImmutableSet;

public class PermissionsManagerUtilsTest {
	private final static String DEFAULT_REALM = "0";
	private UserInfo adminUserInfo;
	private UserInfo userInfo;
	private UserInfo otherUserInfo;
	private static Long ownerId;
	private Set<String> realmIds;

	@BeforeEach
	public void setUp(){
		ownerId = 1234L;
		userInfo = new UserInfo(false, ownerId,DEFAULT_REALM);
		otherUserInfo = new UserInfo(false, 56789L, DEFAULT_REALM);
		adminUserInfo = new UserInfo(true, 1L, DEFAULT_REALM);
		otherUserInfo.getGroups().add(AuthorizationConstants.BOOTSTRAP_PRINCIPAL.AUTHENTICATED_USERS_GROUP.getPrincipalId());
		realmIds = Set.of(DEFAULT_REALM);
	}

	@Test
	public void testValidateACLContent() throws Exception {
		ResourceAccess userRA = new ResourceAccess();
		userRA.setPrincipalId(userInfo.getId());
		Set<ACCESS_TYPE> ats = new HashSet<ACCESS_TYPE>();
		ats.add(ACCESS_TYPE.CHANGE_PERMISSIONS);
		userRA.setAccessType(ats);

		Set<ResourceAccess> ras = new HashSet<ResourceAccess>();
		ras.add(userRA);

		AccessControlList acl = new AccessControlList();
		acl.setId("resource id");
		acl.setResourceAccess(ras);

		// Should not throw any exceptions
		PermissionsManagerUtils.validateACLContent(acl, userInfo, realmIds,ownerId);
	}

	@Test
	public void testValidateACLContent_UserMissing()throws Exception {
		AccessControlList acl = new AccessControlList();
		acl.setId("resource id");

		assertThrows(InvalidModelException.class, ()-> {
			// Should fail, since user is not included with proper permissions in ACL
			PermissionsManagerUtils.validateACLContent(acl, otherUserInfo, realmIds, ownerId);
		});
	}


	@Test
	public void testValidateACLContent_AdminMissing()throws Exception {
		AccessControlList acl = new AccessControlList();
		acl.setId("resource id");

		// Should not throw any exceptions
		PermissionsManagerUtils.validateACLContent(acl, adminUserInfo, realmIds, ownerId);
	}

	@Test
	public void testValidateACLContent_OwnerMissing()throws Exception {
		AccessControlList acl = new AccessControlList();
		acl.setId("resource id");

		// Should not throw any exceptions
		PermissionsManagerUtils.validateACLContent(acl, userInfo, realmIds, ownerId);
	}

	@Test
	public void testValidateACLContent_UserInsufficientPermissions() throws Exception {
		ResourceAccess userRA = new ResourceAccess();
		userRA.setPrincipalId(userInfo.getId());
		Set<ACCESS_TYPE> ats = new HashSet<ACCESS_TYPE>();
		ats.add(ACCESS_TYPE.READ);
		userRA.setAccessType(ats);

		Set<ResourceAccess> ras = new HashSet<ResourceAccess>();
		ras.add(userRA);

		AccessControlList acl = new AccessControlList();
		acl.setId("resource id");
		acl.setResourceAccess(ras);

		assertThrows(InvalidModelException.class, ()-> {
			// Should fail since user does not have permission editing rights in ACL
			PermissionsManagerUtils.validateACLContent(acl, otherUserInfo, realmIds, ownerId);
		});
	}

	@Test
	public void testValidateACLContent_indirectMembership() throws Exception {
		ResourceAccess userRA = new ResourceAccess();
		// 'other user' should be a member of 'authenticated users'
		Long groupId = AuthorizationConstants.BOOTSTRAP_PRINCIPAL.AUTHENTICATED_USERS_GROUP.getPrincipalId();
		assertTrue(otherUserInfo.getGroups().contains(groupId));
		// giving 'authenticated users' change_permissions access should fulfill the requirement
		// that the editor of the ACL does not remove their own access
		userRA.setPrincipalId(groupId);
		Set<ACCESS_TYPE> ats = new HashSet<ACCESS_TYPE>();
		ats.add(ACCESS_TYPE.CHANGE_PERMISSIONS);
		userRA.setAccessType(ats);

		Set<ResourceAccess> ras = new HashSet<ResourceAccess>();
		ras.add(userRA);

		AccessControlList acl = new AccessControlList();
		acl.setId("resource id");
		acl.setResourceAccess(ras);

		PermissionsManagerUtils.validateACLContent(acl, otherUserInfo, realmIds, ownerId);

	}

	@Test
	public void testValidateACLContentAnonDownload() throws Exception {
		ResourceAccess userRA = new ResourceAccess();
		userRA.setPrincipalId(AuthorizationConstants.BOOTSTRAP_PRINCIPAL.PUBLIC_GROUP.getPrincipalId());
		Set<ACCESS_TYPE> ats = new HashSet<ACCESS_TYPE>();
		ats.add(ACCESS_TYPE.DOWNLOAD);
		userRA.setAccessType(ats);

		Set<ResourceAccess> ras = new HashSet<ResourceAccess>();
		ras.add(userRA);

		AccessControlList acl = new AccessControlList();
		acl.setId("resource id");
		acl.setResourceAccess(ras);

		assertThrows(InvalidModelException.class, ()-> {
			PermissionsManagerUtils.validateACLContent(acl, userInfo, realmIds, ownerId);
		});
	}

	/*
	 * PLFM-3632s
	 */
	@Test
	public void testValidateACLContentNonCertifiedUserMakeACLPublic() throws Exception {
		ResourceAccess userRA = new ResourceAccess();
		userRA.setPrincipalId(AuthorizationConstants.BOOTSTRAP_PRINCIPAL.AUTHENTICATED_USERS_GROUP.getPrincipalId());
		Set<ACCESS_TYPE> ats = new HashSet<ACCESS_TYPE>();
		ats.add(ACCESS_TYPE.DOWNLOAD);
		userRA.setAccessType(ats);
		Set<ResourceAccess> ras = new HashSet<ResourceAccess>();
		ras.add(userRA);
		AccessControlList acl = new AccessControlList();
		acl.setId("resource id");
		acl.setResourceAccess(ras);
		assertThrows(UserCertificationRequiredException.class, ()-> {
			// userInfo is not certified
			PermissionsManagerUtils.validateACLContent(acl, userInfo, realmIds, ownerId);
		});
	}

	@Test
	public void testValidateACLContentCertifiedUserMakeACLPublic() throws Exception {
		ResourceAccess userRA = new ResourceAccess();
		userRA.setPrincipalId(AuthorizationConstants.BOOTSTRAP_PRINCIPAL.AUTHENTICATED_USERS_GROUP.getPrincipalId());
		Set<ACCESS_TYPE> ats = new HashSet<ACCESS_TYPE>();
		ats.add(ACCESS_TYPE.DOWNLOAD);
		userRA.setAccessType(ats);
		Set<ResourceAccess> ras = new HashSet<ResourceAccess>();
		ras.add(userRA);
		AccessControlList acl = new AccessControlList();
		acl.setId("resource id");
		acl.setResourceAccess(ras);
		// certify userInfo
		userInfo.getGroups().add(AuthorizationConstants.BOOTSTRAP_PRINCIPAL.CERTIFIED_USERS.getPrincipalId());
		PermissionsManagerUtils.validateACLContent(acl, userInfo, realmIds, ownerId);
	}
	
	@Test
	public void testValidateACLContentWithAnonymousUser() {
		ResourceAccess userRA = new ResourceAccess();
		userRA.setPrincipalId(AuthorizationConstants.BOOTSTRAP_PRINCIPAL.ANONYMOUS_USER.getPrincipalId());
		Set<ACCESS_TYPE> ats = ImmutableSet.of(ACCESS_TYPE.READ);
		userRA.setAccessType(ats);

		AccessControlList acl = new AccessControlList();
		acl.setId("resource id");
		acl.setResourceAccess(ImmutableSet.of(userRA));

		InvalidModelException ex = assertThrows(InvalidModelException.class, () -> {
			// Call under test
			PermissionsManagerUtils.validateACLContent(acl, userInfo, realmIds, ownerId);
		});

		assertEquals("Cannot assign permissions to anonymous. To share resources with anonymous users, use the PUBLIC group id (" + BOOTSTRAP_PRINCIPAL.PUBLIC_GROUP.getPrincipalId() + ")", ex.getMessage());
	}

	@Test
	public void testValidateACLContentWithREADOnPublicGroup() {
		ResourceAccess userRA = new ResourceAccess();
		userRA.setPrincipalId(AuthorizationConstants.BOOTSTRAP_PRINCIPAL.PUBLIC_GROUP.getPrincipalId());
		Set<ACCESS_TYPE> ats = ImmutableSet.of(ACCESS_TYPE.READ);
		userRA.setAccessType(ats);

		AccessControlList acl = new AccessControlList();
		acl.setId("resource id");
		acl.setResourceAccess(ImmutableSet.of(userRA));
		
		// Call under test
		PermissionsManagerUtils.validateACLContent(acl, userInfo, realmIds, ownerId);

	}
	
	@Test
	public void testValidateACLContentWithPublicGroupAndDifferentThanREAD() {
		ResourceAccess userRA = new ResourceAccess();
		userRA.setPrincipalId(AuthorizationConstants.BOOTSTRAP_PRINCIPAL.PUBLIC_GROUP.getPrincipalId());
		
		Set<ACCESS_TYPE> ats = Arrays.asList(ACCESS_TYPE.values()).stream().filter( type -> !ACCESS_TYPE.READ.equals(type)).collect(Collectors.toSet());
		
		userRA.setAccessType(ats);

		AccessControlList acl = new AccessControlList();
		acl.setId("resource id");
		acl.setResourceAccess(ImmutableSet.of(userRA));
		
		InvalidModelException ex = assertThrows(InvalidModelException.class, () -> {
			// Call under test
			PermissionsManagerUtils.validateACLContent(acl, userInfo, realmIds, ownerId);
		});
		
		assertEquals("Only READ permissions can be assigned to the public group", ex.getMessage());
		
	}

	@Test
	public void testValidateACLContentForDifferentRealACLPrincipal() {
		ResourceAccess userRA = new ResourceAccess();
		userRA.setPrincipalId(userInfo.getId());
		Set<ACCESS_TYPE> ats = new HashSet<ACCESS_TYPE>();
		ats.add(ACCESS_TYPE.CHANGE_PERMISSIONS);
		userRA.setAccessType(ats);

		Set<ResourceAccess> ras = new HashSet<ResourceAccess>();
		ras.add(userRA);

		AccessControlList acl = new AccessControlList();
		acl.setId("resource id");
		acl.setResourceAccess(ras);
		Set<String> realmIdSet = Set.of(DEFAULT_REALM, "1"); // add a different realm to the set of realms

		// Should not throw any exceptions
		InvalidModelException ex = assertThrows(InvalidModelException.class, () -> {
			// Call under test
			PermissionsManagerUtils.validateACLContent(acl, userInfo, realmIdSet, ownerId);
		});

		assertEquals("All principals in the ACL must be from the same realm.", ex.getMessage());
	}

	@Test
	public void testValidateACLContenttRealmACLPrincipalAreDifferentThenCaller() {
		ResourceAccess userRA = new ResourceAccess();
		userRA.setPrincipalId(userInfo.getId());
		Set<ACCESS_TYPE> ats = new HashSet<ACCESS_TYPE>();
		ats.add(ACCESS_TYPE.CHANGE_PERMISSIONS);
		userRA.setAccessType(ats);

		Set<ResourceAccess> ras = new HashSet<ResourceAccess>();
		ras.add(userRA);

		AccessControlList acl = new AccessControlList();
		acl.setId("resource id");
		acl.setResourceAccess(ras);
		Set<String> realmIdSet = Set.of( "1");

		// Should not throw any exceptions caller is in default realm and ACl principal in different realm
		InvalidModelException ex = assertThrows(InvalidModelException.class, () -> {
			// Call under test
			PermissionsManagerUtils.validateACLContent(acl, userInfo, realmIdSet, ownerId);
		});

		assertEquals("All principals in the ACL must be from the same realm as the caller principal.", ex.getMessage());
	}

}