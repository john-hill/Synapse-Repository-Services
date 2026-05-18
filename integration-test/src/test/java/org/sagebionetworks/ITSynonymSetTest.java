package org.sagebionetworks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

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

@ExtendWith(ITTestExtension.class)
public class ITSynonymSetTest {

	private static final String EQUIVALENT_DEF =
			"{\"type\":\"synonym_graph\",\"synonyms\":[\"cancer, tumor, neoplasm\"]}";
	private static final String TWO_RULE_DEF =
			"{\"type\":\"synonym_graph\",\"synonyms\":[\"cancer, tumor, neoplasm\",\"AD => Alzheimer's disease\"]}";

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
				.setDefinition(EQUIVALENT_DEF);

		// call under test
		SynonymSet created = adminSynapse.createSynonymSet(toCreate);
		assertNotNull(created.getId());
		assertNotNull(created.getEtag());
		assertEquals(name, created.getName());
		assertEquals(EQUIVALENT_DEF, created.getDefinition());

		// call under test
		SynonymSet fetched = adminSynapse.getSynonymSet(created.getId());
		assertEquals(created.getId(), fetched.getId());
		assertEquals(created.getEtag(), fetched.getEtag());
		assertEquals(name, fetched.getName());
		assertEquals(EQUIVALENT_DEF, fetched.getDefinition());

		// UPDATE — swap to a two-rule definition
		fetched.setDescription("Updated description");
		fetched.setDefinition(TWO_RULE_DEF);

		// call under test
		SynonymSet updated = adminSynapse.updateSynonymSet(fetched);
		assertEquals("Updated description", updated.getDescription());
		assertEquals(TWO_RULE_DEF, updated.getDefinition());
		assertNotNull(updated.getEtag());

		// call under test
		ListSynonymSetsResponse listResponse = adminSynapse.listSynonymSets(
				new ListSynonymSetsRequest().setOrganizationName(orgName));
		assertNotNull(listResponse.getResults());
		assertTrue(listResponse.getResults().stream().anyMatch(s -> created.getId().equals(s.getId())));
	}
}
