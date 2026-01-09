package org.sagebionetworks.repo.service;

import org.sagebionetworks.repo.manager.RealmManager;
import org.sagebionetworks.repo.model.auth.Realm;
import org.sagebionetworks.repo.model.auth.RealmIdList;
import org.sagebionetworks.repo.model.auth.RealmPrincipal;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class RealmServiceImpl implements RealmService {
	
	@Autowired
	RealmManager realmManager;

	@Override
	public RealmIdList listRealmIds() {
		return realmManager.listRealmIds();
	}

	@Override
	public Realm getRealm(String realmId) {
		return realmManager.getRealm(realmId);
	}

	@Override
	public RealmPrincipal getRealmPrincipals(String realmId) {
		return realmManager.getRealmPrincipals(realmId);

	}
}
