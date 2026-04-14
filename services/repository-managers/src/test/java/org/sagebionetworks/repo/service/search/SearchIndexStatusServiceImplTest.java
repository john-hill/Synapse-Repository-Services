package org.sagebionetworks.repo.service.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;

import java.util.Date;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.sagebionetworks.repo.manager.EntityManager;
import org.sagebionetworks.repo.manager.UserManager;
import org.sagebionetworks.repo.manager.entity.EntityAuthorizationManager;
import org.sagebionetworks.repo.model.ACCESS_TYPE;
import org.sagebionetworks.repo.model.auth.AuthorizationStatus;
import org.sagebionetworks.repo.model.UnauthorizedException;
import org.sagebionetworks.repo.model.UserInfo;
import org.sagebionetworks.repo.model.search.table.SearchIndex;
import org.sagebionetworks.repo.model.search.table.SearchIndexState;
import org.sagebionetworks.repo.model.search.table.SearchIndexStatus;
import org.sagebionetworks.table.cluster.ConnectionFactory;
import org.sagebionetworks.table.cluster.search.SearchIndexStatusDao;

@ExtendWith(MockitoExtension.class)
public class SearchIndexStatusServiceImplTest {

	@Mock
	private UserManager userManager;
	@Mock
	private EntityManager entityManager;
	@Mock
	private EntityAuthorizationManager entityAuthorizationManager;
	@Mock
	private ConnectionFactory connectionFactory;
	@Mock
	private SearchIndexStatusDao statusDao;

	private SearchIndexStatusServiceImpl service;

	private static final Long USER_ID = 123L;
	private static final String SEARCH_INDEX_ID = "syn456";

	@BeforeEach
	void setUp() {
		service = new SearchIndexStatusServiceImpl(userManager, entityManager, entityAuthorizationManager, connectionFactory);
		lenient().when(connectionFactory.getSearchIndexStatusDao()).thenReturn(statusDao);
	}

	@Test
	void testGetSearchIndexStatusWithActiveState() {
		UserInfo user = new UserInfo(false);
		user.setId(USER_ID);
		when(userManager.getUserInfo(USER_ID)).thenReturn(user);
		when(entityManager.getEntity(user, SEARCH_INDEX_ID, SearchIndex.class)).thenReturn(new SearchIndex());
		when(entityAuthorizationManager.hasAccess(user, SEARCH_INDEX_ID, ACCESS_TYPE.READ))
				.thenReturn(AuthorizationStatus.authorized());

		SearchIndexStatus expected = new SearchIndexStatus();
		expected.setSearchIndexId(SEARCH_INDEX_ID);
		expected.setState(SearchIndexState.ACTIVE);
		expected.setChangedOn(new Date());
		expected.setAppliedConfiguration("{\"mappings\":{}}");
		when(statusDao.getStatus(456L)).thenReturn(Optional.of(expected));

		// call under test
		SearchIndexStatus result = service.getSearchIndexStatus(USER_ID, SEARCH_INDEX_ID);

		assertEquals(expected, result);
	}

	@Test
	void testGetSearchIndexStatusWithMissingStatusReturnsIdOnly() {
		UserInfo user = new UserInfo(false);
		user.setId(USER_ID);
		when(userManager.getUserInfo(USER_ID)).thenReturn(user);
		when(entityManager.getEntity(user, SEARCH_INDEX_ID, SearchIndex.class)).thenReturn(new SearchIndex());
		when(entityAuthorizationManager.hasAccess(user, SEARCH_INDEX_ID, ACCESS_TYPE.READ))
				.thenReturn(AuthorizationStatus.authorized());
		when(statusDao.getStatus(456L)).thenReturn(Optional.empty());

		// call under test
		SearchIndexStatus result = service.getSearchIndexStatus(USER_ID, SEARCH_INDEX_ID);

		assertEquals(SEARCH_INDEX_ID, result.getSearchIndexId());
		assertNull(result.getState());
		assertNull(result.getErrorMessage());
	}

	@Test
	void testGetSearchIndexStatusWithAdminBypassesAclCheck() {
		UserInfo admin = new UserInfo(true);
		admin.setId(USER_ID);
		when(userManager.getUserInfo(USER_ID)).thenReturn(admin);
		when(entityManager.getEntity(admin, SEARCH_INDEX_ID, SearchIndex.class)).thenReturn(new SearchIndex());
		when(statusDao.getStatus(456L)).thenReturn(Optional.empty());

		// call under test
		service.getSearchIndexStatus(USER_ID, SEARCH_INDEX_ID);

		// Admin should NOT trigger ACL check
		verifyZeroInteractions(entityAuthorizationManager);
	}

	@Test
	void testGetSearchIndexStatusWithUnauthorizedUser() {
		UserInfo user = new UserInfo(false);
		user.setId(USER_ID);
		when(userManager.getUserInfo(USER_ID)).thenReturn(user);
		when(entityManager.getEntity(user, SEARCH_INDEX_ID, SearchIndex.class)).thenReturn(new SearchIndex());
		when(entityAuthorizationManager.hasAccess(user, SEARCH_INDEX_ID, ACCESS_TYPE.READ))
				.thenReturn(AuthorizationStatus.accessDenied("no access"));

		// call under test
		assertThrows(UnauthorizedException.class,
				() -> service.getSearchIndexStatus(USER_ID, SEARCH_INDEX_ID));

		verifyZeroInteractions(statusDao);
	}

	@Test
	void testGetSearchIndexStatusWithNullUserId() {
		// call under test
		assertThrows(IllegalArgumentException.class,
				() -> service.getSearchIndexStatus(null, SEARCH_INDEX_ID));

		verifyZeroInteractions(userManager);
	}

	@Test
	void testGetSearchIndexStatusWithNullSearchIndexId() {
		// call under test
		assertThrows(IllegalArgumentException.class,
				() -> service.getSearchIndexStatus(USER_ID, null));

		verifyZeroInteractions(userManager);
	}
}
