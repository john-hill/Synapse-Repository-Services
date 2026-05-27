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
import org.sagebionetworks.repo.model.search.SearchFieldValue;
import org.sagebionetworks.repo.model.search.SearchHit;
import org.sagebionetworks.repo.model.search.SearchQueryPart;
import org.sagebionetworks.repo.model.search.SearchQueryResults;
import org.sagebionetworks.repo.model.search.table.SearchAutocompleteRequest;
import org.sagebionetworks.repo.model.search.table.SearchIndex;
import org.sagebionetworks.repo.model.search.table.SearchIndexQuery;
import org.sagebionetworks.repo.model.search.table.SearchIndexState;
import org.sagebionetworks.repo.model.search.table.SearchIndexStatus;
import org.sagebionetworks.repo.model.table.ColumnModel;
import org.sagebionetworks.repo.model.table.ColumnType;
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

	private Object buildBody() {
		// Minimal valid body: a match clause on the NAME_COLUMN, expressed as the opaque
		// OpenSearch DSL the manager forwards to OpenSearchManager. The manager doesn't
		// inspect the body any more than this — its own validation lives in
		// OpenSearchManagerImpl.executeSearch — so any non-null Map literal works.
		Map<String, Object> matchClause = new HashMap<>();
		matchClause.put(NAME_COLUMN, "test");
		Map<String, Object> queryDsl = new HashMap<>();
		queryDsl.put("match", matchClause);
		Map<String, Object> body = new HashMap<>();
		body.put("query", queryDsl);
		return body;
	}

	/** Wrap a body in a SearchIndexQuery bound to {@link #SEARCH_INDEX_ID}. */
	private SearchIndexQuery buildRequest(Object body) {
		return new SearchIndexQuery().setSearchIndexId(SEARCH_INDEX_ID).setSearchQuery(body);
	}

	/** Wrap a body plus an explicit set of response parts. */
	private SearchIndexQuery buildRequest(Object body, SearchQueryPart... parts) {
		Set<SearchQueryPart> partSet = parts.length == 0
				? EnumSet.noneOf(SearchQueryPart.class)
				: EnumSet.copyOf(Arrays.asList(parts));
		return new SearchIndexQuery()
				.setSearchIndexId(SEARCH_INDEX_ID)
				.setSearchQuery(body)
				.setResponseParts(partSet);
	}

	/**
	 * Build a minimal autocomplete-shaped body: a {@code prefix} match on
	 * {@link #NAME_COLUMN}'s {@code .keyword} sub-field, wrapped in a {@code query}
	 * envelope. The autocomplete validator (now inside OpenSearchManager) requires the
	 * top-level clause inside {@code query} to be {@code prefix}, {@code match_phrase_prefix},
	 * or {@code match_bool_prefix} — but since OpenSearchManager is mocked here, that
	 * validation does not run.
	 */
	private static Object buildAutocompleteBody() {
		Map<String, Object> prefixArgs = new HashMap<>();
		prefixArgs.put(NAME_COLUMN + ".keyword", "te");
		Map<String, Object> prefixClause = new HashMap<>();
		prefixClause.put("prefix", prefixArgs);
		Map<String, Object> body = new HashMap<>();
		body.put("query", prefixClause);
		return body;
	}

	/** Build a minimal SearchAutocompleteRequest bound to {@link #SEARCH_INDEX_ID}. */
	private SearchAutocompleteRequest buildAutocompleteRequest() {
		return new SearchAutocompleteRequest()
				.setSearchIndexId(SEARCH_INDEX_ID)
				.setBody(buildAutocompleteBody());
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
	 * Builds a SearchQueryResults shaped like what OpenSearchManager returns to this
	 * manager: hit field names are already user-facing column names (the OpenSearchManager
	 * does the column-id → column-name rewrite internally before returning).
	 */
	private SearchQueryResults buildRawResults() {
		SearchHit hit = new SearchHit();
		hit.setRowId(42L);
		hit.setFields(new ArrayList<>(Arrays.asList(
				new SearchFieldValue().setName(NAME_COLUMN).setValue("Alice"),
				new SearchFieldValue().setName(DESC_COLUMN).setValue("bio"))));
		return new SearchQueryResults().setTotalHits(1L).setHits(new ArrayList<>(Collections.singletonList(hit)));
	}

	/**
	 * Verifies {@code openSearchManager.search(...)} was called exactly once with the expected
	 * arguments. The opaque body is captured so the caller can assert on its contents; the
	 * {@code columns} list is captured and its names asserted against
	 * {@code expectedColumnNames}.
	 *
	 * <p>Using concrete matchers here instead of {@code any()} ensures the test actually
	 * verifies the values the manager passed — not merely that the method was invoked.
	 */
	private Object verifyOpenSearchSearch(Set<SearchQueryPart> expectedParts,
			List<String> expectedColumnNames) {
		ArgumentCaptor<Object> bodyCaptor = ArgumentCaptor.forClass(Object.class);
		@SuppressWarnings({"unchecked", "rawtypes"})
		ArgumentCaptor<List<ColumnModel>> columnsCaptor = (ArgumentCaptor) ArgumentCaptor.forClass(List.class);
		verify(openSearchManager).search(
				eq("search-index-1"),
				bodyCaptor.capture(),
				columnsCaptor.capture(),
				eq(expectedParts));
		assertEquals(expectedColumnNames, columnsCaptor.getValue().stream()
				.map(ColumnModel::getName).collect(Collectors.toList()));
		return bodyCaptor.getValue();
	}

	/**
	 * Autocomplete analog of {@link #verifyOpenSearchSearch}.
	 */
	private Object verifyOpenSearchAutocomplete(Set<SearchQueryPart> expectedParts,
			List<String> expectedColumnNames) {
		ArgumentCaptor<Object> bodyCaptor = ArgumentCaptor.forClass(Object.class);
		@SuppressWarnings({"unchecked", "rawtypes"})
		ArgumentCaptor<List<ColumnModel>> columnsCaptor = (ArgumentCaptor) ArgumentCaptor.forClass(List.class);
		verify(openSearchManager).autocomplete(
				eq("search-index-1"),
				bodyCaptor.capture(),
				columnsCaptor.capture(),
				eq(expectedParts));
		assertEquals(expectedColumnNames, columnsCaptor.getValue().stream()
				.map(ColumnModel::getName).collect(Collectors.toList()));
		return bodyCaptor.getValue();
	}

	/**
	 * Stubs {@code openSearchManager.search(...)} to return {@code returnValue} when called
	 * with matching arguments. Uses concrete matchers throughout — no positional {@code any()} —
	 * so a manager that wires the wrong options or columns misses the stub, receives
	 * {@code null}, and fails the test explicitly. The manager forwards the opaque body
	 * unchanged, so we only assert that it is a Map carrying a {@code query} key.
	 */
	private void stubOpenSearchSearchReturns(Set<SearchQueryPart> expectedOptions,
			List<String> expectedColumnNames, SearchQueryResults returnValue) {
		when(openSearchManager.search(
				eq("search-index-1"),
				argThat(b -> b instanceof Map && ((Map<?, ?>) b).containsKey("query")),
				argThat(cols -> cols != null && expectedColumnNames.equals(
						cols.stream().map(ColumnModel::getName).collect(Collectors.toList()))),
				eq(expectedOptions)
		)).thenReturn(returnValue);
	}

	/** Autocomplete analog of {@link #stubOpenSearchSearchReturns}. */
	private void stubOpenSearchAutocompleteReturns(Set<SearchQueryPart> expectedOptions,
			List<String> expectedColumnNames, SearchQueryResults returnValue) {
		when(openSearchManager.autocomplete(
				eq("search-index-1"),
				argThat(b -> b instanceof Map && ((Map<?, ?>) b).containsKey("query")),
				argThat(cols -> cols != null && expectedColumnNames.equals(
						cols.stream().map(ColumnModel::getName).collect(Collectors.toList()))),
				eq(expectedOptions)
		)).thenReturn(returnValue);
	}

	@Test
	public void testSearchWithNoReadOnSearchIndex() {
		when(entityManager.getEntity(user, "1", SearchIndex.class))
				.thenThrow(new UnauthorizedException("no access"));

		assertThrows(UnauthorizedException.class, () -> manager.search(user, buildRequest(buildBody())));
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
		assertThrows(UnauthorizedException.class, () -> manager.search(user, buildRequest(buildBody())));
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
				() -> manager.search(user, buildRequest(buildBody())));
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
				() -> manager.search(user, buildRequest(buildBody())));

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
				() -> manager.search(user, buildRequest(buildBody())));

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
				() -> manager.search(user, buildRequest(buildBody())));
		assertTrue(ex.getMessage().contains("still building"));
		verifyNoMoreInteractions(openSearchManager);
	}

	@Test
	public void testSearchWithActiveStatus() {
		SearchIndex si = setupSearchIndex();
		when(entityManager.getEntity(user, "1", SearchIndex.class)).thenReturn(si);
		setupAuthMocks();
		setupHappyPathMocks();
		stubOpenSearchSearchReturns(
				EnumSet.of(SearchQueryPart.HITS, SearchQueryPart.TOTAL_HITS),
				Arrays.asList(NAME_COLUMN, DESC_COLUMN), buildRawResults());

		Object body = buildBody();

		// call under test — request HITS + TOTAL_HITS so the assertions on totalHits work
		SearchQueryResults results = manager.search(user, buildRequest(body,
				SearchQueryPart.HITS, SearchQueryPart.TOTAL_HITS));

		assertNotNull(results);
		assertEquals(1L, results.getTotalHits());
		SearchHit hit = results.getHits().get(0);
		// OpenSearchManager already returns hit field names as user-facing column names; the
		// query-manager just forwards them.
		assertEquals(NAME_COLUMN, hit.getFields().get(0).getName());
		assertEquals(DESC_COLUMN, hit.getFields().get(1).getName());

		verifyOpenSearchSearch(
				EnumSet.of(SearchQueryPart.HITS, SearchQueryPart.TOTAL_HITS),
				Arrays.asList(NAME_COLUMN, DESC_COLUMN));
	}

	@Test
	public void testAutocompleteWithActiveStatusDispatchesToOpenSearchManager() {
		// The manager hardcodes the response parts to HITS and forwards the caller's
		// opaque body unchanged.
		SearchIndex si = setupSearchIndex();
		when(entityManager.getEntity(user, "1", SearchIndex.class)).thenReturn(si);
		setupAuthMocks();
		setupHappyPathMocks();
		stubOpenSearchAutocompleteReturns(
				EnumSet.of(SearchQueryPart.HITS),
				Arrays.asList(NAME_COLUMN, DESC_COLUMN), buildRawResults());

		// call under test
		SearchQueryResults results = manager.autocomplete(user, buildAutocompleteRequest());

		assertNotNull(results);
		assertEquals(NAME_COLUMN, results.getHits().get(0).getFields().get(0).getName());

		// HITS is the only resolved part — the slim request carries no responseParts knob.
		Object forwarded = verifyOpenSearchAutocomplete(
				EnumSet.of(SearchQueryPart.HITS),
				Arrays.asList(NAME_COLUMN, DESC_COLUMN));
		// The forwarded body is the caller's opaque body, untouched.
		assertTrue(forwarded instanceof Map);
		Map<?, ?> forwardedMap = (Map<?, ?>) forwarded;
		assertNotNull(forwardedMap.get("query"));
		assertNull(forwardedMap.get("aggregations"));
		assertNull(forwardedMap.get("sort"));
		assertNull(forwardedMap.get("from"));
		assertNull(forwardedMap.get("size"));
		assertNull(forwardedMap.get("search_after"));
		assertNull(forwardedMap.get("suggest"));
	}

	@Test
	public void testAutocompleteWithReturnFieldsForwardsToOpenSearchManager() {
		// Caller supplies _source.includes inside the body; the manager forwards it unchanged.
		SearchIndex si = setupSearchIndex();
		when(entityManager.getEntity(user, "1", SearchIndex.class)).thenReturn(si);
		setupAuthMocks();
		setupHappyPathMocks();
		stubOpenSearchAutocompleteReturns(
				EnumSet.of(SearchQueryPart.HITS),
				Arrays.asList(NAME_COLUMN, DESC_COLUMN), buildRawResults());

		Map<String, Object> prefixArgs = new HashMap<>();
		prefixArgs.put(NAME_COLUMN + ".keyword", "te");
		Map<String, Object> prefixClause = new HashMap<>();
		prefixClause.put("prefix", prefixArgs);
		Map<String, Object> source = new HashMap<>();
		source.put("includes", new ArrayList<>(Arrays.asList(NAME_COLUMN)));
		Map<String, Object> body = new HashMap<>();
		body.put("query", prefixClause);
		body.put("_source", source);

		// call under test
		manager.autocomplete(user, new SearchAutocompleteRequest()
				.setSearchIndexId(SEARCH_INDEX_ID)
				.setBody(body));

		Object forwarded = verifyOpenSearchAutocomplete(
				EnumSet.of(SearchQueryPart.HITS),
				Arrays.asList(NAME_COLUMN, DESC_COLUMN));
		assertTrue(forwarded instanceof Map);
		Map<?, ?> forwardedMap = (Map<?, ?>) forwarded;
		assertEquals(source, forwardedMap.get("_source"));
	}

	// --- Focused unit tests for package-protected helpers ---

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
				new SearchFieldValue().setName(NAME_COLUMN).setValue("Alice"))));
		// OpenSearchManager returns aggregationResults as an opaque JSON string with field
		// references already rewritten to column names; the manager just forwards it.
		return new SearchQueryResults()
				.setHits(new ArrayList<>(Arrays.asList(hit)))
				.setTotalHits(7L)
				.setAggregationResults("{\"" + NAME_COLUMN + "\":{\"buckets\":[]}}")
				.setOffset(0L);
	}

	@Test
	public void testSearchWithDefaultPartsReturnsHitsOnly() {
		SearchIndex si = setupSearchIndex();
		when(entityManager.getEntity(user, "1", SearchIndex.class)).thenReturn(si);
		setupAuthMocks();
		setupHappyPathMocks();
		stubOpenSearchSearchReturns(
				EnumSet.of(SearchQueryPart.HITS),
				Arrays.asList(NAME_COLUMN, DESC_COLUMN), rawHits());

		// call under test — buildRequest with no parts ⇒ default minimal payload
		SearchQueryResults results = manager.search(user, buildRequest(buildBody()));

		assertNotNull(results.getHits());
		assertNull(results.getTotalHits(),     "totalHits should be null when TOTAL_HITS not requested");
		assertNull(results.getSelectColumns(), "selectColumns should be null when SELECT_COLUMNS not requested");
		assertNull(results.getAggregationResults(),
				"aggregationResults should be null when FACETS not requested");
		assertEquals(0L, results.getOffset(),  "offset is always populated");
	}

	@Test
	public void testSearchWithAllPartsRequested() {
		SearchIndex si = setupSearchIndex();
		when(entityManager.getEntity(user, "1", SearchIndex.class)).thenReturn(si);
		setupAuthMocks();
		setupHappyPathMocks();
		stubOpenSearchSearchReturns(
				EnumSet.of(SearchQueryPart.HITS, SearchQueryPart.TOTAL_HITS,
						SearchQueryPart.SELECT_COLUMNS, SearchQueryPart.FACETS),
				Arrays.asList(NAME_COLUMN, DESC_COLUMN), rawHits());

		// call under test
		SearchQueryResults results = manager.search(user, buildRequest(buildBody(),
				SearchQueryPart.HITS, SearchQueryPart.TOTAL_HITS,
				SearchQueryPart.SELECT_COLUMNS, SearchQueryPart.FACETS));

		assertNotNull(results.getHits());
		assertEquals(7L, results.getTotalHits());
		assertNotNull(results.getSelectColumns());
		assertNotNull(results.getAggregationResults(),
				"aggregationResults should be populated when FACETS is requested");
		assertEquals(0L, results.getOffset());
	}

	@Test
	public void testSearchWithSelectColumnsOnly() {
		SearchIndex si = setupSearchIndex();
		when(entityManager.getEntity(user, "1", SearchIndex.class)).thenReturn(si);
		setupAuthMocks();
		setupHappyPathMocks();
		stubOpenSearchSearchReturns(
				EnumSet.of(SearchQueryPart.SELECT_COLUMNS),
				Arrays.asList(NAME_COLUMN, DESC_COLUMN), rawHits());

		// call under test
		SearchQueryResults results = manager.search(user, buildRequest(buildBody(), SearchQueryPart.SELECT_COLUMNS));

		assertNull(results.getHits(),     "hits should be null when HITS not requested");
		assertNull(results.getTotalHits());
		assertNotNull(results.getSelectColumns(),
				"selectColumns should be populated when SELECT_COLUMNS is requested");
		assertNull(results.getAggregationResults());
		assertEquals(0L, results.getOffset());
	}

	@Test
	public void testSearchWithSelectColumnsAndSourceIncludes() {
		// _source.includes narrows the SELECT_COLUMNS response to the named subset.
		SearchIndex si = setupSearchIndex();
		when(entityManager.getEntity(user, "1", SearchIndex.class)).thenReturn(si);
		setupAuthMocks();
		setupHappyPathMocks();
		stubOpenSearchSearchReturns(
				EnumSet.of(SearchQueryPart.SELECT_COLUMNS),
				Arrays.asList(NAME_COLUMN, DESC_COLUMN), rawHits());

		Map<String, Object> matchClause = new HashMap<>();
		matchClause.put(NAME_COLUMN, "test");
		Map<String, Object> queryDsl = new HashMap<>();
		queryDsl.put("match", matchClause);
		Map<String, Object> source = new HashMap<>();
		source.put("includes", new ArrayList<>(Arrays.asList(NAME_COLUMN)));
		Map<String, Object> body = new HashMap<>();
		body.put("query", queryDsl);
		body.put("_source", source);

		// call under test
		SearchQueryResults results = manager.search(user,
				buildRequest(body, SearchQueryPart.SELECT_COLUMNS));

		assertEquals(1, results.getSelectColumns().size());
		assertEquals(NAME_COLUMN, results.getSelectColumns().get(0).getName());
	}

	@Test
	public void testSearchWithSelectColumnsAndSourceArrayShorthand() {
		// _source as an array is the OpenSearch shorthand for _source.includes.
		SearchIndex si = setupSearchIndex();
		when(entityManager.getEntity(user, "1", SearchIndex.class)).thenReturn(si);
		setupAuthMocks();
		setupHappyPathMocks();
		stubOpenSearchSearchReturns(
				EnumSet.of(SearchQueryPart.SELECT_COLUMNS),
				Arrays.asList(NAME_COLUMN, DESC_COLUMN), rawHits());

		Map<String, Object> matchClause = new HashMap<>();
		matchClause.put(NAME_COLUMN, "test");
		Map<String, Object> queryDsl = new HashMap<>();
		queryDsl.put("match", matchClause);
		Map<String, Object> body = new HashMap<>();
		body.put("query", queryDsl);
		body.put("_source", new ArrayList<>(Arrays.asList(NAME_COLUMN)));

		// call under test
		SearchQueryResults results = manager.search(user,
				buildRequest(body, SearchQueryPart.SELECT_COLUMNS));

		assertEquals(1, results.getSelectColumns().size());
		assertEquals(NAME_COLUMN, results.getSelectColumns().get(0).getName());
	}

	@Test
	public void testSearchWithSelectColumnsAndNoSourceFilter() {
		// No _source key means no narrowing; full SELECT-clause survives.
		SearchIndex si = setupSearchIndex();
		when(entityManager.getEntity(user, "1", SearchIndex.class)).thenReturn(si);
		setupAuthMocks();
		setupHappyPathMocks();
		stubOpenSearchSearchReturns(
				EnumSet.of(SearchQueryPart.SELECT_COLUMNS),
				Arrays.asList(NAME_COLUMN, DESC_COLUMN), rawHits());

		// call under test — body has no _source key (buildBody() omits it).
		SearchQueryResults results = manager.search(user,
				buildRequest(buildBody(), SearchQueryPart.SELECT_COLUMNS));

		assertEquals(2, results.getSelectColumns().size());
		assertEquals(NAME_COLUMN, results.getSelectColumns().get(0).getName());
		assertEquals(DESC_COLUMN, results.getSelectColumns().get(1).getName());
	}

	@Test
	public void testSearchWithSelectColumnsAndBooleanSource() {
		// _source as a boolean (false → no source returned, true → all source) is not a name
		// list; the manager treats this as "no narrowing" for SELECT_COLUMNS purposes.
		SearchIndex si = setupSearchIndex();
		when(entityManager.getEntity(user, "1", SearchIndex.class)).thenReturn(si);
		setupAuthMocks();
		setupHappyPathMocks();
		stubOpenSearchSearchReturns(
				EnumSet.of(SearchQueryPart.SELECT_COLUMNS),
				Arrays.asList(NAME_COLUMN, DESC_COLUMN), rawHits());

		Map<String, Object> matchClause = new HashMap<>();
		matchClause.put(NAME_COLUMN, "test");
		Map<String, Object> queryDsl = new HashMap<>();
		queryDsl.put("match", matchClause);
		Map<String, Object> body = new HashMap<>();
		body.put("query", queryDsl);
		body.put("_source", Boolean.FALSE);

		// call under test
		SearchQueryResults results = manager.search(user,
				buildRequest(body, SearchQueryPart.SELECT_COLUMNS));

		assertEquals(2, results.getSelectColumns().size());
		assertEquals(NAME_COLUMN, results.getSelectColumns().get(0).getName());
		assertEquals(DESC_COLUMN, results.getSelectColumns().get(1).getName());
	}

	@Test
	public void testSearchPassesResolvedPartsToOpenSearchManager() {
		SearchIndex si = setupSearchIndex();
		when(entityManager.getEntity(user, "1", SearchIndex.class)).thenReturn(si);
		setupAuthMocks();
		setupHappyPathMocks();
		stubOpenSearchSearchReturns(
				EnumSet.of(SearchQueryPart.HITS, SearchQueryPart.TOTAL_HITS),
				Arrays.asList(NAME_COLUMN, DESC_COLUMN), rawHits());

		// call under test — request HITS + TOTAL_HITS
		manager.search(user, buildRequest(buildBody(), SearchQueryPart.HITS, SearchQueryPart.TOTAL_HITS));

		// Verify the manager forwarded the resolved EnumSet to OpenSearchManager unchanged.
		// verifyOpenSearchSearch uses eq(expectedParts) on the parts slot, so this assertion
		// is enforced through matcher equality rather than a separate captor.
		verifyOpenSearchSearch(
				EnumSet.of(SearchQueryPart.HITS, SearchQueryPart.TOTAL_HITS),
				Arrays.asList(NAME_COLUMN, DESC_COLUMN));
	}

	// --- Autocomplete: only HITS is ever populated (caller cannot opt in to extras) ---

	@Test
	public void testAutocompleteAlwaysReturnsHitsOnly() {
		SearchIndex si = setupSearchIndex();
		when(entityManager.getEntity(user, "1", SearchIndex.class)).thenReturn(si);
		setupAuthMocks();
		setupHappyPathMocks();
		// rawHits carries totalHits / aggregationResults too — assert the manager strips them.
		stubOpenSearchAutocompleteReturns(
				EnumSet.of(SearchQueryPart.HITS),
				Arrays.asList(NAME_COLUMN, DESC_COLUMN), rawHits());

		// call under test
		SearchQueryResults results = manager.autocomplete(user, buildAutocompleteRequest());

		assertNotNull(results.getHits());
		assertNull(results.getTotalHits(),
				"autocomplete must not surface totalHits regardless of OpenSearchManager output");
		assertNull(results.getSelectColumns());
		assertNull(results.getAggregationResults(),
				"autocomplete must not surface aggregationResults regardless of OpenSearchManager output");
		assertEquals(0L, results.getOffset());
	}

	// --- Validation tests ---

	@Test
	public void testAutocompleteWithNullRequestThrows() {
		// call under test
		assertThrows(IllegalArgumentException.class, () -> manager.autocomplete(user, null));
		verifyNoMoreInteractions(entityManager, connectionFactory, openSearchManager, tableManagerSupport);
	}

	@Test
	public void testAutocompleteWithNullSearchIndexIdThrows() {
		SearchAutocompleteRequest request = new SearchAutocompleteRequest().setBody(buildAutocompleteBody());

		// call under test
		assertThrows(IllegalArgumentException.class, () -> manager.autocomplete(user, request));
		verifyNoMoreInteractions(entityManager, connectionFactory, openSearchManager, tableManagerSupport);
	}

	@Test
	public void testAutocompleteWithNullBodyThrows() {
		SearchAutocompleteRequest request = new SearchAutocompleteRequest().setSearchIndexId(SEARCH_INDEX_ID);

		// call under test
		assertThrows(IllegalArgumentException.class, () -> manager.autocomplete(user, request));
		verifyNoMoreInteractions(entityManager, connectionFactory, openSearchManager, tableManagerSupport);
	}

	@Test
	public void testSearchWithNullRequestThrows() {
		// call under test
		assertThrows(IllegalArgumentException.class, () -> manager.search(user, null));
	}

	@Test
	public void testSearchWithNullSearchIndexIdThrows() {
		SearchIndexQuery request = new SearchIndexQuery().setSearchQuery(buildBody());

		// call under test
		assertThrows(IllegalArgumentException.class, () -> manager.search(user, request));
	}

	// A bound literal column with a synthetic id round-trips through the query path
	// without tripping `Collectors.toMap`'s no-null-values rule when nameToId is built.
	// The alias intentionally differs from the literal value so the rename is
	// observable in the bound schema reaching OpenSearch.
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
		@SuppressWarnings({"unchecked", "rawtypes"})
		ArgumentCaptor<List<ColumnModel>> columnsCaptor = (ArgumentCaptor) ArgumentCaptor.forClass(List.class);
		when(openSearchManager.search(eq("search-index-1"),
				argThat(b -> b instanceof Map && ((Map<?, ?>) b).containsKey("query")), columnsCaptor.capture(),
				eq(EnumSet.of(SearchQueryPart.HITS))))
				.thenReturn(new SearchQueryResults().setHits(Collections.emptyList()));

		// call under test
		manager.search(user, buildRequest(buildBody()));

		// The bound list with both real-id and synthetic-id columns reached OpenSearch — the
		// caller's body is opaque, so the column list is what carries the schema information
		// the OpenSearchManager needs for name→id rewriting.
		assertEquals(Arrays.asList(nameCol, tagAliasCol), columnsCaptor.getValue());
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
				() -> manager.search(user, buildRequest(buildBody())));
		assertTrue(ex.getMessage().contains("no bound schema"),
				"expected 'no bound schema' in message, got: " + ex.getMessage());
		verifyNoMoreInteractions(openSearchManager);
	}
}
