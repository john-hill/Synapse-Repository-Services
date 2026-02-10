package org.sagebionetworks.repo.manager;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.sagebionetworks.StackConfigurationSingleton;
import org.sagebionetworks.repo.model.ACCESS_TYPE;
import org.sagebionetworks.repo.model.AccessControlList;
import org.sagebionetworks.repo.model.AccessControlListDAO;
import org.sagebionetworks.repo.model.DatastoreException;
import org.sagebionetworks.repo.model.ObjectType;
import org.sagebionetworks.repo.model.ResourceAccess;
import org.sagebionetworks.repo.model.UserGroupDAO;
import org.sagebionetworks.repo.model.UserInfo;
import org.sagebionetworks.repo.model.auth.AuthorizationStatus;
import org.sagebionetworks.util.ValidateArgument;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.google.common.collect.Sets;

@Service
public class AccessControlListManagerImpl implements AccessControlListManager {
	
	public static final Long TRASH_FOLDER_ID = Long.parseLong(
			StackConfigurationSingleton.singleton().getTrashFolderEntityId());

	private  final AccessControlListDAO aclDao;
	private  final UserGroupDAO userGroupDAO;

	@Autowired
	public AccessControlListManagerImpl(AccessControlListDAO aclDao, UserGroupDAO userGroupDAO) {
		super();
		this.aclDao = aclDao;
		this.userGroupDAO = userGroupDAO;
	}

	@Override
	public void create(UserInfo userInfo, AccessControlList acl, ObjectType objectType, Long ownerId) {
		PermissionsManagerUtils.validateACLContent(acl, userInfo, ownerId);
		validateRealm(userInfo, acl);
		this.aclDao.create(acl, objectType);
	}

	private void validateRealm(UserInfo userInfo, AccessControlList acl) {
		List<String> principalIds = acl.getResourceAccess().stream().map(ResourceAccess::getPrincipalId)
				.map(String::valueOf).collect(Collectors.toList());

		Set<String> realmIds = new HashSet<>(userGroupDAO.getUserRealm(principalIds).keySet());

		if (realmIds.size() > 1) {
			throw new IllegalArgumentException("All principals in the ACL must be from the same realm.");
		}
		if (realmIds.size() == 1 && !realmIds.contains(userInfo.getRealmId())) { //should we allow admins to set ACLs for other realms?
			throw new IllegalArgumentException("All principals in the ACL must be from the same realm as the user.");
		}
	}

	@Override
	public void update(UserInfo userInfo, AccessControlList acl, ObjectType objectType, Long ownerId) {
		PermissionsManagerUtils.validateACLContent(acl, userInfo, ownerId);
		validateRealm(userInfo, acl);
		aclDao.update(acl, objectType);
	}


	@Override
	public void delete(String objectId, ObjectType objectType) {
		this.aclDao.delete(objectId, objectType);
	}

	@Override
	public int delete(List<Long> ids, ObjectType ownerType) throws DatastoreException {
		return aclDao.delete(ids, ownerType);
	}

	@Override
	public Set<Long> getAccessibleBenefactors(UserInfo userInfo, ObjectType objectType, Set<Long> benefactors, ACCESS_TYPE...types) {
		Set<Long> results = null;
		if (userInfo.isAdmin()){
			// admin same as input
			results = Sets.newHashSet(benefactors);
		}else{
			// non-adim run a query
			results = this.aclDao.getAccessibleBenefactors(userInfo.getGroups(), benefactors, objectType, types);
		}
		if (ObjectType.ENTITY.equals(objectType)) {
			// The trash folder should not be in the results
			results.remove(TRASH_FOLDER_ID);
		}
		return results;
	}

	@Override
	public boolean canAccess(Set<Long> groups, String objectId, ObjectType objectType, ACCESS_TYPE accessType) {
		//if the acl belongs the realm and user is admin of that realm its authorized to access
		return aclDao.canAccess(groups, objectId, objectType, accessType);
	}

	@Override
	public AuthorizationStatus canAccess(UserInfo user, String resourceId, ObjectType resourceType, ACCESS_TYPE permission) {
		return aclDao.canAccess(user, resourceId, resourceType, permission);
	}

	@Override
	public Optional<AccessControlList> getAcl(String objectId, ObjectType objectType) {
		return aclDao.getAcl(objectId, objectType);
	}

	@Override
	public Set<Long> getAccessibleProjectIds(Set<Long> principalIds) {
		ValidateArgument.required(principalIds, "principalIds");
		if(principalIds.isEmpty()){
			return new HashSet<>(0);
		}
		return aclDao.getAccessibleProjectIds(principalIds, ACCESS_TYPE.READ);
	}

	@Override
	public List<Long> getChildrenEntitiesWithAcls(List<Long> parentIds) {
		return aclDao.getChildrenEntitiesWithAcls(parentIds);
	}

	@Override
	public Set<Long> getNonVisibleChilrenOfEntity(Set<Long> groups, String parentId) {
		return aclDao.getNonVisibleChilrenOfEntity(groups, parentId);
	}

	@Override
	public void truncateAll() {
		aclDao.truncateAll();
	}

}
