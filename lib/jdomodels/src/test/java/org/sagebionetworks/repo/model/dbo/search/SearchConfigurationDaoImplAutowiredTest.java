package org.sagebionetworks.repo.model.dbo.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.sagebionetworks.repo.model.AuthorizationConstants;
import org.sagebionetworks.repo.model.ConflictingUpdateException;
import org.sagebionetworks.repo.model.dbo.schema.OrganizationDao;
import org.sagebionetworks.repo.model.schema.Organization;
import org.sagebionetworks.repo.model.search.table.ColumnAnalyzerOverride;
import org.sagebionetworks.repo.model.search.table.ColumnAnalyzerOverrideEntry;
import org.sagebionetworks.repo.model.search.table.SearchConfiguration;
import org.sagebionetworks.repo.model.search.table.SynonymSet;
import org.sagebionetworks.repo.model.search.table.TextAnalyzer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(locations = { "classpath:jdomodels-test-context.xml" })
public class SearchConfigurationDaoImplAutowiredTest {

	@Autowired
	private SearchConfigurationDao searchConfigurationDao;

	@Autowired
	private OrganizationDao organizationDao;

	@Autowired
	private TextAnalyzerDao textAnalyzerDao;

	@Autowired
	private SynonymSetDao synonymSetDao;

	@Autowired
	private ColumnAnalyzerOverrideDao columnAnalyzerOverrideDao;

	private Long adminUserId;
	private String organizationName;
	private String organizationId;

	@BeforeEach
	public void before() {
		adminUserId = AuthorizationConstants.BOOTSTRAP_PRINCIPAL.THE_ADMIN_USER.getPrincipalId();
		searchConfigurationDao.truncateAll();
		synonymSetDao.truncateAll();
		columnAnalyzerOverrideDao.truncateAll();
		textAnalyzerDao.truncateAll();
		Organization org = organizationDao.createOrganization("test-org-" + UUID.randomUUID(), adminUserId);
		organizationId = org.getId();
		organizationName = org.getName();
	}

	@AfterEach
	public void after() {
		searchConfigurationDao.truncateAll();
		synonymSetDao.truncateAll();
		columnAnalyzerOverrideDao.truncateAll();
		textAnalyzerDao.truncateAll();
		if (organizationId != null) {
			organizationDao.deleteOrganization(organizationId);
		}
	}

	@Test
	public void testCreateAndGet() {
		SearchConfiguration created = searchConfigurationDao.create(adminUserId, newConfig("test-create", "A test config"));

		assertNotNull(created.getId());
		assertNotNull(created.getEtag());
		assertEquals("test-create", created.getName());
		assertEquals("A test config", created.getDescription());
		assertEquals(organizationName, created.getOrganizationName());
		assertNotNull(created.getCreatedOn());
		assertNotNull(created.getModifiedOn());
		assertEquals(adminUserId.toString(), created.getCreatedBy());
		assertEquals(adminUserId.toString(), created.getModifiedBy());

		Optional<SearchConfiguration> fetched = searchConfigurationDao.get(created.getId());
		assertTrue(fetched.isPresent());
		assertEquals(created.getId(), fetched.get().getId());
		assertEquals(created.getEtag(), fetched.get().getEtag());
	}

	@Test
	public void testGetNotFound() {
		Optional<SearchConfiguration> result = searchConfigurationDao.get("999999");
		assertFalse(result.isPresent());
	}

	@Test
	public void testCreateDuplicateNameInSameOrgThrows() {
		searchConfigurationDao.create(adminUserId, newConfig("duplicate-name", "First"));

		SearchConfiguration second = newConfig("duplicate-name", "Second");
		assertThrows(IllegalArgumentException.class, () -> searchConfigurationDao.create(adminUserId, second));
	}

	@Test
	public void testUpdatePersistsChangesAndRotatesEtag() {
		SearchConfiguration created = searchConfigurationDao.create(adminUserId, newConfig("test-update", "original"));
		String originalEtag = created.getEtag();

		created.setName("test-update-renamed");
		created.setDescription("updated");

		SearchConfiguration updated = searchConfigurationDao.update(adminUserId, created);

		assertEquals("test-update-renamed", updated.getName());
		assertEquals("updated", updated.getDescription());
		assertNotEquals(originalEtag, updated.getEtag());
	}

	@Test
	public void testUpdateWithStaleEtagThrows() {
		SearchConfiguration created = searchConfigurationDao.create(adminUserId, newConfig("test-occ", null));

		// First update succeeds and rotates the etag
		created.setDescription("first update");
		searchConfigurationDao.update(adminUserId, created);

		// Second update with the now-stale etag must fail
		created.setDescription("stale update");
		assertThrows(ConflictingUpdateException.class, () -> searchConfigurationDao.update(adminUserId, created));
	}

	@Test
	public void testDelete() {
		SearchConfiguration created = searchConfigurationDao.create(adminUserId, newConfig("test-delete", null));

		assertTrue(searchConfigurationDao.get(created.getId()).isPresent());
		searchConfigurationDao.delete(created.getId());
		assertFalse(searchConfigurationDao.get(created.getId()).isPresent());
	}

	@Test
	public void testListByOrganization() {
		searchConfigurationDao.create(adminUserId, newConfig("config-a", null));
		searchConfigurationDao.create(adminUserId, newConfig("config-b", null));

		List<SearchConfiguration> results = searchConfigurationDao.list(organizationName, 10, 0);
		assertEquals(2, results.size());
	}

	@Test
	public void testListAll() {
		searchConfigurationDao.create(adminUserId, newConfig("config-a", null));
		searchConfigurationDao.create(adminUserId, newConfig("config-b", null));

		List<SearchConfiguration> results = searchConfigurationDao.listAll(10, 0);
		assertTrue(results.size() >= 2);
	}

	@Test
	public void testCreateWithSynonymSetIds() {
		SynonymSet ss = synonymSetDao.create(adminUserId, newSynonymSet("syn-set-1"));

		SearchConfiguration config = newConfig("with-synonyms", null);
		config.setSynonymSetIds(Arrays.asList(ss.getId()));

		SearchConfiguration created = searchConfigurationDao.create(adminUserId, config);
		assertNotNull(created.getSynonymSetIds());
		assertEquals(1, created.getSynonymSetIds().size());
		assertEquals(ss.getId(), created.getSynonymSetIds().get(0));

		// Verify round-trip via get
		SearchConfiguration fetched = searchConfigurationDao.get(created.getId()).get();
		assertEquals(Arrays.asList(ss.getId()), fetched.getSynonymSetIds());
	}

	@Test
	public void testCreateWithColumnAnalyzerOverrideIds() {
		TextAnalyzer analyzer = textAnalyzerDao.create(newTextAnalyzer("analyzer-1"), adminUserId);
		ColumnAnalyzerOverride override = columnAnalyzerOverrideDao.create(adminUserId, newColumnAnalyzerOverride("override-1", analyzer.getId()));

		SearchConfiguration config = newConfig("with-overrides", null);
		config.setColumnAnalyzerOverrideIds(Arrays.asList(override.getId()));

		SearchConfiguration created = searchConfigurationDao.create(adminUserId, config);
		assertNotNull(created.getColumnAnalyzerOverrideIds());
		assertEquals(1, created.getColumnAnalyzerOverrideIds().size());
		assertEquals(override.getId(), created.getColumnAnalyzerOverrideIds().get(0));
	}

	@Test
	public void testCreateWithDefaultAnalyzerId() {
		TextAnalyzer analyzer = textAnalyzerDao.create(newTextAnalyzer("default-analyzer"), adminUserId);

		SearchConfiguration config = newConfig("with-default-analyzer", null);
		config.setDefaultAnalyzerId(analyzer.getId());

		SearchConfiguration created = searchConfigurationDao.create(adminUserId, config);
		assertEquals(analyzer.getId(), created.getDefaultAnalyzerId());

		SearchConfiguration fetched = searchConfigurationDao.get(created.getId()).get();
		assertEquals(analyzer.getId(), fetched.getDefaultAnalyzerId());
	}

	@Test
	public void testUpdateReplacesJunctionRows() {
		SynonymSet ss1 = synonymSetDao.create(adminUserId, newSynonymSet("syn-set-a"));
		SynonymSet ss2 = synonymSetDao.create(adminUserId, newSynonymSet("syn-set-b"));

		SearchConfiguration config = newConfig("junction-update", null);
		config.setSynonymSetIds(Arrays.asList(ss1.getId()));
		SearchConfiguration created = searchConfigurationDao.create(adminUserId, config);
		assertEquals(1, created.getSynonymSetIds().size());

		// Update to replace ss1 with ss2
		created.setSynonymSetIds(Arrays.asList(ss2.getId()));
		SearchConfiguration updated = searchConfigurationDao.update(adminUserId, created);
		assertEquals(1, updated.getSynonymSetIds().size());
		assertEquals(ss2.getId(), updated.getSynonymSetIds().get(0));
	}

	@Test
	public void testDeleteCascadesJunctionRows() {
		SynonymSet ss = synonymSetDao.create(adminUserId, newSynonymSet("cascade-test"));

		SearchConfiguration config = newConfig("cascade-delete", null);
		config.setSynonymSetIds(Arrays.asList(ss.getId()));
		SearchConfiguration created = searchConfigurationDao.create(adminUserId, config);

		// Delete should succeed (junction rows cascade)
		searchConfigurationDao.delete(created.getId());
		assertFalse(searchConfigurationDao.get(created.getId()).isPresent());

		// The synonym set itself should still exist
		assertTrue(synonymSetDao.get(ss.getId()).isPresent());
	}

	@Test
	public void testTruncateAll() {
		searchConfigurationDao.create(adminUserId, newConfig("truncate-a", null));
		searchConfigurationDao.create(adminUserId, newConfig("truncate-b", null));

		assertTrue(searchConfigurationDao.listAll(10, 0).size() >= 2);

		searchConfigurationDao.truncateAll();

		assertEquals(0, searchConfigurationDao.listAll(10, 0).size());
	}

	private SearchConfiguration newConfig(String name, String description) {
		SearchConfiguration config = new SearchConfiguration();
		config.setName(name);
		config.setDescription(description);
		config.setOrganizationName(organizationName);
		return config;
	}

	private SynonymSet newSynonymSet(String name) {
		SynonymSet set = new SynonymSet();
		set.setName(name);
		set.setOrganizationName(organizationName);
		return set;
	}

	private TextAnalyzer newTextAnalyzer(String name) {
		TextAnalyzer analyzer = new TextAnalyzer();
		analyzer.setName(name);
		analyzer.setOrganizationName(organizationName);
		return analyzer;
	}

	private ColumnAnalyzerOverride newColumnAnalyzerOverride(String name, String analyzerId) {
		ColumnAnalyzerOverrideEntry entry = new ColumnAnalyzerOverrideEntry();
		entry.setColumnName("testColumn");
		entry.setIndexAnalyzerId(analyzerId);
		entry.setSearchAnalyzerId(analyzerId);

		ColumnAnalyzerOverride override = new ColumnAnalyzerOverride();
		override.setName(name);
		override.setOrganizationName(organizationName);
		override.setOverrides(Collections.singletonList(entry));
		return override;
	}
}
