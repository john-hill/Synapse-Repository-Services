package org.sagebionetworks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.sagebionetworks.client.AsynchJobType;
import org.sagebionetworks.client.SynapseClient;
import org.sagebionetworks.repo.model.table.search.SearchQuery;
import org.sagebionetworks.repo.model.table.search.SearchResults;

@ExtendWith(ITTestExtension.class)
public class ITSearchIndexQueryTest {

	private static final long MAX_WAIT_MS = 1000 * 30; // 30 sec

	private SynapseClient synapse;

	public ITSearchIndexQueryTest(SynapseClient synapse) {
		this.synapse = synapse;
	}

	@Test
	public void testSearchIndexQueryRoundTrip() throws Exception {
		SearchQuery request = new SearchQuery();
		request.setSearchIndexId("syn123");
		request.setQueryText("test");

		AsyncJobHelper.assertAysncJobResult(synapse, AsynchJobType.SearchIndexQuery, request,
				(SearchResults results) -> {
					assertNotNull(results);
					assertEquals(0L, results.getTotalHits());
					assertNotNull(results.getHits());
					assertTrue(results.getHits().isEmpty());
				}, MAX_WAIT_MS);
	}

	@Test
	public void testSearchAutocomplete() throws Exception {
		SearchQuery request = new SearchQuery();
		request.setSearchIndexId("syn123");
		request.setQueryText("test");

		SearchResults results = synapse.searchAutocomplete(request);

		assertNotNull(results);
		assertEquals(0L, results.getTotalHits());
		assertNotNull(results.getHits());
		assertTrue(results.getHits().isEmpty());
	}
}
