package org.sagebionetworks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
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
import org.sagebionetworks.repo.model.search.SearchQueryPart;
import org.sagebionetworks.repo.model.search.SearchQueryResults;
import org.sagebionetworks.repo.model.search.table.SearchIndexQuery;

/**
 * Integration tests for the SearchIndex query path.
 *
 * The two test methods are split deliberately so a single AOSS analyzer issue can't take
 * out coverage of both paths:
 *
 *   - {@link #testAsyncQueryWithDefaultAnalyzer()} exercises the async start-job/poll-job path
 *     and the {@code responseParts} opt-in mechanic against an index built with the platform
 *     default analyzer. No custom analyzer override.
 *   - {@link #testAutocompleteWithEdgeNgram()} exercises the sync autocomplete endpoint against
 *     an index built with a {@code ColumnAnalyzerOverride} mapped to the bootstrapped
 *     {@code AUTOCOMPLETE} / {@code AUTOCOMPLETE_SEARCH} edge-ngram analyzers.
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
	 * Async query path against an index built with the platform default analyzer (no
	 * ColumnAnalyzerOverride). Verifies the start-job/poll-job round trip plus the
	 * {@code responseParts} opt-in mechanic — HITS + TOTAL_HITS + SELECT_COLUMNS are
	 * populated when requested, and remain null when left at the default.
	 */
	@Test
	public void testAsyncQueryWithDefaultAnalyzer() throws Exception {
		Project project = new Project();
		project.setName("ITAsyncQuery_Default_" + UUID.randomUUID());
		project = synapse.createEntity(project);
		entitiesToDelete.add(project);

		grantPublicRead(project.getId());

		ColumnModel nameCol = new ColumnModel();
		nameCol.setName("geneName");
		nameCol.setColumnType(ColumnType.STRING);
		nameCol.setMaximumSize(100L);
		nameCol = synapse.createColumnModel(nameCol);

		TableEntity table = new TableEntity();
		table.setName("AsyncQueryDefaultTable");
		table.setParentId(project.getId());
		table.setColumnIds(Arrays.asList(nameCol.getId()));
		table = synapse.createEntity(table);
		entitiesToDelete.add(table);

		// The lifecycle worker queries table data as the anonymous user, which requires the
		// source table to be marked OPEN_DATA (Sage governance).
		adminSynapse.changeEntitysDataType(table.getId(), DataType.OPEN_DATA);

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

		// SearchIndex with no SearchConfiguration — default analyzer (no edge_ngram).
		SearchIndex searchIndex = new SearchIndex();
		searchIndex.setName("AsyncQueryDefaultSearchIndex");
		searchIndex.setParentId(project.getId());
		searchIndex.setDefiningSQL("select * from " + table.getId());
		searchIndex = adminSynapse.createEntity(searchIndex);
		entitiesToDelete.add(searchIndex);

		// Async query with all opt-in parts.
		SearchIndexQuery fullQuery = new SearchIndexQuery();
		fullQuery.setSearchIndexId(searchIndex.getId());
		fullQuery.setSearchQuery(new SearchQuery());
		fullQuery.setResponseParts(EnumSet.of(
				SearchQueryPart.HITS, SearchQueryPart.TOTAL_HITS, SearchQueryPart.SELECT_COLUMNS));

		// call under test — async start/poll path with responseParts populated
		AsyncJobHelper.assertAysncJobResult(synapse, AsynchJobType.SearchIndexQuery, fullQuery,
			(SearchQueryResults results) -> {
				assertNotNull(results);
				assertEquals(3L, (long) results.getTotalHits());
				assertNotNull(results.getSelectColumns(),
					"selectColumns should be populated when SELECT_COLUMNS is requested");
				assertEquals(1, results.getSelectColumns().size(),
					"definingSQL is 'select * from <table>' with one column (geneName)");
				assertEquals("geneName", results.getSelectColumns().get(0).getName());
			},
			MAX_QUERY_TIMEOUT_MS,
			AsyncJobHelper.INFINITE_RETRIES
		);

		// Async query with responseParts left null — defaults to HITS only, the rest must be null.
		SearchIndexQuery defaultPartsQuery = new SearchIndexQuery();
		defaultPartsQuery.setSearchIndexId(searchIndex.getId());
		defaultPartsQuery.setSearchQuery(new SearchQuery());

		// call under test — async path, default response parts
		AsyncJobHelper.assertAysncJobResult(synapse, AsynchJobType.SearchIndexQuery, defaultPartsQuery,
			(SearchQueryResults results) -> {
				assertNotNull(results);
				assertNotNull(results.getHits(), "HITS is always populated by default");
				assertNull(results.getTotalHits(),
					"totalHits should be null when responseParts is left at default (HITS only)");
				assertNull(results.getSelectColumns(),
					"selectColumns should be null when responseParts is left at default (HITS only)");
			},
			MAX_QUERY_TIMEOUT_MS,
			AsyncJobHelper.INFINITE_RETRIES
		);
	}

	/**
	 * Sync autocomplete with edge_ngram AUTOCOMPLETE analyzer configured via
	 * ColumnAnalyzerOverride and SearchConfiguration. This is the optimal setup for
	 * high-performance type-ahead: edge_ngram pre-computes prefix tokens at index time so
	 * matching is an exact token lookup.
	 */
	@Test
	public void testAutocompleteWithEdgeNgram() throws Exception {
		Project project = new Project();
		project.setName("ITSearchAutocomplete_EdgeNgram_" + UUID.randomUUID());
		project = synapse.createEntity(project);
		entitiesToDelete.add(project);

		grantPublicRead(project.getId());

		ListTextAnalyzersResponse analyzers = adminSynapse.listTextAnalyzers(new ListTextAnalyzersRequest());
		String orgName = analyzers.getResults().get(0).getOrganizationName();

		// AUTOCOMPLETE owns both analyzer.default (edge_ngram index) and analyzer.default_search
		// (non-ngram search), so a single qname covers index and search time.
		ColumnAnalyzerOverrideEntry entry = new ColumnAnalyzerOverrideEntry();
		entry.setColumnName("geneName");
		entry.setAnalyzer(new org.json.JSONObject().put("$ref", orgName + "-AUTOCOMPLETE"));

		ColumnAnalyzerOverride override = new ColumnAnalyzerOverride();
		override.setName("IT_AUTOCOMPLETE_OVERRIDE_" + UUID.randomUUID().toString().replace("-", ""));
		override.setOrganizationName(orgName);
		override.setOverrides(Arrays.asList(entry));
		override = adminSynapse.createColumnAnalyzerOverride(override);

		String overrideQualifiedName = orgName + "-" + override.getName();
		SearchConfiguration config = new SearchConfiguration();
		config.setName("IT_AUTOCOMPLETE_CONFIG_" + UUID.randomUUID().toString().replace("-", ""));
		config.setOrganizationName(orgName);
		config.setColumnAnalyzerOverrides(Arrays.asList(
				new org.json.JSONObject().put("$ref", overrideQualifiedName)));
		config = adminSynapse.createSearchConfiguration(config);

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

		adminSynapse.changeEntitysDataType(table.getId(), DataType.OPEN_DATA);

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

		SearchIndex searchIndex = new SearchIndex();
		searchIndex.setName("AutocompleteEdgeNgramSearchIndex");
		searchIndex.setParentId(project.getId());
		searchIndex.setDefiningSQL("select * from " + table.getId());
		searchIndex.setSearchConfigurationId(config.getId());
		searchIndex = adminSynapse.createEntity(searchIndex);
		entitiesToDelete.add(searchIndex);

		// Wait for the index to be ACTIVE by polling a count query. If the index ends up FAILED
		// the manager raises IllegalArgumentException with the stored errorMessage which the
		// async helper surfaces verbatim — that's what we want, since the autocomplete check
		// below would otherwise time out without context.
		SearchIndexQuery waitIndexQuery = new SearchIndexQuery();
		waitIndexQuery.setSearchIndexId(searchIndex.getId());
		waitIndexQuery.setSearchQuery(new SearchQuery());
		waitIndexQuery.setResponseParts(EnumSet.of(SearchQueryPart.TOTAL_HITS));

		AsyncJobHelper.assertAysncJobResult(synapse, AsynchJobType.SearchIndexQuery, waitIndexQuery,
			(SearchQueryResults results) -> assertEquals(3L, (long) results.getTotalHits()),
			MAX_QUERY_TIMEOUT_MS,
			AsyncJobHelper.INFINITE_RETRIES
		);

		SearchIndexQuery autocompleteIndexQuery = new SearchIndexQuery();
		autocompleteIndexQuery.setSearchIndexId(searchIndex.getId());
		autocompleteIndexQuery.setSearchQuery(new SearchQuery().setQueryText("BRC"));

		// call under test
		SearchQueryResults autocompleteResults = synapse.searchAutocomplete(autocompleteIndexQuery);
		assertNotNull(autocompleteResults);
		assertNotNull(autocompleteResults.getHits());
		assertTrue(autocompleteResults.getHits().size() >= 2,
			"Expected at least 2 autocomplete hits for 'BRC' (BRCA1, BRCA2)");
		// Default responseParts should omit the opt-in parts
		assertNull(autocompleteResults.getTotalHits(),
			"totalHits should be null when responseParts is left at default (HITS only)");
		assertNull(autocompleteResults.getSelectColumns(),
			"selectColumns should be null when responseParts is left at default (HITS only)");
	}

	/**
	 * End-to-end test that the inline defaultAnalyzer literal on a SearchConfiguration is
	 * actually wired through to the AOSS index — not silently dropped at build time.
	 *
	 * <p>SearchConfiguration carries a bare OpenSearch settings.analysis block as its
	 * defaultAnalyzer (no $ref to a saved TextAnalyzer). The build succeeds and the index
	 * returns rows: if the inline literal were dropped, the index would still build with
	 * the column-type default and the query would still pass — but a failed build would
	 * surface IllegalArgumentException through the async helper, and an inline literal
	 * carrying a malformed analyzer chain (the negative case) would fail the build.</p>
	 */
	@Test
	public void testAsyncQueryWithInlineDefaultAnalyzer() throws Exception {
		Project project = new Project();
		project.setName("ITAsyncQuery_InlineDefault_" + UUID.randomUUID());
		project = synapse.createEntity(project);
		entitiesToDelete.add(project);

		grantPublicRead(project.getId());

		ListTextAnalyzersResponse analyzers = adminSynapse.listTextAnalyzers(new ListTextAnalyzersRequest());
		String orgName = analyzers.getResults().get(0).getOrganizationName();

		// Bare OpenSearch settings.analysis block written directly onto defaultAnalyzer.
		// No envelope. No $ref. The lifecycle's materializeInlineAnalyzerSlots must
		// recognize this and inject a synthetic qname into the loaded-analyzers map.
		org.json.JSONObject inlineDefault = new org.json.JSONObject().put(
				"analyzer", new org.json.JSONObject().put(
						"default", new org.json.JSONObject()
								.put("type", "custom")
								.put("tokenizer", "standard")
								.put("filter", new org.json.JSONArray().put("lowercase"))));
		SearchConfiguration config = new SearchConfiguration();
		config.setName("IT_INLINE_DEFAULT_CONFIG_" + UUID.randomUUID().toString().replace("-", ""));
		config.setOrganizationName(orgName);
		config.setDefaultAnalyzer(inlineDefault);
		config = adminSynapse.createSearchConfiguration(config);

		ColumnModel nameCol = new ColumnModel();
		nameCol.setName("geneName");
		nameCol.setColumnType(ColumnType.STRING);
		nameCol.setMaximumSize(100L);
		nameCol = synapse.createColumnModel(nameCol);

		TableEntity table = new TableEntity();
		table.setName("InlineDefaultAnalyzerTable");
		table.setParentId(project.getId());
		table.setColumnIds(Arrays.asList(nameCol.getId()));
		table = synapse.createEntity(table);
		entitiesToDelete.add(table);

		adminSynapse.changeEntitysDataType(table.getId(), DataType.OPEN_DATA);

		List<ColumnModel> columns = synapse.getColumnModelsForTableEntity(table.getId());
		RowSet rowSet = new RowSet();
		rowSet.setTableId(table.getId());
		rowSet.setHeaders(TableModelUtils.getSelectColumns(columns));
		rowSet.setRows(Arrays.asList(
				new Row().setValues(Arrays.asList("BRCA1")),
				new Row().setValues(Arrays.asList("BRCA2"))));
		synapse.appendRowsToTable(rowSet, MAX_APPEND_TIMEOUT, table.getId());

		SearchIndex searchIndex = new SearchIndex();
		searchIndex.setName("InlineDefaultSearchIndex");
		searchIndex.setParentId(project.getId());
		searchIndex.setDefiningSQL("select * from " + table.getId());
		searchIndex.setSearchConfigurationId(config.getId());
		searchIndex = adminSynapse.createEntity(searchIndex);
		entitiesToDelete.add(searchIndex);

		// Async query — if the lifecycle silently dropped the inline literal it would still
		// pass with the column-type default, BUT a malformed inline literal would fail the
		// build with IllegalArgumentException surfaced verbatim. Using a valid bare-block
		// here means the build must succeed AND the AOSS index must respond to queries.
		SearchIndexQuery query = new SearchIndexQuery();
		query.setSearchIndexId(searchIndex.getId());
		query.setSearchQuery(new SearchQuery());
		query.setResponseParts(EnumSet.of(SearchQueryPart.TOTAL_HITS));

		AsyncJobHelper.assertAysncJobResult(synapse, AsynchJobType.SearchIndexQuery, query,
				(SearchQueryResults results) -> assertEquals(2L, (long) results.getTotalHits()),
				MAX_QUERY_TIMEOUT_MS,
				AsyncJobHelper.INFINITE_RETRIES);
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
