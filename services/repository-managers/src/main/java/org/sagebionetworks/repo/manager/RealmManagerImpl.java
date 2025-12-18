package org.sagebionetworks.repo.manager;

import org.sagebionetworks.repo.model.RealmDao;
import org.sagebionetworks.repo.model.UserGroup;
import org.sagebionetworks.repo.model.UserGroupDAO;
import org.sagebionetworks.repo.model.auth.Realm;
import org.sagebionetworks.repo.model.auth.RealmIdList;
import org.sagebionetworks.repo.transactions.WriteTransaction;
import org.sagebionetworks.util.ValidateArgument;
import org.springframework.beans.factory.annotation.Autowired;

public class RealmManagerImpl implements RealmManager {

	@Autowired
	private RealmDao realmDao;
	
	@Autowired
	private UserGroupDAO userGroupDAO;
	
	
	String createRealmPrincipal(String realmId, boolean isIndvidual) {
		UserGroup userGroup = new UserGroup();
		userGroup.setIsIndividual(isIndvidual);
		userGroup.setRealmId(realmId);
		return String.valueOf(userGroupDAO.create(userGroup));
	}

	@Override
	@WriteTransaction
	public Realm createRealm(Realm realm) {
		ValidateArgument.required(realm.getName(), "name");
		ValidateArgument.requiredNotEmpty(realm.getIdentityProvider(), "identity providers");
		realm = realmDao.createRealm(realm);
		realm.setAnonymousUser(createRealmPrincipal(realm.getId(), true));
		realm.setPublicGroup(createRealmPrincipal(realm.getId(), false));
		realm.setAuthenticatedUsers(createRealmPrincipal(realm.getId(), false));
		realm.setAdministrativeGroup(createRealmPrincipal(realm.getId(), false)); /// TODO this should be a team, not just a group
		realm = realmDao.updateRealm(realm);
		return realm;
	}

	@Override
	public RealmIdList listRealmIds() {
		return realmDao.listRealmIds();
	}

	@Override
	@WriteTransaction
	public void deleteRealm(String realmId) {
		realmDao.deleteRealm(Long.parseLong(realmId));
		// TODO delete principals
	}

}
