package org.sagebionetworks.agentcore;

import org.sagebionetworks.agentcore.config.AgentRegistryConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

@SpringBootApplication
@Import(AgentRegistryConfiguration.class)
public class AgentCoreApplication {

	public static void main(String[] args) {
		SpringApplication.run(AgentCoreApplication.class, args);
	}

}
