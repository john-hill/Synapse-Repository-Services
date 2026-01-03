package org.sagebionetworks.repo.manager;

import org.sagebionetworks.repo.model.auth.Realm;
import org.sagebionetworks.repo.model.auth.RealmIdList;

public interface RealmManager {

	/**
	 * Create a new realm.
	 * @param realm the client can only specify the name and the list of identity providers.
	 *   The name must be unique, as must be each identity provider listed.
	 * @return
	 */
	Realm createRealm(Realm realm);
	
	/**
	 * 
	 * @param id
	 * @return
	 */
	Realm getRealm(String id);
	
	
	/**
	 * Update a Realm
	 * @param realm 
	 * @return
	 */
	Realm updateRealm(Realm realm);
	
	
	/**
	 * List the IDS of the existing realms.
	 * @return
	 */
	RealmIdList listRealmIds();
	
	/**
	 * Delete a realm.
	 * @param realmId
	 */
	void deleteRealm(String realmId);
}
