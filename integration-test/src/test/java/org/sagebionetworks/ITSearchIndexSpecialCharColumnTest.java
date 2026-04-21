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
import org.sagebionetworks.repo.model.search.FacetRequest;
import org.sagebionetworks.repo.model.search.KeyValues;
import org.sagebionetworks.repo.model.search.SearchQueryResults;
import org.sagebionetworks.repo.model.search.SearchQueryType;
import org.sagebionetworks.repo.model.search.SortDirection;
import org.sagebionetworks.repo.model.search.SortField;
import org.sagebionetworks.repo.model.search.table.SearchIndex;
import org.sagebionetworks.repo.model.search.table.SearchIndexQuery;
import org.sagebionetworks.repo.model.table.ColumnModel;
import org.sagebionetworks.repo.model.table.ColumnType;
import org.sagebionetworks.repo.model.table.Row;
import org.sagebionetworks.repo.model.table.RowSet;
import org.sagebionetworks.repo.model.table.TableEntity;
import org.sagebionetworks.table.cluster.utils.TableModelUtils;

/**
 * Integration test verifying that columns with special characters in their names
 * (dots, spaces, apostrophes, parentheses, brackets) work correctly with
 * SearchIndex queries. Column names are translated to IDs internally so
 * OpenSearch field name restrictions are bypassed.
 */
@ExtendWith(ITTestExtension.class)
public class ITSearchIndexSpecialCharColumnTest {

	private static final long MAX_QUERY_TIMEOUT_MS = 1000 * 60 * 5;
	private static final long MAX_APPEND_TIMEOUT = 30 * 1000;
	private static final String SAGE_TEAM_ID = AuthorizationConstants.BOOTSTRAP_PRINCIPAL.SAGE_BIONETWORKS
			.getPrincipalId().toString();

	private final SynapseAdminClient adminSynapse;
	private final SynapseClient synapse;
	private final List<Entity> entitiesToDelete = new ArrayList<>();

	public ITSearchIndexSpecialCharColumnTest(SynapseAdminClient adminSynapse, SynapseClient synapse) {
		this.adminSynapse = adminSynapse;
		this.synapse = synapse;
	}

	@BeforeEach
	public void before() throws SynapseException {
		adminSynapse.clearAllLocks();
		String userId = synapse.getMyProfile().getOwnerId();
		adminSynapse.addTeamMember(SAGE_TEAM_ID, userId, null, null);
	}

	@AfterEach
	public void after() {
		try {
			adminSynapse.removeTeamMember(SAGE_TEAM_ID, synapse.getMyProfile().getOwnerId());
		} catch (SynapseException e) {
			// ignore
		}
		for (int i = entitiesToDelete.size() - 1; i >= 0; i--) {
			try {
				adminSynapse.deleteEntity(entitiesToDelete.get(i));
			} catch (SynapseException e) {
				// ignore
			}
		}
	}

	@Test
	public void testColumnsWithSpecialCharacters() throws Exception {
		// 1. Create project
		Project project = new Project();
		project.setName("ITSpecialCharTest_" + UUID.randomUUID());
		project = synapse.createEntity(project);
		entitiesToDelete.add(project);

		// 2. Create columns with special characters in names
		ColumnModel studyNameCol = new ColumnModel();
		studyNameCol.setName("Study Name");
		studyNameCol.setColumnType(ColumnType.STRING);
		studyNameCol.setMaximumSize(100L);
		studyNameCol = synapse.createColumnModel(studyNameCol);

		ColumnModel diagnosisCol = new ColumnModel();
		diagnosisCol.setName("patient's diagnosis");
		diagnosisCol.setColumnType(ColumnType.STRING);
		diagnosisCol.setMaximumSize(100L);
		diagnosisCol = synapse.createColumnModel(diagnosisCol);

		ColumnModel ageCol = new ColumnModel();
		ageCol.setName("Age (years)");
		ageCol.setColumnType(ColumnType.INTEGER);
		ageCol = synapse.createColumnModel(ageCol);

		ColumnModel dataFieldCol = new ColumnModel();
		dataFieldCol.setName("data.field");
		dataFieldCol.setColumnType(ColumnType.STRING);
		dataFieldCol.setMaximumSize(100L);
		dataFieldCol = synapse.createColumnModel(dataFieldCol);

		ColumnModel metadataCol = new ColumnModel();
		metadataCol.setName("[metadata]");
		metadataCol.setColumnType(ColumnType.STRING);
		metadataCol.setMaximumSize(100L);
		metadataCol = synapse.createColumnModel(metadataCol);

		// 3. Create table with these columns
		TableEntity table = new TableEntity();
		table.setName("SpecialCharTable");
		table.setParentId(project.getId());
		table.setColumnIds(Arrays.asList(
				studyNameCol.getId(), diagnosisCol.getId(), ageCol.getId(),
				dataFieldCol.getId(), metadataCol.getId()));
		table = synapse.createEntity(table);
		entitiesToDelete.add(table);

		// 4. Populate with test data
		List<ColumnModel> columns = synapse.getColumnModelsForTableEntity(table.getId());
		RowSet rowSet = new RowSet();
		rowSet.setTableId(table.getId());
		rowSet.setHeaders(TableModelUtils.getSelectColumns(columns));
		rowSet.setRows(Arrays.asList(
			new Row().setValues(Arrays.asList("Alzheimer Study", "Alzheimer's Disease", "65", "genome_data", "v1.0")),
			new Row().setValues(Arrays.asList("Cancer Research", "Lung Cancer", "50", "proteomics_data", "v2.1")),
			new Row().setValues(Arrays.asList("Parkinson Trial", "Parkinson's Disease", "72", "imaging_data", "v1.5"))
		));
		synapse.appendRowsToTable(rowSet, MAX_APPEND_TIMEOUT, table.getId());

		// 5. Create SearchIndex
		SearchIndex searchIndex = new SearchIndex();
		searchIndex.setName("SpecialCharSearchIndex");
		searchIndex.setParentId(project.getId());
		searchIndex.setDefiningSQL("select * from " + table.getId());
		searchIndex = synapse.createEntity(searchIndex);
		entitiesToDelete.add(searchIndex);

		// 6. Wait for index to be built via match-all query
		SearchIndexQuery waitIndexQuery = new SearchIndexQuery();
		waitIndexQuery.setSearchIndexId(searchIndex.getId());
		waitIndexQuery.setQueryType(SearchQueryType.MATCH_ALL);
		waitIndexQuery.setLimit(10L);

		AsyncJobHelper.assertAysncJobResult(synapse, AsynchJobType.SearchIndexQuery, waitIndexQuery,
			(SearchQueryResults results) -> {
				assertNotNull(results);
				assertEquals(3L, results.getTotalHits());
			},
			MAX_QUERY_TIMEOUT_MS,
			AsyncJobHelper.INFINITE_RETRIES
		);

		// 7. Test text search with queryFields using special char column names
		SearchIndexQuery textIndexQuery = new SearchIndexQuery();
		textIndexQuery.setSearchIndexId(searchIndex.getId());
		textIndexQuery.setQueryType(SearchQueryType.SIMPLE_QUERY_STRING);
		textIndexQuery.setQueryText("Alzheimer");
		textIndexQuery.setQueryFields(Arrays.asList("Study Name", "data.field"));

		AsyncJobHelper.assertAysncJobResult(synapse, AsynchJobType.SearchIndexQuery, textIndexQuery,
			(SearchQueryResults results) -> {
				assertNotNull(results);
				assertTrue(results.getTotalHits() > 0, "Expected hits for 'Alzheimer' in special char columns");
			},
			MAX_QUERY_TIMEOUT_MS,
			AsyncJobHelper.INFINITE_RETRIES
		);

		// 8. Test terms filter on column with space in name
		KeyValues filter = new KeyValues();
		filter.setKey("Study Name");
		filter.setValues(Arrays.asList("Alzheimer Study"));

		SearchIndexQuery filterIndexQuery = new SearchIndexQuery();
		filterIndexQuery.setSearchIndexId(searchIndex.getId());
		filterIndexQuery.setQueryType(SearchQueryType.MATCH_ALL);
		filterIndexQuery.setTermsFilters(Arrays.asList(filter));

		AsyncJobHelper.assertAysncJobResult(synapse, AsynchJobType.SearchIndexQuery, filterIndexQuery,
			(SearchQueryResults results) -> {
				assertNotNull(results);
				assertEquals(1L, results.getTotalHits(), "Expected exactly one hit for 'Study Name' = 'Alzheimer Study'");
			},
			MAX_QUERY_TIMEOUT_MS,
			AsyncJobHelper.INFINITE_RETRIES
		);

		// 9. Test sort on column with parentheses
		SortField sortField = new SortField();
		sortField.setColumnName("Age (years)");
		sortField.setDirection(SortDirection.ASC);

		SearchIndexQuery sortIndexQuery = new SearchIndexQuery();
		sortIndexQuery.setSearchIndexId(searchIndex.getId());
		sortIndexQuery.setQueryType(SearchQueryType.MATCH_ALL);
		sortIndexQuery.setSort(Arrays.asList(sortField));
		sortIndexQuery.setReturnFields(Arrays.asList("Study Name", "Age (years)"));

		AsyncJobHelper.assertAysncJobResult(synapse, AsynchJobType.SearchIndexQuery, sortIndexQuery,
			(SearchQueryResults results) -> {
				assertNotNull(results);
				assertEquals(3L, results.getTotalHits());
				// Verify return field keys use original column names (not IDs)
				assertNotNull(results.getHits());
				assertTrue(results.getHits().size() > 0);
				assertTrue(results.getHits().get(0).getFields().stream()
						.anyMatch(fv -> "Study Name".equals(fv.getName())),
						"Return fields should use original column name 'Study Name', not column ID");
				assertTrue(results.getHits().get(0).getFields().stream()
						.anyMatch(fv -> "Age (years)".equals(fv.getName())),
						"Return fields should use original column name 'Age (years)', not column ID");
			},
			MAX_QUERY_TIMEOUT_MS,
			AsyncJobHelper.INFINITE_RETRIES
		);

		// 10. Test facet on column with apostrophe
		FacetRequest facetRequest = new FacetRequest();
		facetRequest.setColumnName("patient's diagnosis");
		facetRequest.setMaxValueCount(10L);

		SearchIndexQuery facetIndexQuery = new SearchIndexQuery();
		facetIndexQuery.setSearchIndexId(searchIndex.getId());
		facetIndexQuery.setQueryType(SearchQueryType.MATCH_ALL);
		facetIndexQuery.setFacetRequests(Arrays.asList(facetRequest));

		AsyncJobHelper.assertAysncJobResult(synapse, AsynchJobType.SearchIndexQuery, facetIndexQuery,
			(SearchQueryResults results) -> {
				assertNotNull(results);
				assertNotNull(results.getFacets());
				assertTrue(results.getFacets().size() > 0, "Expected facet results");
				assertEquals("patient's diagnosis", results.getFacets().get(0).getColumnName(),
						"Facet column name should use original name with apostrophe, not column ID");
			},
			MAX_QUERY_TIMEOUT_MS,
			AsyncJobHelper.INFINITE_RETRIES
		);
	}
}
