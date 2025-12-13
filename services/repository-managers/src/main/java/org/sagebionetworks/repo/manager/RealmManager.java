package org.sagebionetworks.repo.manager;

import org.sagebionetworks.repo.model.auth.Realm;
import org.sagebionetworks.repo.model.auth.RealmList;

public interface RealmManager {

	/**
	 * Create a new realm.
	 * @param realm
	 * @return
	 */
	Realm createRealm(Realm realm);
	
	/**
	 * List the existing realms.
	 * @return
	 */
	RealmList listRealms();
	
	/**
	 * Delete a realm.
	 * @param realmId
	 */
	void deleteRealm(String realmId);
}
