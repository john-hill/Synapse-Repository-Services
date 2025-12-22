package org.sagebionetworks.repo.model;

import org.sagebionetworks.repo.model.auth.Realm;
import org.sagebionetworks.repo.model.auth.RealmIdList;

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
	 * List the IDs of the existing realms.  Note, since the number of realms is small, this is not paginated.
	 * @return
	 */
	public RealmIdList listRealmIds();
	
	/**
	 * Delete the given realm
	 */
	public void deleteRealm(long id);
	
	
	/**
	 * Create default realm, with id 0
	 */
	public void bootstrapDefaultRealm();

	/**
	 * Add principals to default realm
	 */
	public void addPrincipalsToDefaultRealm();

}
