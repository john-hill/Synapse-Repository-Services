package org.sagebionetworks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.sagebionetworks.client.SynapseAdminClient;
import org.sagebionetworks.client.exceptions.SynapseException;
import org.sagebionetworks.client.exceptions.SynapseNotFoundException;
import org.sagebionetworks.repo.model.Entity;
import org.sagebionetworks.repo.model.Project;
import org.sagebionetworks.repo.model.search.table.BindSearchConfigToEntityRequest;
import org.sagebionetworks.repo.model.search.table.ColumnAnalyzerOverride;
import org.sagebionetworks.repo.model.search.table.ColumnAnalyzerOverrideEntry;
import org.sagebionetworks.repo.model.search.table.ListSearchConfigurationsRequest;
import org.sagebionetworks.repo.model.search.table.ListSearchConfigurationsResponse;
import org.sagebionetworks.repo.model.search.table.ListTextAnalyzersRequest;
import org.sagebionetworks.repo.model.search.table.ListTextAnalyzersResponse;
import org.sagebionetworks.repo.model.search.table.SearchConfigBinding;
import org.sagebionetworks.repo.model.search.table.SearchConfiguration;
import org.sagebionetworks.repo.model.search.table.SynonymRule;
import org.sagebionetworks.repo.model.search.table.SynonymRuleType;
import org.sagebionetworks.repo.model.search.table.SynonymSet;
import org.sagebionetworks.repo.model.search.table.TextAnalyzer;

@ExtendWith(ITTestExtension.class)
public class ITSearchConfigurationTest {

	private final SynapseAdminClient adminSynapse;

	public ITSearchConfigurationTest(SynapseAdminClient adminSynapse) {
		this.adminSynapse = adminSynapse;
	}

	@BeforeEach
	public void before() throws SynapseException {
		adminSynapse.clearAllLocks();
	}

	@Test
	public void testCRUDWithSearchConfiguration() throws SynapseException {
		// Get org name and a bootstrapped analyzer to use as default
		ListTextAnalyzersResponse analyzers = adminSynapse.listTextAnalyzers(new ListTextAnalyzersRequest());
		TextAnalyzer bootstrappedAnalyzer = analyzers.getResults().get(0);
		String orgName = bootstrappedAnalyzer.getOrganizationName();
		String defaultAnalyzerName = orgName + "-" + bootstrappedAnalyzer.getName();

		// Names are unique per organization with no delete endpoint, so use UUID
		// suffixes to avoid collisions across re-runs of the test.
		String uniqueSuffix = UUID.randomUUID().toString().replace("-", "");
		String synonymName = "IT_CONFIG_SYNONYMS_" + uniqueSuffix;
		String overrideLocalName = "IT_CONFIG_OVERRIDE_" + uniqueSuffix;
		String configName = "IT_TEST_CONFIG_" + uniqueSuffix;

		// Create a synonym set to reference
		SynonymRule equivalentRule = new SynonymRule();
		equivalentRule.setRuleType(SynonymRuleType.EQUIVALENT);
		equivalentRule.setTerms(Arrays.asList("cancer", "tumor", "neoplasm"));
		SynonymRule explicitRule = new SynonymRule();
		explicitRule.setRuleType(SynonymRuleType.EXPLICIT);
		explicitRule.setTerms(Arrays.asList("AD", "Alzheimer's disease"));
		SynonymSet synonymSet = new SynonymSet();
		synonymSet.setName(synonymName);
		synonymSet.setOrganizationName(orgName);
		synonymSet.setRules(Arrays.asList(equivalentRule, explicitRule));
		SynonymSet createdSynonymSet = adminSynapse.createSynonymSet(synonymSet);
		String synonymSetName = orgName + "-" + createdSynonymSet.getName();

		// Create a column analyzer override to reference
		ColumnAnalyzerOverrideEntry entry = new ColumnAnalyzerOverrideEntry();
		entry.setColumnName("abstract");
		entry.setIndexAnalyzer(defaultAnalyzerName);
		entry.setSearchAnalyzer(defaultAnalyzerName);
		ColumnAnalyzerOverride override = new ColumnAnalyzerOverride();
		override.setName(overrideLocalName);
		override.setOrganizationName(orgName);
		override.setOverrides(Collections.singletonList(entry));
		ColumnAnalyzerOverride createdOverride = adminSynapse.createColumnAnalyzerOverride(override);
		String overrideName = orgName + "-" + createdOverride.getName();

		// CREATE — include all three reference types
		SearchConfiguration toCreate = new SearchConfiguration();
		toCreate.setName(configName);
		toCreate.setDescription("Integration test search configuration");
		toCreate.setOrganizationName(orgName);
		toCreate.setDefaultAnalyzer(defaultAnalyzerName);
		toCreate.setSynonymSets(Arrays.asList(synonymSetName));
		toCreate.setColumnAnalyzerOverrides(Arrays.asList(overrideName));

		// call under test
		SearchConfiguration created = adminSynapse.createSearchConfiguration(toCreate);
		assertNotNull(created.getId());
		assertNotNull(created.getEtag());
		assertEquals(configName, created.getName());
		assertEquals("Integration test search configuration", created.getDescription());
		assertEquals(defaultAnalyzerName, created.getDefaultAnalyzer());
		assertEquals(Arrays.asList(synonymSetName), created.getSynonymSets());
		assertEquals(Arrays.asList(overrideName), created.getColumnAnalyzerOverrides());

		// call under test — verify GET returns the same data
		SearchConfiguration fetched = adminSynapse.getSearchConfiguration(created.getId());
		assertEquals(created, fetched);

		// call under test — UPDATE: change description and verify references survive
		fetched.setDescription("Updated description");
		SearchConfiguration updated = adminSynapse.updateSearchConfiguration(fetched);
		assertEquals("Updated description", updated.getDescription());
		assertNotEquals(created.getEtag(), updated.getEtag());
		assertEquals(defaultAnalyzerName, updated.getDefaultAnalyzer());
		assertEquals(Arrays.asList(synonymSetName), updated.getSynonymSets());
		assertEquals(Arrays.asList(overrideName), updated.getColumnAnalyzerOverrides());

		// call under test — UPDATE: clear optional references
		updated.setDefaultAnalyzer(null);
		updated.setSynonymSets(null);
		updated.setColumnAnalyzerOverrides(null);
		SearchConfiguration cleared = adminSynapse.updateSearchConfiguration(updated);
		assertNull(cleared.getDefaultAnalyzer());
		assertTrue(cleared.getSynonymSets() == null || cleared.getSynonymSets().isEmpty());
		assertTrue(cleared.getColumnAnalyzerOverrides() == null || cleared.getColumnAnalyzerOverrides().isEmpty());

		// call under test — LIST by org
		ListSearchConfigurationsRequest listRequest = new ListSearchConfigurationsRequest();
		listRequest.setOrganizationName(orgName);
		ListSearchConfigurationsResponse listResponse = adminSynapse.listSearchConfigurations(listRequest);
		assertNotNull(listResponse.getResults());
		assertTrue(listResponse.getResults().stream().anyMatch(c -> created.getId().equals(c.getId())));
	}

	@Test
	public void testBindAndUnbindSearchConfigToEntity() throws SynapseException {
		// Create a project to bind to
		Project project = new Project();
		project.setName("IT_BIND_TEST_PROJECT_" + UUID.randomUUID().toString().replace("-", ""));
		Entity createdProject = adminSynapse.createEntity(project);

		try {
			// Create a search configuration
			ListTextAnalyzersRequest analyzerReq = new ListTextAnalyzersRequest();
			ListTextAnalyzersResponse analyzers = adminSynapse.listTextAnalyzers(analyzerReq);
			String orgName = analyzers.getResults().get(0).getOrganizationName();

			SearchConfiguration config = new SearchConfiguration();
			config.setName("IT_BIND_CONFIG_" + UUID.randomUUID().toString().replace("-", ""));
			config.setOrganizationName(orgName);
			SearchConfiguration createdConfig = adminSynapse.createSearchConfiguration(config);

			// call under test — BIND
			BindSearchConfigToEntityRequest bindRequest = new BindSearchConfigToEntityRequest();
			bindRequest.setEntityId(createdProject.getId());
			bindRequest.setSearchConfigurationId(createdConfig.getId());
			SearchConfigBinding binding = adminSynapse.bindSearchConfigToEntity(bindRequest);

			assertNotNull(binding.getBindId());
			assertEquals(createdConfig.getId(), binding.getSearchConfigurationId());
			assertEquals(createdProject.getId(), "syn" + binding.getObjectId());
			assertEquals("entity", binding.getObjectType());
			assertNotNull(binding.getCreatedOn());

			// call under test — GET binding
			SearchConfigBinding fetched = adminSynapse.getSearchConfigBindingForEntity(createdProject.getId());
			assertEquals(binding.getBindId(), fetched.getBindId());
			assertEquals(createdConfig.getId(), fetched.getSearchConfigurationId());

			// call under test — UNBIND
			adminSynapse.clearSearchConfigBindingForEntity(createdProject.getId());

			// Verify binding is gone
			assertThrows(SynapseNotFoundException.class, () ->
				adminSynapse.getSearchConfigBindingForEntity(createdProject.getId()));
		} finally {
			adminSynapse.deleteEntity(createdProject);
		}
	}
}
