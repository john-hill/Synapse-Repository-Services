package org.sagebionetworks.table.cluster.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.sagebionetworks.repo.model.search.table.SearchIndexState;
import org.sagebionetworks.repo.model.search.table.SearchIndexStatus;
import org.sagebionetworks.table.cluster.ConnectionFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(locations = { "classpath:table-cluster-spb.xml" })
public class SearchIndexStatusDaoImplTest {

	@Autowired
	private ConnectionFactory connectionFactory;

	private SearchIndexStatusDao dao;

	@BeforeEach
	void setUp() {
		dao = connectionFactory.getSearchIndexStatusDao();
		dao.truncateAll();
	}

	@Test
	void testCreateAndGetState() {
		dao.createOrUpdate(42L, SearchIndexState.CREATING, null, null);
		Optional<SearchIndexState> state = dao.getState(42L);
		assertTrue(state.isPresent());
		assertEquals(SearchIndexState.CREATING, state.get());
	}

	@Test
	void testUpsertUpdatesExisting() {
		dao.createOrUpdate(42L, SearchIndexState.CREATING, null, null);
		dao.createOrUpdate(42L, SearchIndexState.ACTIVE, null, null);
		assertEquals(SearchIndexState.ACTIVE, dao.getState(42L).orElseThrow());
	}

	@Test
	void testCreateOrUpdateWithAppliedConfig() {
		String configJson = "{\"mappings\": {}, \"settings\": {\"analysis\": {}}}";
		dao.createOrUpdate(42L, SearchIndexState.ACTIVE, null, configJson);
		assertEquals(SearchIndexState.ACTIVE, dao.getState(42L).orElseThrow());
		assertTrue(dao.getAppliedConfiguration(42L).isPresent());
		assertEquals(configJson, dao.getAppliedConfiguration(42L).get());
	}

	@Test
	void testGetAppliedConfigurationEmpty() {
		assertTrue(dao.getAppliedConfiguration(999L).isEmpty());
	}

	@Test
	void testUpsertWithErrorMessage() {
		dao.createOrUpdate(42L, SearchIndexState.FAILED, "Something broke", null);
		assertEquals(SearchIndexState.FAILED, dao.getState(42L).orElseThrow());
	}

	@Test
	void testExistsReturnsFalseForMissing() {
		assertFalse(dao.exists(999L));
	}

	@Test
	void testExistsReturnsTrueAfterCreate() {
		dao.createOrUpdate(42L, SearchIndexState.CREATING, null, null);
		assertTrue(dao.exists(42L));
	}

	@Test
	void testDeleteRemovesRow() {
		dao.createOrUpdate(42L, SearchIndexState.ACTIVE, null, null);
		dao.delete(42L);
		assertFalse(dao.exists(42L));
	}

	@Test
	void testGetStateMissingReturnsEmpty() {
		assertTrue(dao.getState(999L).isEmpty());
	}

	@Test
	void testGetStatusWithFailedState() {
		dao.createOrUpdate(42L, SearchIndexState.FAILED, "Something broke", null);
		// call under test
		Optional<SearchIndexStatus> result = dao.getStatus(42L);
		assertTrue(result.isPresent());
		SearchIndexStatus status = result.get();
		assertEquals("syn42", status.getSearchIndexId());
		assertEquals(SearchIndexState.FAILED, status.getState());
		assertEquals("Something broke", status.getErrorMessage());
		assertNull(status.getAppliedConfiguration());
		assertNotNull(status.getChangedOn());
		assertNull(status.getLastBuildOn());
	}

	@Test
	void testGetStatusWithActiveStateIncludesAppliedConfiguration() {
		String configJson = "{\"settings\":{\"analysis\":{}},\"mappings\":{}}";
		dao.createOrUpdate(42L, SearchIndexState.ACTIVE, null, configJson);
		// call under test
		Optional<SearchIndexStatus> result = dao.getStatus(42L);
		assertTrue(result.isPresent());
		SearchIndexStatus status = result.get();
		assertEquals(SearchIndexState.ACTIVE, status.getState());
		assertNull(status.getErrorMessage());
		assertNotNull(status.getAppliedConfiguration());
		// MySQL JSON column may reorder keys
		assertTrue(status.getAppliedConfiguration().contains("mappings"));
		assertTrue(status.getAppliedConfiguration().contains("settings"));
		assertNotNull(status.getChangedOn());
		assertNotNull(status.getLastBuildOn());
	}

	@Test
	void testLastBuildOnPreservedAfterFailedRebuild() {
		dao.createOrUpdate(42L, SearchIndexState.ACTIVE, null, "{}");
		SearchIndexStatus active = dao.getStatus(42L).orElseThrow();
		assertNotNull(active.getLastBuildOn());

		// A subsequent FAILED update should preserve the previous lastBuildOn
		dao.createOrUpdate(42L, SearchIndexState.FAILED, "rebuild failed", null);
		// call under test
		SearchIndexStatus failed = dao.getStatus(42L).orElseThrow();
		assertEquals(SearchIndexState.FAILED, failed.getState());
		assertNotNull(failed.getLastBuildOn());
		assertEquals(active.getLastBuildOn(), failed.getLastBuildOn());
	}

	@Test
	void testGetStatusMissingReturnsEmpty() {
		// call under test
		assertTrue(dao.getStatus(999L).isEmpty());
	}
}
