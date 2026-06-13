package org.sagebionetworks.repo.model.config;

import org.sagebionetworks.repo.model.dbo.migration.MigratableTableDAO;
import org.sagebionetworks.repo.model.dbo.migration.MigrationTypeProvider;
import org.sagebionetworks.repo.model.dbo.migration.MigrationTypeProviderImpl;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * Model configuration for lib-jdomodels.
 * Imports DatabaseInfrastructureConfiguration for core database beans.
 *
 * @deprecated This class is being phased out. New code should directly import
 * DatabaseInfrastructureConfiguration and database-semaphore's TransactionConfig.
 */
@Deprecated
@Configuration
@Import(DatabaseInfrastructureConfiguration.class)
public class ModelConfig {

	/**
	 * Creates MigrationTypeProvider from the MigratableTableDAO.
	 * This bean remains here because it depends on MigratableTableDAO which is defined in lib-jdomodels.
	 */
	@Bean
	public MigrationTypeProvider createMigrationTypeProvider(MigratableTableDAO migratableTableDao) {
		return new MigrationTypeProviderImpl(migratableTableDao.getAllMigratableTypes());
	}
}
