package org.sagebionetworks.repo.service;

import org.sagebionetworks.repo.model.auth.Realm;
import org.sagebionetworks.repo.model.auth.RealmIdList;
import org.sagebionetworks.repo.model.auth.RealmPrincipal;

/**
 * Generic service class to support controllers accessing UserGroups.
 *
 */
public interface RealmService {
	/**
	 * 
	 * @return
	 */
	public RealmIdList listRealmIds();

	/**
	 * 
	 * @param realmId
	 * @return
	 */
	public Realm getRealm(String realmId);
	
	public RealmPrincipal getRealmPrincipals(String realmId);
}
