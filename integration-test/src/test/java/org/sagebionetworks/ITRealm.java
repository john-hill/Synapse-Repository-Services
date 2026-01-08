package org.sagebionetworks;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.sagebionetworks.client.SynapseAdminClient;
import org.sagebionetworks.client.SynapseClient;
import org.sagebionetworks.client.exceptions.SynapseException;
import org.sagebionetworks.client.exceptions.SynapseNotFoundException;
import org.sagebionetworks.repo.model.Project;
import org.sagebionetworks.repo.model.statistics.ObjectStatisticsResponse;
import org.sagebionetworks.repo.model.statistics.ProjectFilesStatisticsRequest;
import org.sagebionetworks.repo.model.statistics.ProjectFilesStatisticsResponse;

@ExtendWith(ITTestExtension.class)
public class ITRealm {

	private SynapseClient synapse;
	private SynapseAdminClient adminSynapse;

    public ITRealm(SynapseAdminClient adminSynapse, SynapseClient synapse) {
		this.adminSynapse = adminSynapse;
		this.synapse = synapse;
	}

	@Test
	public void testRoundTrip() throws SynapseException {

		// create realm
		
		// list realms
		
		// get realm by id
		
		// get realm principals
		
		// delete realm
	}

}
