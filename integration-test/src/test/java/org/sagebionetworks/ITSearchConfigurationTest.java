package org.sagebionetworks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;

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
		String orgName = analyzers.getResults().get(0).getOrganizationName();
		String defaultAnalyzerId = analyzers.getResults().get(0).getId();

		// Create a synonym set to reference
		SynonymRule equivalentRule = new SynonymRule();
		equivalentRule.setRuleType(SynonymRuleType.EQUIVALENT);
		equivalentRule.setTerms(Arrays.asList("cancer", "tumor", "neoplasm"));
		SynonymRule explicitRule = new SynonymRule();
		explicitRule.setRuleType(SynonymRuleType.EXPLICIT);
		explicitRule.setTerms(Arrays.asList("AD", "Alzheimer's disease"));
		SynonymSet synonymSet = new SynonymSet();
		synonymSet.setName("IT_CONFIG_SYNONYMS");
		synonymSet.setOrganizationName(orgName);
		synonymSet.setRules(Arrays.asList(equivalentRule, explicitRule));
		SynonymSet createdSynonymSet = adminSynapse.createSynonymSet(synonymSet);

		// Create a column analyzer override to reference
		ColumnAnalyzerOverrideEntry entry = new ColumnAnalyzerOverrideEntry();
		entry.setColumnName("abstract");
		entry.setIndexAnalyzerId(defaultAnalyzerId);
		entry.setSearchAnalyzerId(defaultAnalyzerId);
		ColumnAnalyzerOverride override = new ColumnAnalyzerOverride();
		override.setName("IT_CONFIG_OVERRIDE");
		override.setOrganizationName(orgName);
		override.setOverrides(Collections.singletonList(entry));
		ColumnAnalyzerOverride createdOverride = adminSynapse.createColumnAnalyzerOverride(override);

		// CREATE — include all three reference types
		SearchConfiguration toCreate = new SearchConfiguration();
		toCreate.setName("IT_TEST_CONFIG");
		toCreate.setDescription("Integration test search configuration");
		toCreate.setOrganizationName(orgName);
		toCreate.setDefaultAnalyzerId(defaultAnalyzerId);
		toCreate.setSynonymSetIds(Arrays.asList(createdSynonymSet.getId()));
		toCreate.setColumnAnalyzerOverrideIds(Arrays.asList(createdOverride.getId()));

		// call under test
		SearchConfiguration created = adminSynapse.createSearchConfiguration(toCreate);
		assertNotNull(created.getId());
		assertNotNull(created.getEtag());
		assertEquals("IT_TEST_CONFIG", created.getName());
		assertEquals("Integration test search configuration", created.getDescription());
		assertEquals(defaultAnalyzerId, created.getDefaultAnalyzerId());
		assertEquals(Arrays.asList(createdSynonymSet.getId()), created.getSynonymSetIds());
		assertEquals(Arrays.asList(createdOverride.getId()), created.getColumnAnalyzerOverrideIds());

		// call under test — verify GET returns the same data
		SearchConfiguration fetched = adminSynapse.getSearchConfiguration(created.getId());
		assertEquals(created, fetched);

		// call under test — UPDATE: change description and verify references survive
		fetched.setDescription("Updated description");
		SearchConfiguration updated = adminSynapse.updateSearchConfiguration(fetched);
		assertEquals("Updated description", updated.getDescription());
		assertNotEquals(created.getEtag(), updated.getEtag());
		assertEquals(defaultAnalyzerId, updated.getDefaultAnalyzerId());
		assertEquals(Arrays.asList(createdSynonymSet.getId()), updated.getSynonymSetIds());
		assertEquals(Arrays.asList(createdOverride.getId()), updated.getColumnAnalyzerOverrideIds());

		// call under test — UPDATE: clear optional references
		updated.setDefaultAnalyzerId(null);
		updated.setSynonymSetIds(null);
		updated.setColumnAnalyzerOverrideIds(null);
		SearchConfiguration cleared = adminSynapse.updateSearchConfiguration(updated);
		assertNull(cleared.getDefaultAnalyzerId());
		assertTrue(cleared.getSynonymSetIds() == null || cleared.getSynonymSetIds().isEmpty());
		assertTrue(cleared.getColumnAnalyzerOverrideIds() == null || cleared.getColumnAnalyzerOverrideIds().isEmpty());

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
		project.setName("IT_BIND_TEST_PROJECT");
		Entity createdProject = adminSynapse.createEntity(project);

		try {
			// Create a search configuration
			ListTextAnalyzersRequest analyzerReq = new ListTextAnalyzersRequest();
			ListTextAnalyzersResponse analyzers = adminSynapse.listTextAnalyzers(analyzerReq);
			String orgName = analyzers.getResults().get(0).getOrganizationName();

			SearchConfiguration config = new SearchConfiguration();
			config.setName("IT_BIND_CONFIG");
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
