package org.sagebionetworks.repo.model.dbo.dao;

import static org.sagebionetworks.repo.model.query.jdo.SqlConstants.COL_PRINCIPAL_EMAIL;
import static org.sagebionetworks.repo.model.query.jdo.SqlConstants.COL_PRINCIPAL_E_TAG;
import static org.sagebionetworks.repo.model.query.jdo.SqlConstants.COL_PRINCIPAL_ID;
import static org.sagebionetworks.repo.model.query.jdo.SqlConstants.COL_PRINCIPAL_IS_INDIVIDUAL;
import static org.sagebionetworks.repo.model.query.jdo.SqlConstants.COL_PRINCIPAL_PRINCIPAL_NAME_UNIQUE;
import static org.sagebionetworks.repo.model.query.jdo.SqlConstants.LIMIT_PARAM_NAME;
import static org.sagebionetworks.repo.model.query.jdo.SqlConstants.OFFSET_PARAM_NAME;
import static org.sagebionetworks.repo.model.query.jdo.SqlConstants.TABLE_PRINCIPAL;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.sagebionetworks.ids.IdGenerator;
import org.sagebionetworks.ids.IdGenerator.TYPE;
import org.sagebionetworks.repo.model.DatastoreException;
import org.sagebionetworks.repo.model.InvalidModelException;
import org.sagebionetworks.repo.model.Principal;
import org.sagebionetworks.repo.model.PrincipalDAO;
import org.sagebionetworks.repo.model.dbo.DBOBasicDao;
import org.sagebionetworks.repo.model.dbo.persistence.DBOCredential;
import org.sagebionetworks.repo.model.dbo.persistence.DBOPrincipal;
import org.sagebionetworks.repo.model.query.jdo.SqlConstants;
import org.sagebionetworks.repo.web.NotFoundException;
import org.sagebionetworks.securitytools.HMACUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.simple.SimpleJdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

public class DBOPrincipalDaoImpl implements PrincipalDAO {

	@Autowired
	private DBOBasicDao basicDao;

	@Autowired
	private IdGenerator idGenerator;
	
	@Autowired
	private SimpleJdbcTemplate simpleJdbcTemplate;
	
	private List<Principal> bootstrapUsers;
	
	private static final String ID_PARAM_NAME = "id";
	private static final String NAME_PARAM_NAME = "name";
	private static final String IS_INDIVIDUAL_PARAM_NAME = "isIndividual";
	private static final String ETAG_PARAM_NAME = "etag";
	
	private static final String SELECT_MULTI_BY_PRINCIPAL_IDS = 
			"SELECT * FROM "+TABLE_PRINCIPAL+
			" WHERE "+COL_PRINCIPAL_ID+" IN (:"+ID_PARAM_NAME+")";
	
	private static final String SELECT_BY_IS_INDIVID_OMITTING_SQL = 
			"SELECT * FROM "+TABLE_PRINCIPAL+
			" WHERE "+COL_PRINCIPAL_IS_INDIVIDUAL+"=:"+IS_INDIVIDUAL_PARAM_NAME+
			" AND "+COL_PRINCIPAL_PRINCIPAL_NAME_UNIQUE+" NOT IN (:"+NAME_PARAM_NAME+") LIMIT :"+LIMIT_PARAM_NAME+" OFFSET :"+OFFSET_PARAM_NAME;
		
	private static final String SELECT_ETAG_AND_LOCK_ROW_BY_ID = 
			"SELECT "+COL_PRINCIPAL_E_TAG+" FROM "+TABLE_PRINCIPAL+
			" WHERE "+COL_PRINCIPAL_ID+"=:"+ID_PARAM_NAME+
			" FOR UPDATE";
	
	private static final String UPDATE_ETAG_LIST = 
			"UPDATE "+TABLE_PRINCIPAL+
			" SET "+COL_PRINCIPAL_E_TAG+"=:"+ETAG_PARAM_NAME+
			" WHERE "+COL_PRINCIPAL_ID+"=:"+ID_PARAM_NAME;

	private static final String SQL_COUNT_USER_GROUPS = "SELECT COUNT("+COL_PRINCIPAL_ID+") FROM "+TABLE_PRINCIPAL + " WHERE "+COL_PRINCIPAL_ID+"=:"+ID_PARAM_NAME;

	private static final RowMapper<DBOPrincipal> userGroupRowMapper = (new DBOPrincipal()).getTableMapping();
	
	
	/**
	 * This is injected by Spring
	 * @param bootstrapUsers
	 */
	public void setBootstrapUsers(List<Principal> bootstrapUsers) {
		this.bootstrapUsers = bootstrapUsers;
	}

	@Override
	public List<Principal> getBootstrapUsers() {
		return this.bootstrapUsers;
	}
	
	@Override
	public long getCount()  throws DatastoreException {
		return basicDao.getCount(DBOPrincipal.class);
	}

	@Override
	@Transactional(readOnly = false, propagation = Propagation.REQUIRED)
	public String create(Principal dto) throws DatastoreException,
			InvalidModelException {
		// Validate the principal name
		PrincipalUtils.validatePrincipalName(dto.getPrincipalName(), dto.getIsIndividual());
		
		// Users must have a valid email address
		if(dto.getIsIndividual()){
			if(dto.getEmail() == null){
				throw new IllegalArgumentException("New users cannot have a null email");
			}
			if(!dto.getEmail().contains("@")){
				throw new IllegalArgumentException("New users must have a valid email. The provide email is invalid: "+dto.getEmail());
			}
		}
		
		DBOPrincipal dbo = PrincipalUtils.createDBO(dto);		
		// If the create is successful, it should have a new etag
		dbo.setEtag(UUID.randomUUID().toString());
		dbo.setId(generateNewPrinipalId());

		createPrincipalPrivate(dbo);
		return dbo.getId().toString();
	}
	
	/**
	 * A helper to generate new principal IDs.  When we are done migrating to the new 
	 * ID generator we can remove this and just use the ID generator.
	 * @return
	 */
	private long generateNewPrinipalId(){
		// Since we are in the process of moving from one ID generator to another
		// we need to ensure that the new ID generator never issues IDs that
		// are already in use.  We will be able to remove this after this
		// code is deployed to production (Stack 24).
		idGenerator.reserveId(getMaxUserId(), TYPE.PRINCIPAL_ID);
		// Now get the next ID
		return idGenerator.generateNewId(TYPE.PRINCIPAL_ID);
	}
	
	private long getMaxUserId(){
		return this.simpleJdbcTemplate.queryForLong("SELECT MAX("+COL_PRINCIPAL_ID+") FROM "+TABLE_PRINCIPAL);
	}
	/**
	 * Do not make this public!
	 * 
	 * @param dbo
	 * @return
	 */
	private void createPrincipalPrivate(DBOPrincipal dbo){
		try {
			dbo = basicDao.createNew(dbo);
		} catch (Exception e) {
			throw new DatastoreException("id=" + dbo.getId() + " name="+dbo.getPrincipalNameDisplay(), e);
		}
		Boolean isIndividual = dbo.getIsIndividual();
		if (isIndividual != null && isIndividual.booleanValue()) {
			// Create a row for the authentication DAO
			DBOCredential credDBO = new DBOCredential();
			credDBO.setPrincipalId(dbo.getId());
			credDBO.setSecretKey(HMACUtils.newHMACSHA1Key());
			basicDao.createNew(credDBO);
		}
	}

	public boolean doesIdExist(Long id) {
		Map<String, Object> parameters = new HashMap<String, Object>();
		parameters.put(ID_PARAM_NAME, id);
		try{
			long count = simpleJdbcTemplate.queryForLong(SQL_COUNT_USER_GROUPS, parameters);
			return count > 0;
		}catch(Exception e){
			// Can occur when the schema does not exist.
			return false;
		}
	}
	
	@Override
	public Principal get(String id) throws DatastoreException,
			NotFoundException {
		MapSqlParameterSource param = new MapSqlParameterSource();
		param.addValue(ID_PARAM_NAME, id);
		DBOPrincipal dbo;
		try {
			dbo = basicDao.getObjectByPrimaryKey(DBOPrincipal.class, param);
		} catch (NotFoundException e) {
			// Rethrow the basic DAO's generic error message
			throw new NotFoundException("Principal (" + id + ") does not exist", e);
		}
		return PrincipalUtils.createDTO(dbo);
	}
	
	@Override
	public List<Principal> get(List<String> ids) throws DatastoreException {
		List<Principal> dtos = new ArrayList<Principal>();
		if (ids.isEmpty()) {
			return dtos;
		}
		MapSqlParameterSource param = new MapSqlParameterSource();
		param.addValue(ID_PARAM_NAME, ids);
		List<DBOPrincipal> dbos = simpleJdbcTemplate.query(SELECT_MULTI_BY_PRINCIPAL_IDS, userGroupRowMapper, param);
		return PrincipalUtils.createDTOs(dbos);
	}

	@Transactional(readOnly = false, propagation = Propagation.REQUIRED)
	@Override
	public void delete(String id) throws DatastoreException, NotFoundException {
		MapSqlParameterSource param = new MapSqlParameterSource();
		param.addValue(ID_PARAM_NAME, id);
		basicDao.deleteObjectByPrimaryKey(DBOPrincipal.class, param);
	}
	
	/**
	 * This is called by Spring after all properties are set
	 */
	@Override
	@Transactional(readOnly = false, propagation = Propagation.REQUIRED)
	public void bootstrapUsers() throws Exception {
		// Boot strap all users and groups
		if (this.bootstrapUsers == null) {
			throw new IllegalArgumentException("bootstrapUsers cannot be null");
		}
		
		// For each one determine if it exists, if not create it
		for (Principal principal: this.bootstrapUsers) {
			if (principal.getId() == null) {
				throw new IllegalArgumentException("Bootstrap users must have an id");
			}
			if (principal.getPrincipalName() == null) {
				throw new IllegalArgumentException("Bootstrap users must have a principalName");
			}
			if (!this.doesIdExist(Long.parseLong(principal.getId()))) {
				principal.setEtag(UUID.randomUUID().toString());
				// Create this users
				DBOPrincipal dbo = PrincipalUtils.createDBO(principal);
				dbo.setCreationDate(new Date(System.currentTimeMillis()));
				this.createPrincipalPrivate(dbo);
			}
		}

//		// A few additional users are required for testing
//		if (!StackConfiguration.isProductionStack()) {
//			String testUsers[] = new String[]{ 
//					StackConfiguration.getIntegrationTestUserAdminName(), 
//					StackConfiguration.getIntegrationTestRejectTermsOfUseName(), 
//					StackConfiguration.getIntegrationTestUserOneName(), 
//					StackConfiguration.getIntegrationTestUserTwoName(), 
//					StackConfiguration.getIntegrationTestUserThreeName(), 
//					AuthorizationConstants.ADMIN_USER_NAME, 
//					AuthorizationConstants.TEST_GROUP_NAME, 
//					AuthorizationConstants.TEST_USER_NAME };
//			for (String username : testUsers) {
//				if (!this.doesPrincipalExist(username)) {
//					Principal ug = new Principal();
//					ug.setPrincipalName(username);
//					ug.setIsIndividual(!username.equals(AuthorizationConstants.TEST_GROUP_NAME));
//					ug.setId(this.create(ug));
//				}
//				Principal ug = new Principal();
//				PrincipalUtils.copyDboToDto(this.findGroup(username), ug);
//				ug.setCreationDate(null);
//				ug.setEtag(null);
//				ug.setUri(null);
//				if (!this.bootstrapUsers.contains(ug)) {
//					this.bootstrapUsers.add(ug);
//				}
//			}
//		}
	}
	
	@Transactional(readOnly = false, propagation = Propagation.REQUIRED)
	@Override
	public String getEtagForUpdate(String id) {
		MapSqlParameterSource param = new MapSqlParameterSource();
		param.addValue(ID_PARAM_NAME, id);
		return simpleJdbcTemplate.queryForObject(SELECT_ETAG_AND_LOCK_ROW_BY_ID, 
				new RowMapper<String>() {
					@Override
					public String mapRow(ResultSet rs, int rowNum)
							throws SQLException {
						return rs.getString(SqlConstants.COL_PRINCIPAL_E_TAG);
					}
				}, param);
	}

	@Transactional(readOnly = false, propagation = Propagation.REQUIRED)
	@Override
	public void touch(String id) {
		MapSqlParameterSource param = new MapSqlParameterSource();
		param.addValue(ID_PARAM_NAME, id);
		param.addValue(ETAG_PARAM_NAME, UUID.randomUUID().toString());
		simpleJdbcTemplate.update(UPDATE_ETAG_LIST, param);
	}

	@Override
	public boolean doesPrincipalExistWithEmail(String email) {
		if(email == null) throw new IllegalArgumentException("Email cannot be null");
		// lookup a user with their email address
		long count = simpleJdbcTemplate.queryForLong("SELECT COUNT("+COL_PRINCIPAL_ID+") FROM "+TABLE_PRINCIPAL+" WHERE "+COL_PRINCIPAL_EMAIL+" = ?", email.toLowerCase());
		return count > 0;
	}

	@Override
	public boolean doesPrincipalExistWithPrincipalName(String principalName) {
		if(principalName == null) throw new IllegalArgumentException("Principal Name cannot be null");
		// Convert the name to the unique name for this lookup
		String uniqueName = PrincipalUtils.getUniquePrincipalName(principalName);
		// lookup a user with their email address
		long count = simpleJdbcTemplate.queryForLong("SELECT COUNT("+COL_PRINCIPAL_ID+") FROM "+TABLE_PRINCIPAL+" WHERE "+COL_PRINCIPAL_PRINCIPAL_NAME_UNIQUE+" = ?", uniqueName);
		return count > 0;
	}

	@Override
	public Principal findUserWithEmail(String email) throws NotFoundException {
		if(email == null) throw new IllegalArgumentException("Email cannot be null");
		try {
			// lookup a user with their email address
			DBOPrincipal dbo = simpleJdbcTemplate.queryForObject("SELECT * FROM "+TABLE_PRINCIPAL+" WHERE "+COL_PRINCIPAL_EMAIL+" = ?", userGroupRowMapper, email.toLowerCase());
			return PrincipalUtils.createDTO(dbo);
		} catch (EmptyResultDataAccessException e) {
			throw new NotFoundException("A user does not exist with email: "+email);
		}
	}

	@Override
	public Principal findPrincipalWithPrincipalName(String principalName, boolean isIndividual) throws NotFoundException {
		if(principalName == null) throw new IllegalArgumentException("Principal Name cannot be null");
		// Convert the name to the unique name for this lookup
		String uniqueName = PrincipalUtils.getUniquePrincipalName(principalName);
		try {
			// lookup a user with their email address
			DBOPrincipal dbo = simpleJdbcTemplate.queryForObject("SELECT * FROM "+TABLE_PRINCIPAL+" WHERE "+COL_PRINCIPAL_PRINCIPAL_NAME_UNIQUE+" = ? AND "+COL_PRINCIPAL_IS_INDIVIDUAL+" = ?", userGroupRowMapper, uniqueName, isIndividual);
			return PrincipalUtils.createDTO(dbo);
		} catch (EmptyResultDataAccessException e) {
			throw new NotFoundException("A principal does not exist with principalName: "+principalName+" and isIndividual: "+isIndividual);
		}
	}

	@Override
	public List<Principal> getAllPrincipals(long limit, long offset) {
    	List<DBOPrincipal> dbos = simpleJdbcTemplate.query("SELECT * FROM "+TABLE_PRINCIPAL+" LIMIT ? OFFSET ?", userGroupRowMapper, limit, offset);
    	return PrincipalUtils.createDTOs(dbos);
	}

	@Override
	public List<Principal> getAllPrincipals(boolean isIndividual,
			long limit, long offset) throws DatastoreException {
    	List<DBOPrincipal> dbos = simpleJdbcTemplate.query("SELECT * FROM "+TABLE_PRINCIPAL+" WHERE "+COL_PRINCIPAL_IS_INDIVIDUAL+" = ? LIMIT ? OFFSET ?", userGroupRowMapper, isIndividual, limit, offset);
    	return PrincipalUtils.createDTOs(dbos);
	}

	@Override
	public List<Principal> getAllPrincipalsExcept(boolean isIndividual,
			List<String> principalNamesToExclude, long limit, long offset)
			throws DatastoreException {
		// the SQL will be invalid for an empty list, so we 'divert' that case:
		if (principalNamesToExclude.isEmpty()) return getAllPrincipals(isIndividual, limit, offset);
		// Use the unique names
		List<String> uniqueNames = new LinkedList<String>();
		for(String original: principalNamesToExclude){
			uniqueNames.add(PrincipalUtils.getUniquePrincipalName(original));
		}
		MapSqlParameterSource param = new MapSqlParameterSource();
		param.addValue(IS_INDIVIDUAL_PARAM_NAME, isIndividual);		
		param.addValue(OFFSET_PARAM_NAME, offset);
		if (limit<=0) throw new IllegalArgumentException("'to' param must be greater than 'from' param.");
		param.addValue(LIMIT_PARAM_NAME, limit);	
		param.addValue(NAME_PARAM_NAME, uniqueNames);
		List<DBOPrincipal> dbos = simpleJdbcTemplate.query(SELECT_BY_IS_INDIVID_OMITTING_SQL, userGroupRowMapper, param);
		return PrincipalUtils.createDTOs(dbos);
	}
	
}
