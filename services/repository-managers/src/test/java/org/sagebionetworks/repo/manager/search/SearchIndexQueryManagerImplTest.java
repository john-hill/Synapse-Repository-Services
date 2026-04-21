package org.sagebionetworks.repo.manager.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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
import org.sagebionetworks.repo.model.dbo.search.ColumnAnalyzerOverrideDao;
import org.sagebionetworks.repo.model.dbo.search.SynonymSetDao;
import org.sagebionetworks.repo.model.dbo.search.TextAnalyzerDao;
import org.sagebionetworks.repo.model.entity.IdAndVersion;
import org.sagebionetworks.repo.model.search.table.SearchIndex;
import org.sagebionetworks.repo.model.search.table.SearchIndexState;
import org.sagebionetworks.repo.model.search.SearchQuery;
import org.sagebionetworks.repo.model.search.SearchQueryType;
import org.sagebionetworks.repo.model.search.SearchQueryResults;
import org.sagebionetworks.table.cluster.ConnectionFactory;
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

	private static final String SEARCH_INDEX_ID = "1";

	private SearchQuery buildQuery() {
		SearchQuery query = new SearchQuery();
		query.setQueryText("test");
		return query;
	}

	@Test
	public void testSearchWithNoReadOnSearchIndex() {
		SearchIndex si = setupSearchIndex();
		when(entityManager.getEntity(user, "1", SearchIndex.class)).thenReturn(si);
		when(entityAuthorizationManager.hasAccess(user, "1", ACCESS_TYPE.READ))
				.thenReturn(AuthorizationStatus.accessDenied("no access"));

		assertThrows(UnauthorizedException.class, () -> manager.search(user, SEARCH_INDEX_ID, buildQuery()));
		verifyNoMoreInteractions(connectionFactory, openSearchManager);
	}

	@Test
	public void testSearchWithNoReadOnSourceTable() {
		SearchIndex si = setupSearchIndex();
		when(entityManager.getEntity(user, "1", SearchIndex.class)).thenReturn(si);
		when(entityAuthorizationManager.hasAccess(user, "1", ACCESS_TYPE.READ))
				.thenReturn(AuthorizationStatus.authorized());
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
		when(statusDao.getState(1L)).thenReturn(Optional.of(SearchIndexState.CREATING));

		IllegalStateException ex = assertThrows(IllegalStateException.class,
				() -> manager.search(user, SEARCH_INDEX_ID, buildQuery()));
		assertEquals(true, ex.getMessage().contains("still building"));
		verifyNoMoreInteractions(openSearchManager);
	}

	@Test
	public void testSearchWithFailedStatus() {
		SearchIndex si = setupSearchIndex();
		when(entityManager.getEntity(user, "1", SearchIndex.class)).thenReturn(si);
		setupAuthMocks();
		when(connectionFactory.getSearchIndexStatusDao()).thenReturn(statusDao);
		when(statusDao.getState(1L)).thenReturn(Optional.of(SearchIndexState.FAILED));

		IllegalStateException ex = assertThrows(IllegalStateException.class,
				() -> manager.search(user, SEARCH_INDEX_ID, buildQuery()));
		assertEquals(true, ex.getMessage().contains("build failed"));
		verifyNoMoreInteractions(openSearchManager);
	}

	@Test
	public void testSearchWithDeletingStatus() {
		SearchIndex si = setupSearchIndex();
		when(entityManager.getEntity(user, "1", SearchIndex.class)).thenReturn(si);
		setupAuthMocks();
		when(connectionFactory.getSearchIndexStatusDao()).thenReturn(statusDao);
		when(statusDao.getState(1L)).thenReturn(Optional.of(SearchIndexState.DELETING));

		IllegalStateException ex = assertThrows(IllegalStateException.class,
				() -> manager.search(user, SEARCH_INDEX_ID, buildQuery()));
		assertEquals(true, ex.getMessage().contains("being deleted"));
		verifyNoMoreInteractions(openSearchManager);
	}

	@Test
	public void testSearchWithMissingStatus() {
		SearchIndex si = setupSearchIndex();
		when(entityManager.getEntity(user, "1", SearchIndex.class)).thenReturn(si);
		setupAuthMocks();
		when(connectionFactory.getSearchIndexStatusDao()).thenReturn(statusDao);
		when(statusDao.getState(1L)).thenReturn(Optional.empty());

		IllegalStateException ex = assertThrows(IllegalStateException.class,
				() -> manager.search(user, SEARCH_INDEX_ID, buildQuery()));
		assertEquals(true, ex.getMessage().contains("still building"));
		verifyNoMoreInteractions(openSearchManager);
	}

	@Test
	public void testSearchWithActiveStatus() {
		SearchIndex si = setupSearchIndex();
		when(entityManager.getEntity(user, "1", SearchIndex.class)).thenReturn(si);
		setupAuthMocks();
		when(connectionFactory.getSearchIndexStatusDao()).thenReturn(statusDao);
		when(statusDao.getState(1L)).thenReturn(Optional.of(SearchIndexState.ACTIVE));
		when(searchConfigurationResolver.resolve(user, null, "syn789"))
				.thenReturn(Optional.empty());

		IdAndVersion sourceId = IdAndVersion.parse("syn456");
		when(tableManagerSupport.getIndexDescription(sourceId)).thenReturn(null);

		// getSchemaOfDefiningSQL calls QueryTranslator.builder() which validates
		// indexDescription is non-null. Since we return null from the mock, this throws
		// IllegalArgumentException. The important thing is that we got past auth and
		// status checks.
		assertThrows(IllegalArgumentException.class, () -> manager.search(user, SEARCH_INDEX_ID, buildQuery()));
		verify(tableManagerSupport).getIndexDescription(sourceId);
	}

	@Test
	public void testAutocompleteWithActiveStatus() {
		SearchIndex si = setupSearchIndex();
		when(entityManager.getEntity(user, "1", SearchIndex.class)).thenReturn(si);
		setupAuthMocks();
		when(connectionFactory.getSearchIndexStatusDao()).thenReturn(statusDao);
		when(statusDao.getState(1L)).thenReturn(Optional.of(SearchIndexState.ACTIVE));
		when(searchConfigurationResolver.resolve(user, null, "syn789"))
				.thenReturn(Optional.empty());

		IdAndVersion sourceId = IdAndVersion.parse("syn456");
		when(tableManagerSupport.getIndexDescription(sourceId)).thenReturn(null);

		SearchQuery query = buildQuery();
		query.setQueryType(SearchQueryType.MATCH);
		query.setLimit(20L);

		try {
			manager.autocomplete(user, SEARCH_INDEX_ID, query);
		} catch (NullPointerException | IllegalArgumentException e) {
			// Expected due to null IndexDescription from mock
		}

		// Verify the query type is NOT modified by the manager (OssClient handles this)
		assertEquals(SearchQueryType.MATCH, query.getQueryType());
	}

	private void setupStatusAndConfig() {
		when(connectionFactory.getSearchIndexStatusDao()).thenReturn(statusDao);
		when(statusDao.getState(any())).thenReturn(Optional.of(SearchIndexState.ACTIVE));
		when(searchConfigurationResolver.resolve(any(), any(), any())).thenReturn(Optional.empty());
	}

	// Category 3: Parameterized dual-READ auth matrix
	@ParameterizedTest(name = "indexRead={0}, tableRead={1}, shouldSucceed={2}")
	@CsvSource({
		"true, true, true",
		"true, false, false",
		"false, true, false",
		"false, false, false"
	})
	void testDualReadAuthMatrix(boolean indexRead, boolean tableRead, boolean shouldSucceed) {
		SearchIndex searchIndex = setupSearchIndex();
		SearchQuery query = buildQuery();

		when(entityManager.getEntity(any(), eq("1"), eq(SearchIndex.class))).thenReturn(searchIndex);
		when(entityAuthorizationManager.hasAccess(any(), eq("1"), eq(ACCESS_TYPE.READ)))
			.thenReturn(indexRead ? AuthorizationStatus.authorized() : AuthorizationStatus.accessDenied("no"));

		if (indexRead) {
			when(entityAuthorizationManager.hasAccess(any(), eq("syn456"), eq(ACCESS_TYPE.READ)))
				.thenReturn(tableRead ? AuthorizationStatus.authorized() : AuthorizationStatus.accessDenied("no"));
		}

		if (shouldSucceed) {
			setupStatusAndConfig();

			IdAndVersion sourceId = IdAndVersion.parse("syn456");
			when(tableManagerSupport.getIndexDescription(sourceId)).thenReturn(null);

			// Will throw NPE on getSchemaOfDefiningSQL since tableManagerSupport returns null
			// but the auth checks pass — verify they were called
			try {
				manager.search(user, SEARCH_INDEX_ID, query);
			} catch (NullPointerException | IllegalArgumentException e) {
				// Expected — auth passed, QueryTranslator setup is null
			}
			verify(entityAuthorizationManager).hasAccess(any(), eq("1"), eq(ACCESS_TYPE.READ));
			verify(entityAuthorizationManager).hasAccess(any(), eq("syn456"), eq(ACCESS_TYPE.READ));
		} else {
			assertThrows(UnauthorizedException.class, () -> manager.search(user, SEARCH_INDEX_ID, query));
		}
	}
}
