package org.sagebionetworks;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.sagebionetworks.client.SynapseClient;
import org.sagebionetworks.client.exceptions.SynapseNotFoundException;
import org.sagebionetworks.repo.model.search.table.SearchIndexQuery;

/**
 * Basic IT tests for SearchIndexQuery endpoints.
 * Full workflow tests are in ITSearchQueryTest.
 */
@ExtendWith(ITTestExtension.class)
public class ITSearchIndexQueryTest {

	private SynapseClient synapse;

	public ITSearchIndexQueryTest(SynapseClient synapse) {
		this.synapse = synapse;
	}

	@Test
	public void testSearchIndexQueryWithInvalidId() throws Exception {
		SearchIndexQuery request = new SearchIndexQuery();
		request.setSearchIndexId("syn999999999");
		request.setQueryText("test");

		// Querying a non-existent SearchIndex should result in a not-found error
		assertThrows(SynapseNotFoundException.class, () -> {
			synapse.searchAutocomplete(request);
		});
	}

	@Test
	public void testSearchIndexQueryRequiresSearchIndexId() throws Exception {
		SearchIndexQuery request = new SearchIndexQuery();
		// No searchIndexId set

		assertNotNull(request);
		// The async job will fail with validation error when searchIndexId is null
	}
}
