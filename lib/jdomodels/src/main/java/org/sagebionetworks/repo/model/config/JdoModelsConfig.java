package org.sagebionetworks.repo.model.config;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.sagebionetworks.StackConfiguration;
import org.sagebionetworks.database.semaphore.SemaphoreConfig;
import org.sagebionetworks.ids.IdGenerator;
import org.sagebionetworks.ids.IdGeneratorConfig;
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
 * This config provides core DBO infrastructure (DBOBasicDao, DDLUtils, TransactionalMessenger)
 * and bootstrap data. It includes component scanning for @Repository DAOs that have been
 * converted from XML.
 *
 * For tests that need additional beans from dao-beans.spb.xml, use:
 * @ContextConfiguration(locations = { "classpath:jdomodels-test-context.xml" })
 */
@Configuration
@Import({ DatabaseInfrastructureConfiguration.class, SemaphoreConfig.class, IdGeneratorConfig.class })
@org.springframework.context.annotation.ComponentScan(basePackages = {
	"org.sagebionetworks.repo.model.dbo.auth",
	"org.sagebionetworks.repo.model.dbo.dao",
	"org.sagebionetworks.repo.model.dbo.principal",
	"org.sagebionetworks.repo.model.dbo.wikiV2",
	"org.sagebionetworks.repo.model.message"
})
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
	 *
	 * Using fluent setters (Java 8 compatible) for clean initialization.
	 */
	@Bean
	public List<BootstrapPrincipal> bootstrapPrincipals() {
		// @formatter:off
		return List.of(
			// Migration Admin User (ID: 1)
			new BootstrapUser()
				.setId(1L)
				.setEmail(new BootstrapAlias().setAliasName("migrationAdmin@sagebase.org").setAliasId(1L))
				.setUserName(new BootstrapAlias().setAliasName("migrationAdmin").setAliasId(11866L)),

			// Administrators Group (ID: 2)
			new BootstrapGroup()
				.setId(2L)
				.setGroupAlias(new BootstrapAlias().setAliasName("Administrators").setAliasId(2L)),

			// AUTHENTICATED_USERS Group (ID: 273948)
			new BootstrapGroup()
				.setId(273948L)
				.setGroupAlias(new BootstrapAlias().setAliasName("AUTHENTICATED_USERS").setAliasId(3L)),

			// PUBLIC Group (ID: 273949)
			new BootstrapGroup()
				.setId(273949L)
				.setGroupAlias(new BootstrapAlias().setAliasName("PUBLIC").setAliasId(4L)),

			// Anonymous User (ID: 273950)
			new BootstrapUser()
				.setId(273950L)
				.setEmail(new BootstrapAlias().setAliasName("anonymous@sagebase.org").setAliasId(5L))
				.setUserName(new BootstrapAlias().setAliasName("anonymous").setAliasId(11867L)),

			// Certified Users Group (ID: 464532)
			new BootstrapGroup()
				.setId(464532L)
				.setGroupAlias(new BootstrapAlias().setAliasName("Certified Users").setAliasId(12L)),

			// Synapse Report Team (ID: 4689)
			new BootstrapGroup()
				.setId(4689L)
				.setGroupAlias(new BootstrapAlias().setAliasName("Synapse Report Team").setAliasId(13L)),

			// Trusted Message Senders (ID: 3392315)
			new BootstrapGroup()
				.setId(3392315L)
				.setGroupAlias(new BootstrapAlias().setAliasName("Trusted Message Senders").setAliasId(14L)),

			// Synapse Access and Compliance Team (ID: 3320424)
			new BootstrapGroup()
				.setId(3320424L)
				.setGroupAlias(new BootstrapAlias().setAliasName("Synapse Access and Compliance Team").setAliasId(15L)),

			// ACT Reviewer Group (ID: 5)
			new BootstrapGroup()
				.setId(5L)
				.setGroupAlias(new BootstrapAlias().setAliasName("ACT Reviewer").setAliasId(17L))
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
	
	@Bean
	public RealmDao getRealmDao(DBOBasicDao basicDao, IdGenerator idGenerator,
			NamedParameterJdbcTemplate namedJdbcTemplate, JdbcTemplate jdbcTemplate) {
		RealmDaoImpl dao =  new RealmDaoImpl(basicDao, idGenerator, namedJdbcTemplate, jdbcTemplate);
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
		// Map of principal ID to realm principal type ID
		// This maps bootstrap principal IDs to their realm principal DBO IDs
		java.util.Map<String, Long> principalIdToRealmPrincipalDboId = new java.util.HashMap<>();
		principalIdToRealmPrincipalDboId.put("273950", 1L); // Anonymous user
		principalIdToRealmPrincipalDboId.put("273948", 2L); // Authenticated users
		principalIdToRealmPrincipalDboId.put("273949", 3L); // Public group
		principalIdToRealmPrincipalDboId.put("2", 4L); // Administrators group

		realmDao.addPrincipalsToDefaultRealm(principalIdToRealmPrincipalDboId);
		return dao;
	}

	/**
	 * Creates GroupMembersDAO with dependency on UserGroupDAO to ensure proper initialization order.
	 * Note: The bootstrapGroups() method is currently a no-op - actual group bootstrap happens
	 * in TeamManagerImpl.bootstrapTeams().
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
