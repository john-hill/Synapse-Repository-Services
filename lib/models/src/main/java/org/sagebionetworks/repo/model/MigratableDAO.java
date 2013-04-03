package org.sagebionetworks.repo.model;

import java.util.List;


/**
 * 
 * Interface for DAOs of objects which can be migrated
 * 
 * @author brucehoff
 *
 */
public interface MigratableDAO {
	
	/**
	 * 
	 * @return the number of items in the entire system of the type managed by the DAO
	 * @throws DatastoreException
	 */
	long getCount() throws DatastoreException;
	
	/**
	 * 
	 * @param offset  zero based offset
	 * @param limit page size
	 * @param includeDependencies says whether to include dependencies for each object or omit (for efficiency)
	 * @return paginated list of objects in the system (optionally with their dependencies)
	 * @throws DatastoreException
	 */
	QueryResults<MigratableObjectData> getMigrationObjectData(long offset, long limit, boolean includeDependencies) throws DatastoreException;
	
	/**
	 * The type of object that is being migrated.
	 * @return
	 */
	MigratableObjectType getMigratableObjectType();
	
	/**
	 * Given a list of ID, return the status of every object with that ID.  If the object does not exist then it should be excluded from the results.
	 * 
	 * @param id
	 * @return
	 */
	List<MigratableObjectStatus> listObjectStatus(List<String> ids);

}
