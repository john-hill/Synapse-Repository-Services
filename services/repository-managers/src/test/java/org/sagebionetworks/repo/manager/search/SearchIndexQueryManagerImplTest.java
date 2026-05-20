package org.sagebionetworks.repo.manager.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.sagebionetworks.repo.manager.EntityManager;
import org.sagebionetworks.repo.manager.table.TableManagerSupport;
import org.sagebionetworks.repo.model.UnauthorizedException;
import org.sagebionetworks.repo.model.UserInfo;
import org.sagebionetworks.repo.model.dbo.dao.table.TableModelTestUtils;
import org.sagebionetworks.repo.model.entity.IdAndVersion;
import org.sagebionetworks.repo.model.search.FacetRequest;
import org.sagebionetworks.repo.model.search.KeyRange;
import org.sagebionetworks.repo.model.search.KeyValues;
import org.sagebionetworks.repo.model.search.SearchFieldValue;
import org.sagebionetworks.repo.model.search.SearchHit;
import org.sagebionetworks.repo.model.search.SearchQuery;
import org.sagebionetworks.repo.model.search.SearchQueryPart;
import org.sagebionetworks.repo.model.search.SearchQueryResults;
import org.sagebionetworks.repo.model.search.SortDirection;
import org.sagebionetworks.repo.model.search.SortField;
import org.sagebionetworks.repo.model.search.table.SearchIndex;
import org.sagebionetworks.repo.model.search.table.SearchIndexQuery;
import org.sagebionetworks.repo.model.search.table.SearchIndexState;
import org.sagebionetworks.repo.model.search.table.SearchIndexStatus;
import org.sagebionetworks.repo.model.table.ColumnModel;
import org.sagebionetworks.repo.model.table.ColumnType;
import org.sagebionetworks.repo.model.table.FacetColumnResult;
import org.sagebionetworks.repo.model.table.FacetColumnResultValues;
import org.sagebionetworks.repo.model.table.SelectColumn;
import org.sagebionetworks.table.cluster.ConnectionFactory;
import org.sagebionetworks.table.cluster.description.TableIndexDescription;
import org.sagebionetworks.table.cluster.search.SearchIndexStatusDao;

/**
 * Mockito unit tests for {@link SearchIndexQueryManagerImpl}. Covers the translation
 * helpers, state-machine gates ({@code checkIndexStatus}), response-part resolution,
 * and the full search/autocomplete flow with mocked dependencies. End-to-end behavior
 * against a live cluster is exercised separately by the {@code ITSearchQuery}
 * integration test.
 */
@ExtendWith(MockitoExtension.class)
public class SearchIndexQueryManagerImplTest {

	@Mock
	private EntityManager entityManager;
	@Mock
	private ConnectionFactory connectionFactory;
	@Mock
	private OpenSearchManager openSearchManager;
	@Mock
	private TableManagerSupport tableManagerSupport;
	@Mock
	private SearchIndexStatusDao searchIndexStatusDao;

	@InjectMocks
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
		// tableManagerSupport.validateTableReadAccess is void; default Mockito behavior does
		// nothing → pass. getIndexDescription is stubbed in setupHappyPathMocks. Tests that
		// only need auth to pass without exercising the index-description path stub it here.
		when(tableManagerSupport.getIndexDescription(SOURCE_ID))
				.thenReturn(new TableIndexDescription(SOURCE_ID));
	}

	private SearchQuery buildQuery() {
		SearchQuery query = new SearchQuery();
		query.setQueryText("test");
		return query;
	}

	/** Wrap a SearchQuery in a SearchIndexQuery bound to {@link #SEARCH_INDEX_ID}. */
	private SearchIndexQuery buildRequest(SearchQuery query) {
		return new SearchIndexQuery().setSearchIndexId(SEARCH_INDEX_ID).setSearchQuery(query);
	}

	/** Wrap a SearchQuery plus an explicit set of response parts. */
	private SearchIndexQuery buildRequest(SearchQuery query, SearchQueryPart... parts) {
		Set<SearchQueryPart> partSet = parts.length == 0
				? EnumSet.noneOf(SearchQueryPart.class)
				: EnumSet.copyOf(Arrays.asList(parts));
		return new SearchIndexQuery()
				.setSearchIndexId(SEARCH_INDEX_ID)
				.setSearchQuery(query)
				.setResponseParts(partSet);
	}

	/**
	 * Wires up the mocks needed for the full execute-query flow to succeed:
	 * status check, schema resolution. Returns the schema so tests can reference column
	 * names/IDs.
	 */
	private List<ColumnModel> setupHappyPathMocks() {
		List<ColumnModel> schema = Arrays.asList(
				TableModelTestUtils.createColumn(Long.parseLong(NAME_COLUMN_ID), NAME_COLUMN, ColumnType.STRING),
				TableModelTestUtils.createColumn(Long.parseLong(DESC_COLUMN_ID), DESC_COLUMN, ColumnType.STRING));
		when(connectionFactory.getSearchIndexStatusDao()).thenReturn(searchIndexStatusDao);
		when(searchIndexStatusDao.getStatus(1L)).thenReturn(Optional.of(
				new SearchIndexStatus().setSearchIndexId(SEARCH_INDEX_ID).setState(SearchIndexState.ACTIVE)));
		when(tableManagerSupport.getTableSchema(IdAndVersion.parse(SEARCH_INDEX_ID)))
				.thenReturn(schema);
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

	/**
	 * Verifies {@code openSearchManager.search(...)} was called exactly once with the expected
	 * arguments. The {@code SearchQuery} is captured so the caller can assert on the post-
	 * translation form; the {@code columns} list is captured and its names asserted against
	 * {@code expectedColumnNames}.
	 *
	 * <p>Using concrete matchers here instead of {@code any()} ensures the test actually
	 * verifies the values the manager passed — not merely that the method was invoked.
	 */
	private SearchQuery verifyOpenSearchSearch(Set<SearchQueryPart> expectedParts,
			List<String> expectedColumnNames) {
		ArgumentCaptor<SearchQuery> queryCaptor = ArgumentCaptor.forClass(SearchQuery.class);
		@SuppressWarnings({"unchecked", "rawtypes"})
		ArgumentCaptor<List<ColumnModel>> columnsCaptor = (ArgumentCaptor) ArgumentCaptor.forClass(List.class);
		verify(openSearchManager).search(
				eq("search-index-1"),
				queryCaptor.capture(),
				columnsCaptor.capture(),
				eq(expectedParts));
		assertEquals(expectedColumnNames, columnsCaptor.getValue().stream()
				.map(ColumnModel::getName).collect(Collectors.toList()));
		return queryCaptor.getValue();
	}

	/**
	 * Autocomplete analog of {@link #verifyOpenSearchSearch}.
	 */
	private SearchQuery verifyOpenSearchAutocomplete(Set<SearchQueryPart> expectedParts,
			List<String> expectedColumnNames) {
		ArgumentCaptor<SearchQuery> queryCaptor = ArgumentCaptor.forClass(SearchQuery.class);
		@SuppressWarnings({"unchecked", "rawtypes"})
		ArgumentCaptor<List<ColumnModel>> columnsCaptor = (ArgumentCaptor) ArgumentCaptor.forClass(List.class);
		verify(openSearchManager).autocomplete(
				eq("search-index-1"),
				queryCaptor.capture(),
				columnsCaptor.capture(),
				eq(expectedParts));
		assertEquals(expectedColumnNames, columnsCaptor.getValue().stream()
				.map(ColumnModel::getName).collect(Collectors.toList()));
		return queryCaptor.getValue();
	}

	/**
	 * Stubs {@code openSearchManager.search(...)} to return {@code returnValue} when called
	 * with matching arguments. Uses concrete matchers throughout — no positional {@code any()} —
	 * so a manager that wires the wrong options/columns/queryText misses the stub, receives
	 * {@code null}, and fails the test explicitly.
	 */
	private void stubOpenSearchSearchReturns(String expectedQueryText,
			Set<SearchQueryPart> expectedOptions,
			List<String> expectedColumnNames, SearchQueryResults returnValue) {
		when(openSearchManager.search(
				eq("search-index-1"),
				argThat(q -> q != null && Objects.equals(expectedQueryText, q.getQueryText())),
				argThat(cols -> cols != null && expectedColumnNames.equals(
						cols.stream().map(ColumnModel::getName).collect(Collectors.toList()))),
				eq(expectedOptions)
		)).thenReturn(returnValue);
	}

	/** Autocomplete analog of {@link #stubOpenSearchSearchReturns}. */
	private void stubOpenSearchAutocompleteReturns(String expectedQueryText,
			Set<SearchQueryPart> expectedOptions,
			List<String> expectedColumnNames, SearchQueryResults returnValue) {
		when(openSearchManager.autocomplete(
				eq("search-index-1"),
				argThat(q -> q != null && Objects.equals(expectedQueryText, q.getQueryText())),
				argThat(cols -> cols != null && expectedColumnNames.equals(
						cols.stream().map(ColumnModel::getName).collect(Collectors.toList()))),
				eq(expectedOptions)
		)).thenReturn(returnValue);
	}

	@Test
	public void testSearchWithNoReadOnSearchIndex() {
		when(entityManager.getEntity(user, "1", SearchIndex.class))
				.thenThrow(new UnauthorizedException("no access"));

		assertThrows(UnauthorizedException.class, () -> manager.search(user, buildRequest(buildQuery())));
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
		assertThrows(UnauthorizedException.class, () -> manager.search(user, buildRequest(buildQuery())));
		verifyNoMoreInteractions(connectionFactory, openSearchManager);
	}

	@Test
	public void testSearchWithCreatingStatus() {
		SearchIndex si = setupSearchIndex();
		when(entityManager.getEntity(user, "1", SearchIndex.class)).thenReturn(si);
		setupAuthMocks();
		when(connectionFactory.getSearchIndexStatusDao()).thenReturn(searchIndexStatusDao);
		when(searchIndexStatusDao.getStatus(1L)).thenReturn(Optional.of(
				new SearchIndexStatus().setSearchIndexId(SEARCH_INDEX_ID).setState(SearchIndexState.CREATING)));

		IllegalStateException ex = assertThrows(IllegalStateException.class,
				() -> manager.search(user, buildRequest(buildQuery())));
		assertTrue(ex.getMessage().contains("still building"));
		verifyNoMoreInteractions(openSearchManager);
	}

	@Test
	public void testSearchWithFailedStatusIncludesStoredErrorMessage() {
		SearchIndex si = setupSearchIndex();
		when(entityManager.getEntity(user, "1", SearchIndex.class)).thenReturn(si);
		setupAuthMocks();
		when(connectionFactory.getSearchIndexStatusDao()).thenReturn(searchIndexStatusDao);
		when(searchIndexStatusDao.getStatus(1L)).thenReturn(Optional.of(new SearchIndexStatus()
				.setSearchIndexId(SEARCH_INDEX_ID)
				.setState(SearchIndexState.FAILED)
				.setErrorMessage("Column 'bogus_col' does not exist.")));

		// call under test
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				() -> manager.search(user, buildRequest(buildQuery())));

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
		when(connectionFactory.getSearchIndexStatusDao()).thenReturn(searchIndexStatusDao);
		when(searchIndexStatusDao.getStatus(1L)).thenReturn(Optional.of(new SearchIndexStatus()
				.setSearchIndexId(SEARCH_INDEX_ID)
				.setState(SearchIndexState.FAILED)));

		// call under test — no stored error, fall back to the generic remediation hint
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				() -> manager.search(user, buildRequest(buildQuery())));

		assertTrue(ex.getMessage().contains("Delete or update the SearchIndex"));
		verifyNoMoreInteractions(openSearchManager);
	}

	@Test
	public void testSearchWithMissingStatus() {
		SearchIndex si = setupSearchIndex();
		when(entityManager.getEntity(user, "1", SearchIndex.class)).thenReturn(si);
		setupAuthMocks();
		when(connectionFactory.getSearchIndexStatusDao()).thenReturn(searchIndexStatusDao);
		when(searchIndexStatusDao.getStatus(1L)).thenReturn(Optional.empty());

		IllegalStateException ex = assertThrows(IllegalStateException.class,
				() -> manager.search(user, buildRequest(buildQuery())));
		assertTrue(ex.getMessage().contains("still building"));
		verifyNoMoreInteractions(openSearchManager);
	}

	@Test
	public void testSearchWithActiveStatus() {
		SearchIndex si = setupSearchIndex();
		when(entityManager.getEntity(user, "1", SearchIndex.class)).thenReturn(si);
		setupAuthMocks();
		setupHappyPathMocks();
		stubOpenSearchSearchReturns("test",
				EnumSet.of(SearchQueryPart.HITS, SearchQueryPart.TOTAL_HITS),
				Arrays.asList(NAME_COLUMN, DESC_COLUMN), buildRawResults());

		SearchQuery query = buildQuery();

		// call under test — request HITS + TOTAL_HITS so the assertions on totalHits work
		SearchQueryResults results = manager.search(user, buildRequest(query,
				SearchQueryPart.HITS, SearchQueryPart.TOTAL_HITS));

		assertNotNull(results);
		assertEquals(1L, results.getTotalHits());
		SearchHit hit = results.getHits().get(0);
		// Column IDs returned by OpenSearch should be translated back to user-facing names
		assertEquals(NAME_COLUMN, hit.getFields().get(0).getName());
		assertEquals(DESC_COLUMN, hit.getFields().get(1).getName());
		// Highlight name has its ".searchable" suffix stripped and ID translated to name
		assertEquals(NAME_COLUMN, hit.getHighlights().get(0).getName());

		verifyOpenSearchSearch(
				EnumSet.of(SearchQueryPart.HITS, SearchQueryPart.TOTAL_HITS),
				Arrays.asList(NAME_COLUMN, DESC_COLUMN));
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
		when(connectionFactory.getSearchIndexStatusDao()).thenReturn(searchIndexStatusDao);
		when(searchIndexStatusDao.getStatus(1L)).thenReturn(Optional.of(
				new SearchIndexStatus().setSearchIndexId(SEARCH_INDEX_ID).setState(SearchIndexState.ACTIVE)));
		when(tableManagerSupport.getIndexDescription(SOURCE_ID)).thenReturn(new TableIndexDescription(SOURCE_ID));
		when(tableManagerSupport.getTableSchema(IdAndVersion.parse(SEARCH_INDEX_ID)))
				.thenReturn(schema);

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
		stubOpenSearchSearchReturns("Alzheimer",
				EnumSet.of(SearchQueryPart.HITS, SearchQueryPart.TOTAL_HITS, SearchQueryPart.FACETS),
				Arrays.asList("Study Name", "patient's diagnosis", "Age (years)", "data.field", "[metadata]"),
				raw);

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

		// call under test — request HITS + TOTAL_HITS + FACETS so we exercise translation in every code path
		SearchQueryResults results = manager.search(user, buildRequest(query,
				SearchQueryPart.HITS, SearchQueryPart.TOTAL_HITS, SearchQueryPart.FACETS));

		// Verify the query reached OpenSearch with every user-facing name translated to an ID
		SearchQuery translated = verifyOpenSearchSearch(
				EnumSet.of(SearchQueryPart.HITS, SearchQueryPart.TOTAL_HITS, SearchQueryPart.FACETS),
				Arrays.asList("Study Name", "patient's diagnosis", "Age (years)", "data.field", "[metadata]"));

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
		stubOpenSearchAutocompleteReturns("test",
				EnumSet.of(SearchQueryPart.HITS),
				Arrays.asList(NAME_COLUMN, DESC_COLUMN), buildRawResults());

		SearchQuery query = buildQuery();

		// call under test
		SearchQueryResults results = manager.autocomplete(user, buildRequest(query));

		assertNotNull(results);
		assertEquals(NAME_COLUMN, results.getHits().get(0).getFields().get(0).getName());

		// Auto-populated queryFields should contain all searchable columns (translated to IDs
		// before reaching OpenSearch). The STRING columns in our schema are text-searchable.
		SearchQuery translated = verifyOpenSearchAutocomplete(
				EnumSet.of(SearchQueryPart.HITS),
				Arrays.asList(NAME_COLUMN, DESC_COLUMN));
		List<String> queryFields = translated.getQueryFields();
		assertNotNull(queryFields);
		assertTrue(queryFields.contains(NAME_COLUMN_ID));
		assertTrue(queryFields.contains(DESC_COLUMN_ID));
	}

	@Test
	public void testAutocompleteWithEmptyQueryFields() {
		// Exercises the branch where isAutocomplete=true and queryFields is non-null but empty —
		// the empty case should enter the auto-populate block.
		SearchIndex si = setupSearchIndex();
		when(entityManager.getEntity(user, "1", SearchIndex.class)).thenReturn(si);
		setupAuthMocks();
		setupHappyPathMocks();
		stubOpenSearchAutocompleteReturns("test",
				EnumSet.of(SearchQueryPart.HITS),
				Arrays.asList(NAME_COLUMN, DESC_COLUMN), buildRawResults());

		SearchQuery query = buildQuery();
		query.setQueryFields(new ArrayList<>());

		// call under test
		manager.autocomplete(user, buildRequest(query));

		SearchQuery translated = verifyOpenSearchAutocomplete(
				EnumSet.of(SearchQueryPart.HITS),
				Arrays.asList(NAME_COLUMN, DESC_COLUMN));
		List<String> queryFields = translated.getQueryFields();
		assertTrue(queryFields.contains(NAME_COLUMN_ID));
		assertTrue(queryFields.contains(DESC_COLUMN_ID));
	}

	@Test
	public void testAutocompleteWithPrePopulatedQueryFields() {
		// Exercises the branch where isAutocomplete=true but queryFields is already populated —
		// auto-populate should be skipped.
		SearchIndex si = setupSearchIndex();
		when(entityManager.getEntity(user, "1", SearchIndex.class)).thenReturn(si);
		setupAuthMocks();
		setupHappyPathMocks();
		stubOpenSearchAutocompleteReturns("test",
				EnumSet.of(SearchQueryPart.HITS),
				Arrays.asList(NAME_COLUMN, DESC_COLUMN), buildRawResults());

		SearchQuery query = buildQuery();
		query.setQueryFields(new ArrayList<>(Arrays.asList(NAME_COLUMN)));

		// call under test
		manager.autocomplete(user, buildRequest(query));

		SearchQuery translated = verifyOpenSearchAutocomplete(
				EnumSet.of(SearchQueryPart.HITS),
				Arrays.asList(NAME_COLUMN, DESC_COLUMN));
		// Only the caller-provided field survives (translated to its ID); the other column was NOT auto-added.
		assertEquals(Arrays.asList(NAME_COLUMN_ID), translated.getQueryFields());
	}

	// --- Focused unit tests for package-protected helpers ---

	@Test
	public void testGetSearchableColumnNamesIncludesTextAndLinkOnly() {
		List<ColumnModel> columns = Arrays.asList(
				new ColumnModel().setName("title").setColumnType(ColumnType.STRING),
				new ColumnModel().setName("docFile").setColumnType(ColumnType.LINK),
				new ColumnModel().setName("authorId").setColumnType(ColumnType.USERID),
				new ColumnModel().setName("createdOn").setColumnType(ColumnType.DATE));

		// call under test — only TEXT-category and LINK columns auto-populate queryFields.
		List<String> result = manager.getSearchableColumnNames(columns);

		assertEquals(Arrays.asList("title", "docFile"), result);
	}

	@Test
	public void testGetSearchableColumnNamesWithEmpty() {
		// call under test
		List<String> names = manager.getSearchableColumnNames(Collections.emptyList());

		assertTrue(names.isEmpty());
	}

	@Test
	public void testGetIndexNameAppliesPrefix() {
		// call under test — AOSS index name is derived from the SearchIndex entity ID.
		assertEquals("search-index-syn123", manager.getIndexName("syn123"));
	}

	// --- checkIndexStatus (state machine) ---

	@Test
	public void testCheckIndexStatusWithMissingThrowsRecoverable() {
		when(connectionFactory.getSearchIndexStatusDao()).thenReturn(searchIndexStatusDao);
		when(searchIndexStatusDao.getStatus(123L)).thenReturn(Optional.empty());

		// call under test — missing status row means a build hasn't completed yet.
		IllegalStateException e = assertThrows(IllegalStateException.class,
				() -> manager.checkIndexStatus("syn123"));
		assertTrue(e.getMessage().contains("still building"),
				"Caller (worker) translates 'still building' → RecoverableMessageException: " + e.getMessage());
	}

	@Test
	public void testCheckIndexStatusWithCreatingThrowsRecoverable() {
		when(connectionFactory.getSearchIndexStatusDao()).thenReturn(searchIndexStatusDao);
		when(searchIndexStatusDao.getStatus(123L)).thenReturn(Optional.of(
				new SearchIndexStatus().setState(SearchIndexState.CREATING)));

		// call under test
		IllegalStateException e = assertThrows(IllegalStateException.class,
				() -> manager.checkIndexStatus("syn123"));
		assertTrue(e.getMessage().contains("still building"));
	}

	@Test
	public void testCheckIndexStatusWithActiveReturnsNormally() {
		when(connectionFactory.getSearchIndexStatusDao()).thenReturn(searchIndexStatusDao);
		when(searchIndexStatusDao.getStatus(123L)).thenReturn(Optional.of(
				new SearchIndexStatus().setState(SearchIndexState.ACTIVE)));

		// call under test
		manager.checkIndexStatus("syn123");
		// No exception means happy path. Nothing else to assert.
	}

	@Test
	public void testCheckIndexStatusWithFailedSurfacesStoredErrorMessage() {
		when(connectionFactory.getSearchIndexStatusDao()).thenReturn(searchIndexStatusDao);
		when(searchIndexStatusDao.getStatus(123L)).thenReturn(Optional.of(
				new SearchIndexStatus().setState(SearchIndexState.FAILED)
						.setErrorMessage("TextAnalyzer 'biomed-ghost' (defaultAnalyzer) does not resolve.")));

		// call under test
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
				() -> manager.checkIndexStatus("syn123"));

		assertTrue(e.getMessage().contains("biomed-ghost"),
				"Stored error message must be surfaced verbatim: " + e.getMessage());
		assertTrue(e.getMessage().contains("Delete or update the SearchIndex"),
				"Remediation hint must be appended: " + e.getMessage());
	}

	@Test
	public void testCheckIndexStatusWithFailedAndBlankErrorUsesGenericMessage() {
		when(connectionFactory.getSearchIndexStatusDao()).thenReturn(searchIndexStatusDao);
		when(searchIndexStatusDao.getStatus(123L)).thenReturn(Optional.of(
				new SearchIndexStatus().setState(SearchIndexState.FAILED).setErrorMessage("")));

		// call under test
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
				() -> manager.checkIndexStatus("syn123"));

		assertTrue(e.getMessage().contains("Delete or update the SearchIndex to trigger a rebuild."),
				"Generic remediation hint must replace blank stored error: " + e.getMessage());
	}

	@ParameterizedTest
	@EnumSource(value = SearchIndexState.class, names = {"CREATING"})
	public void testCheckIndexStatusTransitionalStatesThrowIllegalState(SearchIndexState state) {
		when(connectionFactory.getSearchIndexStatusDao()).thenReturn(searchIndexStatusDao);
		when(searchIndexStatusDao.getStatus(123L)).thenReturn(Optional.of(
				new SearchIndexStatus().setState(state)));

		// call under test — every transitional state must surface IllegalStateException so the
		// worker can translate to RecoverableMessageException.
		assertThrows(IllegalStateException.class, () -> manager.checkIndexStatus("syn123"));
	}

	// --- translateFieldWithBoost ---

	@Test
	public void testTranslateFieldWithBoostRewritesNameAndKeepsBoost() {
		Map<String, String> nameToId = Collections.singletonMap("title", "col-1");
		// call under test
		assertEquals("col-1^3", manager.translateFieldWithBoost("title^3", nameToId));
	}

	@Test
	public void testTranslateFieldWithBoostKeepsBoostWhenNameUnknown() {
		// call under test — unknown name passes through; boost preserved verbatim.
		assertEquals("ghost^3.5", manager.translateFieldWithBoost("ghost^3.5", Collections.emptyMap()));
	}

	@Test
	public void testTranslateFieldWithBoostNoCaretRewritesPlainName() {
		Map<String, String> nameToId = Collections.singletonMap("title", "col-1");
		// call under test
		assertEquals("col-1", manager.translateFieldWithBoost("title", nameToId));
	}

	// --- translateNames ---

	@Test
	public void testTranslateNamesWithNullReturnsNull() {
		// call under test
		assertNull(manager.translateNames(null, Collections.emptyMap()));
	}

	@Test
	public void testTranslateNamesReplacesKnownNamesAndPassesUnknownThrough() {
		Map<String, String> nameToId = new HashMap<>();
		nameToId.put("title", "col-1");

		// call under test — unknown names pass through (callers send to AOSS as-is for diagnostics).
		List<String> result = manager.translateNames(Arrays.asList("title", "unknown"), nameToId);

		assertEquals(Arrays.asList("col-1", "unknown"), result);
	}

	// --- translateQueryNamesToIds ---

	@Test
	public void testTranslateQueryNamesToIdsRewritesAllNameBearingFields() {
		Map<String, String> nameToId = new HashMap<>();
		nameToId.put("title", "col-1");
		nameToId.put("year", "col-2");
		nameToId.put("authors", "col-3");

		SearchQuery query = new SearchQuery()
				.setQueryFields(Arrays.asList("title^2", "year"))
				.setTermsFilters(Collections.singletonList(new KeyValues().setKey("authors").setValues(Arrays.asList("a"))))
				.setRangeFilters(Collections.singletonList(new KeyRange().setKey("year")))
				.setFacetRequests(Collections.singletonList(new FacetRequest().setColumnName("title")))
				.setExistsFilters(Arrays.asList("title"))
				.setNotExistsFilters(Arrays.asList("year"))
				.setReturnFields(Arrays.asList("title", "authors"))
				.setSort(Arrays.asList(new SortField().setColumnName("title"), new SortField().setColumnName("_score")));

		// call under test
		manager.translateQueryNamesToIds(query, nameToId);

		assertEquals(Arrays.asList("col-1^2", "col-2"), query.getQueryFields());
		assertEquals("col-3", query.getTermsFilters().get(0).getKey());
		assertEquals("col-2", query.getRangeFilters().get(0).getKey());
		assertEquals("col-1", query.getFacetRequests().get(0).getColumnName());
		assertEquals(Arrays.asList("col-1"), query.getExistsFilters());
		assertEquals(Arrays.asList("col-2"), query.getNotExistsFilters());
		assertEquals(Arrays.asList("col-1", "col-3"), query.getReturnFields());
		assertEquals("col-1", query.getSort().get(0).getColumnName());
		// _score is a reserved sort key — must NOT be translated.
		assertEquals("_score", query.getSort().get(1).getColumnName());
	}

	@Test
	public void testTranslateQueryNamesToIdsKeepsUnknownSortAsIs() {
		Map<String, String> nameToId = Collections.singletonMap("title", "col-1");
		SearchQuery query = new SearchQuery()
				.setSort(Collections.singletonList(new SortField().setColumnName("ghost")));

		// call under test
		manager.translateQueryNamesToIds(query, nameToId);

		assertEquals("ghost", query.getSort().get(0).getColumnName());
	}

	// --- translateResultIdsToNames / translateHitIdsToNames ---

	@Test
	public void testTranslateHitIdsToNamesStripsSearchableSuffixOnHighlights() {
		Map<String, String> idToName = Collections.singletonMap("col-1", "title");
		SearchHit hit = new SearchHit()
				.setFields(Collections.singletonList(new SearchFieldValue().setName("col-1").setValue("hello")))
				.setHighlights(Collections.singletonList(
						new SearchFieldValue().setName("col-1.searchable").setValue("<em>hello</em>")));

		// call under test
		manager.translateHitIdsToNames(hit, idToName);

		assertEquals("title", hit.getFields().get(0).getName());
		// Highlight key stripped of .searchable then translated to user-facing name.
		assertEquals("title", hit.getHighlights().get(0).getName());
	}

	@Test
	public void testTranslateHitIdsToNamesKeepsUnknownHighlightAsIs() {
		Map<String, String> idToName = Collections.emptyMap();
		SearchHit hit = new SearchHit()
				.setHighlights(Collections.singletonList(
						new SearchFieldValue().setName("ghost").setValue("x")));

		// call under test — unknown ID stays in the result for caller debugging visibility.
		manager.translateHitIdsToNames(hit, idToName);

		assertEquals("ghost", hit.getHighlights().get(0).getName());
	}

	// --- buildQueryMetadata ---

	@Test
	public void testBuildQueryMetadataThrowsWhenNoSchemaBound() {
		IdAndVersion id = IdAndVersion.parse("syn123");
		when(tableManagerSupport.getTableSchema(id)).thenReturn(Collections.emptyList());

		// call under test
		IllegalStateException e = assertThrows(IllegalStateException.class,
				() -> manager.buildQueryMetadata(id));

		assertTrue(e.getMessage().contains("no bound schema"),
				"Exception must hint at the recovery path: " + e.getMessage());
	}

	@Test
	public void testBuildQueryMetadataReturnsParallelLists() {
		IdAndVersion id = IdAndVersion.parse("syn123");
		List<ColumnModel> columns = Arrays.asList(
				new ColumnModel().setId("col-1").setName("title").setColumnType(ColumnType.STRING),
				new ColumnModel().setId("col-2").setName("year").setColumnType(ColumnType.INTEGER));
		when(tableManagerSupport.getTableSchema(id)).thenReturn(columns);

		// call under test
		SearchIndexQueryManagerImpl.QueryMetadata metadata = manager.buildQueryMetadata(id);

		assertEquals(columns, metadata.getColumns());
		assertEquals(2, metadata.getSelectColumns().size());
	}

	// --- responseParts: resolveRequestedParts ---

	@Test
	public void testResolveRequestedPartsWithNullReturnsHitsOnly() {
		// call under test — default minimal payload is HITS only.
		Set<SearchQueryPart> result = SearchIndexQueryManagerImpl.resolveRequestedParts(null);

		assertEquals(EnumSet.of(SearchQueryPart.HITS), result);
	}

	@Test
	public void testResolveRequestedPartsWithEmptyReturnsHitsOnly() {
		// call under test
		Set<SearchQueryPart> result = SearchIndexQueryManagerImpl.resolveRequestedParts(Collections.emptySet());

		assertEquals(EnumSet.of(SearchQueryPart.HITS), result);
	}

	@Test
	public void testResolveRequestedPartsCopiesInputAsEnumSet() {
		Set<SearchQueryPart> input = new java.util.HashSet<>(
				Arrays.asList(SearchQueryPart.HITS, SearchQueryPart.TOTAL_HITS, SearchQueryPart.FACETS));
		// call under test
		Set<SearchQueryPart> result = SearchIndexQueryManagerImpl.resolveRequestedParts(input);

		assertEquals(input, result);
		assertTrue(result instanceof EnumSet, "Result must be an EnumSet for O(1) contains");
	}

	// --- responseParts: filterSelectColumnsForReturnFields ---

	@Test
	public void testFilterSelectColumnsForReturnFieldsWithNullColumnsReturnsNull() {
		// call under test
		assertNull(manager.filterSelectColumnsForReturnFields(null, Arrays.asList("a")));
	}

	@Test
	public void testFilterSelectColumnsForReturnFieldsWithNullReturnFieldsKeepsAll() {
		List<SelectColumn> input = Arrays.asList(
				new SelectColumn().setName("title"), new SelectColumn().setName("abstract"));
		// call under test — null returnFields → keep everything.
		assertEquals(input, manager.filterSelectColumnsForReturnFields(input, null));
	}

	@Test
	public void testFilterSelectColumnsForReturnFieldsWithEmptyReturnFieldsKeepsAll() {
		List<SelectColumn> input = Arrays.asList(new SelectColumn().setName("title"));
		// call under test
		assertEquals(input, manager.filterSelectColumnsForReturnFields(input, Collections.emptyList()));
	}

	@Test
	public void testFilterSelectColumnsForReturnFieldsKeepsOrderAndFilters() {
		List<SelectColumn> input = Arrays.asList(
				new SelectColumn().setName("title"),
				new SelectColumn().setName("abstract"),
				new SelectColumn().setName("authors"));
		// call under test — names not in returnFields drop; SELECT order preserved.
		List<SelectColumn> result = manager.filterSelectColumnsForReturnFields(
				input, Arrays.asList("authors", "title"));

		assertEquals(Arrays.asList("title", "authors"),
				result.stream().map(SelectColumn::getName).collect(Collectors.toList()));
	}

	// --- State-table: responseParts → which response fields populated ---

	/**
	 * Mocks the openSearchManager to return a fully-populated SearchQueryResults regardless
	 * of which parts are requested. The manager's own gating then determines which fields
	 * survive — that's what we want to assert here. (Real OpenSearchManagerImpl populates
	 * only the requested fields; the AutoWired test covers that.)
	 */
	private SearchQueryResults rawHits() {
		SearchHit hit = new SearchHit();
		hit.setRowId(1L);
		hit.setFields(new ArrayList<>(Arrays.asList(
				new SearchFieldValue().setName(NAME_COLUMN_ID).setValue("Alice"))));
		return new SearchQueryResults()
				.setHits(new ArrayList<>(Arrays.asList(hit)))
				.setTotalHits(7L)
				.setFacets(new ArrayList<>(Arrays.asList(new FacetColumnResultValues().setColumnName(NAME_COLUMN_ID))))
				.setOffset(0L);
	}

	@Test
	public void testSearchWithDefaultPartsReturnsHitsOnly() {
		SearchIndex si = setupSearchIndex();
		when(entityManager.getEntity(user, "1", SearchIndex.class)).thenReturn(si);
		setupAuthMocks();
		setupHappyPathMocks();
		stubOpenSearchSearchReturns("test",
				EnumSet.of(SearchQueryPart.HITS),
				Arrays.asList(NAME_COLUMN, DESC_COLUMN), rawHits());

		// call under test — buildRequest with no parts ⇒ default minimal payload
		SearchQueryResults results = manager.search(user, buildRequest(buildQuery()));

		assertNotNull(results.getHits());
		assertNull(results.getTotalHits(),     "totalHits should be null when TOTAL_HITS not requested");
		assertNull(results.getSelectColumns(), "selectColumns should be null when SELECT_COLUMNS not requested");
		assertNull(results.getFacets(),        "facets should be null when FACETS not requested");
		assertEquals(0L, results.getOffset(),  "offset is always populated");
	}

	@Test
	public void testSearchWithAllPartsRequested() {
		SearchIndex si = setupSearchIndex();
		when(entityManager.getEntity(user, "1", SearchIndex.class)).thenReturn(si);
		setupAuthMocks();
		setupHappyPathMocks();
		stubOpenSearchSearchReturns("test",
				EnumSet.of(SearchQueryPart.HITS, SearchQueryPart.TOTAL_HITS,
						SearchQueryPart.SELECT_COLUMNS, SearchQueryPart.FACETS),
				Arrays.asList(NAME_COLUMN, DESC_COLUMN), rawHits());

		// call under test
		SearchQueryResults results = manager.search(user, buildRequest(buildQuery(),
				SearchQueryPart.HITS, SearchQueryPart.TOTAL_HITS,
				SearchQueryPart.SELECT_COLUMNS, SearchQueryPart.FACETS));

		assertNotNull(results.getHits());
		assertEquals(7L, results.getTotalHits());
		assertNotNull(results.getSelectColumns());
		assertNotNull(results.getFacets());
		assertEquals(0L, results.getOffset());
	}

	@Test
	public void testSearchWithSelectColumnsOnly() {
		SearchIndex si = setupSearchIndex();
		when(entityManager.getEntity(user, "1", SearchIndex.class)).thenReturn(si);
		setupAuthMocks();
		setupHappyPathMocks();
		stubOpenSearchSearchReturns("test",
				EnumSet.of(SearchQueryPart.SELECT_COLUMNS),
				Arrays.asList(NAME_COLUMN, DESC_COLUMN), rawHits());

		// call under test
		SearchQueryResults results = manager.search(user, buildRequest(buildQuery(), SearchQueryPart.SELECT_COLUMNS));

		assertNull(results.getHits(),     "hits should be null when HITS not requested");
		assertNull(results.getTotalHits());
		assertNotNull(results.getSelectColumns(),
				"selectColumns should be populated when SELECT_COLUMNS is requested");
		assertNull(results.getFacets());
		assertEquals(0L, results.getOffset());
	}

	@Test
	public void testSearchWithSelectColumnsRespectsReturnFields() {
		SearchIndex si = setupSearchIndex();
		when(entityManager.getEntity(user, "1", SearchIndex.class)).thenReturn(si);
		setupAuthMocks();
		setupHappyPathMocks();
		stubOpenSearchSearchReturns("test",
				EnumSet.of(SearchQueryPart.SELECT_COLUMNS),
				Arrays.asList(NAME_COLUMN, DESC_COLUMN), rawHits());

		// returnFields narrows to one column; selectColumns response should match
		SearchQuery query = buildQuery();
		query.setReturnFields(new ArrayList<>(Arrays.asList(NAME_COLUMN)));

		// call under test
		SearchQueryResults results = manager.search(user, buildRequest(query, SearchQueryPart.SELECT_COLUMNS));

		assertEquals(1, results.getSelectColumns().size());
		assertEquals(NAME_COLUMN, results.getSelectColumns().get(0).getName());
	}

	@Test
	public void testSearchPassesResolvedPartsToOpenSearchManager() {
		SearchIndex si = setupSearchIndex();
		when(entityManager.getEntity(user, "1", SearchIndex.class)).thenReturn(si);
		setupAuthMocks();
		setupHappyPathMocks();
		stubOpenSearchSearchReturns("test",
				EnumSet.of(SearchQueryPart.HITS, SearchQueryPart.TOTAL_HITS),
				Arrays.asList(NAME_COLUMN, DESC_COLUMN), rawHits());

		// call under test — request HITS + TOTAL_HITS
		manager.search(user, buildRequest(buildQuery(), SearchQueryPart.HITS, SearchQueryPart.TOTAL_HITS));

		// Verify the manager forwarded the resolved EnumSet to OpenSearchManager unchanged.
		// verifyOpenSearchSearch uses eq(expectedParts) on the parts slot, so this assertion
		// is enforced through matcher equality rather than a separate captor.
		verifyOpenSearchSearch(
				EnumSet.of(SearchQueryPart.HITS, SearchQueryPart.TOTAL_HITS),
				Arrays.asList(NAME_COLUMN, DESC_COLUMN));
	}

	@Test
	public void testSearchWithoutFacetsClearsFacetRequestsBeforeOpenSearch() {
		// When FACETS is absent from responseParts, the manager should drop facetRequests
		// from the SearchQuery before forwarding to OpenSearchManager — so the OS layer
		// doesn't waste cycles building aggregations.
		SearchIndex si = setupSearchIndex();
		when(entityManager.getEntity(user, "1", SearchIndex.class)).thenReturn(si);
		setupAuthMocks();
		setupHappyPathMocks();
		stubOpenSearchSearchReturns("test",
				EnumSet.of(SearchQueryPart.HITS),
				Arrays.asList(NAME_COLUMN, DESC_COLUMN), rawHits());

		SearchQuery query = buildQuery();
		query.setFacetRequests(new ArrayList<>(Arrays.asList(
				new FacetRequest().setColumnName(NAME_COLUMN))));

		// call under test — default HITS-only, FACETS not requested
		manager.search(user, buildRequest(query));

		SearchQuery translated = verifyOpenSearchSearch(
				EnumSet.of(SearchQueryPart.HITS),
				Arrays.asList(NAME_COLUMN, DESC_COLUMN));
		assertNull(translated.getFacetRequests(),
				"facetRequests should be cleared when FACETS is not in responseParts");
	}

	@Test
	public void testSearchWithFacetsKeepsFacetRequests() {
		SearchIndex si = setupSearchIndex();
		when(entityManager.getEntity(user, "1", SearchIndex.class)).thenReturn(si);
		setupAuthMocks();
		setupHappyPathMocks();
		stubOpenSearchSearchReturns("test",
				EnumSet.of(SearchQueryPart.HITS, SearchQueryPart.FACETS),
				Arrays.asList(NAME_COLUMN, DESC_COLUMN), rawHits());

		SearchQuery query = buildQuery();
		query.setFacetRequests(new ArrayList<>(Arrays.asList(
				new FacetRequest().setColumnName(NAME_COLUMN))));

		// call under test
		manager.search(user, buildRequest(query, SearchQueryPart.HITS, SearchQueryPart.FACETS));

		SearchQuery translated = verifyOpenSearchSearch(
				EnumSet.of(SearchQueryPart.HITS, SearchQueryPart.FACETS),
				Arrays.asList(NAME_COLUMN, DESC_COLUMN));
		assertNotNull(translated.getFacetRequests(),
				"facetRequests should be preserved when FACETS is in responseParts");
		// And translated from name → ID
		assertEquals(NAME_COLUMN_ID, translated.getFacetRequests().get(0).getColumnName());
	}

	// --- Same state-table assertions for autocomplete (consistency across endpoints) ---

	@Test
	public void testAutocompleteWithDefaultPartsReturnsHitsOnly() {
		SearchIndex si = setupSearchIndex();
		when(entityManager.getEntity(user, "1", SearchIndex.class)).thenReturn(si);
		setupAuthMocks();
		setupHappyPathMocks();
		stubOpenSearchAutocompleteReturns("test",
				EnumSet.of(SearchQueryPart.HITS),
				Arrays.asList(NAME_COLUMN, DESC_COLUMN), rawHits());

		// call under test
		SearchQueryResults results = manager.autocomplete(user, buildRequest(buildQuery()));

		assertNotNull(results.getHits());
		assertNull(results.getTotalHits());
		assertNull(results.getSelectColumns());
		assertNull(results.getFacets());
		assertEquals(0L, results.getOffset());
	}

	@Test
	public void testAutocompleteWithAllPartsRequested() {
		SearchIndex si = setupSearchIndex();
		when(entityManager.getEntity(user, "1", SearchIndex.class)).thenReturn(si);
		setupAuthMocks();
		setupHappyPathMocks();
		stubOpenSearchAutocompleteReturns("test",
				EnumSet.of(SearchQueryPart.HITS, SearchQueryPart.TOTAL_HITS,
						SearchQueryPart.SELECT_COLUMNS, SearchQueryPart.FACETS),
				Arrays.asList(NAME_COLUMN, DESC_COLUMN), rawHits());

		// call under test
		SearchQueryResults results = manager.autocomplete(user, buildRequest(buildQuery(),
				SearchQueryPart.HITS, SearchQueryPart.TOTAL_HITS,
				SearchQueryPart.SELECT_COLUMNS, SearchQueryPart.FACETS));

		assertNotNull(results.getHits());
		assertEquals(7L, results.getTotalHits());
		assertNotNull(results.getSelectColumns());
		// FACETS may be null in real autocomplete (no facetRequests), but here the mocked OS
		// returned a facet result and we asked for FACETS, so it's preserved.
		assertNotNull(results.getFacets());
	}

	@Test
	public void testAutocompletePassesResolvedPartsToOpenSearchManager() {
		SearchIndex si = setupSearchIndex();
		when(entityManager.getEntity(user, "1", SearchIndex.class)).thenReturn(si);
		setupAuthMocks();
		setupHappyPathMocks();
		stubOpenSearchAutocompleteReturns("test",
				EnumSet.of(SearchQueryPart.SELECT_COLUMNS),
				Arrays.asList(NAME_COLUMN, DESC_COLUMN), rawHits());

		// call under test
		manager.autocomplete(user, buildRequest(buildQuery(), SearchQueryPart.SELECT_COLUMNS));

		// eq(expectedParts) on the parts slot enforces that the manager forwarded the resolved
		// EnumSet unchanged — no separate captor needed.
		verifyOpenSearchAutocomplete(
				EnumSet.of(SearchQueryPart.SELECT_COLUMNS),
				Arrays.asList(NAME_COLUMN, DESC_COLUMN));
	}

	// --- Validation tests ---

	@Test
	public void testAutocompleteWithNullRequestThrows() {
		// call under test
		assertThrows(IllegalArgumentException.class, () -> manager.autocomplete(user, null));
	}

	@Test
	public void testAutocompleteWithNullSearchQueryThrows() {
		SearchIndexQuery request = new SearchIndexQuery().setSearchIndexId(SEARCH_INDEX_ID);

		// call under test
		assertThrows(IllegalArgumentException.class, () -> manager.autocomplete(user, request));
	}

	@Test
	public void testSearchWithNullRequestThrows() {
		// call under test
		assertThrows(IllegalArgumentException.class, () -> manager.search(user, null));
	}

	@Test
	public void testSearchWithNullSearchIndexIdThrows() {
		SearchIndexQuery request = new SearchIndexQuery().setSearchQuery(buildQuery());

		// call under test
		assertThrows(IllegalArgumentException.class, () -> manager.search(user, request));
	}

	// A bound literal column with a synthetic id round-trips through the query path
	// without tripping `Collectors.toMap`'s no-null-values rule when nameToId is built.
	// The alias intentionally differs from the literal value so the rename is
	// observable in the bound schema and the query-field translation.
	@Test
	public void testSearchWithLiteralColumnInDefiningSqlAssignsSyntheticId() {
		SearchIndex si = setupSearchIndex();
		si.setDefiningSQL("SELECT name, 'tag' as tag_alias FROM syn456");
		when(entityManager.getEntity(user, "1", SearchIndex.class)).thenReturn(si);
		setupAuthMocks();
		when(connectionFactory.getSearchIndexStatusDao()).thenReturn(searchIndexStatusDao);
		when(searchIndexStatusDao.getStatus(1L)).thenReturn(Optional.of(
				new SearchIndexStatus().setSearchIndexId(SEARCH_INDEX_ID).setState(SearchIndexState.ACTIVE)));
		ColumnModel nameCol = TableModelTestUtils.createColumn(
				Long.parseLong(NAME_COLUMN_ID), NAME_COLUMN, ColumnType.STRING);
		ColumnModel tagAliasCol = new ColumnModel().setId("999").setName("tag_alias")
				.setColumnType(ColumnType.STRING).setMaximumSize(50L);
		when(tableManagerSupport.getTableSchema(IdAndVersion.parse(SEARCH_INDEX_ID)))
				.thenReturn(Arrays.asList(nameCol, tagAliasCol));
		// Capture the columns and SearchQuery that reach OpenSearch so we can assert the
		// synthetic-id column was included and the user-facing alias was translated to its id.
		@SuppressWarnings({"unchecked", "rawtypes"})
		ArgumentCaptor<List<ColumnModel>> columnsCaptor = (ArgumentCaptor) ArgumentCaptor.forClass(List.class);
		ArgumentCaptor<SearchQuery> queryCaptor = ArgumentCaptor.forClass(SearchQuery.class);
		when(openSearchManager.search(eq("search-index-1"), queryCaptor.capture(), columnsCaptor.capture(),
				eq(EnumSet.of(SearchQueryPart.HITS))))
				.thenReturn(new SearchQueryResults().setHits(Collections.emptyList()));

		// call under test
		SearchQuery query = buildQuery();
		query.setQueryFields(new ArrayList<>(Arrays.asList(NAME_COLUMN, "tag_alias")));
		manager.search(user, buildRequest(query));

		// The bound list with both real-id and synthetic-id columns reached OpenSearch.
		assertEquals(Arrays.asList(nameCol, tagAliasCol), columnsCaptor.getValue());
		// User-facing names (including the alias) in queryFields were translated to their ids
		// before reaching OpenSearch.
		assertEquals(Arrays.asList(NAME_COLUMN_ID, "999"), queryCaptor.getValue().getQueryFields());
		// The schema is read once via `getTableSchema(searchIndexId)` — no per-request
		// QueryTranslator construction. Verify the new code path is taken.
		verify(tableManagerSupport).getTableSchema(IdAndVersion.parse(SEARCH_INDEX_ID));
	}

	@Test
	public void testSearchWhenSearchIndexHasNoBoundSchemaThrows() {
		SearchIndex si = setupSearchIndex();
		when(entityManager.getEntity(user, "1", SearchIndex.class)).thenReturn(si);
		setupAuthMocks();
		when(connectionFactory.getSearchIndexStatusDao()).thenReturn(searchIndexStatusDao);
		when(searchIndexStatusDao.getStatus(1L)).thenReturn(Optional.of(
				new SearchIndexStatus().setSearchIndexId(SEARCH_INDEX_ID).setState(SearchIndexState.ACTIVE)));
		// SearchIndex created on a stack before `registerSchema` existed — no bound columns.
		when(tableManagerSupport.getTableSchema(IdAndVersion.parse(SEARCH_INDEX_ID)))
				.thenReturn(Collections.emptyList());

		// call under test — clear failure rather than NPE in nameToId construction.
		IllegalStateException ex = assertThrows(IllegalStateException.class,
				() -> manager.search(user, buildRequest(buildQuery())));
		assertTrue(ex.getMessage().contains("no bound schema"),
				"expected 'no bound schema' in message, got: " + ex.getMessage());
		verifyNoMoreInteractions(openSearchManager);
	}
}
