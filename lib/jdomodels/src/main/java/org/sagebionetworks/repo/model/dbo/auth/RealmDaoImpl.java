package org.sagebionetworks.repo.model.dbo.auth;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.sagebionetworks.ids.IdGenerator;
import org.sagebionetworks.ids.IdType;
import org.sagebionetworks.repo.model.DatastoreException;
import org.sagebionetworks.repo.model.RealmDao;
import org.sagebionetworks.repo.model.auth.IdentityProvider;
import org.sagebionetworks.repo.model.auth.OAuthIdentityProvider;
import org.sagebionetworks.repo.model.auth.Realm;
import org.sagebionetworks.repo.model.auth.RealmList;
import org.sagebionetworks.repo.model.auth.SynapseIdentityProvider;
import org.sagebionetworks.repo.model.dbo.DBOBasicDao;
import org.sagebionetworks.repo.model.dbo.persistence.DBORealm;
import org.sagebionetworks.repo.model.dbo.persistence.DBORealmIdentityProvider;
import org.sagebionetworks.repo.model.message.TransactionalMessenger;
import org.sagebionetworks.repo.model.oauth.OAuthProvider;
import org.sagebionetworks.repo.transactions.WriteTransaction;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

public class RealmDaoImpl implements RealmDao {
	
	@Autowired
	private DBOBasicDao basicDao;
	
	@Autowired
	private IdGenerator idGenerator;
	
	@Autowired
	private TransactionalMessenger transactionalMessenger;
	
	@Autowired
	private NamedParameterJdbcTemplate namedJdbcTemplate;
	
	@Autowired
	private JdbcTemplate jdbcTemplate;
	
	private static final String SYNAPSE_IDENTITY_PROVIDER = "SYNAPSE";
	

	Long stringToLong(String s) {
		Long result = null;
		if (s!=null) {
			result = Long.parseLong(s);
		}
		return result;
	}
	DBORealm copyRealmToDBORealm(Realm realm) {
		DBORealm dbo = new DBORealm();
		dbo.setId(stringToLong(realm.getId()));
		dbo.setEtag(realm.getEtag());
		dbo.setName(realm.getName());
		dbo.setCreationDate(realm.getCreatedOn());
		dbo.setAdministrativeGroup(stringToLong(realm.getAdministrativeGroup()));
		dbo.setAnonymousUserId(stringToLong(realm.getAnonymousUser()));
		dbo.setAuthenticatedUsers(stringToLong(realm.getAuthenticatedUsers()));
		dbo.setPublicGroup(stringToLong(realm.getPublicGroup()));
		return dbo;
	}
	
	List<DBORealmIdentityProvider> copyRealmToRealmIdps(Realm realm) {
		List<DBORealmIdentityProvider> result = new ArrayList<DBORealmIdentityProvider>();
		if (realm.getIdentityProvider()!=null) {
			for (IdentityProvider idp : realm.getIdentityProvider()) {
				DBORealmIdentityProvider realmIdp = new DBORealmIdentityProvider();
				realmIdp.setRealmId(stringToLong(realm.getId()));
				if (idp instanceof SynapseIdentityProvider) {
					realmIdp.setIdentityProvider(SYNAPSE_IDENTITY_PROVIDER);
				} else if (idp instanceof OAuthIdentityProvider) {
					realmIdp.setIdentityProvider(((OAuthIdentityProvider)idp).getProvider().name());
				} else {
					throw new IllegalArgumentException("Unexpected type "+idp.getClass().getName());
				}
				result.add(realmIdp);
			}
		}
		return result;
	}

	private DBORealm createPrivate(Realm dto) {
		DBORealm dbo = copyRealmToDBORealm(dto);
		dbo.setEtag(UUID.randomUUID().toString());
		// Bootstrapped realm will have ID already assigned.
		if(dbo.getId() == null){
			// We allow the ID generator to create all other IDs
			dbo.setId(idGenerator.generateNewId(IdType.REALM));
		}
		
		try {
			dbo = basicDao.createNew(dbo);
		} catch (Exception e) {
			throw new DatastoreException("id=" + dbo.getId(), e);
		}	
		
		List<DBORealmIdentityProvider> realmIdpList = copyRealmToRealmIdps(dto);
		// TODO recreate the records
		
		return dbo;
	}


	@WriteTransaction
	@Override
	public Realm createRealm(Realm dto) {
		// The public version unconditionally clears the ID so a new one will be assigned
		dto.setId(null);
		DBORealm dbo = createPrivate(dto);
		return dto;
	}
	
	@WriteTransaction
	@Override
	public Realm updateRealm(Realm realm) {
		// TODO Auto-generated method stub
		// TODO don't forget to delete the existing IDP records before creating the new ones
		return null;
	}

	@Override
	public Realm getRealm(String id) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public RealmList listRealms() {
		// TODO Auto-generated method stub
		return null;
	}

	@WriteTransaction
	@Override
	public void deleteRealm(long id) {
		// TODO Auto-generated method stub

	}

	@WriteTransaction
	@Override
	public Realm bootstrapDefaultRealm() {
		// TODO Auto-generated method stub
		return null;
	}

}
