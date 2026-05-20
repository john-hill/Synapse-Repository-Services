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
import org.sagebionetworks.repo.model.search.table.SearchConfigBinding;
import org.sagebionetworks.repo.model.search.table.SearchConfiguration;
import org.sagebionetworks.repo.model.search.table.SynonymSet;
import org.sagebionetworks.repo.model.search.table.TextAnalyzer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(locations = { "classpath:jdomodels-test-context.xml" })
public class SearchConfigurationDaoImplAutowiredTest {

	private static final String SYNSET_DEFINITION =
			"{\"type\":\"synonym_graph\",\"synonyms\":[\"cancer, tumor\"]}";

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
	private String defaultAnalyzerQName;

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

		// Every SearchConfiguration in the new schema needs both default analyzers set, so
		// pre-create one analyzer in org1 that the helper reuses.
		TextAnalyzer defaultAnalyzer = textAnalyzerDao.create(newTextAnalyzer(org1Name, "default_analyzer"), adminUserId);
		defaultAnalyzerQName = org1Name + "-" + defaultAnalyzer.getName();
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
	public void testCreateAndGetWithDefaultAnalyzer() {
		SearchConfiguration toCreate = newConfig(org1Name, "test_create", "A test config");

		// call under test
		SearchConfiguration created = searchConfigurationDao.create(adminUserId, toCreate);

		assertNotNull(created.getId());
		assertNotNull(created.getEtag());
		assertEquals("test_create", created.getName());
		assertEquals("A test config", created.getDescription());
		assertEquals(org1Name, created.getOrganizationName());
		assertEquals(defaultAnalyzerQName, created.getDefaultAnalyzer());
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
	public void testUpdateWithModifiedDefaultsAndDescription() {
		TextAnalyzer analyzer2 = textAnalyzerDao.create(newTextAnalyzer(org1Name, "analyzer_new"), adminUserId);
		String analyzer2Name = org1Name + "-" + analyzer2.getName();

		SearchConfiguration toCreate = newConfig(org1Name, "test_update", "original");
		SearchConfiguration created = searchConfigurationDao.create(adminUserId, toCreate);
		String originalEtag = created.getEtag();

		created.setName("test_update_renamed");
		created.setDescription("updated");
		created.setDefaultAnalyzer(analyzer2Name);

		// call under test
		SearchConfiguration updated = searchConfigurationDao.update(adminUserId, created);

		assertEquals("test_update_renamed", updated.getName());
		assertEquals("updated", updated.getDescription());
		assertNotEquals(originalEtag, updated.getEtag());
		assertEquals(analyzer2Name, updated.getDefaultAnalyzer());
	}

	@Test
	public void testUpdateWithStaleEtagThrows() {
		SearchConfiguration created = searchConfigurationDao.create(adminUserId, newConfig(org1Name, "test_occ", null));

		created.setDescription("first update");
		searchConfigurationDao.update(adminUserId, created);

		created.setDescription("stale update");

		// call under test
		assertThrows(ConflictingUpdateException.class, () -> searchConfigurationDao.update(adminUserId, created));
	}

	@Test
	public void testDeleteWithExistingConfig() {
		SearchConfiguration created = searchConfigurationDao.create(adminUserId, newConfig(org1Name, "test_delete", null));
		assertTrue(searchConfigurationDao.get(created.getId()).isPresent());

		// call under test
		searchConfigurationDao.delete(created.getId());

		assertFalse(searchConfigurationDao.get(created.getId()).isPresent());
	}

	@Test
	public void testListWithMultipleOrganizations() {
		// Create 2 configs in org1 (names chosen so alphabetical order is deterministic)
		SearchConfiguration org1A = searchConfigurationDao.create(adminUserId, newConfig(org1Name, "aaa_config", "first"));
		SearchConfiguration org1B = searchConfigurationDao.create(adminUserId, newConfig(org1Name, "bbb_config", "second"));

		// Org2 needs its own default analyzer to satisfy the NOT NULL default-analyzer constraint
		TextAnalyzer org2Analyzer = textAnalyzerDao.create(newTextAnalyzer(org2Name, "default_analyzer"), adminUserId);
		String org2DefaultQName = org2Name + "-" + org2Analyzer.getName();

		SearchConfiguration org2A = searchConfigurationDao.create(adminUserId,
				newConfig(org2Name, "ccc_config", "third").setDefaultAnalyzer(org2DefaultQName));
		SearchConfiguration org2B = searchConfigurationDao.create(adminUserId,
				newConfig(org2Name, "ddd_config", "fourth").setDefaultAnalyzer(org2DefaultQName));

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
	public void testCreateWithColumnAnalyzerOverrides() {
		TextAnalyzer analyzer = textAnalyzerDao.create(newTextAnalyzer(org1Name, "analyzer_1"), adminUserId);
		String analyzerQualifiedName = org1Name + "-" + analyzer.getName();
		ColumnAnalyzerOverride override = columnAnalyzerOverrideDao.create(adminUserId,
				newColumnAnalyzerOverride(org1Name, "override_1", analyzerQualifiedName));
		String overrideQualifiedName = org1Name + "-" + override.getName();

		SearchConfiguration config = newConfig(org1Name, "with_overrides", null)
				.setColumnAnalyzerOverrides(Arrays.asList(overrideQualifiedName));

		// call under test
		SearchConfiguration created = searchConfigurationDao.create(adminUserId, config);

		assertEquals(Arrays.asList(overrideQualifiedName), created.getColumnAnalyzerOverrides());
	}

	@Test
	public void testDeleteDoesNotCascadeToReferencedResources() {
		// SynonymSets are referenced from TextAnalyzers (not SearchConfigurations) under the
		// new shape; SearchConfiguration delete should leave them alone regardless.
		SynonymSet ss = synonymSetDao.create(adminUserId,
				newSynonymSet(org1Name, "cascade_test").setDefinition(SYNSET_DEFINITION));

		SearchConfiguration created = searchConfigurationDao.create(adminUserId, newConfig(org1Name, "cascade_delete", null));

		// call under test
		searchConfigurationDao.delete(created.getId());

		assertFalse(searchConfigurationDao.get(created.getId()).isPresent());
		assertTrue(synonymSetDao.get(ss.getId()).isPresent());
	}

	@Test
	public void testTruncateAll() {
		searchConfigurationDao.create(adminUserId, newConfig(org1Name, "truncate_a", null));
		searchConfigurationDao.create(adminUserId, newConfig(org1Name, "truncate_b", null));
		assertTrue(searchConfigurationDao.listAll(10, 0).size() >= 2);

		// call under test
		searchConfigurationDao.truncateAll();

		assertEquals(0, searchConfigurationDao.listAll(10, 0).size());
	}

	// --- Binding tests ---

	@Test
	public void testBindSearchConfigToObjectWithNewBinding() {
		SearchConfiguration config = searchConfigurationDao.create(adminUserId, newConfig(org1Name, "bind_test", null));
		Long configId = Long.parseLong(config.getId());
		Long objectId = 999L;

		// call under test
		searchConfigurationDao.bindSearchConfigToObject(configId, objectId, "entity", adminUserId);

		Optional<SearchConfigBinding> result = searchConfigurationDao.getSearchConfigBindingForObject(objectId, "entity");
		assertTrue(result.isPresent());
		SearchConfigBinding binding = result.get();
		assertNotNull(binding.getBindId());
		assertEquals(config.getId(), binding.getSearchConfigurationId());
		assertEquals(String.valueOf(objectId), binding.getObjectId());
		assertEquals("entity", binding.getObjectType());
		assertEquals(adminUserId.toString(), binding.getCreatedBy());
		assertNotNull(binding.getCreatedOn());
	}

	@Test
	public void testBindSearchConfigToObjectWithReplacesExisting() {
		SearchConfiguration configA = searchConfigurationDao.create(adminUserId, newConfig(org1Name, "bind_replace_a", null));
		SearchConfiguration configB = searchConfigurationDao.create(adminUserId, newConfig(org1Name, "bind_replace_b", null));
		Long objectId = 888L;

		searchConfigurationDao.bindSearchConfigToObject(Long.parseLong(configA.getId()), objectId, "entity", adminUserId);
		Optional<SearchConfigBinding> first = searchConfigurationDao.getSearchConfigBindingForObject(objectId, "entity");
		assertEquals(configA.getId(), first.get().getSearchConfigurationId());

		// call under test
		searchConfigurationDao.bindSearchConfigToObject(Long.parseLong(configB.getId()), objectId, "entity", adminUserId);

		Optional<SearchConfigBinding> result = searchConfigurationDao.getSearchConfigBindingForObject(objectId, "entity");
		assertTrue(result.isPresent());
		assertEquals(configB.getId(), result.get().getSearchConfigurationId());
		assertEquals(first.get().getBindId(), result.get().getBindId());
	}

	@Test
	public void testGetSearchConfigBindingForObjectWithNoBinding() {
		// call under test
		Optional<SearchConfigBinding> result = searchConfigurationDao.getSearchConfigBindingForObject(777L, "entity");

		assertFalse(result.isPresent());
	}

	@Test
	public void testClearSearchConfigBindingWithExistingBinding() {
		SearchConfiguration config = searchConfigurationDao.create(adminUserId, newConfig(org1Name, "bind_clear", null));
		Long objectId = 666L;
		searchConfigurationDao.bindSearchConfigToObject(Long.parseLong(config.getId()), objectId, "entity", adminUserId);
		assertTrue(searchConfigurationDao.getSearchConfigBindingForObject(objectId, "entity").isPresent());

		// call under test
		searchConfigurationDao.clearSearchConfigBinding(objectId, "entity");

		assertFalse(searchConfigurationDao.getSearchConfigBindingForObject(objectId, "entity").isPresent());
	}

	@Test
	public void testClearSearchConfigBindingWithNoBinding() {
		// call under test — should be idempotent (no error)
		searchConfigurationDao.clearSearchConfigBinding(555L, "entity");

		assertFalse(searchConfigurationDao.getSearchConfigBindingForObject(555L, "entity").isPresent());
	}

	@Test
	public void testBindSearchConfigToObjectWithNonExistentConfig() {
		Long nonExistentConfigId = 999999L;
		Long objectId = 444L;

		// call under test — no FK constraint, so this should succeed
		searchConfigurationDao.bindSearchConfigToObject(nonExistentConfigId, objectId, "entity", adminUserId);

		Optional<SearchConfigBinding> result = searchConfigurationDao.getSearchConfigBindingForObject(objectId, "entity");
		assertTrue(result.isPresent());
		assertEquals(String.valueOf(nonExistentConfigId), result.get().getSearchConfigurationId());
	}

	private SearchConfiguration newConfig(String organizationName, String name, String description) {
		return new SearchConfiguration()
				.setName(name)
				.setDescription(description)
				.setOrganizationName(organizationName)
				.setDefaultAnalyzer(defaultAnalyzerQName);
	}

	private TextAnalyzer newTextAnalyzer(String organizationName, String name) {
		return new TextAnalyzer()
				.setName(name)
				.setOrganizationName(organizationName)
				.setSettings("{\"analyzer\":{\"default\":{\"type\":\"custom\",\"tokenizer\":\"standard\"}}}");
	}

	private SynonymSet newSynonymSet(String organizationName, String name) {
		return new SynonymSet()
				.setName(name)
				.setOrganizationName(organizationName);
	}

	private ColumnAnalyzerOverride newColumnAnalyzerOverride(String organizationName, String name, String analyzerQualifiedName) {
		return new ColumnAnalyzerOverride()
				.setName(name)
				.setOrganizationName(organizationName)
				.setOverrides(Collections.singletonList(new ColumnAnalyzerOverrideEntry()
						.setColumnName("testColumn")
						.setAnalyzer(analyzerQualifiedName)));
	}
}
