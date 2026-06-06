package org.sagebionetworks.docusign;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.sagebionetworks.StackConfiguration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.junit.jupiter.SpringExtension;

@ExtendWith(SpringExtension.class)
public class DocuSignClientConfigTest {
	@Autowired
	private StackConfiguration stackConfiguration;
	
	@Test
	public void testGetDocuSignAccountId() throws Exception {
		assumeTrue(stackConfiguration.getDocuSignEnabled(),
				"DocuSign integration is disabled — skipping testGetDocuSignAccountId test.");
		
		// will throw exception if missing
		stackConfiguration.getDocuSignAccountId();
	}

	@Test
	public void testGetDocuSignIntegrationKey() throws Exception {
		assumeTrue(stackConfiguration.getDocuSignEnabled(),
				"DocuSign integration is disabled — skipping getDocuSignIntegrationKey test.");
		
		// will throw exception if missing
		stackConfiguration.getDocuSignIntegrationKey();
	}

	@Test
	public void testGetDocuSignPrivateKey() throws Exception {
		assumeTrue(stackConfiguration.getDocuSignEnabled(),
				"DocuSign integration is disabled — skipping getDocuSignPrivateKey test.");
				
		// will throw exception if missing
		stackConfiguration.getDocuSignPrivateKey();
	}

	@Test
	public void testGetDocuSignUserId() throws Exception {
		assumeTrue(stackConfiguration.getDocuSignEnabled(),
				"DocuSign integration is disabled — skipping getDocuSignUserId test.");
		
		// will throw exception if missing
		stackConfiguration.getDocuSignUserId();
	}

}
