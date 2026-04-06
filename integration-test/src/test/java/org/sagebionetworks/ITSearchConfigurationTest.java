package org.sagebionetworks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
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
import org.sagebionetworks.repo.model.search.table.SynonymRule;
import org.sagebionetworks.repo.model.search.table.SynonymRuleType;
import org.sagebionetworks.repo.model.search.table.SynonymSet;

@ExtendWith(ITTestExtension.class)
public class ITSearchConfigurationTest {

	private final SynapseAdminClient adminSynapse;
	private final List<String> configsToDelete = new ArrayList<>();
	private final List<String> synonymSetsToDelete = new ArrayList<>();

	public ITSearchConfigurationTest(SynapseAdminClient adminSynapse) {
		this.adminSynapse = adminSynapse;
	}

	@BeforeEach
	public void before() throws SynapseException {
		adminSynapse.clearAllLocks();
	}

	@AfterEach
	public void after() {
		for (String id : configsToDelete) {
			try {
				adminSynapse.deleteSearchConfiguration(id);
			} catch (SynapseException e) {
				// ignore
			}
		}
		for (String id : synonymSetsToDelete) {
			try {
				adminSynapse.deleteSynonymSet(id);
			} catch (SynapseException e) {
				// ignore
			}
		}
	}

	@Test
	public void testCRUDWithSearchConfiguration() throws SynapseException {
		// Get org name from bootstrapped analyzers
		ListTextAnalyzersResponse analyzers = adminSynapse.listTextAnalyzers(new ListTextAnalyzersRequest());
		String orgName = analyzers.getResults().get(0).getOrganizationName();
		String defaultAnalyzerId = analyzers.getResults().get(0).getId();

		// Create a synonym set to reference
		SynonymRule rule = new SynonymRule();
		rule.setRuleType(SynonymRuleType.EQUIVALENT);
		rule.setTerms(Arrays.asList("cancer", "tumor", "neoplasm"));
		SynonymSet synonymSet = new SynonymSet();
		synonymSet.setName("IT_CONFIG_SYNONYMS");
		synonymSet.setOrganizationName(orgName);
		synonymSet.setRules(Arrays.asList(rule));
		SynonymSet createdSynonymSet = adminSynapse.createSynonymSet(synonymSet);
		synonymSetsToDelete.add(createdSynonymSet.getId());

		// CREATE with real data
		SearchConfiguration toCreate = new SearchConfiguration();
		toCreate.setName("IT_TEST_CONFIG");
		toCreate.setDescription("Integration test search configuration");
		toCreate.setOrganizationName(orgName);
		toCreate.setDefaultAnalyzerId(defaultAnalyzerId);
		toCreate.setSynonymSetIds(Arrays.asList(createdSynonymSet.getId()));

		// call under test
		SearchConfiguration created = adminSynapse.createSearchConfiguration(toCreate);
		assertNotNull(created.getId());
		assertNotNull(created.getEtag());
		assertEquals("IT_TEST_CONFIG", created.getName());
		assertEquals(defaultAnalyzerId, created.getDefaultAnalyzerId());
		assertEquals(Arrays.asList(createdSynonymSet.getId()), created.getSynonymSetIds());
		configsToDelete.add(created.getId());

		// call under test
		SearchConfiguration fetched = adminSynapse.getSearchConfiguration(created.getId());
		assertEquals(created.getId(), fetched.getId());
		assertEquals(created.getEtag(), fetched.getEtag());
		assertEquals("IT_TEST_CONFIG", fetched.getName());
		assertEquals(defaultAnalyzerId, fetched.getDefaultAnalyzerId());
		assertEquals(Arrays.asList(createdSynonymSet.getId()), fetched.getSynonymSetIds());

		// call under test
		fetched.setDescription("Updated description");
		SearchConfiguration updated = adminSynapse.updateSearchConfiguration(fetched);
		assertEquals("Updated description", updated.getDescription());
		assertEquals(defaultAnalyzerId, updated.getDefaultAnalyzerId());
		assertNotNull(updated.getEtag());

		// call under test
		ListSearchConfigurationsRequest listRequest = new ListSearchConfigurationsRequest();
		listRequest.setOrganizationName(orgName);
		ListSearchConfigurationsResponse listResponse = adminSynapse.listSearchConfigurations(listRequest);
		assertNotNull(listResponse.getResults());
		assertTrue(listResponse.getResults().stream().anyMatch(c -> created.getId().equals(c.getId())));

		// call under test
		adminSynapse.deleteSearchConfiguration(created.getId());
		configsToDelete.remove(created.getId());

		// call under test
		assertThrows(SynapseNotFoundException.class, () -> adminSynapse.getSearchConfiguration(created.getId()));
	}
}
