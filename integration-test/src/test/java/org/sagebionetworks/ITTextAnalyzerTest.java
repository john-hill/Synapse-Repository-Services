package org.sagebionetworks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.sagebionetworks.client.SynapseAdminClient;
import org.sagebionetworks.client.exceptions.SynapseBadRequestException;
import org.sagebionetworks.client.exceptions.SynapseException;
import org.sagebionetworks.repo.model.search.table.ListTextAnalyzersRequest;
import org.sagebionetworks.repo.model.search.table.ListTextAnalyzersResponse;
import org.sagebionetworks.repo.model.search.table.TextAnalyzer;
import org.sagebionetworks.repo.model.search.table.TextAnalyzerSettings;

@ExtendWith(ITTestExtension.class)
public class ITTextAnalyzerTest {

	private final SynapseAdminClient adminSynapse;

	public ITTextAnalyzerTest(SynapseAdminClient adminSynapse) {
		this.adminSynapse = adminSynapse;
	}

	@BeforeEach
	public void before() throws SynapseException {
		adminSynapse.clearAllLocks();
	}

	@Test
	public void testCRUDWithTextAnalyzerSettings() throws SynapseException {
		// The org.sagebionetworks organization is bootstrapped on startup
		// List system analyzers to get the organization ID
		ListTextAnalyzersRequest listRequest = new ListTextAnalyzersRequest();
		ListTextAnalyzersResponse listResponse = adminSynapse.listTextAnalyzers(listRequest);
		assertNotNull(listResponse.getResults());
		// System analyzers are bootstrapped, so there should be at least 6
		assertTrue(listResponse.getResults().size() >= 6);

		String orgName = listResponse.getResults().get(0).getOrganizationName();

		// CREATE
		TextAnalyzer toCreate = new TextAnalyzer();
		toCreate.setName("IT_TEST_ANALYZER_" + UUID.randomUUID().toString().replace("-", ""));
		toCreate.setDescription("Integration test analyzer");
		toCreate.setOrganizationName(orgName);
		TextAnalyzerSettings settings = new TextAnalyzerSettings();
		settings.setTokenizer("standard");
		settings.setFilterOrder(Arrays.asList("lowercase"));
		toCreate.setSettings(settings);

		// call under test
		TextAnalyzer created = adminSynapse.createTextAnalyzer(toCreate);
		assertNotNull(created.getId());
		assertNotNull(created.getEtag());
		assertEquals(toCreate.getName(), created.getName());

		// call under test
		TextAnalyzer fetched = adminSynapse.getTextAnalyzer(created.getId());
		assertEquals(created.getId(), fetched.getId());
		assertEquals(created.getEtag(), fetched.getEtag());
		assertEquals(toCreate.getName(), fetched.getName());

		// call under test
		fetched.setDescription("Updated description");
		TextAnalyzer updated = adminSynapse.updateTextAnalyzer(fetched);
		assertEquals("Updated description", updated.getDescription());
		assertNotNull(updated.getEtag());

		// call under test
		ListTextAnalyzersRequest orgRequest = new ListTextAnalyzersRequest();
		orgRequest.setOrganizationName(orgName);
		ListTextAnalyzersResponse orgResponse = adminSynapse.listTextAnalyzers(orgRequest);
		assertNotNull(orgResponse.getResults());
		assertTrue(orgResponse.getResults().stream().anyMatch(a -> created.getId().equals(a.getId())));

	}

	@Test
	public void testCreateWithInvalidTokenizerReturns400() throws SynapseException {
		ListTextAnalyzersRequest listRequest = new ListTextAnalyzersRequest();
		ListTextAnalyzersResponse listResponse = adminSynapse.listTextAnalyzers(listRequest);
		String orgName = listResponse.getResults().get(0).getOrganizationName();

		TextAnalyzer toCreate = new TextAnalyzer();
		toCreate.setName("IT_INVALID_TOKENIZER_" + UUID.randomUUID().toString().replace("-", ""));
		toCreate.setDescription("Should fail validation");
		toCreate.setOrganizationName(orgName);
		TextAnalyzerSettings settings = new TextAnalyzerSettings();
		settings.setTokenizer("nonexistent_tokenizer_xyz");
		toCreate.setSettings(settings);

		// call under test
		SynapseBadRequestException ex = assertThrows(SynapseBadRequestException.class,
			() -> adminSynapse.createTextAnalyzer(toCreate));
		assertTrue(ex.getMessage().contains("Invalid analyzer configuration"));
	}

	@Test
	public void testCreateWithInvalidFilterReturns400() throws SynapseException {
		ListTextAnalyzersRequest listRequest = new ListTextAnalyzersRequest();
		ListTextAnalyzersResponse listResponse = adminSynapse.listTextAnalyzers(listRequest);
		String orgName = listResponse.getResults().get(0).getOrganizationName();

		TextAnalyzer toCreate = new TextAnalyzer();
		toCreate.setName("IT_INVALID_FILTER_" + UUID.randomUUID().toString().replace("-", ""));
		toCreate.setDescription("Should fail validation");
		toCreate.setOrganizationName(orgName);
		TextAnalyzerSettings settings = new TextAnalyzerSettings();
		settings.setTokenizer("standard");
		settings.setFilterOrder(Arrays.asList("bogus_filter_name_xyz"));
		toCreate.setSettings(settings);

		// call under test
		SynapseBadRequestException ex = assertThrows(SynapseBadRequestException.class,
			() -> adminSynapse.createTextAnalyzer(toCreate));
		assertTrue(ex.getMessage().contains("Invalid analyzer configuration"));
	}

	@Test
	public void testCreateWithCustomFiltersValidatesSuccessfully() throws SynapseException {
		ListTextAnalyzersRequest listRequest = new ListTextAnalyzersRequest();
		ListTextAnalyzersResponse listResponse = adminSynapse.listTextAnalyzers(listRequest);
		String orgName = listResponse.getResults().get(0).getOrganizationName();

		TextAnalyzer toCreate = new TextAnalyzer();
		toCreate.setName("IT_CUSTOM_FILTERS_" + UUID.randomUUID().toString().replace("-", ""));
		toCreate.setDescription("Custom stop filter");
		toCreate.setOrganizationName(orgName);
		TextAnalyzerSettings settings = new TextAnalyzerSettings();
		settings.setTokenizer("standard");
		settings.setTokenFilters("{\"my_stop\":{\"type\":\"stop\",\"stopwords\":\"_english_\"}}");
		settings.setFilterOrder(Arrays.asList("my_stop", "lowercase"));
		toCreate.setSettings(settings);

		// call under test
		TextAnalyzer created = adminSynapse.createTextAnalyzer(toCreate);
		assertNotNull(created.getId());
		assertEquals(toCreate.getName(), created.getName());
	}

	@Test
	public void testUpdateWithInvalidSettingsReturns400() throws SynapseException {
		ListTextAnalyzersRequest listRequest = new ListTextAnalyzersRequest();
		ListTextAnalyzersResponse listResponse = adminSynapse.listTextAnalyzers(listRequest);
		String orgName = listResponse.getResults().get(0).getOrganizationName();

		// Create a valid analyzer first
		TextAnalyzer toCreate = new TextAnalyzer();
		toCreate.setName("IT_UPDATE_INVALID_" + UUID.randomUUID().toString().replace("-", ""));
		toCreate.setDescription("Will be updated with invalid settings");
		toCreate.setOrganizationName(orgName);
		TextAnalyzerSettings validSettings = new TextAnalyzerSettings();
		validSettings.setTokenizer("standard");
		validSettings.setFilterOrder(Arrays.asList("lowercase"));
		toCreate.setSettings(validSettings);

		TextAnalyzer created = adminSynapse.createTextAnalyzer(toCreate);

		// Update with invalid settings
		TextAnalyzerSettings invalidSettings = new TextAnalyzerSettings();
		invalidSettings.setTokenizer("nonexistent_tokenizer_xyz");
		created.setSettings(invalidSettings);

		// call under test
		SynapseBadRequestException ex = assertThrows(SynapseBadRequestException.class,
			() -> adminSynapse.updateTextAnalyzer(created));
		assertTrue(ex.getMessage().contains("Invalid analyzer configuration"));

		// Verify original is unchanged
		TextAnalyzer fetched = adminSynapse.getTextAnalyzer(created.getId());
		assertEquals("standard", fetched.getSettings().getTokenizer());
	}
}
