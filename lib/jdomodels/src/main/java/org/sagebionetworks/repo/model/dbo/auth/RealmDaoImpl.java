package org.sagebionetworks.repo.model.dbo.auth;

import static org.sagebionetworks.repo.model.query.jdo.SqlConstants.COL_REALM_ID;
import static org.sagebionetworks.repo.model.query.jdo.SqlConstants.COL_REALM_IDP_PROVIDER;
import static org.sagebionetworks.repo.model.query.jdo.SqlConstants.COL_REALM_IDP_REALM_ID;
import static org.sagebionetworks.repo.model.query.jdo.SqlConstants.TABLE_REALM;
import static org.sagebionetworks.repo.model.query.jdo.SqlConstants.TABLE_REALM_IDP;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.sagebionetworks.ids.IdGenerator;
import org.sagebionetworks.ids.IdType;
import org.sagebionetworks.repo.model.AuthorizationConstants;
import org.sagebionetworks.repo.model.RealmDao;
import org.sagebionetworks.repo.model.auth.IdentityProvider;
import org.sagebionetworks.repo.model.auth.OAuthIdentityProvider;
import org.sagebionetworks.repo.model.auth.Realm;
import org.sagebionetworks.repo.model.auth.RealmIdList;
import org.sagebionetworks.repo.model.auth.SynapseIdentityProvider;
import org.sagebionetworks.repo.model.dbo.DBOBasicDao;
import org.sagebionetworks.repo.model.dbo.SinglePrimaryKeySqlParameterSource;
import org.sagebionetworks.repo.model.dbo.persistence.DBORealm;
import org.sagebionetworks.repo.model.dbo.persistence.DBORealmIdentityProvider;
import org.sagebionetworks.repo.model.oauth.OAuthProvider;
import org.sagebionetworks.repo.transactions.WriteTransaction;
import org.sagebionetworks.repo.web.NotFoundException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

public class RealmDaoImpl implements RealmDao {
	
	@Autowired
	private DBOBasicDao basicDao;
	
	@Autowired
	private IdGenerator idGenerator;
	
	@Autowired
	private NamedParameterJdbcTemplate namedJdbcTemplate;
	
	@Autowired
	private JdbcTemplate jdbcTemplate;
	
	private static final String SYNAPSE_IDENTITY_PROVIDER = "SYNAPSE";
	
	private static final String DEFAULT_REALM_NAME = "SYNAPSE";
	
	private static final String DELETE_IDPS_SQL = "DELETE FROM "+
		TABLE_REALM_IDP+" WHERE "+COL_REALM_IDP_REALM_ID+" = ?";
	
	private static final String REALM_IDP_SQL = "SELECT * FROM "+TABLE_REALM_IDP+" WHERE "+COL_REALM_IDP_REALM_ID+"=?";
	
	private static final String SELECT_ALL_IDS_SQL = "SELECT "+COL_REALM_ID+" FROM "+TABLE_REALM;
	
	private static final String DELETE_REALM_SQL = "DELETE FROM "+TABLE_REALM+" WHERE "+COL_REALM_ID+"=?";
	

	static Long stringToLong(String s) {
		Long result = null;
		if (s!=null) {
			result = Long.parseLong(s);
		}
		return result;
	}
	static DBORealm copyRealmToDBORealm(Realm realm) {
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
	
	static Realm copyDBORealmToRealm(DBORealm dbo) {
		Realm result = new Realm();
		result.setId(dbo.getId().toString());
		result.setEtag(dbo.getEtag());
		result.setName(dbo.getName());
		result.setCreatedOn(dbo.getCreationDate());
		result.setAdministrativeGroup(dbo.getAdministrativeGroup().toString());
		result.setAnonymousUser(dbo.getAnonymousUserId().toString());
		result.setAuthenticatedUsers(dbo.getAuthenticatedUsers().toString());
		result.setPublicGroup(dbo.getPublicGroup().toString());
		return result;
	}
	
	static List<DBORealmIdentityProvider> copyRealmToRealmIdps(Realm realm) {
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
	
	static void copyRealmIdpsToRealm(List<DBORealmIdentityProvider> dboList, Realm realm) {
		List<IdentityProvider> dtoList = new ArrayList<IdentityProvider>();
		for (DBORealmIdentityProvider dbo : dboList) {
			if (SYNAPSE_IDENTITY_PROVIDER.equals(dbo.getIdentityProvider())) {
				dtoList.add(new SynapseIdentityProvider());
			} else {
				OAuthIdentityProvider dto = new OAuthIdentityProvider();
				dto.setProvider(OAuthProvider.valueOf(dbo.getIdentityProvider()));
				dtoList.add(dto);
			}
		}
		realm.setIdentityProvider(dtoList);
	}

	private DBORealm createPrivate(Realm dto) {
		dto.setEtag(UUID.randomUUID().toString());
		dto.setCreatedOn(new Date());
		DBORealm dbo = copyRealmToDBORealm(dto);
		dbo = basicDao.createNew(dbo);
		
		// remove existing IDPs for this realm
		jdbcTemplate.update(DELETE_IDPS_SQL, dto.getId());
		// create the IDPs
		List<DBORealmIdentityProvider> idps = copyRealmToRealmIdps(dto);
		basicDao.createBatch(idps);
		return dbo;
	}


	@WriteTransaction
	@Override
	public Realm createRealm(Realm dto) {
		dto.setId(""+idGenerator.generateNewId(IdType.REALM));
		DBORealm dbo = createPrivate(dto);
		return dto;
	}
	
	@WriteTransaction
	@Override
	public Realm updateRealm(Realm dto) {
		// TODO compare dto.getEtag() to current etag
		dto.setEtag(UUID.randomUUID().toString());
		// TODO don't allow changing ID or createdOn
		// TODO four principals cannot be null
		DBORealm dbo = copyRealmToDBORealm(dto);
		// remove existing IDPs for this realm
		jdbcTemplate.update(DELETE_IDPS_SQL, dto.getId());
		// create the IDPs
		List<DBORealmIdentityProvider> idps = copyRealmToRealmIdps(dto);
		basicDao.createBatch(idps);
		return dto;
	}

	@Override
	public Realm getRealm(String id) {
		SqlParameterSource param = new SinglePrimaryKeySqlParameterSource(id);
		Optional<DBORealm> dboOptional = basicDao.getObjectByPrimaryKeyIfExists(DBORealm.class, param);
		if (dboOptional.isEmpty()) {
			throw new NotFoundException(id);
		}
		DBORealm dbo = dboOptional.get();
		Realm result = copyDBORealmToRealm(dbo);
		List<DBORealmIdentityProvider> idps = namedJdbcTemplate.query(REALM_IDP_SQL, new RowMapper<DBORealmIdentityProvider>(){
			@Override
			public DBORealmIdentityProvider mapRow(ResultSet rs, int rowNum) throws SQLException {
				DBORealmIdentityProvider dboRealmIdp = new DBORealmIdentityProvider();
				dboRealmIdp.setRealmId(rs.getLong(COL_REALM_IDP_REALM_ID));
				dboRealmIdp.setIdentityProvider(rs.getString(COL_REALM_IDP_PROVIDER));
				return dboRealmIdp;
			}});
		copyRealmIdpsToRealm(idps, result);
		return result;
	}

	@Override
	public RealmIdList listRealmIds() {
		RealmIdList result = new RealmIdList();
		result.setRealms(
			namedJdbcTemplate.queryForList(SELECT_ALL_IDS_SQL, new MapSqlParameterSource(), String.class)
		);
		return result;
	}

	@WriteTransaction
	@Override
	public void deleteRealm(long id) {
		jdbcTemplate.update(DELETE_REALM_SQL, id);


	}

	@WriteTransaction
	@Override
	public Realm bootstrapDefaultRealm() {
		Realm defaultRealm = new Realm();
		defaultRealm.setId(AuthorizationConstants.DEFAULT_REALM_ID);
		defaultRealm.setName(DEFAULT_REALM_NAME);
		List<IdentityProvider> idps = new ArrayList<IdentityProvider>();
		SynapseIdentityProvider synapseIdp = new SynapseIdentityProvider();
		idps.add(synapseIdp);
		OAuthIdentityProvider googleIdp = new OAuthIdentityProvider();
		googleIdp.setProvider(OAuthProvider.GOOGLE_OAUTH_2_0);
		idps.add(googleIdp);
		OAuthIdentityProvider orcidIdp = new OAuthIdentityProvider();
		orcidIdp.setProvider(OAuthProvider.ORCID);
		idps.add(orcidIdp);
		defaultRealm.setIdentityProvider(idps);
		// Note that we create the realm without the four realm principals. This
		// is because each principal has to reference its realm.  So we create
		// the realm, then create the principals, and then, finally, update
		// the realm to reference its principals.
		createPrivate(defaultRealm);
		return defaultRealm;
	}

}
