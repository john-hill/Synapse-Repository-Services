package org.sagebionetworks.repo.manager;

import java.util.Collection;
import java.util.List;

import org.sagebionetworks.repo.model.AuthorizationConstants.DEFAULT_GROUPS;
import org.sagebionetworks.repo.model.DatastoreException;
import org.sagebionetworks.repo.model.UnauthorizedException;
import org.sagebionetworks.repo.model.Principal;
import org.sagebionetworks.repo.model.UserInfo;
import org.sagebionetworks.repo.model.auth.NewUser;
import org.sagebionetworks.repo.web.NotFoundException;

public interface UserManager {

	/**
	 * Get the User and UserGroup information for the given user name.
	 * Has the side effect of creating permissions-related objects for the
	 * groups that the user is in.
	 * 
	 * @param userName the name (email address) of the user of interest
	 */
	public UserInfo getUserInfo(String userName) throws DatastoreException, NotFoundException;
	
	/**
	 * Get the User and UserGroup information for the given user ID.
	 * Has the side effect of creating permissions-related objects for the
	 * groups that the user is in.
	 * 
	 * @param principalId the ID of the user of interest
	 */
	public UserInfo getUserInfo(Long principalId) throws DatastoreException, NotFoundException;

	/**
	 * Get a default group
	 * @throws NotFoundException 
	 */
	public Principal getDefaultUserGroup(DEFAULT_GROUPS group) throws DatastoreException, NotFoundException;

	/**
	 * Find a principal using the principal's name
	 * @param principalName
	 * @param isIndividual
	 * @return
	 * @throws NotFoundException 
	 */
	public Principal findPrincipalWithPrincipalName(String principalName, boolean isIndividual) throws NotFoundException;	
	
	/**
	 * Creates a new user
	 */
	public void createUser(NewUser user);
	
	/**
	 * Does a principal exist with the given email address?
	 */
	public boolean doesPrincipalExistWithEmail(String name);
	
	
	/**
	 * Does a principal exist with the given principal name?
	 * 
	 * @param principalName
	 * @return
	 */
	public boolean doesPrincipalExistWithPrincipalName(String principalName);

	/**
	 * @param principalId
	 * @return for a group, returns the group name, for a user returns the display name in the user's profile
	 */
	public String getDisplayName(Long principalId) throws NotFoundException;
	
//	/**
//	 * Returns the group name
//	 */
//	public String getGroupName(String principalId) throws NotFoundException;

	/**
	 * Get all non-individual user groups, including Public.
	 */
	public Collection<Principal> getGroups() throws DatastoreException;

	/**
	 * Get non-individual user groups (including Public) in range
	 **/
	public List<Principal> getGroupsInRange(UserInfo userInfo, long startIncl, long endExcl, String sort, boolean ascending) throws DatastoreException, UnauthorizedException;

	/**
	 * Delete a principal using the principal id.
	 * @param groupId
	 * @throws NotFoundException 
	 * @throws DatastoreException 
	 */
	public void delete(String principalId) throws DatastoreException, NotFoundException;
}
