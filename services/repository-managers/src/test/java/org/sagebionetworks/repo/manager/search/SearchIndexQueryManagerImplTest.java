package org.sagebionetworks.repo.manager.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.sagebionetworks.repo.manager.EntityManager;
import org.sagebionetworks.repo.manager.UserManager;
import org.sagebionetworks.repo.manager.entity.EntityAuthorizationManager;
import org.sagebionetworks.repo.manager.table.TableManagerSupport;
import org.sagebionetworks.repo.model.ACCESS_TYPE;
import org.sagebionetworks.repo.model.UnauthorizedException;
import org.sagebionetworks.repo.model.UserInfo;
import org.sagebionetworks.repo.model.auth.AuthorizationStatus;
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
import org.sagebionetworks.repo.model.search.table.SearchIndex;
import org.sagebionetworks.repo.model.search.table.SearchIndexState;
import org.sagebionetworks.repo.model.search.table.SearchIndexStatus;
import org.sagebionetworks.repo.model.table.ColumnModel;
import org.sagebionetworks.repo.model.table.ColumnType;
import org.sagebionetworks.repo.model.table.FacetColumnResult;
import org.sagebionetworks.repo.model.table.FacetColumnResultValues;
import org.sagebionetworks.table.cluster.ConnectionFactory;
import org.sagebionetworks.table.cluster.description.TableIndexDescription;
import org.sagebionetworks.table.cluster.search.SearchIndexStatusDao;

@ExtendWith(MockitoExtension.class)
public class SearchIndexQueryManagerImplTest {

	@Mock
	private EntityManager entityManager;
	@Mock
	private EntityAuthorizationManager entityAuthorizationManager;
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
				entityManager, entityAuthorizationManager, connectionFactory,
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
		when(entityAuthorizationManager.hasAccess(any(UserInfo.class), any(String.class), any(ACCESS_TYPE.class)))
				.thenReturn(AuthorizationStatus.authorized());
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
		when(searchConfigurationResolver.resolve(user, null, "syn789")).thenReturn(Optional.empty());
		when(tableManagerSupport.getIndexDescription(SOURCE_ID)).thenReturn(new TableIndexDescription(SOURCE_ID));
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
		when(entityAuthorizationManager.hasAccess(user, "syn456", ACCESS_TYPE.READ))
				.thenReturn(AuthorizationStatus.accessDenied("no access to source"));

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
}
