package org.sagebionetworks.repo.manager.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.sagebionetworks.repo.manager.EntityManager;
import org.sagebionetworks.repo.manager.UserManager;
import org.sagebionetworks.repo.manager.table.TableManagerSupport;
import org.sagebionetworks.repo.model.UnauthorizedException;
import org.sagebionetworks.repo.model.UserInfo;
import org.sagebionetworks.repo.model.dbo.dao.table.TableModelTestUtils;
import org.sagebionetworks.repo.model.dbo.search.ColumnAnalyzerOverrideDao;
import org.sagebionetworks.repo.model.dbo.search.SynonymSetDao;
import org.sagebionetworks.repo.model.dbo.search.TextAnalyzerDao;
import org.sagebionetworks.repo.model.entity.IdAndVersion;
import org.sagebionetworks.repo.model.search.FacetRequest;
import org.sagebionetworks.repo.model.search.KeyRange;
import org.sagebionetworks.repo.model.search.KeyValues;
import org.sagebionetworks.repo.model.search.SearchFieldValue;
import org.sagebionetworks.repo.model.search.SearchHit;
import org.sagebionetworks.repo.model.search.SearchQuery;
import org.sagebionetworks.repo.model.search.SearchQueryResults;
import org.sagebionetworks.repo.model.search.SortDirection;
import org.sagebionetworks.repo.model.search.SortField;
import org.sagebionetworks.repo.model.search.table.ColumnAnalyzerOverride;
import org.sagebionetworks.repo.model.search.table.ColumnAnalyzerOverrideEntry;
import org.sagebionetworks.repo.model.search.table.SearchConfiguration;
import org.sagebionetworks.repo.model.search.table.SearchIndex;
import org.sagebionetworks.repo.model.search.table.SearchIndexState;
import org.sagebionetworks.repo.model.search.table.SearchIndexStatus;
import org.sagebionetworks.repo.model.search.table.TextAnalyzer;
import org.sagebionetworks.repo.model.table.ColumnModel;
import org.sagebionetworks.repo.model.table.ColumnType;
import org.sagebionetworks.repo.model.table.FacetColumnResult;
import org.sagebionetworks.repo.model.table.FacetColumnResultValues;
import org.sagebionetworks.repo.model.table.SelectColumn;
import org.sagebionetworks.table.cluster.ConnectionFactory;
import org.sagebionetworks.table.cluster.description.TableIndexDescription;
import org.sagebionetworks.table.cluster.search.SearchIndexStatusDao;

@ExtendWith(MockitoExtension.class)
public class SearchIndexQueryManagerImplTest {

	@Mock
	private EntityManager entityManager;
	@Mock
	private ConnectionFactory connectionFactory;
	@Mock
	private OpenSearchManager openSearchManager;
	@Mock
	private SearchConfigurationResolver searchConfigurationResolver;
	@Mock
	private UserManager userManager;
	@Mock
	private TableManagerSupport tableManagerSupport;
	@Mock
	private ColumnAnalyzerOverrideDao columnAnalyzerOverrideDao;
	@Mock
	private SynonymSetDao synonymSetDao;
	@Mock
	private TextAnalyzerDao textAnalyzerDao;
	@Mock
	private SearchIndexStatusDao statusDao;

	private SearchIndexQueryManagerImpl manager;
	private UserInfo user;

	private static final String SEARCH_INDEX_ID = "1";
	private static final IdAndVersion SOURCE_ID = IdAndVersion.parse("syn456");
	private static final String NAME_COLUMN_ID = "111";
	private static final String DESC_COLUMN_ID = "222";
	private static final String NAME_COLUMN = "name";
	private static final String DESC_COLUMN = "description";

	@BeforeEach
	public void setUp() {
		manager = new SearchIndexQueryManagerImpl(
				entityManager, connectionFactory,
				openSearchManager, searchConfigurationResolver, userManager,
				tableManagerSupport, columnAnalyzerOverrideDao, synonymSetDao, textAnalyzerDao);
		user = new UserInfo(false);
		user.setId(999L);
	}

	private SearchIndex setupSearchIndex() {
		SearchIndex si = new SearchIndex();
		si.setId("1");
		si.setDefiningSQL("SELECT * FROM syn456");
		si.setParentId("syn789");
		return si;
	}

	private void setupAuthMocks() {
		// tableManagerSupport.validateTableReadAccess is void; default Mockito behavior does nothing → pass.
		// getIndexDescription is stubbed in setupHappyPathMocks. Tests that only need auth to pass
		// without exercising the index-description path stub it here.
		when(tableManagerSupport.getIndexDescription(SOURCE_ID))
				.thenReturn(new TableIndexDescription(SOURCE_ID));
	}

	private SearchQuery buildQuery() {
		SearchQuery query = new SearchQuery();
		query.setQueryText("test");
		return query;
	}

	/**
	 * Wires up the mocks needed for the full execute-query flow to succeed:
	 * status check, QueryTranslator schema resolution, and analyzer lookup.
	 * Returns the schema so tests can reference column names/IDs.
	 */
	private List<ColumnModel> setupHappyPathMocks() {
		List<ColumnModel> schema = Arrays.asList(
				TableModelTestUtils.createColumn(Long.parseLong(NAME_COLUMN_ID), NAME_COLUMN, ColumnType.STRING),
				TableModelTestUtils.createColumn(Long.parseLong(DESC_COLUMN_ID), DESC_COLUMN, ColumnType.STRING));
		when(connectionFactory.getSearchIndexStatusDao()).thenReturn(statusDao);
		when(statusDao.getStatus(1L)).thenReturn(Optional.of(
				new SearchIndexStatus().setSearchIndexId(SEARCH_INDEX_ID).setState(SearchIndexState.ACTIVE)));
		// Lenient because testSearchWithConfigDefaultAnalyzer overrides this for a non-null configId.
		lenient().when(searchConfigurationResolver.resolve(user, null, "syn789")).thenReturn(Optional.empty());
		// Note: getIndexDescription is already stubbed by setupAuthMocks().
		when(tableManagerSupport.getTableSchema(SOURCE_ID)).thenReturn(schema);
		for (ColumnModel cm : schema) {
			when(tableManagerSupport.getColumnModel(cm.getId())).thenReturn(cm);
		}
		when(textAnalyzerDao.getByQualifiedNames(anyList())).thenReturn(Collections.emptyMap());
		return schema;
	}

	/**
	 * Builds a SearchQueryResults shaped like what OpenSearch would return: a single hit
	 * whose field names are column IDs (pre-translation). The manager translates these
	 * back to user-facing names in {@code translateResultIdsToNames}.
	 */
	private SearchQueryResults buildRawResults() {
		SearchHit hit = new SearchHit();
		hit.setRowId(42L);
		hit.setFields(new ArrayList<>(Arrays.asList(
				new SearchFieldValue().setName(NAME_COLUMN_ID).setValue("Alice"),
				new SearchFieldValue().setName(DESC_COLUMN_ID).setValue("bio"))));
		hit.setHighlights(new ArrayList<>(Collections.singletonList(
				new SearchFieldValue().setName(NAME_COLUMN_ID + ".searchable").setValue("<em>Alice</em>"))));
		return new SearchQueryResults().setTotalHits(1L).setHits(new ArrayList<>(Collections.singletonList(hit)));
	}

	@Test
	public void testSearchWithNoReadOnSearchIndex() {
		when(entityManager.getEntity(user, "1", SearchIndex.class))
				.thenThrow(new UnauthorizedException("no access"));

		assertThrows(UnauthorizedException.class, () -> manager.search(user, SEARCH_INDEX_ID, buildQuery()));
		verifyNoMoreInteractions(connectionFactory, openSearchManager);
	}

	@Test
	public void testSearchWithNoReadOnSourceTable() {
		SearchIndex si = setupSearchIndex();
		when(entityManager.getEntity(user, "1", SearchIndex.class)).thenReturn(si);
		TableIndexDescription indexDescription = new TableIndexDescription(SOURCE_ID);
		when(tableManagerSupport.getIndexDescription(SOURCE_ID)).thenReturn(indexDescription);
		doThrow(new UnauthorizedException("no access to source"))
				.when(tableManagerSupport).validateTableReadAccess(user, indexDescription);

		// call under test
		assertThrows(UnauthorizedException.class, () -> manager.search(user, SEARCH_INDEX_ID, buildQuery()));
		verifyNoMoreInteractions(connectionFactory, openSearchManager);
	}

	@Test
	public void testSearchWithCreatingStatus() {
		SearchIndex si = setupSearchIndex();
		when(entityManager.getEntity(user, "1", SearchIndex.class)).thenReturn(si);
		setupAuthMocks();
		when(connectionFactory.getSearchIndexStatusDao()).thenReturn(statusDao);
		when(statusDao.getStatus(1L)).thenReturn(Optional.of(
				new SearchIndexStatus().setSearchIndexId(SEARCH_INDEX_ID).setState(SearchIndexState.CREATING)));

		IllegalStateException ex = assertThrows(IllegalStateException.class,
				() -> manager.search(user, SEARCH_INDEX_ID, buildQuery()));
		assertTrue(ex.getMessage().contains("still building"));
		verifyNoMoreInteractions(openSearchManager);
	}

	@Test
	public void testSearchWithFailedStatusIncludesStoredErrorMessage() {
		SearchIndex si = setupSearchIndex();
		when(entityManager.getEntity(user, "1", SearchIndex.class)).thenReturn(si);
		setupAuthMocks();
		when(connectionFactory.getSearchIndexStatusDao()).thenReturn(statusDao);
		when(statusDao.getStatus(1L)).thenReturn(Optional.of(new SearchIndexStatus()
				.setSearchIndexId(SEARCH_INDEX_ID)
				.setState(SearchIndexState.FAILED)
				.setErrorMessage("Column 'bogus_col' does not exist.")));

		// call under test
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				() -> manager.search(user, SEARCH_INDEX_ID, buildQuery()));

		assertTrue(ex.getMessage().contains("build failed"));
		assertTrue(ex.getMessage().contains("Column 'bogus_col' does not exist."),
				"Expected the stored error message to be forwarded to the user: " + ex.getMessage());
		assertTrue(ex.getMessage().contains("Delete or update the SearchIndex"));
		verifyNoMoreInteractions(openSearchManager);
	}

	@Test
	public void testSearchWithFailedStatusAndMissingErrorMessage() {
		SearchIndex si = setupSearchIndex();
		when(entityManager.getEntity(user, "1", SearchIndex.class)).thenReturn(si);
		setupAuthMocks();
		when(connectionFactory.getSearchIndexStatusDao()).thenReturn(statusDao);
		when(statusDao.getStatus(1L)).thenReturn(Optional.of(new SearchIndexStatus()
				.setSearchIndexId(SEARCH_INDEX_ID)
				.setState(SearchIndexState.FAILED)));

		// call under test — no stored error, fall back to the generic remediation hint
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				() -> manager.search(user, SEARCH_INDEX_ID, buildQuery()));

		assertTrue(ex.getMessage().contains("Delete or update the SearchIndex"));
		verifyNoMoreInteractions(openSearchManager);
	}

	@Test
	public void testSearchWithMissingStatus() {
		SearchIndex si = setupSearchIndex();
		when(entityManager.getEntity(user, "1", SearchIndex.class)).thenReturn(si);
		setupAuthMocks();
		when(connectionFactory.getSearchIndexStatusDao()).thenReturn(statusDao);
		when(statusDao.getStatus(1L)).thenReturn(Optional.empty());

		IllegalStateException ex = assertThrows(IllegalStateException.class,
				() -> manager.search(user, SEARCH_INDEX_ID, buildQuery()));
		assertTrue(ex.getMessage().contains("still building"));
		verifyNoMoreInteractions(openSearchManager);
	}

	@Test
	public void testSearchWithActiveStatus() {
		SearchIndex si = setupSearchIndex();
		when(entityManager.getEntity(user, "1", SearchIndex.class)).thenReturn(si);
		setupAuthMocks();
		setupHappyPathMocks();
		when(openSearchManager.search(eq("search-index-1"), any(), any(), any(), any(), any()))
				.thenReturn(buildRawResults());

		SearchQuery query = buildQuery();

		// call under test
		SearchQueryResults results = manager.search(user, SEARCH_INDEX_ID, query);

		assertNotNull(results);
		assertEquals(1L, results.getTotalHits());
		SearchHit hit = results.getHits().get(0);
		// Column IDs returned by OpenSearch should be translated back to user-facing names
		assertEquals(NAME_COLUMN, hit.getFields().get(0).getName());
		assertEquals(DESC_COLUMN, hit.getFields().get(1).getName());
		// Highlight name has its ".searchable" suffix stripped and ID translated to name
		assertEquals(NAME_COLUMN, hit.getHighlights().get(0).getName());

		verify(openSearchManager).search(eq("search-index-1"), any(), any(), any(), any(), any());
	}

	/**
	 * Columns with special characters in their names (spaces, apostrophes, parens, dots,
	 * brackets) must round-trip through the translation layer: user-facing names going in
	 * get mapped to column IDs before reaching OpenSearch, and the IDs coming back in
	 * results get translated back to the original names. This used to require an end-to-end
	 * IT to verify, but the logic is entirely local to the manager — OpenSearch only ever
	 * sees numeric column IDs, so the special characters never leave this layer.
	 */
	@Test
	public void testSearchWithSpecialCharColumnNames() {
		// Columns whose names would be problematic if used as OpenSearch field names directly
		String studyNameId = "101";
		String diagnosisId = "102";
		String ageId = "103";
		String dataFieldId = "104";
		String metadataId = "105";
		ColumnModel studyName = TableModelTestUtils.createColumn(Long.parseLong(studyNameId),
				"Study Name", ColumnType.STRING);
		ColumnModel diagnosis = TableModelTestUtils.createColumn(Long.parseLong(diagnosisId),
				"patient's diagnosis", ColumnType.STRING);
		ColumnModel age = TableModelTestUtils.createColumn(Long.parseLong(ageId),
				"Age (years)", ColumnType.INTEGER);
		ColumnModel dataField = TableModelTestUtils.createColumn(Long.parseLong(dataFieldId),
				"data.field", ColumnType.STRING);
		ColumnModel metadata = TableModelTestUtils.createColumn(Long.parseLong(metadataId),
				"[metadata]", ColumnType.STRING);
		List<ColumnModel> schema = Arrays.asList(studyName, diagnosis, age, dataField, metadata);

		SearchIndex si = setupSearchIndex();
		when(entityManager.getEntity(user, "1", SearchIndex.class)).thenReturn(si);
		setupAuthMocks();
		when(connectionFactory.getSearchIndexStatusDao()).thenReturn(statusDao);
		when(statusDao.getStatus(1L)).thenReturn(Optional.of(
				new SearchIndexStatus().setSearchIndexId(SEARCH_INDEX_ID).setState(SearchIndexState.ACTIVE)));
		when(searchConfigurationResolver.resolve(user, null, "syn789")).thenReturn(Optional.empty());
		when(tableManagerSupport.getIndexDescription(SOURCE_ID)).thenReturn(new TableIndexDescription(SOURCE_ID));
		when(tableManagerSupport.getTableSchema(SOURCE_ID)).thenReturn(schema);
		for (ColumnModel cm : schema) {
			when(tableManagerSupport.getColumnModel(cm.getId())).thenReturn(cm);
		}
		when(textAnalyzerDao.getByQualifiedNames(anyList())).thenReturn(Collections.emptyMap());

		// Build results shaped like what OpenSearch would return: fields keyed by ID, a
		// highlight keyed by "id.searchable", and a facet keyed by ID. These should all be
		// translated back to their special-char original names.
		SearchHit hit = new SearchHit();
		hit.setRowId(1L);
		hit.setFields(new ArrayList<>(Arrays.asList(
				new SearchFieldValue().setName(studyNameId).setValue("Alzheimer Study"),
				new SearchFieldValue().setName(ageId).setValue("65"))));
		hit.setHighlights(new ArrayList<>(Collections.singletonList(
				new SearchFieldValue().setName(studyNameId + ".searchable").setValue("<em>Alzheimer</em> Study"))));
		FacetColumnResult facetResult = new FacetColumnResultValues().setColumnName(diagnosisId);
		SearchQueryResults raw = new SearchQueryResults()
				.setTotalHits(1L)
				.setHits(new ArrayList<>(Collections.singletonList(hit)))
				.setFacets(new ArrayList<>(Collections.singletonList(facetResult)));
		when(openSearchManager.search(eq("search-index-1"), any(), any(), any(), any(), any()))
				.thenReturn(raw);

		// Build a SearchQuery exercising every translation path with special-char names
		SearchQuery query = new SearchQuery();
		query.setQueryText("Alzheimer");
		query.setQueryFields(new ArrayList<>(Arrays.asList("Study Name", "data.field^3")));
		query.setTermsFilters(new ArrayList<>(Collections.singletonList(
				new KeyValues().setKey("Study Name").setValues(Arrays.asList("Alzheimer Study")))));
		query.setRangeFilters(new ArrayList<>(Collections.singletonList(
				new KeyRange().setKey("Age (years)").setMin("0").setMax("100"))));
		query.setFacetRequests(new ArrayList<>(Collections.singletonList(
				new FacetRequest().setColumnName("patient's diagnosis").setMaxValueCount(10L))));
		query.setExistsFilters(new ArrayList<>(Collections.singletonList("[metadata]")));
		query.setNotExistsFilters(new ArrayList<>(Collections.singletonList("data.field")));
		query.setReturnFields(new ArrayList<>(Arrays.asList("Study Name", "Age (years)")));
		query.setSort(new ArrayList<>(Arrays.asList(
				new SortField().setColumnName("Age (years)").setDirection(SortDirection.ASC),
				new SortField().setColumnName("_score").setDirection(SortDirection.DESC))));

		// call under test
		SearchQueryResults results = manager.search(user, SEARCH_INDEX_ID, query);

		// Verify the query reached OpenSearch with every user-facing name translated to an ID
		ArgumentCaptor<SearchQuery> captor = ArgumentCaptor.forClass(SearchQuery.class);
		verify(openSearchManager).search(eq("search-index-1"), captor.capture(), any(), any(), any(), any());
		SearchQuery translated = captor.getValue();

		assertEquals(Arrays.asList(studyNameId, dataFieldId + "^3"), translated.getQueryFields());
		assertEquals(studyNameId, translated.getTermsFilters().get(0).getKey());
		assertEquals(ageId, translated.getRangeFilters().get(0).getKey());
		assertEquals(diagnosisId, translated.getFacetRequests().get(0).getColumnName());
		assertEquals(Collections.singletonList(metadataId), translated.getExistsFilters());
		assertEquals(Collections.singletonList(dataFieldId), translated.getNotExistsFilters());
		assertEquals(Arrays.asList(studyNameId, ageId), translated.getReturnFields());
		assertEquals(ageId, translated.getSort().get(0).getColumnName());
		// "_score" is never a column name — it must be left alone
		assertEquals("_score", translated.getSort().get(1).getColumnName());

		// Verify result IDs were translated back to special-char original names
		assertEquals(1L, results.getTotalHits());
		SearchHit resultHit = results.getHits().get(0);
		assertEquals("Study Name", resultHit.getFields().get(0).getName());
		assertEquals("Age (years)", resultHit.getFields().get(1).getName());
		// Highlight strips ".searchable" suffix and translates ID back to name
		assertEquals("Study Name", resultHit.getHighlights().get(0).getName());
		// Facet column name translates back to its apostrophe-containing original
		assertEquals("patient's diagnosis", results.getFacets().get(0).getColumnName());
	}

	@Test
	public void testAutocompleteWithActiveStatus() {
		SearchIndex si = setupSearchIndex();
		when(entityManager.getEntity(user, "1", SearchIndex.class)).thenReturn(si);
		setupAuthMocks();
		setupHappyPathMocks();
		when(openSearchManager.autocomplete(eq("search-index-1"), any(), any(), any(), any(), any()))
				.thenReturn(buildRawResults());

		SearchQuery query = buildQuery();

		// call under test
		SearchQueryResults results = manager.autocomplete(user, SEARCH_INDEX_ID, query);

		assertNotNull(results);
		assertEquals(NAME_COLUMN, results.getHits().get(0).getFields().get(0).getName());

		// Auto-populated queryFields should contain all searchable columns (translated to IDs
		// before reaching OpenSearch). The STRING columns in our schema are text-searchable.
		ArgumentCaptor<SearchQuery> queryCaptor = ArgumentCaptor.forClass(SearchQuery.class);
		verify(openSearchManager).autocomplete(eq("search-index-1"), queryCaptor.capture(), any(), any(), any(), any());
		List<String> queryFields = queryCaptor.getValue().getQueryFields();
		assertNotNull(queryFields);
		assertTrue(queryFields.contains(NAME_COLUMN_ID));
		assertTrue(queryFields.contains(DESC_COLUMN_ID));
	}

	@Test
	public void testAutocompleteWithEmptyQueryFields() {
		// Exercises the branch at L135 where isAutocomplete=true and queryFields is non-null but empty —
		// the empty case should enter the auto-populate block.
		SearchIndex si = setupSearchIndex();
		when(entityManager.getEntity(user, "1", SearchIndex.class)).thenReturn(si);
		setupAuthMocks();
		setupHappyPathMocks();
		when(openSearchManager.autocomplete(eq("search-index-1"), any(), any(), any(), any(), any()))
				.thenReturn(buildRawResults());

		SearchQuery query = buildQuery();
		query.setQueryFields(new ArrayList<>());

		// call under test
		manager.autocomplete(user, SEARCH_INDEX_ID, query);

		ArgumentCaptor<SearchQuery> queryCaptor = ArgumentCaptor.forClass(SearchQuery.class);
		verify(openSearchManager).autocomplete(eq("search-index-1"), queryCaptor.capture(), any(), any(), any(), any());
		List<String> queryFields = queryCaptor.getValue().getQueryFields();
		assertTrue(queryFields.contains(NAME_COLUMN_ID));
		assertTrue(queryFields.contains(DESC_COLUMN_ID));
	}

	@Test
	public void testCopyMissingIdsFromSelectColumnsAllBranches() {
		// Four cases to cover every combination of: schema.getId()==null AND selectColumn.getId()!=null
		// - schema id null + selectCol id non-null → copy
		// - schema id null + selectCol id null → no copy
		// - schema id non-null + selectCol id non-null → no copy (schema wins)
		// - schema id non-null + selectCol id null → no copy
		List<ColumnModel> schemaOfSelect = Arrays.asList(
				new ColumnModel().setName("a"),                  // id=null
				new ColumnModel().setName("b"),                  // id=null
				new ColumnModel().setId("3").setName("c"),       // id non-null
				new ColumnModel().setId("4").setName("d"));      // id non-null
		List<SelectColumn> selectColumns = Arrays.asList(
				new SelectColumn().setId("111").setName("a"),    // non-null
				new SelectColumn().setName("b"),                 // null
				new SelectColumn().setId("333").setName("c"),    // non-null (won't copy, schema already has id)
				new SelectColumn().setName("d"));                // null

		// call under test
		manager.copyMissingIdsFromSelectColumns(schemaOfSelect, selectColumns);

		assertEquals("111", schemaOfSelect.get(0).getId());      // copied
		assertNull(schemaOfSelect.get(1).getId());               // not copied — source was null
		assertEquals("3", schemaOfSelect.get(2).getId());        // not copied — target already had id
		assertEquals("4", schemaOfSelect.get(3).getId());        // not copied — target already had id
	}

	@Test
	public void testCopyMissingIdsFromSelectColumnsWithMismatchedLengths() {
		// Exercises the loop-bound branches: the shorter list terminates the loop.
		List<ColumnModel> schemaOfSelect = Arrays.asList(
				new ColumnModel().setName("a"),
				new ColumnModel().setName("b"));
		List<SelectColumn> selectColumns = Arrays.asList(
				new SelectColumn().setId("111").setName("a"));   // only one entry

		// call under test — must not throw IndexOutOfBounds
		manager.copyMissingIdsFromSelectColumns(schemaOfSelect, selectColumns);

		assertEquals("111", schemaOfSelect.get(0).getId());
		assertNull(schemaOfSelect.get(1).getId());               // loop stopped before index 1
	}

	@Test
	public void testAutocompleteWithPrePopulatedQueryFields() {
		// Exercises the branch at L135 where isAutocomplete=true but queryFields is already populated —
		// auto-populate should be skipped.
		SearchIndex si = setupSearchIndex();
		when(entityManager.getEntity(user, "1", SearchIndex.class)).thenReturn(si);
		setupAuthMocks();
		setupHappyPathMocks();
		when(openSearchManager.autocomplete(eq("search-index-1"), any(), any(), any(), any(), any()))
				.thenReturn(buildRawResults());

		SearchQuery query = buildQuery();
		query.setQueryFields(new ArrayList<>(Arrays.asList(NAME_COLUMN)));

		// call under test
		manager.autocomplete(user, SEARCH_INDEX_ID, query);

		ArgumentCaptor<SearchQuery> queryCaptor = ArgumentCaptor.forClass(SearchQuery.class);
		verify(openSearchManager).autocomplete(eq("search-index-1"), queryCaptor.capture(), any(), any(), any(), any());
		// Only the caller-provided field survives (translated to its ID); the other column was NOT auto-added.
		assertEquals(Arrays.asList(NAME_COLUMN_ID), queryCaptor.getValue().getQueryFields());
	}

	@Test
	public void testSearchWithConfigDefaultAnalyzer() {
		// Exercises the non-null config branch at L145 and L216 in collectAndLoadAnalyzers.
		SearchIndex si = setupSearchIndex();
		si.setSearchConfigurationId("cfg1");
		when(entityManager.getEntity(user, "1", SearchIndex.class)).thenReturn(si);
		setupAuthMocks();
		setupHappyPathMocks();
		SearchConfiguration config = new SearchConfiguration();
		config.setDefaultAnalyzer("org.sage-DEFAULT");
		when(searchConfigurationResolver.resolve(user, "cfg1", "syn789"))
				.thenReturn(Optional.of(config));
		when(openSearchManager.search(eq("search-index-1"), any(), any(), eq("org.sage-DEFAULT"), any(), any()))
				.thenReturn(buildRawResults());

		// call under test
		manager.search(user, SEARCH_INDEX_ID, buildQuery());

		verify(openSearchManager).search(eq("search-index-1"), any(), any(), eq("org.sage-DEFAULT"), any(), any());
	}

	@Test
	public void testCollectAndLoadAnalyzersWithConfigButNullDefault() {
		// Exercises the L216 partial branch where config != null but defaultAnalyzer == null.
		SearchConfiguration config = new SearchConfiguration();
		// defaultAnalyzer explicitly left null
		when(textAnalyzerDao.getByQualifiedNames(anyList())).thenReturn(Collections.emptyMap());

		// call under test — must not add null to the qualified name set
		manager.collectAndLoadAnalyzers(config, null, Collections.emptyList());
	}

	// --- Focused unit tests for package-protected helpers ---

	@Test
	public void testGetSearchableColumnNamesWithMixedTypes() {
		List<ColumnModel> columns = Arrays.asList(
				TableModelTestUtils.createColumn(1L, "str", ColumnType.STRING),
				TableModelTestUtils.createColumn(2L, "link", ColumnType.LINK),
				TableModelTestUtils.createColumn(3L, "num", ColumnType.INTEGER),
				TableModelTestUtils.createColumn(4L, "flag", ColumnType.BOOLEAN));

		// call under test
		List<String> names = manager.getSearchableColumnNames(columns);

		assertEquals(Arrays.asList("str", "link"), names);
	}

	@Test
	public void testGetSearchableColumnNamesWithEmpty() {
		// call under test
		List<String> names = manager.getSearchableColumnNames(Collections.emptyList());

		assertTrue(names.isEmpty());
	}

	@Test
	public void testGetIndexName() {
		// call under test
		assertEquals("search-index-syn42", manager.getIndexName("syn42"));
	}

	@Test
	public void testCheckIndexStatusWithActive() {
		when(connectionFactory.getSearchIndexStatusDao()).thenReturn(statusDao);
		when(statusDao.getStatus(1L)).thenReturn(Optional.of(
				new SearchIndexStatus().setSearchIndexId(SEARCH_INDEX_ID).setState(SearchIndexState.ACTIVE)));

		// call under test — should not throw
		manager.checkIndexStatus(SEARCH_INDEX_ID);
	}

	@Test
	public void testCheckIndexStatusWithMissing() {
		when(connectionFactory.getSearchIndexStatusDao()).thenReturn(statusDao);
		when(statusDao.getStatus(1L)).thenReturn(Optional.empty());

		// call under test
		IllegalStateException ex = assertThrows(IllegalStateException.class,
				() -> manager.checkIndexStatus(SEARCH_INDEX_ID));
		assertTrue(ex.getMessage().contains("still building"));
	}

	@Test
	public void testCheckIndexStatusWithCreating() {
		when(connectionFactory.getSearchIndexStatusDao()).thenReturn(statusDao);
		when(statusDao.getStatus(1L)).thenReturn(Optional.of(
				new SearchIndexStatus().setSearchIndexId(SEARCH_INDEX_ID).setState(SearchIndexState.CREATING)));

		// call under test
		IllegalStateException ex = assertThrows(IllegalStateException.class,
				() -> manager.checkIndexStatus(SEARCH_INDEX_ID));
		assertTrue(ex.getMessage().contains("still building"));
	}

	@Test
	public void testCheckIndexStatusWithFailedAndErrorMessage() {
		when(connectionFactory.getSearchIndexStatusDao()).thenReturn(statusDao);
		when(statusDao.getStatus(1L)).thenReturn(Optional.of(new SearchIndexStatus()
				.setSearchIndexId(SEARCH_INDEX_ID)
				.setState(SearchIndexState.FAILED)
				.setErrorMessage("boom")));

		// call under test
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				() -> manager.checkIndexStatus(SEARCH_INDEX_ID));
		assertTrue(ex.getMessage().contains("boom"));
	}

	@Test
	public void testCheckIndexStatusWithFailedAndNullErrorMessage() {
		when(connectionFactory.getSearchIndexStatusDao()).thenReturn(statusDao);
		when(statusDao.getStatus(1L)).thenReturn(Optional.of(new SearchIndexStatus()
				.setSearchIndexId(SEARCH_INDEX_ID)
				.setState(SearchIndexState.FAILED)));

		// call under test
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				() -> manager.checkIndexStatus(SEARCH_INDEX_ID));
		assertTrue(ex.getMessage().contains("Delete or update the SearchIndex"));
	}

	@Test
	public void testCheckIndexStatusWithFailedAndBlankErrorMessage() {
		when(connectionFactory.getSearchIndexStatusDao()).thenReturn(statusDao);
		when(statusDao.getStatus(1L)).thenReturn(Optional.of(new SearchIndexStatus()
				.setSearchIndexId(SEARCH_INDEX_ID)
				.setState(SearchIndexState.FAILED)
				.setErrorMessage("   ")));

		// call under test
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				() -> manager.checkIndexStatus(SEARCH_INDEX_ID));
		assertTrue(ex.getMessage().contains("Delete or update the SearchIndex"));
	}

	@Test
	public void testLoadColumnAnalyzerOverridesWithNullConfig() {
		// call under test
		assertTrue(manager.loadColumnAnalyzerOverrides(null).isEmpty());
	}

	@Test
	public void testLoadColumnAnalyzerOverridesWithNullList() {
		SearchConfiguration config = new SearchConfiguration();

		// call under test
		assertTrue(manager.loadColumnAnalyzerOverrides(config).isEmpty());
	}

	@Test
	public void testLoadColumnAnalyzerOverridesWithEmptyList() {
		SearchConfiguration config = new SearchConfiguration();
		config.setColumnAnalyzerOverrides(Collections.emptyList());

		// call under test
		assertTrue(manager.loadColumnAnalyzerOverrides(config).isEmpty());
	}

	@Test
	public void testLoadColumnAnalyzerOverridesWithPopulated() {
		SearchConfiguration config = new SearchConfiguration();
		config.setColumnAnalyzerOverrides(Arrays.asList("org.sage-OV1"));
		ColumnAnalyzerOverride ov = new ColumnAnalyzerOverride();
		ov.setName("OV1");
		Map<String, ColumnAnalyzerOverride> map = new HashMap<>();
		map.put("org.sage-OV1", ov);
		when(columnAnalyzerOverrideDao.getByQualifiedNames(Arrays.asList("org.sage-OV1"))).thenReturn(map);

		// call under test
		List<ColumnAnalyzerOverride> result = manager.loadColumnAnalyzerOverrides(config);

		assertEquals(1, result.size());
		assertEquals("OV1", result.get(0).getName());
	}

	@Test
	public void testCollectAndLoadAnalyzersWithNullConfigAndOverrides() {
		List<ColumnModel> columns = Arrays.asList(
				TableModelTestUtils.createColumn(1L, "a", ColumnType.STRING));
		ArgumentCaptor<List<String>> captor = ArgumentCaptor.forClass(List.class);
		when(textAnalyzerDao.getByQualifiedNames(captor.capture())).thenReturn(Collections.emptyMap());

		// call under test — null config, null overrides: still gets SCIENTIFIC + per-column-type defaults
		Map<String, TextAnalyzer> result = manager.collectAndLoadAnalyzers(null, null, columns);

		assertNotNull(result);
		// SCIENTIFIC is always included (keyword .searchable)
		assertTrue(captor.getValue().contains("org.sagebionetworks-SCIENTIFIC"));
	}

	@Test
	public void testCollectAndLoadAnalyzersWithOverridesAndConfigDefault() {
		SearchConfiguration config = new SearchConfiguration();
		config.setDefaultAnalyzer("org.sage-DEFAULT");

		ColumnAnalyzerOverrideEntry entry = new ColumnAnalyzerOverrideEntry();
		entry.setColumnName("c1");
		entry.setIndexAnalyzer("org.sage-IDX");
		entry.setSearchAnalyzer("org.sage-SRCH");
		ColumnAnalyzerOverride ov = new ColumnAnalyzerOverride();
		ov.setOverrides(Arrays.asList(entry));
		List<ColumnAnalyzerOverride> overrides = Arrays.asList(ov);

		List<ColumnModel> columns = Arrays.asList(
				TableModelTestUtils.createColumn(1L, "a", ColumnType.STRING));

		ArgumentCaptor<List<String>> captor = ArgumentCaptor.forClass(List.class);
		when(textAnalyzerDao.getByQualifiedNames(captor.capture())).thenReturn(Collections.emptyMap());

		// call under test
		manager.collectAndLoadAnalyzers(config, overrides, columns);

		List<String> requested = captor.getValue();
		assertTrue(requested.contains("org.sagebionetworks-SCIENTIFIC"));
		assertTrue(requested.contains("org.sage-DEFAULT"));
		assertTrue(requested.contains("org.sage-IDX"));
		assertTrue(requested.contains("org.sage-SRCH"));
	}

	@Test
	public void testCollectAndLoadAnalyzersWithOverrideEntryNullAnalyzers() {
		// entry with null index/search analyzers — exercises the null-check branches
		ColumnAnalyzerOverrideEntry entry = new ColumnAnalyzerOverrideEntry();
		entry.setColumnName("c1");
		// indexAnalyzer and searchAnalyzer left null
		ColumnAnalyzerOverride ov = new ColumnAnalyzerOverride();
		ov.setOverrides(Arrays.asList(entry));
		List<ColumnAnalyzerOverride> overrides = Arrays.asList(ov);

		ArgumentCaptor<List<String>> captor = ArgumentCaptor.forClass(List.class);
		when(textAnalyzerDao.getByQualifiedNames(captor.capture())).thenReturn(Collections.emptyMap());

		// call under test
		manager.collectAndLoadAnalyzers(null, overrides, Collections.emptyList());

		// Null index/search analyzers must not be added to the qualified-name request list.
		assertFalse(captor.getValue().contains(null));
	}

	@Test
	public void testCollectAndLoadAnalyzersWithOverrideNullOverridesList() {
		// ColumnAnalyzerOverride whose overrides list is null — exercises the null-check
		ColumnAnalyzerOverride ov = new ColumnAnalyzerOverride();
		ov.setOverrides(null);

		ArgumentCaptor<List<String>> captor = ArgumentCaptor.forClass(List.class);
		when(textAnalyzerDao.getByQualifiedNames(captor.capture())).thenReturn(Collections.emptyMap());

		// call under test
		manager.collectAndLoadAnalyzers(null, Arrays.asList(ov), Collections.emptyList());

		// Only the always-included SCIENTIFIC analyzer should be requested; nulls must not leak through.
		assertEquals(Arrays.asList("org.sagebionetworks-SCIENTIFIC"), captor.getValue());
	}

	@Test
	public void testTranslateFieldWithBoostWithoutBoost() {
		Map<String, String> nameToId = Map.of("name", "111");

		// call under test
		assertEquals("111", manager.translateFieldWithBoost("name", nameToId));
	}

	@Test
	public void testTranslateFieldWithBoostWithBoost() {
		Map<String, String> nameToId = Map.of("name", "111");

		// call under test
		assertEquals("111^3", manager.translateFieldWithBoost("name^3", nameToId));
	}

	@Test
	public void testTranslateFieldWithBoostUnknownName() {
		Map<String, String> nameToId = Map.of("name", "111");

		// call under test — unknown field passes through unchanged (with and without boost)
		assertEquals("unknown", manager.translateFieldWithBoost("unknown", nameToId));
		assertEquals("unknown^2", manager.translateFieldWithBoost("unknown^2", nameToId));
	}

	@Test
	public void testTranslateNamesWithNull() {
		// call under test
		assertNull(manager.translateNames(null, Collections.emptyMap()));
	}

	@Test
	public void testTranslateNamesWithUnknownPassesThrough() {
		Map<String, String> nameToId = Map.of("name", "111");

		// call under test
		assertEquals(Arrays.asList("111", "other"),
				manager.translateNames(Arrays.asList("name", "other"), nameToId));
	}

	@Test
	public void testTranslateQueryNamesToIdsWithAllFieldsPopulated() {
		Map<String, String> nameToId = Map.of("name", "111", "age", "222");
		SearchQuery q = new SearchQuery();
		q.setQueryFields(new ArrayList<>(Arrays.asList("name^3", "unknown")));
		q.setTermsFilters(new ArrayList<>(Arrays.asList(new KeyValues().setKey("name"))));
		q.setRangeFilters(new ArrayList<>(Arrays.asList(new KeyRange().setKey("age"))));
		q.setFacetRequests(new ArrayList<>(Arrays.asList(new FacetRequest().setColumnName("name"))));
		q.setExistsFilters(new ArrayList<>(Arrays.asList("name")));
		q.setNotExistsFilters(new ArrayList<>(Arrays.asList("age")));
		q.setReturnFields(new ArrayList<>(Arrays.asList("name")));
		q.setSort(new ArrayList<>(Arrays.asList(
				new SortField().setColumnName("name"),
				new SortField().setColumnName("_score"),          // must be preserved as-is
				new SortField().setColumnName("unknown"))));       // unknown stays as-is

		// call under test
		manager.translateQueryNamesToIds(q, nameToId);

		assertEquals(Arrays.asList("111^3", "unknown"), q.getQueryFields());
		assertEquals("111", q.getTermsFilters().get(0).getKey());
		assertEquals("222", q.getRangeFilters().get(0).getKey());
		assertEquals("111", q.getFacetRequests().get(0).getColumnName());
		assertEquals(Arrays.asList("111"), q.getExistsFilters());
		assertEquals(Arrays.asList("222"), q.getNotExistsFilters());
		assertEquals(Arrays.asList("111"), q.getReturnFields());
		assertEquals("111", q.getSort().get(0).getColumnName());
		assertEquals("_score", q.getSort().get(1).getColumnName());
		assertEquals("unknown", q.getSort().get(2).getColumnName());
	}

	@Test
	public void testTranslateQueryNamesToIdsWithAllFieldsNull() {
		// Every optional list null — must not NPE
		SearchQuery q = new SearchQuery();

		// call under test
		manager.translateQueryNamesToIds(q, Collections.emptyMap());
	}

	@Test
	public void testTranslateResultIdsToNamesWithFullShape() {
		Map<String, String> idToName = Map.of("111", "name", "222", "age");

		SearchHit hit = new SearchHit();
		hit.setFields(new ArrayList<>(Arrays.asList(
				new SearchFieldValue().setName("111").setValue("Alice"),
				new SearchFieldValue().setName("unknown").setValue("v"))));
		hit.setHighlights(new ArrayList<>(Arrays.asList(
				new SearchFieldValue().setName("111.searchable").setValue("<em>Alice</em>"),
				new SearchFieldValue().setName("222").setValue("<em>30</em>"),
				new SearchFieldValue().setName("unk.searchable").setValue("v"))));

		SearchQueryResults results = new SearchQueryResults()
				.setHits(new ArrayList<>(Arrays.asList(hit)))
				.setFacets(new ArrayList<>(Arrays.asList(
						new FacetColumnResultValues().setColumnName("111"))));

		// call under test
		manager.translateResultIdsToNames(results, idToName);

		assertEquals("name", hit.getFields().get(0).getName());
		assertEquals("unknown", hit.getFields().get(1).getName());
		assertEquals("name", hit.getHighlights().get(0).getName());   // .searchable stripped + ID translated
		assertEquals("age", hit.getHighlights().get(1).getName());    // no .searchable, just ID translate
		assertEquals("unk", hit.getHighlights().get(2).getName());    // unknown ID → strip suffix, passthrough
		assertEquals("name", results.getFacets().get(0).getColumnName());
	}

	@Test
	public void testTranslateResultIdsToNamesWithNullHitsAndFacets() {
		// Everything null — must not NPE
		SearchQueryResults results = new SearchQueryResults();

		// call under test
		manager.translateResultIdsToNames(results, Collections.emptyMap());
	}

	@Test
	public void testTranslateHitIdsToNamesWithNullHighlights() {
		SearchHit hit = new SearchHit()
				.setFields(new ArrayList<>(Arrays.asList(
						new SearchFieldValue().setName("111").setValue("v"))));
		// highlights left null

		// call under test
		manager.translateHitIdsToNames(hit, Map.of("111", "name"));

		assertEquals("name", hit.getFields().get(0).getName());
	}
}
