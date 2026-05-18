package org.sagebionetworks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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

		String uniqueSuffix = UUID.randomUUID().toString().replace("-", "");
		String overrideLocalName = "IT_CONFIG_OVERRIDE_" + uniqueSuffix;
		String configName = "IT_TEST_CONFIG_" + uniqueSuffix;

		// Create a column analyzer override to reference
		ColumnAnalyzerOverride createdOverride = adminSynapse.createColumnAnalyzerOverride(new ColumnAnalyzerOverride()
				.setName(overrideLocalName)
				.setOrganizationName(orgName)
				.setOverrides(Collections.singletonList(new ColumnAnalyzerOverrideEntry()
						.setColumnName("abstract")
						.setIndexAnalyzer(defaultAnalyzerName)
						.setSearchAnalyzer(defaultAnalyzerName))));
		String overrideName = orgName + "-" + createdOverride.getName();

		// CREATE — both default analyzers are required; overrides optional
		SearchConfiguration toCreate = new SearchConfiguration()
				.setName(configName)
				.setDescription("Integration test search configuration")
				.setOrganizationName(orgName)
				.setDefaultIndexAnalyzer(defaultAnalyzerName)
				.setDefaultSearchAnalyzer(defaultAnalyzerName)
				.setColumnAnalyzerOverrides(Arrays.asList(overrideName));

		// call under test
		SearchConfiguration created = adminSynapse.createSearchConfiguration(toCreate);
		assertNotNull(created.getId());
		assertNotNull(created.getEtag());
		assertEquals(configName, created.getName());
		assertEquals("Integration test search configuration", created.getDescription());
		assertEquals(defaultAnalyzerName, created.getDefaultIndexAnalyzer());
		assertEquals(defaultAnalyzerName, created.getDefaultSearchAnalyzer());
		assertEquals(Arrays.asList(overrideName), created.getColumnAnalyzerOverrides());

		// call under test — verify GET returns the same data
		SearchConfiguration fetched = adminSynapse.getSearchConfiguration(created.getId());
		assertEquals(created, fetched);

		// call under test — UPDATE: change description, verify references survive
		fetched.setDescription("Updated description");
		SearchConfiguration updated = adminSynapse.updateSearchConfiguration(fetched);
		assertEquals("Updated description", updated.getDescription());
		assertNotEquals(created.getEtag(), updated.getEtag());
		assertEquals(defaultAnalyzerName, updated.getDefaultIndexAnalyzer());
		assertEquals(defaultAnalyzerName, updated.getDefaultSearchAnalyzer());
		assertEquals(Arrays.asList(overrideName), updated.getColumnAnalyzerOverrides());

		// call under test — LIST by org
		ListSearchConfigurationsResponse listResponse = adminSynapse.listSearchConfigurations(
				new ListSearchConfigurationsRequest().setOrganizationName(orgName));
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
			ListTextAnalyzersResponse analyzers = adminSynapse.listTextAnalyzers(new ListTextAnalyzersRequest());
			TextAnalyzer bootstrappedAnalyzer = analyzers.getResults().get(0);
			String orgName = bootstrappedAnalyzer.getOrganizationName();
			String defaultAnalyzerName = orgName + "-" + bootstrappedAnalyzer.getName();

			SearchConfiguration createdConfig = adminSynapse.createSearchConfiguration(new SearchConfiguration()
					.setName("IT_BIND_CONFIG_" + UUID.randomUUID().toString().replace("-", ""))
					.setOrganizationName(orgName)
					.setDefaultIndexAnalyzer(defaultAnalyzerName)
					.setDefaultSearchAnalyzer(defaultAnalyzerName));

			// call under test — BIND
			SearchConfigBinding binding = adminSynapse.bindSearchConfigToEntity(new BindSearchConfigToEntityRequest()
					.setEntityId(createdProject.getId())
					.setSearchConfigurationId(createdConfig.getId()));

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
