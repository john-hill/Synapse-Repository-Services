package org.sagebionetworks.agentcore.config;

import org.sagebionetworks.repo.model.config.ModelConfig;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.ImportResource;

/**
 * Imports database infrastructure beans.
 * Required for DAOs and managers that depend on JdbcTemplate, IdGenerator, DataSource, etc.
 */
@Configuration
@Import(ModelConfig.class)                           // DataSource, JdbcTemplate, txManager
@ImportResource({
	"classpath:id-generator.spb.xml",                // IdGenerator
	"classpath:private/dbo-beans.spb.xml",           // DBOBasicDao and DBO registrations
	"classpath:private/dao-beans.spb.xml",           // DAOs and basic database beans
	"classpath:private/transaction-spb.xml"          // Transaction manager advice
})
public class DatabaseConfiguration {
}
