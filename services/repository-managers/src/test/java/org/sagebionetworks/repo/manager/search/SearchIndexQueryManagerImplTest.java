package org.sagebionetworks.repo.manager.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.sagebionetworks.repo.manager.EntityManager;
import org.sagebionetworks.repo.manager.table.TableManagerSupport;
import org.sagebionetworks.repo.model.entity.IdAndVersion;
import org.sagebionetworks.repo.model.search.FacetRequest;
import org.sagebionetworks.repo.model.search.KeyRange;
import org.sagebionetworks.repo.model.search.KeyValues;
import org.sagebionetworks.repo.model.search.SearchFieldValue;
import org.sagebionetworks.repo.model.search.SearchHit;
import org.sagebionetworks.repo.model.search.SearchQuery;
import org.sagebionetworks.repo.model.search.SearchQueryPart;
import org.sagebionetworks.repo.model.search.SortField;
import org.sagebionetworks.repo.model.search.table.SearchIndexState;
import org.sagebionetworks.repo.model.search.table.SearchIndexStatus;
import org.sagebionetworks.repo.model.table.ColumnModel;
import org.sagebionetworks.repo.model.table.ColumnType;
import org.sagebionetworks.repo.model.table.SelectColumn;
import org.sagebionetworks.table.cluster.ConnectionFactory;
import org.sagebionetworks.table.cluster.search.SearchIndexStatusDao;

/**
 * Mockito unit tests for {@link SearchIndexQueryManagerImpl}. Focuses on the
 * translation helpers, state-machine gates ({@code checkIndexStatus}), and response-part
 * resolution. The full {@code executeQuery} orchestration is exercised end-to-end via
 * the {@code ITSearchQuery} integration test.
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

	// --- resolveRequestedParts (static) ---

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

	// --- filterSelectColumnsForReturnFields ---

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
				result.stream().map(SelectColumn::getName).collect(java.util.stream.Collectors.toList()));
	}

	// --- getSearchableColumnNames ---

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

	// --- getIndexName ---

	@Test
	public void testGetIndexNameAppliesPrefix() {
		// call under test — AOSS index name is derived from the SearchIndex entity ID.
		assertEquals("search-index-syn123", manager.getIndexName("syn123"));
	}

}
