package org.sagebionetworks.repo.model;

import java.util.Collection;
import java.util.List;

import org.sagebionetworks.repo.web.NotFoundException;

public interface PrincipalDAO {
	
	/**
	 * Find a principal using an email address.
	 * @param email
	 * @param isIndividual
	 * @return
	 * @throws NotFoundException 
	 */
	public Principal findUserWithEmail(String email) throws NotFoundException;
	
	/**
	 * Find a principal using the principal's name
	 * @param principalName
	 * @param isIndividual
	 * @return
	 * @throws NotFoundException 
	 */
	public Principal findPrincipalWithPrincipalName(String principalName, boolean isIndividual) throws NotFoundException;	
	
	/**
	 * @return the list of user groups for the given principal IDs
	 */
	public List<Principal> get(List<String> ids) throws DatastoreException;

	/**
	 * a variant of the generic 'getAll' query, this allows the caller to
	 * separately retrieve the individual and non-individual groups.
	 */	
	public Collection<Principal> getAll(boolean isIndividual) throws DatastoreException;

	/**
	 * a variant of the generic 'getAll' query, this allows the caller to
	 * separately retrieve the individual and non-individual groups.
	 */	
	public Collection<Principal> getAllExcept(boolean isIndividual, Collection<String> groupNamesToOmit) throws DatastoreException;

	/**
	 * a variant of the generic 'getInRange' query, this allows the caller to
	 * separately retrieve the individual and non-individual groups.
	 */

	public List<Principal> getInRange(long fromIncl, long toExcl, boolean isIndividual) throws DatastoreException;

	/**
	 * This allows the caller to
	 * separately retrieve the individual and non-individual groups,
	 * while specifying names of groups to filter out
	 */

	public List<Principal> getInRangeExcept(long fromIncl, long toExcl,
			boolean isIndividual, Collection<String> groupNamesToOmit)
			throws DatastoreException;
	/**
	 * Does a principal exist with the given email?
	 */
	public boolean doesPrincipalExistWithEmail(String email);
	
	
	/**
	 * Does a principal exist with the given principal name?
	 * 
	 * @param principalName
	 * @return
	 */
	public boolean doesPrincipalExistWithPrincipalName(String principalName);
	
	/**
	 * Get the bootstrap users.
	 */
	public List<Principal> getBootstrapUsers();
	
	/**
	 * Ensure the bootstrap users exist
	 */
	public void bootstrapUsers() throws Exception;
	
	/**
	 * Gets and locks a row of the table
	 */
	public String getEtagForUpdate(String id);

	/**
	 * Updates the etag the group with the given ID
	 */
	public void touch(String id);

	/**
	 * Create a new principal
	 * @param newPrincipal
	 * @return
	 */
	public String create(Principal newPrincipal);

	/**
	 * Delete a principal using the passed principal ID.
	 * @param id
	 * @throws NotFoundException 
	 * @throws DatastoreException 
	 */
	public void delete(String id) throws DatastoreException, NotFoundException;

	/**
	 * Get a principal using its principal ID.
	 * @param id
	 * @return
	 * @throws NotFoundException 
	 * @throws DatastoreException 
	 */
	public Principal get(String id) throws DatastoreException, NotFoundException;

	public long getCount();

}
