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
import org.sagebionetworks.repo.model.search.SearchAutocompleteBody;
import org.sagebionetworks.repo.model.search.SearchQuery;
import org.sagebionetworks.repo.model.search.SearchQueryPart;
import org.sagebionetworks.repo.model.search.SearchQueryResults;
import org.sagebionetworks.repo.model.search.table.SearchAutocompleteRequest;
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

	/** Build a SearchQuery wrapping an opaque {@code match_all} clause — the
	 * catalog-style minimum payload now that {@code query} is required. */
	private static SearchQuery matchAllBody() {
		return new SearchQuery().setQuery(
				java.util.Map.of("match_all", java.util.Collections.emptyMap()));
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
		fullQuery.setSearchQuery(matchAllBody());
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
		defaultPartsQuery.setSearchQuery(matchAllBody());

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
	 * End-to-end contract guard for the {@code postFilter} field on SearchQuery.
	 *
	 * <p>{@code post_filter} is applied <i>after</i> aggregations are computed, so aggregation
	 * buckets must reflect the unfiltered population matched by {@code query} while the returned
	 * hits are narrowed by {@code postFilter}. A {@code bool.filter} placed inside {@code query}
	 * has the opposite shape (aggregations also shrink). The test fixture seeds two distinct
	 * status values so the assertion can distinguish those two semantics.</p>
	 */
	@Test
	public void testAsyncQueryWithPostFilter() throws Exception {
		Project project = new Project();
		project.setName("ITAsyncQuery_PostFilter_" + UUID.randomUUID());
		project = synapse.createEntity(project);
		entitiesToDelete.add(project);

		grantPublicRead(project.getId());

		ColumnModel statusCol = new ColumnModel();
		statusCol.setName("status");
		statusCol.setColumnType(ColumnType.STRING);
		statusCol.setMaximumSize(50L);
		statusCol = synapse.createColumnModel(statusCol);

		TableEntity table = new TableEntity();
		table.setName("PostFilterTable");
		table.setParentId(project.getId());
		table.setColumnIds(Arrays.asList(statusCol.getId()));
		table = synapse.createEntity(table);
		entitiesToDelete.add(table);

		adminSynapse.changeEntitysDataType(table.getId(), DataType.OPEN_DATA);

		List<ColumnModel> columns = synapse.getColumnModelsForTableEntity(table.getId());
		RowSet rowSet = new RowSet();
		rowSet.setTableId(table.getId());
		rowSet.setHeaders(TableModelUtils.getSelectColumns(columns));
		// Two ACTIVE rows, three INACTIVE rows — population the aggregation must report,
		// while postFilter narrows visible hits to ACTIVE only.
		rowSet.setRows(Arrays.asList(
			new Row().setValues(Arrays.asList("ACTIVE")),
			new Row().setValues(Arrays.asList("ACTIVE")),
			new Row().setValues(Arrays.asList("INACTIVE")),
			new Row().setValues(Arrays.asList("INACTIVE")),
			new Row().setValues(Arrays.asList("INACTIVE"))
		));
		synapse.appendRowsToTable(rowSet, MAX_APPEND_TIMEOUT, table.getId());

		SearchIndex searchIndex = new SearchIndex();
		searchIndex.setName("PostFilterSearchIndex");
		searchIndex.setParentId(project.getId());
		searchIndex.setDefiningSQL("select * from " + table.getId());
		searchIndex = adminSynapse.createEntity(searchIndex);
		entitiesToDelete.add(searchIndex);

		// Pass bare column names — server auto-routes the text column through .keyword for
		// the terms aggregation and the term post-filter. This is the round-trip proof that
		// the routing happens end-to-end (request → AOSS → response).
		SearchQuery body = new SearchQuery()
				.setQuery(java.util.Map.of("match_all", java.util.Collections.emptyMap()))
				.setAggregations(java.util.Map.of(
						"by_status", java.util.Map.of(
								"terms", java.util.Map.of("field", "status"))))
				.setPost_filter(java.util.Map.of(
						"term", java.util.Map.of("status", "ACTIVE")));

		SearchIndexQuery query = new SearchIndexQuery();
		query.setSearchIndexId(searchIndex.getId());
		query.setSearchQuery(body);
		query.setResponseParts(EnumSet.of(
				SearchQueryPart.HITS, SearchQueryPart.TOTAL_HITS));

		// call under test — postFilter narrows hits but not aggregations
		AsyncJobHelper.assertAysncJobResult(synapse, AsynchJobType.SearchIndexQuery, query,
			(SearchQueryResults results) -> {
				assertNotNull(results);
				// hits + totalHits reflect the post_filter narrowing (ACTIVE only)
				assertEquals(2L, (long) results.getTotalHits(),
						"totalHits must reflect post_filter narrowing — only ACTIVE rows");
				assertNotNull(results.getHits());
				assertEquals(2, results.getHits().size());
				// aggregationResults must include BOTH buckets at full population — the
				// post_filter contract: aggregations see the unfiltered match set.
				// aggregationResults is now an opaque JSON object (Map at the wire layer);
				// no JSON.parse wrapping needed.
				Object aggResults = results.getAggregationResults();
				assertNotNull(aggResults, "aggregationResults must be populated whenever the body supplied aggregations");
				@SuppressWarnings("unchecked")
				java.util.Map<String, Object> root = (java.util.Map<String, Object>) aggResults;
				@SuppressWarnings("unchecked")
				java.util.Map<String, Object> byStatus = (java.util.Map<String, Object>) root.get("by_status");
				@SuppressWarnings("unchecked")
				java.util.List<java.util.Map<String, Object>> buckets =
						(java.util.List<java.util.Map<String, Object>>) byStatus.get("buckets");
				java.util.Map<String, Long> counts = new java.util.HashMap<>();
				for (java.util.Map<String, Object> b : buckets) {
					counts.put((String) b.get("key"), ((Number) b.get("doc_count")).longValue());
				}
				assertEquals(Long.valueOf(2L), counts.get("ACTIVE"),
						"ACTIVE bucket must report 2 (full population, not post-filtered)");
				assertEquals(Long.valueOf(3L), counts.get("INACTIVE"),
						"INACTIVE bucket must be present with full count — post_filter "
						+ "must NOT narrow aggregations (that's the bool.filter shape)");
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
		waitIndexQuery.setSearchQuery(matchAllBody());
		waitIndexQuery.setResponseParts(EnumSet.of(SearchQueryPart.TOTAL_HITS));

		AsyncJobHelper.assertAysncJobResult(synapse, AsynchJobType.SearchIndexQuery, waitIndexQuery,
			(SearchQueryResults results) -> assertEquals(3L, (long) results.getTotalHits()),
			MAX_QUERY_TIMEOUT_MS,
			AsyncJobHelper.INFINITE_RETRIES
		);

		// Autocomplete request shape is the slim SearchAutocompleteRequest: searchIndexId,
		// a prefix-flavored top-level DSL clause, and (optionally) returnFields. The column
		// is bound to the AUTOCOMPLETE analyzer chain so `match_bool_prefix` against it does
		// the edge-ngram work at index time.
		SearchAutocompleteRequest autocompleteRequest = new SearchAutocompleteRequest()
				.setSearchIndexId(searchIndex.getId())
				.setBody(new SearchAutocompleteBody()
						.setQuery(java.util.Map.of("match_bool_prefix",
								java.util.Map.of("geneName", "BRC"))));

		// call under test
		SearchQueryResults autocompleteResults = synapse.searchAutocomplete(autocompleteRequest);
		assertNotNull(autocompleteResults);
		assertNotNull(autocompleteResults.getHits());
		assertTrue(autocompleteResults.getHits().size() >= 2,
			"Expected at least 2 autocomplete hits for 'BRC' (BRCA1, BRCA2)");
		// The slim request has no responseParts knob — autocomplete is always hits-only.
		assertNull(autocompleteResults.getTotalHits(),
			"autocomplete must always omit totalHits");
		assertNull(autocompleteResults.getSelectColumns(),
			"autocomplete must always omit selectColumns");
		assertNull(autocompleteResults.getAggregationResults(),
			"autocomplete must always omit aggregationResults");
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
		query.setSearchQuery(matchAllBody());
		query.setResponseParts(EnumSet.of(SearchQueryPart.TOTAL_HITS));

		AsyncJobHelper.assertAysncJobResult(synapse, AsynchJobType.SearchIndexQuery, query,
				(SearchQueryResults results) -> assertEquals(2L, (long) results.getTotalHits()),
				MAX_QUERY_TIMEOUT_MS,
				AsyncJobHelper.INFINITE_RETRIES);
	}

	/**
	 * End-to-end contract guard for the {@code highlight} field on SearchQuery.
	 *
	 * <p>Seeds a STRING column with phrases that share the search term, sends a {@code match}
	 * query plus a {@code highlight: { fields: { geneName: {} } }} block, and asserts that
	 * each hit's {@code highlights} list carries a {@link org.sagebionetworks.repo.model.search.SearchHighlight}
	 * keyed by the bare column name with snippet fragments wrapped in the default {@code <em>}
	 * tags. This is the round-trip proof that the highlight payload is validated, rewritten,
	 * forwarded to AOSS, and surfaced back as a structured per-hit list.</p>
	 */
	@Test
	public void testAsyncQueryWithHighlight() throws Exception {
		Project project = new Project();
		project.setName("ITAsyncQuery_Highlight_" + UUID.randomUUID());
		project = synapse.createEntity(project);
		entitiesToDelete.add(project);

		grantPublicRead(project.getId());

		ColumnModel descCol = new ColumnModel();
		descCol.setName("geneName");
		descCol.setColumnType(ColumnType.LARGETEXT);
		descCol = synapse.createColumnModel(descCol);

		TableEntity table = new TableEntity();
		table.setName("HighlightTable");
		table.setParentId(project.getId());
		table.setColumnIds(Arrays.asList(descCol.getId()));
		table = synapse.createEntity(table);
		entitiesToDelete.add(table);

		adminSynapse.changeEntitysDataType(table.getId(), DataType.OPEN_DATA);

		List<ColumnModel> columns = synapse.getColumnModelsForTableEntity(table.getId());
		RowSet rowSet = new RowSet();
		rowSet.setTableId(table.getId());
		rowSet.setHeaders(TableModelUtils.getSelectColumns(columns));
		rowSet.setRows(Arrays.asList(
			new Row().setValues(Arrays.asList("BRCA1 tumor suppressor gene")),
			new Row().setValues(Arrays.asList("BRCA2 tumor suppressor gene")),
			new Row().setValues(Arrays.asList("TP53 tumor suppressor gene"))
		));
		synapse.appendRowsToTable(rowSet, MAX_APPEND_TIMEOUT, table.getId());

		SearchIndex searchIndex = new SearchIndex();
		searchIndex.setName("HighlightSearchIndex");
		searchIndex.setParentId(project.getId());
		searchIndex.setDefiningSQL("select * from " + table.getId());
		searchIndex = adminSynapse.createEntity(searchIndex);
		entitiesToDelete.add(searchIndex);

		// match query against the analyzed text column; highlight asks for fragments back on
		// that same column. Bare column names — server resolves and rewrites both directions.
		SearchQuery body = new SearchQuery()
				.setQuery(java.util.Map.of("match", java.util.Map.of("geneName", "tumor")))
				.setHighlight(java.util.Map.of("fields",
						java.util.Map.of("geneName", java.util.Collections.emptyMap())));

		SearchIndexQuery query = new SearchIndexQuery();
		query.setSearchIndexId(searchIndex.getId());
		query.setSearchQuery(body);
		query.setResponseParts(EnumSet.of(SearchQueryPart.HITS, SearchQueryPart.TOTAL_HITS));

		// call under test — highlight payload round-trips and SearchHit.highlights is populated
		AsyncJobHelper.assertAysncJobResult(synapse, AsynchJobType.SearchIndexQuery, query,
			(SearchQueryResults results) -> {
				assertNotNull(results);
				assertEquals(3L, (long) results.getTotalHits());
				assertNotNull(results.getHits());
				assertEquals(3, results.getHits().size());
				for (org.sagebionetworks.repo.model.search.SearchHit hit : results.getHits()) {
					List<org.sagebionetworks.repo.model.search.SearchHighlight> highlights =
							hit.getHighlights();
					assertNotNull(highlights, "highlights must be populated when highlight requested");
					assertEquals(1, highlights.size(),
							"only the geneName field has matches and was requested");
					org.sagebionetworks.repo.model.search.SearchHighlight h = highlights.get(0);
					assertEquals("geneName", h.getName(),
							"server must rewrite the response field reference back to the bare column name");
					assertNotNull(h.getSnippets());
					assertTrue(h.getSnippets().size() >= 1, "expected at least one snippet fragment");
					// Default highlighter wraps matched terms in <em>...</em>.
					assertTrue(h.getSnippets().get(0).contains("<em>tumor</em>"),
							"snippet must wrap the matched 'tumor' term in <em> tags; got: "
									+ h.getSnippets().get(0));
				}
			},
			MAX_QUERY_TIMEOUT_MS,
			AsyncJobHelper.INFINITE_RETRIES
		);
	}

	/**
	 * End-to-end contract guard for the {@code collapse} and {@code rescore} fields on
	 * SearchQuery. Verifies both opaque payloads are validated, field-rewritten, forwarded to
	 * AOSS, and produce the expected effect on the result set:
	 *
	 * <ul>
	 *   <li>{@code collapse} on a STRING column groups so one hit per distinct value is
	 *       returned. Two distinct projects → two collapsed hits, even though the underlying
	 *       table has six rows.</li>
	 *   <li>{@code rescore} carrying a {@code match_phrase} boost re-orders the top hits.
	 *       The test seeds rows with and without the boosted phrase and asserts the boosted
	 *       hits sort to the top.</li>
	 * </ul>
	 */
	@Test
	public void testAsyncQueryWithCollapseAndRescore() throws Exception {
		Project project = new Project();
		project.setName("ITAsyncQuery_CollapseRescore_" + UUID.randomUUID());
		project = synapse.createEntity(project);
		entitiesToDelete.add(project);

		grantPublicRead(project.getId());

		ColumnModel projectIdCol = new ColumnModel();
		projectIdCol.setName("projectId");
		projectIdCol.setColumnType(ColumnType.STRING);
		projectIdCol.setMaximumSize(50L);
		projectIdCol = synapse.createColumnModel(projectIdCol);

		ColumnModel titleCol = new ColumnModel();
		titleCol.setName("title");
		titleCol.setColumnType(ColumnType.LARGETEXT);
		titleCol = synapse.createColumnModel(titleCol);

		TableEntity table = new TableEntity();
		table.setName("CollapseRescoreTable");
		table.setParentId(project.getId());
		table.setColumnIds(Arrays.asList(projectIdCol.getId(), titleCol.getId()));
		table = synapse.createEntity(table);
		entitiesToDelete.add(table);

		adminSynapse.changeEntitysDataType(table.getId(), DataType.OPEN_DATA);

		List<ColumnModel> columns = synapse.getColumnModelsForTableEntity(table.getId());
		RowSet rowSet = new RowSet();
		rowSet.setTableId(table.getId());
		rowSet.setHeaders(TableModelUtils.getSelectColumns(columns));
		// Two projects, three rows each. Rows in projA mention 'amyloid plaques' (the rescore
		// boost target); rows in projB mention 'amyloid' alone (matches the base query but
		// not the rescore phrase).
		rowSet.setRows(Arrays.asList(
			new Row().setValues(Arrays.asList("projA", "amyloid plaques in cortex")),
			new Row().setValues(Arrays.asList("projA", "amyloid plaques in hippocampus")),
			new Row().setValues(Arrays.asList("projA", "amyloid plaques and tau")),
			new Row().setValues(Arrays.asList("projB", "amyloid precursor protein")),
			new Row().setValues(Arrays.asList("projB", "amyloid beta peptide")),
			new Row().setValues(Arrays.asList("projB", "amyloid signaling pathway"))
		));
		synapse.appendRowsToTable(rowSet, MAX_APPEND_TIMEOUT, table.getId());

		SearchIndex searchIndex = new SearchIndex();
		searchIndex.setName("CollapseRescoreSearchIndex");
		searchIndex.setParentId(project.getId());
		searchIndex.setDefiningSQL("select * from " + table.getId());
		searchIndex = adminSynapse.createEntity(searchIndex);
		entitiesToDelete.add(searchIndex);

		// Base match on 'amyloid' — every row matches. Rescore boosts hits whose title
		// contains the exact phrase 'amyloid plaques'. Collapse on projectId groups so
		// only one hit per project is returned.
		SearchQuery body = new SearchQuery()
				.setQuery(java.util.Map.of("match", java.util.Map.of("title", "amyloid")))
				.setCollapse(java.util.Map.of("field", "projectId"))
				.setRescore(java.util.Map.of(
						"window_size", 50,
						"query", java.util.Map.of(
								"rescore_query", java.util.Map.of(
										"match_phrase", java.util.Map.of("title", "amyloid plaques")),
								"query_weight", 1.0,
								"rescore_query_weight", 5.0)));

		SearchIndexQuery query = new SearchIndexQuery();
		query.setSearchIndexId(searchIndex.getId());
		query.setSearchQuery(body);
		query.setResponseParts(EnumSet.of(SearchQueryPart.HITS, SearchQueryPart.TOTAL_HITS));

		// call under test — collapse groups results, rescore re-ranks the top hits
		AsyncJobHelper.assertAysncJobResult(synapse, AsynchJobType.SearchIndexQuery, query,
			(SearchQueryResults results) -> {
				assertNotNull(results);
				// Collapse: one hit per distinct projectId, two projects → two hits.
				assertNotNull(results.getHits());
				assertEquals(2, results.getHits().size(),
						"collapse on projectId must return one hit per distinct value");
				// Rescore: the projA hit (which has 'amyloid plaques') must rank above projB.
				org.sagebionetworks.repo.model.search.SearchHit top = results.getHits().get(0);
				java.util.List<org.sagebionetworks.repo.model.search.SearchFieldValue> fields =
						top.getFields();
				String topProjectId = null;
				for (org.sagebionetworks.repo.model.search.SearchFieldValue fv : fields) {
					if ("projectId".equals(fv.getName())) {
						topProjectId = fv.getValue();
						break;
					}
				}
				assertEquals("projA", topProjectId,
						"rescore boost on 'amyloid plaques' must rank projA above projB");
			},
			MAX_QUERY_TIMEOUT_MS,
			AsyncJobHelper.INFINITE_RETRIES
		);
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
