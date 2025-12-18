package org.sagebionetworks.repo.manager;

import org.sagebionetworks.repo.model.auth.Realm;
import org.sagebionetworks.repo.model.auth.RealmIdList;

public interface RealmManager {

	/**
	 * Create a new realm.
	 * @param realm
	 * @return
	 */
	Realm createRealm(Realm realm);
	
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
