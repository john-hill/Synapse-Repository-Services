package org.sagebionetworks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.sagebionetworks.client.SynapseAdminClient;
import org.sagebionetworks.client.exceptions.SynapseException;
import org.sagebionetworks.client.exceptions.SynapseNotFoundException;
import org.sagebionetworks.repo.model.search.table.ListSearchConfigurationsRequest;
import org.sagebionetworks.repo.model.search.table.ListSearchConfigurationsResponse;
import org.sagebionetworks.repo.model.search.table.ListTextAnalyzersRequest;
import org.sagebionetworks.repo.model.search.table.ListTextAnalyzersResponse;
import org.sagebionetworks.repo.model.search.table.SearchConfiguration;

@ExtendWith(ITTestExtension.class)
public class ITSearchConfigurationTest {

	private final SynapseAdminClient adminSynapse;
	private final List<String> toDelete = new ArrayList<>();

	public ITSearchConfigurationTest(SynapseAdminClient adminSynapse) {
		this.adminSynapse = adminSynapse;
	}

	@BeforeEach
	public void before() throws SynapseException {
		adminSynapse.clearAllLocks();
	}

	@AfterEach
	public void after() {
		for (String id : toDelete) {
			try {
				adminSynapse.deleteSearchConfiguration(id);
			} catch (SynapseException e) {
				// ignore
			}
		}
	}

	@Test
	public void testSearchConfigurationCRUD() throws SynapseException {
		// Get org ID from bootstrapped analyzers
		ListTextAnalyzersResponse analyzers = adminSynapse.listTextAnalyzers(new ListTextAnalyzersRequest());
		String orgName = analyzers.getResults().get(0).getOrganizationName();

		// CREATE
		SearchConfiguration toCreate = new SearchConfiguration();
		toCreate.setName("IT_TEST_CONFIG");
		toCreate.setDescription("Integration test search configuration");
		toCreate.setOrganizationName(orgName);

		SearchConfiguration created = adminSynapse.createSearchConfiguration(toCreate);
		assertNotNull(created.getId());
		assertNotNull(created.getEtag());
		assertEquals("IT_TEST_CONFIG", created.getName());
		toDelete.add(created.getId());

		// GET
		SearchConfiguration fetched = adminSynapse.getSearchConfiguration(created.getId());
		assertEquals(created.getId(), fetched.getId());
		assertEquals(created.getEtag(), fetched.getEtag());
		assertEquals("IT_TEST_CONFIG", fetched.getName());

		// UPDATE
		fetched.setDescription("Updated description");
		SearchConfiguration updated = adminSynapse.updateSearchConfiguration(fetched);
		assertEquals("Updated description", updated.getDescription());
		assertNotNull(updated.getEtag());

		// LIST
		ListSearchConfigurationsRequest listRequest = new ListSearchConfigurationsRequest();
		listRequest.setOrganizationName(orgName);
		ListSearchConfigurationsResponse listResponse = adminSynapse.listSearchConfigurations(listRequest);
		assertNotNull(listResponse.getResults());
		assertTrue(listResponse.getResults().stream().anyMatch(c -> created.getId().equals(c.getId())));

		// DELETE
		adminSynapse.deleteSearchConfiguration(created.getId());
		toDelete.remove(created.getId());

		// Verify deleted
		assertThrows(SynapseNotFoundException.class, () -> adminSynapse.getSearchConfiguration(created.getId()));
	}
}
