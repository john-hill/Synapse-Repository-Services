package org.sagebionetworks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.UUID;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.sagebionetworks.client.SynapseAdminClient;
import org.sagebionetworks.client.exceptions.SynapseException;
import org.sagebionetworks.repo.model.search.table.ListSynonymSetsRequest;
import org.sagebionetworks.repo.model.search.table.ListSynonymSetsResponse;
import org.sagebionetworks.repo.model.search.table.ListTextAnalyzersRequest;
import org.sagebionetworks.repo.model.search.table.ListTextAnalyzersResponse;
import org.sagebionetworks.repo.model.search.table.SynonymSet;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@ExtendWith(ITTestExtension.class)
public class ITSynonymSetTest {

	/**
	 * Java callers build definitions as native JSON objects; no escape ceremonies.
	 */
	private static JSONObject equivalentDef() {
		return new JSONObject()
				.put("type", "synonym_graph")
				.put("synonyms", new JSONArray().put("cancer, tumor, neoplasm"));
	}
	private static JSONObject twoRuleDef() {
		return new JSONObject()
				.put("type", "synonym_graph")
				.put("synonyms", new JSONArray()
						.put("cancer, tumor, neoplasm")
						.put("AD => Alzheimer's disease"));
	}

	private final SynapseAdminClient adminSynapse;

	public ITSynonymSetTest(SynapseAdminClient adminSynapse) {
		this.adminSynapse = adminSynapse;
	}

	@BeforeEach
	public void before() throws SynapseException {
		adminSynapse.clearAllLocks();
	}

	@Test
	public void testCRUDWithSynonymDefinition() throws SynapseException {
		// Get org ID from bootstrapped analyzers
		ListTextAnalyzersResponse analyzers = adminSynapse.listTextAnalyzers(new ListTextAnalyzersRequest());
		String orgName = analyzers.getResults().get(0).getOrganizationName();

		// Names are unique per organization with no delete endpoint, so use a UUID
		// suffix to avoid collisions across re-runs of the test.
		String name = "IT_TEST_SYNONYMS_" + UUID.randomUUID().toString().replace("-", "");

		SynonymSet toCreate = new SynonymSet()
				.setName(name)
				.setDescription("Integration test synonym set")
				.setOrganizationName(orgName)
				.setDefinition(equivalentDef());

		// call under test
		SynonymSet created = adminSynapse.createSynonymSet(toCreate);
		assertNotNull(created.getId());
		assertNotNull(created.getEtag());
		assertEquals(name, created.getName());
		// MySQL stores definition as a JSON column and reformats whitespace on read,
		// so compare semantically rather than character-for-character.
		assertJsonEquals(equivalentDef(), created.getDefinition());

		// call under test
		SynonymSet fetched = adminSynapse.getSynonymSet(created.getId());
		assertEquals(created.getId(), fetched.getId());
		assertEquals(created.getEtag(), fetched.getEtag());
		assertEquals(name, fetched.getName());
		assertJsonEquals(equivalentDef(), fetched.getDefinition());

		// UPDATE — swap to a two-rule definition
		fetched.setDescription("Updated description");
		fetched.setDefinition(twoRuleDef());

		// call under test
		SynonymSet updated = adminSynapse.updateSynonymSet(fetched);
		assertEquals("Updated description", updated.getDescription());
		assertJsonEquals(twoRuleDef(), updated.getDefinition());
		assertNotNull(updated.getEtag());

		// call under test
		ListSynonymSetsResponse listResponse = adminSynapse.listSynonymSets(
				new ListSynonymSetsRequest().setOrganizationName(orgName));
		assertNotNull(listResponse.getResults());
		assertTrue(listResponse.getResults().stream().anyMatch(s -> created.getId().equals(s.getId())));
	}

	/**
	 * Compare two opaque-JSON values structurally via Jackson; both sides may be
	 * {@code JSONObject} / {@code JSONArray} / {@code String} / scalar.
	 */
	private static void assertJsonEquals(Object expected, Object actual) {
		ObjectMapper mapper = new ObjectMapper();
		try {
			JsonNode expectedNode = mapper.readTree(String.valueOf(expected));
			JsonNode actualNode = mapper.readTree(String.valueOf(actual));
			assertEquals(expectedNode, actualNode,
					"JSON mismatch — expected: " + expected + " actual: " + actual);
		} catch (IOException e) {
			throw new AssertionError("Invalid JSON in test assertion", e);
		}
	}
}
