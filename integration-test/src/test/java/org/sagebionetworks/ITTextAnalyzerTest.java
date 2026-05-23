package org.sagebionetworks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.sagebionetworks.client.SynapseAdminClient;
import org.sagebionetworks.client.exceptions.SynapseException;
import org.sagebionetworks.repo.model.search.table.ListTextAnalyzersRequest;
import org.sagebionetworks.repo.model.search.table.ListTextAnalyzersResponse;
import org.sagebionetworks.repo.model.search.table.SynonymSet;
import org.sagebionetworks.repo.model.search.table.TextAnalyzer;

@ExtendWith(ITTestExtension.class)
public class ITTextAnalyzerTest {

	/**
	 * Java callers build settings as native JSON objects via {@link JSONObject} /
	 * {@link JSONArray} — never as pre-stringified JSON. The wire deserializer surfaces
	 * the same shape on the other side.
	 */
	private static JSONObject standardSettings() {
		return new JSONObject().put(
				"analyzer", new JSONObject().put(
						"default", new JSONObject()
								.put("type", "custom")
								.put("tokenizer", "standard")
								.put("filter", new JSONArray().put("lowercase"))));
	}

	private final SynapseAdminClient adminSynapse;

	public ITTextAnalyzerTest(SynapseAdminClient adminSynapse) {
		this.adminSynapse = adminSynapse;
	}

	@BeforeEach
	public void before() throws SynapseException {
		adminSynapse.clearAllLocks();
	}

	@Test
	public void testCRUDWithTextAnalyzer() throws Exception {
		// The org.sagebionetworks organization is bootstrapped on startup.
		ListTextAnalyzersRequest listRequest = new ListTextAnalyzersRequest();
		ListTextAnalyzersResponse listResponse = adminSynapse.listTextAnalyzers(listRequest);
		assertNotNull(listResponse.getResults());
		// System analyzers are bootstrapped, so there should be at least 6.
		assertTrue(listResponse.getResults().size() >= 6);

		String orgName = listResponse.getResults().get(0).getOrganizationName();

		// CREATE
		TextAnalyzer toCreate = new TextAnalyzer();
		toCreate.setName("IT_TEST_ANALYZER_" + UUID.randomUUID().toString().replace("-", ""));
		toCreate.setDescription("Integration test analyzer");
		toCreate.setOrganizationName(orgName);
		toCreate.setSettings(standardSettings());

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
	public void testCreateWithSynonymRefRoundTrips() throws Exception {
		// A TextAnalyzer that references a SynonymSet via $ref must round-trip exactly,
		// confirming the opaque-JSON contract on the wire end-to-end.
		ListTextAnalyzersRequest listRequest = new ListTextAnalyzersRequest();
		String orgName = adminSynapse.listTextAnalyzers(listRequest).getResults().get(0)
				.getOrganizationName();
		String unique = UUID.randomUUID().toString().replace("-", "");

		SynonymSet syn = adminSynapse.createSynonymSet(new SynonymSet()
				.setOrganizationName(orgName)
				.setName("IT_TEST_SYN_" + unique)
				.setDefinition(new JSONObject()
						.put("type", "synonym_graph")
						.put("synonyms", new JSONArray().put("a, b"))));
		String synQname = orgName + "-" + syn.getName();

		JSONObject settings = new JSONObject()
				.put("filter", new JSONObject()
						.put("my_syn", new JSONObject().put("$ref", synQname)))
				.put("analyzer", new JSONObject()
						.put("default", new JSONObject()
								.put("type", "custom")
								.put("tokenizer", "standard")
								.put("filter", new JSONArray().put("lowercase").put("my_syn"))));

		TextAnalyzer toCreate = new TextAnalyzer()
				.setOrganizationName(orgName)
				.setName("IT_TEST_REF_" + unique)
				.setSettings(settings);

		// call under test
		TextAnalyzer created = adminSynapse.createTextAnalyzer(toCreate);

		assertNotNull(created.getId());
		// JSONObject.equals isn't value-based, so compare semantically via Jackson.
		com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
		assertEquals(mapper.readTree(settings.toString()),
				mapper.readTree(String.valueOf(created.getSettings())));
	}
}
