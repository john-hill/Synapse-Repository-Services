package org.sagebionetworks.repo.model.config;

import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * Component scanning configuration for discovering @Service beans in the repo.model package.
 * This replaces the XML-based component-scan configuration from jdomodels-import.xml.
 */
@Configuration
@EnableTransactionManagement
@ComponentScan(
	basePackages = {
		"org.sagebionetworks.repo.model",
		"org.sagebionetworks.lib.dbuserhelper"
	}
)
public class ComponentScanConfiguration {
	// Component scanning discovers:
	// - TransactionalMessengerImpl
	// - TransactionSynchronizationProxyImpl
	// - All DAO implementations with @Service
}
