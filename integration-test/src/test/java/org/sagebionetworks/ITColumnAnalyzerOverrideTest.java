package org.sagebionetworks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.sagebionetworks.client.SynapseAdminClient;
import org.sagebionetworks.client.exceptions.SynapseException;
import org.sagebionetworks.repo.model.search.table.ColumnAnalyzerOverride;
import org.sagebionetworks.repo.model.search.table.ColumnAnalyzerOverrideEntry;
import org.sagebionetworks.repo.model.search.table.ListColumnAnalyzerOverridesRequest;
import org.sagebionetworks.repo.model.search.table.ListColumnAnalyzerOverridesResponse;
import org.sagebionetworks.repo.model.search.table.ListTextAnalyzersRequest;
import org.sagebionetworks.repo.model.search.table.ListTextAnalyzersResponse;
import org.sagebionetworks.repo.model.search.table.TextAnalyzer;

@ExtendWith(ITTestExtension.class)
public class ITColumnAnalyzerOverrideTest {

	private final SynapseAdminClient adminSynapse;

	public ITColumnAnalyzerOverrideTest(SynapseAdminClient adminSynapse) {
		this.adminSynapse = adminSynapse;
	}

	@BeforeEach
	public void before() throws SynapseException {
		adminSynapse.clearAllLocks();
	}

	@Test
	public void testColumnAnalyzerOverrideCRUD() throws SynapseException {
		// Get org name and an analyzer qualified name from bootstrapped analyzers
		ListTextAnalyzersResponse analyzers = adminSynapse.listTextAnalyzers(new ListTextAnalyzersRequest());
		TextAnalyzer firstAnalyzer = analyzers.getResults().get(0);
		String orgName = firstAnalyzer.getOrganizationName();
		String analyzerQualifiedName = orgName + "-" + firstAnalyzer.getName();

		// CREATE
		ColumnAnalyzerOverrideEntry entry = new ColumnAnalyzerOverrideEntry();
		entry.setColumnName("diagnosis");
		entry.setIndexAnalyzer(analyzerQualifiedName);

		ColumnAnalyzerOverride toCreate = new ColumnAnalyzerOverride();
		toCreate.setName("IT_TEST_OVERRIDE");
		toCreate.setDescription("Integration test column analyzer override");
		toCreate.setOrganizationName(orgName);
		toCreate.setOverrides(Arrays.asList(entry));

		// call under test
		ColumnAnalyzerOverride created = adminSynapse.createColumnAnalyzerOverride(toCreate);
		assertNotNull(created.getId());
		assertNotNull(created.getEtag());
		assertEquals("IT_TEST_OVERRIDE", created.getName());
		assertEquals(1, created.getOverrides().size());
		assertEquals("diagnosis", created.getOverrides().get(0).getColumnName());

		// call under test
		ColumnAnalyzerOverride fetched = adminSynapse.getColumnAnalyzerOverride(created.getId());
		assertEquals(created.getId(), fetched.getId());
		assertEquals(created.getEtag(), fetched.getEtag());
		assertEquals("IT_TEST_OVERRIDE", fetched.getName());

		// UPDATE
		fetched.setDescription("Updated description");
		ColumnAnalyzerOverrideEntry additionalEntry = new ColumnAnalyzerOverrideEntry();
		additionalEntry.setColumnName("tissue");
		additionalEntry.setIndexAnalyzer(analyzerQualifiedName);
		fetched.setOverrides(Arrays.asList(entry, additionalEntry));

		// call under test
		ColumnAnalyzerOverride updated = adminSynapse.updateColumnAnalyzerOverride(fetched);
		assertEquals("Updated description", updated.getDescription());
		assertEquals(2, updated.getOverrides().size());
		assertNotNull(updated.getEtag());

		// call under test
		ListColumnAnalyzerOverridesRequest listRequest = new ListColumnAnalyzerOverridesRequest();
		listRequest.setOrganizationName(orgName);
		ListColumnAnalyzerOverridesResponse listResponse = adminSynapse.listColumnAnalyzerOverrides(listRequest);
		assertNotNull(listResponse.getResults());
		assertTrue(listResponse.getResults().stream().anyMatch(o -> created.getId().equals(o.getId())));
	}
}
