package org.sagebionetworks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
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
import org.sagebionetworks.repo.model.ACCESS_TYPE;
import org.sagebionetworks.repo.model.AccessControlList;
import org.sagebionetworks.repo.model.AuthorizationConstants;
import org.sagebionetworks.repo.model.DataType;
import org.sagebionetworks.repo.model.Entity;
import org.sagebionetworks.repo.model.Project;
import org.sagebionetworks.repo.model.ResourceAccess;
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
import org.sagebionetworks.repo.model.search.SearchQuery;
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

	@BeforeEach
	public void before() throws SynapseException {
		adminSynapse.clearAllLocks();
	}

	@AfterEach
	public void after() {
		// Delete in reverse order (search index before table before project)
		for (int i = entitiesToDelete.size() - 1; i >= 0; i--) {
			try {
				adminSynapse.deleteEntity(entitiesToDelete.get(i));
			} catch (SynapseException e) {
				// ignore
			}
		}
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

		// The lifecycle worker queries table data as the anonymous user to enforce
		// OPEN_DATA visibility. Grant PUBLIC READ on the project and DOWNLOAD on the
		// AUTHENTICATED_USERS group so the built index contains public-readable rows.
		grantPublicRead(project.getId());

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

		// The lifecycle worker queries table data as the anonymous user, which
		// requires the source table to be marked OPEN_DATA (Sage governance).
		adminSynapse.changeEntitysDataType(table.getId(), DataType.OPEN_DATA);

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
		searchIndex = adminSynapse.createEntity(searchIndex);
		entitiesToDelete.add(searchIndex);

		// 8. Wait for the index to be ACTIVE
		SearchIndexQuery waitIndexQuery = new SearchIndexQuery();
		waitIndexQuery.setSearchIndexId(searchIndex.getId());
		waitIndexQuery.setSearchQuery(new SearchQuery());

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
		autocompleteIndexQuery.setSearchQuery(new SearchQuery().setQueryText("BRC"));

		// call under test
		SearchQueryResults autocompleteResults = synapse.searchAutocomplete(autocompleteIndexQuery);
		assertNotNull(autocompleteResults);
		assertNotNull(autocompleteResults.getHits());
		assertTrue(autocompleteResults.getTotalHits() >= 2,
			"Expected at least 2 autocomplete hits for 'BRC' (BRCA1, BRCA2)");
	}

	private void grantPublicRead(String entityId) throws SynapseException {
		AccessControlList acl = synapse.getACL(entityId);
		ResourceAccess publicAccess = new ResourceAccess();
		publicAccess.setPrincipalId(
				AuthorizationConstants.BOOTSTRAP_PRINCIPAL.PUBLIC_GROUP.getPrincipalId());
		publicAccess.setAccessType(new HashSet<>(Arrays.asList(ACCESS_TYPE.READ)));
		acl.getResourceAccess().add(publicAccess);
		// Tables require DOWNLOAD to query content — grant it to AUTHENTICATED_USERS.
		ResourceAccess authUsersAccess = new ResourceAccess();
		authUsersAccess.setPrincipalId(
				AuthorizationConstants.BOOTSTRAP_PRINCIPAL.AUTHENTICATED_USERS_GROUP.getPrincipalId());
		authUsersAccess.setAccessType(new HashSet<>(Arrays.asList(ACCESS_TYPE.READ, ACCESS_TYPE.DOWNLOAD)));
		acl.getResourceAccess().add(authUsersAccess);
		synapse.updateACL(acl);
	}
}
