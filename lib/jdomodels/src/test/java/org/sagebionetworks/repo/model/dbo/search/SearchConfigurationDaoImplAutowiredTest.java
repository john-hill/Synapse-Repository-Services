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
import org.sagebionetworks.repo.model.search.table.TextAnalyzerSettings;
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
	private String org1Name;
	private String org1Id;
	private String org2Name;
	private String org2Id;

	@BeforeEach
	public void before() {
		adminUserId = AuthorizationConstants.BOOTSTRAP_PRINCIPAL.THE_ADMIN_USER.getPrincipalId();
		searchConfigurationDao.truncateAll();
		synonymSetDao.truncateAll();
		columnAnalyzerOverrideDao.truncateAll();
		textAnalyzerDao.truncateAll();

		Organization org1 = organizationDao.createOrganization("test-org-" + UUID.randomUUID(), adminUserId);
		org1Id = org1.getId();
		org1Name = org1.getName();

		Organization org2 = organizationDao.createOrganization("test-org-" + UUID.randomUUID(), adminUserId);
		org2Id = org2.getId();
		org2Name = org2.getName();
	}

	@AfterEach
	public void after() {
		searchConfigurationDao.truncateAll();
		synonymSetDao.truncateAll();
		columnAnalyzerOverrideDao.truncateAll();
		textAnalyzerDao.truncateAll();
		if (org1Id != null) {
			organizationDao.deleteOrganization(org1Id);
		}
		if (org2Id != null) {
			organizationDao.deleteOrganization(org2Id);
		}
	}

	@Test
	public void testCreateAndGetWithReferencedEntities() {
		TextAnalyzer analyzer = textAnalyzerDao.create(newTextAnalyzer(org1Name, "analyzer-1"), adminUserId);
		SynonymSet ss = synonymSetDao.create(adminUserId, newSynonymSet(org1Name, "syn-set-1"));

		SearchConfiguration toCreate = newConfig(org1Name, "test-create", "A test config");
		toCreate.setDefaultAnalyzerId(analyzer.getId());
		toCreate.setSynonymSetIds(Arrays.asList(ss.getId()));

		// call under test
		SearchConfiguration created = searchConfigurationDao.create(adminUserId, toCreate);

		assertNotNull(created.getId());
		assertNotNull(created.getEtag());
		assertEquals("test-create", created.getName());
		assertEquals("A test config", created.getDescription());
		assertEquals(org1Name, created.getOrganizationName());
		assertEquals(analyzer.getId(), created.getDefaultAnalyzerId());
		assertEquals(Arrays.asList(ss.getId()), created.getSynonymSetIds());
		assertNotNull(created.getCreatedOn());
		assertNotNull(created.getModifiedOn());
		assertEquals(adminUserId.toString(), created.getCreatedBy());
		assertEquals(adminUserId.toString(), created.getModifiedBy());

		// call under test
		Optional<SearchConfiguration> fetched = searchConfigurationDao.get(created.getId());

		assertTrue(fetched.isPresent());
		assertEquals(created, fetched.get());
	}

	@Test
	public void testGetWithNonExistentId() {
		// call under test
		Optional<SearchConfiguration> result = searchConfigurationDao.get("999999");

		assertFalse(result.isPresent());
	}

	@Test
	public void testCreateWithDuplicateNameInSameOrg() {
		searchConfigurationDao.create(adminUserId, newConfig(org1Name, "duplicate-name", "First"));

		SearchConfiguration second = newConfig(org1Name, "duplicate-name", "Second");

		// call under test
		assertThrows(IllegalArgumentException.class, () -> searchConfigurationDao.create(adminUserId, second));
	}

	@Test
	public void testUpdateWithModifiedReferencesAndDescription() {
		TextAnalyzer analyzer1 = textAnalyzerDao.create(newTextAnalyzer(org1Name, "analyzer-orig"), adminUserId);
		TextAnalyzer analyzer2 = textAnalyzerDao.create(newTextAnalyzer(org1Name, "analyzer-new"), adminUserId);
		SynonymSet ss1 = synonymSetDao.create(adminUserId, newSynonymSet(org1Name, "syn-orig"));
		SynonymSet ss2 = synonymSetDao.create(adminUserId, newSynonymSet(org1Name, "syn-new"));

		SearchConfiguration toCreate = newConfig(org1Name, "test-update", "original");
		toCreate.setDefaultAnalyzerId(analyzer1.getId());
		toCreate.setSynonymSetIds(Arrays.asList(ss1.getId()));

		SearchConfiguration created = searchConfigurationDao.create(adminUserId, toCreate);
		String originalEtag = created.getEtag();

		created.setName("test-update-renamed");
		created.setDescription("updated");
		created.setDefaultAnalyzerId(analyzer2.getId());
		created.setSynonymSetIds(Arrays.asList(ss2.getId()));

		// call under test
		SearchConfiguration updated = searchConfigurationDao.update(adminUserId, created);

		assertEquals("test-update-renamed", updated.getName());
		assertEquals("updated", updated.getDescription());
		assertNotEquals(originalEtag, updated.getEtag());
		assertEquals(analyzer2.getId(), updated.getDefaultAnalyzerId());
		assertEquals(Arrays.asList(ss2.getId()), updated.getSynonymSetIds());
	}

	@Test
	public void testUpdateWithStaleEtagThrows() {
		SearchConfiguration created = searchConfigurationDao.create(adminUserId, newConfig(org1Name, "test-occ", null));

		created.setDescription("first update");
		searchConfigurationDao.update(adminUserId, created);

		created.setDescription("stale update");

		// call under test
		assertThrows(ConflictingUpdateException.class, () -> searchConfigurationDao.update(adminUserId, created));
	}

	@Test
	public void testDeleteWithExistingConfig() {
		SearchConfiguration created = searchConfigurationDao.create(adminUserId, newConfig(org1Name, "test-delete", null));
		assertTrue(searchConfigurationDao.get(created.getId()).isPresent());

		// call under test
		searchConfigurationDao.delete(created.getId());

		assertFalse(searchConfigurationDao.get(created.getId()).isPresent());
	}

	@Test
	public void testListWithMultipleOrganizations() {
		// Create 2 configs in org1 (names chosen so alphabetical order is deterministic)
		SearchConfiguration org1A = searchConfigurationDao.create(adminUserId, newConfig(org1Name, "aaa-config", "first"));
		SearchConfiguration org1B = searchConfigurationDao.create(adminUserId, newConfig(org1Name, "bbb-config", "second"));

		// Create 2 configs in org2
		SearchConfiguration org2A = searchConfigurationDao.create(adminUserId, newConfig(org2Name, "ccc-config", "third"));
		SearchConfiguration org2B = searchConfigurationDao.create(adminUserId, newConfig(org2Name, "ddd-config", "fourth"));

		// call under test — list by org1
		List<SearchConfiguration> org1Results = searchConfigurationDao.list(org1Name, 10, 0);

		assertEquals(2, org1Results.size());
		assertEquals(org1A.getId(), org1Results.get(0).getId());
		assertEquals(org1B.getId(), org1Results.get(1).getId());

		// call under test — list by org2
		List<SearchConfiguration> org2Results = searchConfigurationDao.list(org2Name, 10, 0);

		assertEquals(2, org2Results.size());
		assertEquals(org2A.getId(), org2Results.get(0).getId());
		assertEquals(org2B.getId(), org2Results.get(1).getId());

		// call under test — list all (ordered by NAME ASC across all orgs)
		List<SearchConfiguration> allResults = searchConfigurationDao.listAll(10, 0);

		assertEquals(4, allResults.size());
		assertEquals(org1A.getId(), allResults.get(0).getId());
		assertEquals(org1B.getId(), allResults.get(1).getId());
		assertEquals(org2A.getId(), allResults.get(2).getId());
		assertEquals(org2B.getId(), allResults.get(3).getId());
	}

	@Test
	public void testCreateWithSynonymSetIds() {
		SynonymSet ss = synonymSetDao.create(adminUserId, newSynonymSet(org1Name, "syn-set-1"));

		SearchConfiguration config = newConfig(org1Name, "with-synonyms", null);
		config.setSynonymSetIds(Arrays.asList(ss.getId()));

		// call under test
		SearchConfiguration created = searchConfigurationDao.create(adminUserId, config);

		assertEquals(Arrays.asList(ss.getId()), created.getSynonymSetIds());

		SearchConfiguration fetched = searchConfigurationDao.get(created.getId()).get();
		assertEquals(Arrays.asList(ss.getId()), fetched.getSynonymSetIds());
	}

	@Test
	public void testCreateWithColumnAnalyzerOverrideIds() {
		TextAnalyzer analyzer = textAnalyzerDao.create(newTextAnalyzer(org1Name, "analyzer-1"), adminUserId);
		ColumnAnalyzerOverride override = columnAnalyzerOverrideDao.create(adminUserId, newColumnAnalyzerOverride(org1Name, "override-1", analyzer.getId()));

		SearchConfiguration config = newConfig(org1Name, "with-overrides", null);
		config.setColumnAnalyzerOverrideIds(Arrays.asList(override.getId()));

		// call under test
		SearchConfiguration created = searchConfigurationDao.create(adminUserId, config);

		assertEquals(Arrays.asList(override.getId()), created.getColumnAnalyzerOverrideIds());
	}

	@Test
	public void testCreateWithDefaultAnalyzerId() {
		TextAnalyzer analyzer = textAnalyzerDao.create(newTextAnalyzer(org1Name, "default-analyzer"), adminUserId);

		SearchConfiguration config = newConfig(org1Name, "with-default-analyzer", null);
		config.setDefaultAnalyzerId(analyzer.getId());

		// call under test
		SearchConfiguration created = searchConfigurationDao.create(adminUserId, config);

		assertEquals(analyzer.getId(), created.getDefaultAnalyzerId());

		SearchConfiguration fetched = searchConfigurationDao.get(created.getId()).get();
		assertEquals(analyzer.getId(), fetched.getDefaultAnalyzerId());
	}

	@Test
	public void testUpdateWithReplacedJunctionRows() {
		SynonymSet ss1 = synonymSetDao.create(adminUserId, newSynonymSet(org1Name, "syn-set-a"));
		SynonymSet ss2 = synonymSetDao.create(adminUserId, newSynonymSet(org1Name, "syn-set-b"));

		SearchConfiguration config = newConfig(org1Name, "junction-update", null);
		config.setSynonymSetIds(Arrays.asList(ss1.getId()));
		SearchConfiguration created = searchConfigurationDao.create(adminUserId, config);
		assertEquals(Arrays.asList(ss1.getId()), created.getSynonymSetIds());

		created.setSynonymSetIds(Arrays.asList(ss2.getId()));

		// call under test
		SearchConfiguration updated = searchConfigurationDao.update(adminUserId, created);

		assertEquals(Arrays.asList(ss2.getId()), updated.getSynonymSetIds());
	}

	@Test
	public void testDeleteWithJunctionRowCascade() {
		SynonymSet ss = synonymSetDao.create(adminUserId, newSynonymSet(org1Name, "cascade-test"));

		SearchConfiguration config = newConfig(org1Name, "cascade-delete", null);
		config.setSynonymSetIds(Arrays.asList(ss.getId()));
		SearchConfiguration created = searchConfigurationDao.create(adminUserId, config);

		// call under test
		searchConfigurationDao.delete(created.getId());

		assertFalse(searchConfigurationDao.get(created.getId()).isPresent());
		assertTrue(synonymSetDao.get(ss.getId()).isPresent());
	}

	@Test
	public void testUniquenessConstraintWithMaxLengthNames() {
		// ORGANIZATION_NAME is varchar(250) ascii, NAME is varchar(256).
		char[] orgChars = new char[250];
		java.util.Arrays.fill(orgChars, 'o');
		String maxOrgName = new String(orgChars);
		Organization maxOrg = organizationDao.createOrganization(maxOrgName, adminUserId);
		String maxOrgId = maxOrg.getId();

		try {
			char[] nameChars = new char[256];
			java.util.Arrays.fill(nameChars, 'a');
			String nameA = new String(nameChars);
			nameChars[255] = 'b';
			String nameB = new String(nameChars);

			SearchConfiguration createdA = searchConfigurationDao.create(adminUserId, newConfig(maxOrgName, nameA, "desc-a"));
			SearchConfiguration createdB = searchConfigurationDao.create(adminUserId, newConfig(maxOrgName, nameB, "desc-b"));

			// Verify each config retained its own data (not silently overwritten by index truncation)
			SearchConfiguration fetchedA = searchConfigurationDao.get(createdA.getId()).get();
			assertEquals(nameA, fetchedA.getName());
			assertEquals("desc-a", fetchedA.getDescription());
			assertEquals(maxOrgName, fetchedA.getOrganizationName());

			SearchConfiguration fetchedB = searchConfigurationDao.get(createdB.getId()).get();
			assertEquals(nameB, fetchedB.getName());
			assertEquals("desc-b", fetchedB.getDescription());
			assertEquals(maxOrgName, fetchedB.getOrganizationName());

			// call under test
			assertThrows(IllegalArgumentException.class,
					() -> searchConfigurationDao.create(adminUserId, newConfig(maxOrgName, nameA, null)));
		} finally {
			searchConfigurationDao.truncateAll();
			organizationDao.deleteOrganization(maxOrgId);
		}
	}

	@Test
	public void testTruncateAll() {
		searchConfigurationDao.create(adminUserId, newConfig(org1Name, "truncate-a", null));
		searchConfigurationDao.create(adminUserId, newConfig(org1Name, "truncate-b", null));
		assertTrue(searchConfigurationDao.listAll(10, 0).size() >= 2);

		// call under test
		searchConfigurationDao.truncateAll();

		assertEquals(0, searchConfigurationDao.listAll(10, 0).size());
	}

	private SearchConfiguration newConfig(String organizationName, String name, String description) {
		SearchConfiguration config = new SearchConfiguration();
		config.setName(name);
		config.setDescription(description);
		config.setOrganizationName(organizationName);
		return config;
	}

	private SynonymSet newSynonymSet(String organizationName, String name) {
		SynonymSet set = new SynonymSet();
		set.setName(name);
		set.setOrganizationName(organizationName);
		return set;
	}

	private TextAnalyzer newTextAnalyzer(String organizationName, String name) {
		TextAnalyzer analyzer = new TextAnalyzer();
		analyzer.setName(name);
		analyzer.setOrganizationName(organizationName);
		TextAnalyzerSettings settings = new TextAnalyzerSettings();
		settings.setTokenizer("standard");
		analyzer.setSettings(settings);
		return analyzer;
	}

	private ColumnAnalyzerOverride newColumnAnalyzerOverride(String organizationName, String name, String analyzerId) {
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
