package org.sagebionetworks.repo.manager;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.sagebionetworks.repo.model.AuthenticationDAO;
import org.sagebionetworks.repo.model.AuthorizationConstants;
import org.sagebionetworks.repo.model.AuthorizationConstants.DEFAULT_GROUPS;
import org.sagebionetworks.repo.model.DatastoreException;
import org.sagebionetworks.repo.model.GroupMembersDAO;
import org.sagebionetworks.repo.model.InvalidModelException;
import org.sagebionetworks.repo.model.NameConflictException;
import org.sagebionetworks.repo.model.UnauthorizedException;
import org.sagebionetworks.repo.model.User;
import org.sagebionetworks.repo.model.Principal;
import org.sagebionetworks.repo.model.PrincipalDAO;
import org.sagebionetworks.repo.model.UserInfo;
import org.sagebionetworks.repo.model.UserProfile;
import org.sagebionetworks.repo.model.UserProfileDAO;
import org.sagebionetworks.repo.model.auth.NewUser;
import org.sagebionetworks.repo.model.dbo.dao.AuthorizationUtils;
import org.sagebionetworks.repo.web.NotFoundException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

public class UserManagerImpl implements UserManager {
	
	@Autowired
	private PrincipalDAO userGroupDAO;
	
	@Autowired
	private UserProfileDAO userProfileDAO;
	
	@Autowired
	private GroupMembersDAO groupMembersDAO;
	
	@Autowired
	private AuthenticationDAO authDAO;
	

	public void setUserGroupDAO(PrincipalDAO userGroupDAO) {
		this.userGroupDAO = userGroupDAO;
	}
	
	@Override
	@Transactional(readOnly = false, propagation = Propagation.REQUIRED)
	public void createUser(NewUser user) throws DatastoreException {
		if (userGroupDAO.doesPrincipalExist(user.getEmail())) {
			throw new NameConflictException("User '" + user.getEmail() + "' already exists");
		}
		
		Principal individualGroup = new Principal();
		individualGroup.setPrincipalName(user.getEmail());
		individualGroup.setIsIndividual(true);
		individualGroup.setCreationDate(new Date());
		try {
			String id = userGroupDAO.create(individualGroup);
			individualGroup = userGroupDAO.get(id);
		} catch (NotFoundException ime) {
			// shouldn't happen!
			throw new DatastoreException(ime);
		} catch (InvalidModelException ime) {
			// shouldn't happen!
			throw new DatastoreException(ime);
		}
		
		// Make a user profile for this individual
		UserProfile userProfile = null;
		try {
			userProfile = userProfileDAO.get(individualGroup.getId());
		} catch (NotFoundException nfe) {
			userProfile = null;
		}
		if (userProfile==null) {
			userProfile = new UserProfile();
			userProfile.setOwnerId(individualGroup.getId());
			userProfile.setFirstName(user.getFirstName());
			userProfile.setLastName(user.getLastName());
			userProfile.setDisplayName(user.getDisplayName());
			try {
				userProfileDAO.create(userProfile);
			} catch (InvalidModelException e) {
				throw new RuntimeException(e);
			}
		}
	}

	@Transactional(readOnly = false, propagation = Propagation.REQUIRED)
	@Override
	public UserInfo getUserInfo(String userName) throws DatastoreException, NotFoundException {
		Principal individualGroup = userGroupDAO.findGroup(userName, true);
		if (individualGroup==null) throw new NotFoundException("Cannot find user with name "+userName);
		return getUserInfo(individualGroup);
	}
		
	@Transactional(readOnly = false, propagation = Propagation.REQUIRED)
	@Override
	public UserInfo getUserInfo(Long principalId) throws DatastoreException, NotFoundException {
		Principal individualGroup = userGroupDAO.get(principalId.toString());
		return getUserInfo(individualGroup);
	}
		
	@Transactional(readOnly = false, propagation = Propagation.REQUIRED)
	private UserInfo getUserInfo(Principal individualGroup) throws DatastoreException, NotFoundException {
		
		// Check which group(s) of Anonymous, Public, or Authenticated the user belongs to  
		Set<Principal> groups = new HashSet<Principal>();
		if (!AuthorizationUtils.isUserAnonymous(individualGroup.getPrincipalName())) {
			// All authenticated users belong to the authenticated user group
			groups.add(getDefaultUserGroup(DEFAULT_GROUPS.AUTHENTICATED_USERS));
		}
		
		// Everyone belongs to their own group and to Public
		groups.add(individualGroup);
		groups.add(getDefaultUserGroup(DEFAULT_GROUPS.PUBLIC));
		
		// Add all groups the user belongs to
		groups.addAll(groupMembersDAO.getUsersGroups(individualGroup.getId()));

		// Check to see if the user is an Admin
		boolean isAdmin = false;
		for (Principal group : groups) {
			if (AuthorizationConstants.ADMIN_GROUP_NAME.equals(group.getPrincipalName())) {
				isAdmin = true;
				break;
			}
		}
	
		// Put all the pieces together
		UserInfo ui = new UserInfo(isAdmin);
		ui.setIndividualGroup(individualGroup);
		ui.setGroups(groups);
		ui.setUser(getUser(individualGroup));
		return ui;
	}
	
	/**
	 * Constructs a User object out of information from the UserGroup and UserProfile
	 */
	private User getUser(Principal individualGroup) throws DatastoreException,
			NotFoundException {
		User user = new User();
		user.setUserId(individualGroup.getPrincipalName());
		user.setId(individualGroup.getPrincipalName()); // i.e. username == user id

		if (AuthorizationUtils.isUserAnonymous(individualGroup.getPrincipalName())) {
			return user;
		}

		user.setCreationDate(individualGroup.getCreationDate());
		
		// Get the terms of use acceptance
		user.setAgreesToTermsOfUse(authDAO.hasUserAcceptedToU(individualGroup.getId()));

		// The migrator may delete its own profile during migration
		// But those details do not matter for this user
		if (individualGroup.getPrincipalName().equals(AuthorizationConstants.MIGRATION_USER_NAME)) {
			return user;
		}

		UserProfile up = userProfileDAO.get(individualGroup.getId());
		user.setFname(up.getFirstName());
		user.setLname(up.getLastName());
		user.setDisplayName(up.getDisplayName());

		return user;
	}

	/**
	 * Lazy fetch of the default groups.
	 */
	@Override
	public Principal getDefaultUserGroup(DEFAULT_GROUPS group)
			throws DatastoreException {
		Principal ug = userGroupDAO.findGroup(group.name(), false);
		if (ug == null)
			throw new DatastoreException(group + " should exist.");
		return ug;
	}

	@Override
	public Principal findGroup(String name, boolean b) throws DatastoreException {
		return userGroupDAO.findGroup(name, b);
	}

	@Override
	public boolean doesPrincipalExist(String name) {
		return userGroupDAO.doesPrincipalExist(name);
	}

	@Transactional(readOnly = false, propagation = Propagation.REQUIRED)
	@Override
	public boolean deletePrincipal(String name) {
		return userGroupDAO.deletePrincipal(name);
	}
	
	@Override
	public String getDisplayName(Long principalId) throws NotFoundException, DatastoreException {
		Principal userGroup = userGroupDAO.get(principalId.toString());
		if (userGroup.getIsIndividual()) {
			UserProfile userProfile = userProfileDAO.get(principalId.toString());
			return userProfile.getDisplayName();
		} else {
			return userGroup.getName();
		}
	}
	
	@Override
	public String getGroupName(String principalId) throws NotFoundException {
		Principal userGroup = userGroupDAO.get(principalId);
		return userGroup.getName();
	}
	
	@Override
	public void updateEmail(UserInfo userInfo, String newEmail) throws DatastoreException, NotFoundException {
		
		// The mapping between usernames and user IDs is currently done on a one-to-one basis.
		// This means that changing the email associated with an ID in the UserGroup table 
		//   introduces an inconsistency between the UserGroup table and ID Generator table.
		// Until the Named ID Generator supports a one-to-many mapping, this method is disabled
		throw new NotFoundException("This service is currently unavailable");
		
		/*
		if (userInfo != null) {
			UserGroup userGroup = userGroupDAO.get(userInfo.getIndividualGroup().getId());
			userGroup.setName(newEmail);
			userGroupDAO.update(userGroup);
		}
		*/
	}

	@Override
	public Collection<Principal> getGroups() throws DatastoreException {
		List<String> groupsToOmit = new ArrayList<String>();
		groupsToOmit.add(AuthorizationConstants.BOOTSTRAP_USER_GROUP_NAME);
		return userGroupDAO.getAllExcept(false, groupsToOmit);
	}

	@Override
	public List<Principal> getGroupsInRange(UserInfo userInfo, long startIncl, long endExcl, String sort, boolean ascending) 
			throws DatastoreException, UnauthorizedException {
		List<String> groupsToOmit = new ArrayList<String>();
		groupsToOmit.add(AuthorizationConstants.BOOTSTRAP_USER_GROUP_NAME);
		return userGroupDAO.getInRangeExcept(startIncl, endExcl, false, groupsToOmit);
	}
}
