package org.sagebionetworks.agentcore;

import org.sagebionetworks.agentcore.config.AgentRegistryConfiguration;
import org.sagebionetworks.agentcore.config.DatabaseConfiguration;
import org.sagebionetworks.repo.manager.config.ManagerConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Import;

@SpringBootApplication
@Import({DatabaseConfiguration.class, AgentRegistryConfiguration.class, ManagerConfiguration.class})
@ComponentScan(basePackages = {
	"org.sagebionetworks.agentcore",           // Agent core components
	"org.sagebionetworks.repo.manager",        // Manager implementations
	"org.sagebionetworks.repo.service",        // Service implementations
	"org.sagebionetworks.repo.model.dbo",      // DAO implementations
	"org.sagebionetworks.lib.dbuserhelper"     // Database user helper
})
public class AgentCoreApplication {

	public static void main(String[] args) {
		SpringApplication.run(AgentCoreApplication.class, args);
	}

}
