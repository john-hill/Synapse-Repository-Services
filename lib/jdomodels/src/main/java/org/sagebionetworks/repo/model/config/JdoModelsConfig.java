package org.sagebionetworks.repo.model.config;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.sagebionetworks.StackConfiguration;
import org.sagebionetworks.database.semaphore.SemaphoreConfig;
import org.sagebionetworks.ids.IdGenerator;
import org.sagebionetworks.ids.IdGeneratorConfig;
import org.sagebionetworks.repo.model.AuthorizationConstants.BOOTSTRAP_PRINCIPAL;
import org.sagebionetworks.repo.model.GroupMembersDAO;
import org.sagebionetworks.repo.model.RealmDao;
import org.sagebionetworks.repo.model.UserGroupDAO;
import org.sagebionetworks.repo.model.dbo.DBOBasicDao;
import org.sagebionetworks.repo.model.dbo.DBOBasicDaoImpl;
import org.sagebionetworks.repo.model.dbo.DDLUtils;
import org.sagebionetworks.repo.model.dbo.DDLUtilsImpl;
import org.sagebionetworks.repo.model.dbo.DatabaseObject;
import org.sagebionetworks.repo.model.dbo.auth.RealmDaoImpl;
import org.sagebionetworks.repo.model.dbo.dao.DBOGroupMembersDAOImpl;
import org.sagebionetworks.repo.model.dbo.dao.DBOUserGroupDAOImpl;
import org.sagebionetworks.repo.model.dbo.migration.MigratableTableDAO;
import org.sagebionetworks.repo.model.dbo.migration.MigratableTableDAOImpl;
import org.sagebionetworks.repo.model.dbo.migration.MigrationTypeProvider;
import org.sagebionetworks.repo.model.dbo.migration.MigrationTypeProviderImpl;
import org.sagebionetworks.repo.model.message.TransactionalMessenger;
import org.sagebionetworks.repo.model.principal.BootstrapAlias;
import org.sagebionetworks.repo.model.principal.BootstrapGroup;
import org.sagebionetworks.repo.model.principal.BootstrapPrincipal;
import org.sagebionetworks.repo.model.principal.BootstrapUser;
import org.sagebionetworks.util.Clock;
import org.sagebionetworks.util.DefaultClock;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * JDO Models configuration for DBO infrastructure beans.
 *
 * This config provides core DBO infrastructure (DBOBasicDao, DDLUtils,
 * TransactionalMessenger) and bootstrap data. It includes component scanning
 * for @Repository DAOs that have been converted from XML.
 *
 * For tests that need additional beans from dao-beans.spb.xml, use:
 * 
 * @ContextConfiguration(locations = { "classpath:jdomodels-test-context.xml" })
 */
@Configuration
@Import({ DatabaseInfrastructureConfiguration.class, SemaphoreConfig.class, IdGeneratorConfig.class })
@org.springframework.context.annotation.ComponentScan(basePackages = { "org.sagebionetworks.repo.model.dbo.auth",
		"org.sagebionetworks.repo.model.dbo.dao", "org.sagebionetworks.repo.model.dbo.principal",
		"org.sagebionetworks.repo.model.dbo.wikiV2", "org.sagebionetworks.repo.model.message" })
public class JdoModelsConfig {

	@Bean
	public DDLUtils ddlUtils(JdbcTemplate jdbcTemplate, StackConfiguration stackConfiguration) {
		return new DDLUtilsImpl(jdbcTemplate, stackConfiguration);
	}

	@Bean
	List<DatabaseObject> getAllDBOs() {
		return DboAutoDiscovery.discoverAllDatabaseObjects();
	}

	@Bean
	public DBOBasicDao dboBasicDao(DDLUtils ddlUtils, JdbcTemplate jdbcTemplate,
			NamedParameterJdbcTemplate namedJdbcTemplate, List<DatabaseObject> alldbos) {
		return new DBOBasicDaoImpl(ddlUtils, jdbcTemplate, namedJdbcTemplate, alldbos, createFunctionMap());
	}

	/**
	 * Creates the map of MySQL functions to be created/updated. These are custom
	 * MySQL functions used by the application.
	 */
	private Map<String, String> createFunctionMap() {
		Map<String, String> functionMap = new HashMap<>();
		functionMap.put("getEntityBenefactorId", "schema/functions/GetEntityBenefactorId.ddl.sql");
		functionMap.put("getEntityProjectId", "schema/functions/GetEntityProjectId.ddl.sql");
		functionMap.put("getEntityHierarchy", "schema/functions/GetEntityHierarchy.ddl.sql");
		return functionMap;
	}

	/**
	 * Creates the MigratableTableDAO bean with primary migration objects. The order
	 * of this list determines migration order - dependencies first!
	 */
	@Bean(initMethod = "initialize")
	@DependsOn("dboBasicDao")
	public MigratableTableDAO migratableTableDAO(@Qualifier("migrationJdbcTemplate") JdbcTemplate migrationJdbcTemplate,
			StackConfiguration stackConfiguration, List<DatabaseObject> allDbos) {
		MigratableTableDAOImpl dao = new MigratableTableDAOImpl(migrationJdbcTemplate, stackConfiguration);
		dao.setDatabaseObjectRegister(DboAutoDiscovery.discoverPrimaryMigratableDatabaseObjects(allDbos));
		return dao;
	}

	/**
	 * Creates the list of primary migration objects. Order matters - this list
	 * determines the migration order. migratableTableDAO property.
	 */
	@SuppressWarnings({ "rawtypes", "unchecked" })

	@Bean
	public Clock clock() {
		return new DefaultClock();
	}

	/**
	 * Bootstrap principals that are created on system initialization. DO NOT CHANGE
	 * ANY OF THESE IDS as they represent real objects in production.
	 */
	@Bean
	public List<BootstrapPrincipal> bootstrapPrincipals() {
		// @formatter:off
		return List.of(
			new BootstrapUser()
				.setId(BOOTSTRAP_PRINCIPAL.THE_ADMIN_USER.getPrincipalId())
				.setEmail(new BootstrapAlias().setAliasName("migrationAdmin@sagebase.org").setAliasId(1L))
				.setUserName(new BootstrapAlias().setAliasName("migrationAdmin").setAliasId(11866L)),

			new BootstrapGroup()
				.setId(BOOTSTRAP_PRINCIPAL.ADMINISTRATORS_GROUP.getPrincipalId())
				.setGroupAlias(new BootstrapAlias().setAliasName("Administrators").setAliasId(2L)),

			new BootstrapGroup()
				.setId(BOOTSTRAP_PRINCIPAL.AUTHENTICATED_USERS_GROUP.getPrincipalId())
				.setGroupAlias(new BootstrapAlias().setAliasName("AUTHENTICATED_USERS").setAliasId(3L)),

			new BootstrapGroup()
				.setId(BOOTSTRAP_PRINCIPAL.PUBLIC_GROUP.getPrincipalId())
				.setGroupAlias(new BootstrapAlias().setAliasName("PUBLIC").setAliasId(4L)),

			new BootstrapUser()
				.setId(BOOTSTRAP_PRINCIPAL.ANONYMOUS_USER.getPrincipalId())
				.setEmail(new BootstrapAlias().setAliasName("anonymous@sagebase.org").setAliasId(5L))
				.setUserName(new BootstrapAlias().setAliasName("anonymous").setAliasId(11867L)),

			new BootstrapGroup()
				.setId(BOOTSTRAP_PRINCIPAL.CERTIFIED_USERS.getPrincipalId())
				.setGroupAlias(new BootstrapAlias().setAliasName("CERTIFIED_USERS").setAliasId(6L)),

			new BootstrapGroup()
				.setId(BOOTSTRAP_PRINCIPAL.SYNAPSE_TESTING_GROUP.getPrincipalId())
				.setGroupAlias(new BootstrapAlias().setAliasName("TESTING_USERS").setAliasId(7L)),

			new BootstrapUser()
				.setId(BOOTSTRAP_PRINCIPAL.DATA_ACCESS_NOTFICATIONS_SENDER.getPrincipalId())
				.setEmail(new BootstrapAlias().setAliasName("act@sagebase.org").setAliasId(172631L))
				.setUserName(new BootstrapAlias().setAliasName("ACTAccessManagement").setAliasId(172630L)),

			new BootstrapGroup()
				.setId(BOOTSTRAP_PRINCIPAL.SAGE_BIONETWORKS.getPrincipalId())
				.setGroupAlias(new BootstrapAlias().setAliasName("Sage Bionetworks").setAliasId(11577L))
		);
		// @formatter:on
	}

	/**
	 * Creates MigrationTypeProvider from the MigratableTableDAO. This bean remains
	 * here because it depends on MigratableTableDAO which is defined in
	 * lib-jdomodels.
	 */
	@Bean
	public MigrationTypeProvider createMigrationTypeProvider(MigratableTableDAO migratableTableDao) {
		return new MigrationTypeProviderImpl(migratableTableDao.getAllMigratableTypes());
	}

	@Bean(name = "realmDao")
	public RealmDao getRealmDao(DBOBasicDao basicDao, IdGenerator idGenerator,
			NamedParameterJdbcTemplate namedJdbcTemplate, JdbcTemplate jdbcTemplate) {
		RealmDaoImpl dao = new RealmDaoImpl(basicDao, idGenerator, namedJdbcTemplate, jdbcTemplate);
		dao.bootstrapDefaultRealm();
		return dao;
	}

	@Bean(name = "userGroupDAO")
	public UserGroupDAO getUserGroupDAO(RealmDao realmDao, DBOBasicDao basicDao, IdGenerator idGenerator,
			TransactionalMessenger transactionalMessenger, NamedParameterJdbcTemplate namedJdbcTemplate,
			JdbcTemplate jdbcTemplate, List<BootstrapPrincipal> bootstrapPrincipals) {
		DBOUserGroupDAOImpl dao = new DBOUserGroupDAOImpl(basicDao, idGenerator, transactionalMessenger,
				namedJdbcTemplate, jdbcTemplate, bootstrapPrincipals);
		try {
			dao.bootstrapUsers();
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
		java.util.Map<String, Long> principalIdToRealmPrincipalDboId = new java.util.HashMap<>();
		principalIdToRealmPrincipalDboId.put(BOOTSTRAP_PRINCIPAL.ANONYMOUS_USER.getPrincipalId().toString(), 1L);
		principalIdToRealmPrincipalDboId.put(BOOTSTRAP_PRINCIPAL.AUTHENTICATED_USERS_GROUP.getPrincipalId().toString(),
				2L);
		principalIdToRealmPrincipalDboId.put(BOOTSTRAP_PRINCIPAL.PUBLIC_GROUP.getPrincipalId().toString(), 3L);
		principalIdToRealmPrincipalDboId.put(BOOTSTRAP_PRINCIPAL.ADMINISTRATORS_GROUP.getPrincipalId().toString(), 4L);

		realmDao.addPrincipalsToDefaultRealm(principalIdToRealmPrincipalDboId);
		return dao;
	}

	/**
	 * Creates GroupMembersDAO with dependency on UserGroupDAO to ensure proper
	 * initialization order. Note: The bootstrapGroups() method is currently a no-op
	 * - actual group bootstrap happens in TeamManagerImpl.bootstrapTeams().
	 */
	@Bean
	public GroupMembersDAO groupMembersDAO(NamedParameterJdbcTemplate namedJdbcTemplate, JdbcTemplate jdbcTemplate,
			UserGroupDAO userGroupDAO, TransactionalMessenger transactionalMessenger) {
		DBOGroupMembersDAOImpl dao = new DBOGroupMembersDAOImpl(namedJdbcTemplate, jdbcTemplate, userGroupDAO,
				transactionalMessenger);
		try {
			dao.bootstrapGroups();
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
		return dao;
	}

}
