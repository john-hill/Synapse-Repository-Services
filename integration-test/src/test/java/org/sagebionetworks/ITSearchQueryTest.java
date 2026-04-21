package org.sagebionetworks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.sagebionetworks.client.AsynchJobType;
import org.sagebionetworks.client.SynapseAdminClient;
import org.sagebionetworks.client.SynapseClient;
import org.sagebionetworks.client.exceptions.SynapseException;
import org.sagebionetworks.repo.model.AuthorizationConstants;
import org.sagebionetworks.repo.model.Entity;
import org.sagebionetworks.repo.model.Project;
import org.sagebionetworks.repo.model.table.ColumnModel;
import org.sagebionetworks.repo.model.table.ColumnType;
import org.sagebionetworks.repo.model.table.Row;
import org.sagebionetworks.repo.model.table.RowSet;
import org.sagebionetworks.repo.model.table.TableEntity;
import org.sagebionetworks.table.cluster.utils.TableModelUtils;
import org.sagebionetworks.repo.model.search.table.ColumnAnalyzerOverride;
import org.sagebionetworks.repo.model.search.table.ColumnAnalyzerOverrideEntry;
import org.sagebionetworks.repo.model.search.table.ListTextAnalyzersRequest;
import org.sagebionetworks.repo.model.search.table.ListTextAnalyzersResponse;
import org.sagebionetworks.repo.model.search.table.SearchConfiguration;
import org.sagebionetworks.repo.model.search.table.SearchIndex;
import org.sagebionetworks.repo.model.search.SearchQueryType;
import org.sagebionetworks.repo.model.search.SearchQueryResults;
import org.sagebionetworks.repo.model.search.table.SearchIndexQuery;

/**
 * Integration test for the SearchIndex entity and async SearchQuery workflow.
 * Creates a table with data, builds a SearchIndex from it, and verifies
 * async queries and autocomplete return results.
 */
@ExtendWith(ITTestExtension.class)
public class ITSearchQueryTest {

	private static final long MAX_QUERY_TIMEOUT_MS = 1000 * 60 * 5;
	private static final long MAX_APPEND_TIMEOUT = 30 * 1000;

	private final SynapseAdminClient adminSynapse;
	private final SynapseClient synapse;
	private final List<Entity> entitiesToDelete = new ArrayList<>();

	public ITSearchQueryTest(SynapseAdminClient adminSynapse, SynapseClient synapse) {
		this.adminSynapse = adminSynapse;
		this.synapse = synapse;
	}

	private static final String SAGE_TEAM_ID = AuthorizationConstants.BOOTSTRAP_PRINCIPAL.SAGE_BIONETWORKS
			.getPrincipalId().toString();

	@BeforeEach
	public void before() throws SynapseException {
		adminSynapse.clearAllLocks();
		// The test user must be a Sage Bionetworks team member to manage SearchIndex entities
		String userId = synapse.getMyProfile().getOwnerId();
		adminSynapse.addTeamMember(SAGE_TEAM_ID, userId, null, null);
	}

	@AfterEach
	public void after() {
		// Remove user from Sage team
		try {
			adminSynapse.removeTeamMember(SAGE_TEAM_ID, synapse.getMyProfile().getOwnerId());
		} catch (SynapseException e) {
			// ignore
		}
		// Delete in reverse order (search index before table before project)
		for (int i = entitiesToDelete.size() - 1; i >= 0; i--) {
			try {
				adminSynapse.deleteEntity(entitiesToDelete.get(i));
			} catch (SynapseException e) {
				// ignore
			}
		}
	}

	@Test
	public void testSearchIndexCreateAndAsyncQuery() throws Exception {
		// 1. Create project
		Project project = new Project();
		project.setName("ITSearchQueryTest_" + UUID.randomUUID());
		project = synapse.createEntity(project);
		entitiesToDelete.add(project);

		// 2. Create columns
		ColumnModel nameCol = new ColumnModel();
		nameCol.setName("studyName");
		nameCol.setColumnType(ColumnType.STRING);
		nameCol.setMaximumSize(100L);
		nameCol = synapse.createColumnModel(nameCol);

		ColumnModel descCol = new ColumnModel();
		descCol.setName("description");
		descCol.setColumnType(ColumnType.STRING);
		descCol.setMaximumSize(500L);
		descCol = synapse.createColumnModel(descCol);

		// 3. Create table
		TableEntity table = new TableEntity();
		table.setName("SearchTestTable");
		table.setParentId(project.getId());
		table.setColumnIds(Arrays.asList(nameCol.getId(), descCol.getId()));
		table = synapse.createEntity(table);
		entitiesToDelete.add(table);

		// 4. Populate with rows
		List<ColumnModel> columns = synapse.getColumnModelsForTableEntity(table.getId());
		RowSet rowSet = new RowSet();
		rowSet.setTableId(table.getId());
		rowSet.setHeaders(TableModelUtils.getSelectColumns(columns));
		rowSet.setRows(Arrays.asList(
			new Row().setValues(Arrays.asList("Alzheimer's Disease Genetics Study", "A large-scale genome-wide association study")),
			new Row().setValues(Arrays.asList("Cancer Genomics Atlas", "Comprehensive molecular characterization of tumors")),
			new Row().setValues(Arrays.asList("Parkinson's Research Initiative", "Investigating dopaminergic neuron degeneration"))
		));
		synapse.appendRowsToTable(rowSet, MAX_APPEND_TIMEOUT, table.getId());

		// 5. Create SearchIndex entity
		SearchIndex searchIndex = new SearchIndex();
		searchIndex.setName("TestSearchIndex");
		searchIndex.setParentId(project.getId());
		searchIndex.setDefiningSQL("select * from " + table.getId());
		searchIndex = synapse.createEntity(searchIndex);
		entitiesToDelete.add(searchIndex);
		assertNotNull(searchIndex.getId());

		// 6. Submit async SearchQuery and wait for results
		SearchIndexQuery indexQuery = new SearchIndexQuery();
		indexQuery.setSearchIndexId(searchIndex.getId());
		indexQuery.setQueryType(SearchQueryType.SIMPLE_QUERY_STRING);
		indexQuery.setQueryText("Alzheimer");

		AsyncJobHelper.assertAysncJobResult(synapse, AsynchJobType.SearchIndexQuery, indexQuery,
			(SearchQueryResults results) -> {
				assertNotNull(results);
				assertEquals(1L, (long) results.getTotalHits(), "Expected exactly one hit for 'Alzheimer'");
				assertNotNull(results.getHits());
				assertEquals(1, results.getHits().size());
				assertTrue(results.getHits().get(0).getFields().stream()
						.anyMatch(f -> "Alzheimer's Disease Genetics Study".equals(f.getValue())),
						"Hit should contain the Alzheimer's study name");
			},
			MAX_QUERY_TIMEOUT_MS,
			AsyncJobHelper.INFINITE_RETRIES
		);

		// 7. Test query with no results
		SearchIndexQuery noResultIndexQuery = new SearchIndexQuery();
		noResultIndexQuery.setSearchIndexId(searchIndex.getId());
		noResultIndexQuery.setQueryType(SearchQueryType.SIMPLE_QUERY_STRING);
		noResultIndexQuery.setQueryText("xyznonexistent12345");

		AsyncJobHelper.assertAysncJobResult(synapse, AsynchJobType.SearchIndexQuery, noResultIndexQuery,
			(SearchQueryResults results) -> {
				assertNotNull(results);
				assertEquals(0L, (long) results.getTotalHits());
			},
			MAX_QUERY_TIMEOUT_MS,
			AsyncJobHelper.INFINITE_RETRIES
		);

		// 8. Test match-all query
		SearchIndexQuery matchAllIndexQuery = new SearchIndexQuery();
		matchAllIndexQuery.setSearchIndexId(searchIndex.getId());

		AsyncJobHelper.assertAysncJobResult(synapse, AsynchJobType.SearchIndexQuery, matchAllIndexQuery,
			(SearchQueryResults results) -> {
				assertNotNull(results);
				assertEquals(3L, (long) results.getTotalHits());
			},
			MAX_QUERY_TIMEOUT_MS,
			AsyncJobHelper.INFINITE_RETRIES
		);
	}

	/**
	 * Tests autocomplete WITHOUT edge_ngram analyzer. Uses the default SCIENTIFIC
	 * analyzer with bool_prefix query type, which provides prefix matching by
	 * scanning the term dictionary for matching prefixes.
	 */
	@Test
	public void testAutocompleteWithoutEdgeNgram() throws Exception {
		// 1. Create project
		Project project = new Project();
		project.setName("ITSearchAutocomplete_NoEdgeNgram_" + UUID.randomUUID());
		project = synapse.createEntity(project);
		entitiesToDelete.add(project);

		// 2. Create columns and table
		ColumnModel nameCol = new ColumnModel();
		nameCol.setName("geneName");
		nameCol.setColumnType(ColumnType.STRING);
		nameCol.setMaximumSize(100L);
		nameCol = synapse.createColumnModel(nameCol);

		TableEntity table = new TableEntity();
		table.setName("AutocompleteTestTable");
		table.setParentId(project.getId());
		table.setColumnIds(Arrays.asList(nameCol.getId()));
		table = synapse.createEntity(table);
		entitiesToDelete.add(table);

		// 3. Populate with rows
		List<ColumnModel> columns = synapse.getColumnModelsForTableEntity(table.getId());
		RowSet rowSet = new RowSet();
		rowSet.setTableId(table.getId());
		rowSet.setHeaders(TableModelUtils.getSelectColumns(columns));
		rowSet.setRows(Arrays.asList(
			new Row().setValues(Arrays.asList("BRCA1")),
			new Row().setValues(Arrays.asList("BRCA2")),
			new Row().setValues(Arrays.asList("TP53"))
		));
		synapse.appendRowsToTable(rowSet, MAX_APPEND_TIMEOUT, table.getId());

		// 4. Create SearchIndex entity (no SearchConfiguration — uses default SCIENTIFIC analyzer)
		SearchIndex searchIndex = new SearchIndex();
		searchIndex.setName("AutocompleteSearchIndex");
		searchIndex.setParentId(project.getId());
		searchIndex.setDefiningSQL("select * from " + table.getId());
		searchIndex = synapse.createEntity(searchIndex);
		entitiesToDelete.add(searchIndex);

		// 5. Wait for the index to be ACTIVE
		SearchIndexQuery waitIndexQuery = new SearchIndexQuery();
		waitIndexQuery.setSearchIndexId(searchIndex.getId());

		AsyncJobHelper.assertAysncJobResult(synapse, AsynchJobType.SearchIndexQuery, waitIndexQuery,
			(SearchQueryResults results) -> {
				assertNotNull(results);
				assertEquals(3L, (long) results.getTotalHits());
			},
			MAX_QUERY_TIMEOUT_MS,
			AsyncJobHelper.INFINITE_RETRIES
		);

		// 6. Test autocomplete with bool_prefix (synchronous)
		SearchIndexQuery autocompleteIndexQuery = new SearchIndexQuery();
		autocompleteIndexQuery.setSearchIndexId(searchIndex.getId());
		autocompleteIndexQuery.setQueryText("BRC");

		SearchQueryResults autocompleteResults = synapse.searchAutocomplete(autocompleteIndexQuery);
		assertNotNull(autocompleteResults);
		assertNotNull(autocompleteResults.getHits());
		assertTrue(autocompleteResults.getTotalHits() >= 2,
			"Expected at least 2 autocomplete hits for 'BRC' (BRCA1, BRCA2)");
	}

	/**
	 * Tests autocomplete WITH edge_ngram AUTOCOMPLETE analyzer configured via
	 * ColumnAnalyzerOverride and SearchConfiguration. This is the optimal setup
	 * for high-performance type-ahead: edge_ngram pre-computes prefix tokens at
	 * index time so matching is an exact token lookup.
	 */
	@Test
	public void testAutocompleteWithEdgeNgram() throws Exception {
		// 1. Create project
		Project project = new Project();
		project.setName("ITSearchAutocomplete_EdgeNgram_" + UUID.randomUUID());
		project = synapse.createEntity(project);
		entitiesToDelete.add(project);

		// 2. Get org name from bootstrapped analyzers
		ListTextAnalyzersResponse analyzers = adminSynapse.listTextAnalyzers(new ListTextAnalyzersRequest());
		String orgName = analyzers.getResults().get(0).getOrganizationName();

		// 3. Create ColumnAnalyzerOverride: geneName -> AUTOCOMPLETE (index) + AUTOCOMPLETE_SEARCH (search)
		ColumnAnalyzerOverrideEntry entry = new ColumnAnalyzerOverrideEntry();
		entry.setColumnName("geneName");
		entry.setIndexAnalyzer(orgName + "-AUTOCOMPLETE");
		entry.setSearchAnalyzer(orgName + "-AUTOCOMPLETE_SEARCH");

		ColumnAnalyzerOverride override = new ColumnAnalyzerOverride();
		override.setName("IT_AUTOCOMPLETE_OVERRIDE_" + UUID.randomUUID().toString().replace("-", ""));
		override.setOrganizationName(orgName);
		override.setOverrides(Arrays.asList(entry));
		override = adminSynapse.createColumnAnalyzerOverride(override);

		// 4. Create SearchConfiguration referencing the override
		String overrideQualifiedName = orgName + "-" + override.getName();
		SearchConfiguration config = new SearchConfiguration();
		config.setName("IT_AUTOCOMPLETE_CONFIG_" + UUID.randomUUID().toString().replace("-", ""));
		config.setOrganizationName(orgName);
		config.setColumnAnalyzerOverrides(Arrays.asList(overrideQualifiedName));
		config = adminSynapse.createSearchConfiguration(config);

		// 5. Create columns and table
		ColumnModel nameCol = new ColumnModel();
		nameCol.setName("geneName");
		nameCol.setColumnType(ColumnType.STRING);
		nameCol.setMaximumSize(100L);
		nameCol = synapse.createColumnModel(nameCol);

		TableEntity table = new TableEntity();
		table.setName("AutocompleteEdgeNgramTable");
		table.setParentId(project.getId());
		table.setColumnIds(Arrays.asList(nameCol.getId()));
		table = synapse.createEntity(table);
		entitiesToDelete.add(table);

		// 6. Populate with rows
		List<ColumnModel> columns = synapse.getColumnModelsForTableEntity(table.getId());
		RowSet rowSet = new RowSet();
		rowSet.setTableId(table.getId());
		rowSet.setHeaders(TableModelUtils.getSelectColumns(columns));
		rowSet.setRows(Arrays.asList(
			new Row().setValues(Arrays.asList("BRCA1")),
			new Row().setValues(Arrays.asList("BRCA2")),
			new Row().setValues(Arrays.asList("TP53"))
		));
		synapse.appendRowsToTable(rowSet, MAX_APPEND_TIMEOUT, table.getId());

		// 7. Create SearchIndex entity with the SearchConfiguration
		SearchIndex searchIndex = new SearchIndex();
		searchIndex.setName("AutocompleteEdgeNgramSearchIndex");
		searchIndex.setParentId(project.getId());
		searchIndex.setDefiningSQL("select * from " + table.getId());
		searchIndex.setSearchConfigurationId(config.getId());
		searchIndex = synapse.createEntity(searchIndex);
		entitiesToDelete.add(searchIndex);

		// 8. Wait for the index to be ACTIVE
		SearchIndexQuery waitIndexQuery = new SearchIndexQuery();
		waitIndexQuery.setSearchIndexId(searchIndex.getId());

		AsyncJobHelper.assertAysncJobResult(synapse, AsynchJobType.SearchIndexQuery, waitIndexQuery,
			(SearchQueryResults results) -> {
				assertNotNull(results);
				assertEquals(3L, (long) results.getTotalHits());
			},
			MAX_QUERY_TIMEOUT_MS,
			AsyncJobHelper.INFINITE_RETRIES
		);

		// 9. Test autocomplete (synchronous)
		SearchIndexQuery autocompleteIndexQuery = new SearchIndexQuery();
		autocompleteIndexQuery.setSearchIndexId(searchIndex.getId());
		autocompleteIndexQuery.setQueryText("BRC");

		SearchQueryResults autocompleteResults = synapse.searchAutocomplete(autocompleteIndexQuery);
		assertNotNull(autocompleteResults);
		assertNotNull(autocompleteResults.getHits());
		assertTrue(autocompleteResults.getTotalHits() >= 2,
			"Expected at least 2 autocomplete hits for 'BRC' (BRCA1, BRCA2)");
	}
}
