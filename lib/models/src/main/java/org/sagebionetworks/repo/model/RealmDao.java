package org.sagebionetworks.repo.model;

import org.sagebionetworks.repo.model.auth.Realm;
import org.sagebionetworks.repo.model.auth.RealmList;

public interface RealmDao {
	
	/**
	 * Create a new Realm and associated identity providers, ensuring the identity providers are unique.
	 * TODO name and IdPs are unique
	 * @param realm
	 * @return
	 */
	public Realm createRealm(Realm realm);
	
	
	/**
	 * 
	 * @param realm
	 * @return
	 */
	public Realm updateRealm(Realm realm);
	
	/**
	 * 
	 * @param id
	 * @return
	 */
	public Realm getRealm(String id);
	
	/**
	 * List the existing realms.  Note, since the number of realms is small, this is not paginated.
	 * @return
	 */
	public RealmList listRealms();
	
	/**
	 * Delete the given realm
	 */
	public void deleteRealm(long id);
	
	
	/**
	 * Create default realm, with id 0
	 */
	public Realm bootstrapDefaultRealm();

}
