package org.sagebionetworks.repo.model.dbo.search;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(locations = { "classpath:jdomodels-test-context.xml" })
public class ColumnAnalyzerOverrideDaoImplAutowiredTest {

	@Autowired
	private ColumnAnalyzerOverrideDao columnAnalyzerOverrideDao;

	@Autowired
	private OrganizationDao organizationDao;

	private Long adminUserId;
	private String organizationName;
	private String organizationName2;

	@BeforeEach
	public void before() {
		adminUserId = AuthorizationConstants.BOOTSTRAP_PRINCIPAL.THE_ADMIN_USER.getPrincipalId();
		columnAnalyzerOverrideDao.truncateAll();
		Organization org = organizationDao.createOrganization("test-cao-org-" + UUID.randomUUID(), adminUserId);
		organizationName = org.getName();
		Organization org2 = organizationDao.createOrganization("test-cao-org2-" + UUID.randomUUID(), adminUserId);
		organizationName2 = org2.getName();
	}

	@AfterEach
	public void after() {
		columnAnalyzerOverrideDao.truncateAll();
		if (organizationName != null) {
			organizationDao.deleteOrganization(
				organizationDao.getOrganizationByName(organizationName).getId());
		}
		if (organizationName2 != null) {
			organizationDao.deleteOrganization(
				organizationDao.getOrganizationByName(organizationName2).getId());
		}
	}

	@Test
	public void testCreateAndGet() {
		ColumnAnalyzerOverride override = newOverride("test_create", "A test override");

		// call under test
		ColumnAnalyzerOverride created = columnAnalyzerOverrideDao.create(adminUserId, override);

		assertNotNull(created.getId());
		assertNotNull(created.getEtag());
		assertEquals(organizationName, created.getOrganizationName());
		assertEquals("test_create", created.getName());
		assertEquals("A test override", created.getDescription());
		assertNotNull(created.getOverrides());
		assertEquals(1, created.getOverrides().size());
		assertEquals("column1", created.getOverrides().get(0).getColumnName());
		assertNotNull(created.getCreatedOn());
		assertNotNull(created.getModifiedOn());
		assertEquals(adminUserId.toString(), created.getCreatedBy());
		assertEquals(adminUserId.toString(), created.getModifiedBy());

		// Verify get returns the same data
		Optional<ColumnAnalyzerOverride> fetched = columnAnalyzerOverrideDao.get(created.getId());
		assertTrue(fetched.isPresent());
		assertEquals(created.getId(), fetched.get().getId());
		assertEquals(created.getEtag(), fetched.get().getEtag());
		assertEquals(created.getOrganizationName(), fetched.get().getOrganizationName());
		assertEquals(created.getName(), fetched.get().getName());
	}

	@Test
	public void testCreateVerifiesOverridesRoundTrip() {
		ColumnAnalyzerOverrideEntry entry1 = new ColumnAnalyzerOverrideEntry();
		entry1.setColumnName("diagnosis");
		entry1.setAnalyzer(new org.json.JSONObject().put("$ref", "org.sagebionetworks-SCIENTIFIC"));

		ColumnAnalyzerOverrideEntry entry2 = new ColumnAnalyzerOverrideEntry();
		entry2.setColumnName("tissue");
		entry2.setAnalyzer(new org.json.JSONObject().put("$ref", "org.sagebionetworks-IDENTIFIER"));

		ColumnAnalyzerOverride override = new ColumnAnalyzerOverride();
		override.setName("multi_entry");
		override.setOrganizationName(organizationName);
		override.setOverrides(Arrays.asList(entry1, entry2));

		// call under test
		ColumnAnalyzerOverride created = columnAnalyzerOverrideDao.create(adminUserId, override);

		List<ColumnAnalyzerOverrideEntry> overrides = created.getOverrides();
		assertEquals(2, overrides.size());
		assertEquals("diagnosis", overrides.get(0).getColumnName());
		// Because the DAO reads the typed POJO list back through the schema-to-pojo
		// adapter, the opaque-Object analyzer field arrives as a JSONObjectAdapter rather
		// than a JSONObject. Compare semantically through Jackson — toString() on either
		// shape yields canonical JSON.
		com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
		try {
			assertEquals(mapper.readTree("{\"$ref\":\"org.sagebionetworks-SCIENTIFIC\"}"),
					mapper.readTree(String.valueOf(overrides.get(0).getAnalyzer())));
			assertEquals("tissue", overrides.get(1).getColumnName());
			assertEquals(mapper.readTree("{\"$ref\":\"org.sagebionetworks-IDENTIFIER\"}"),
					mapper.readTree(String.valueOf(overrides.get(1).getAnalyzer())));
		} catch (java.io.IOException e) {
			throw new AssertionError(e);
		}
	}

	@Test
	public void testCRUDWithInlineEntryAnalyzer() {
		// Each entry's `analyzer` field accepts either {"$ref": qname} or a bare OpenSearch
		// settings.analysis block. Persist a mixed pair (one $ref, one inline) and verify
		// both round-trip through the JSON column unchanged.
		org.json.JSONObject inlineAnalyzer = new org.json.JSONObject().put(
				"analyzer", new org.json.JSONObject().put(
						"default", new org.json.JSONObject()
								.put("type", "custom")
								.put("tokenizer", "keyword")
								.put("filter", new org.json.JSONArray().put("lowercase"))));

		ColumnAnalyzerOverrideEntry refEntry = new ColumnAnalyzerOverrideEntry()
				.setColumnName("diagnosis")
				.setAnalyzer(new org.json.JSONObject().put("$ref", "org.sagebionetworks-SCIENTIFIC"));
		ColumnAnalyzerOverrideEntry inlineEntry = new ColumnAnalyzerOverrideEntry()
				.setColumnName("title")
				.setAnalyzer(inlineAnalyzer);
		ColumnAnalyzerOverride toCreate = new ColumnAnalyzerOverride()
				.setName("inline_entry_" + UUID.randomUUID().toString().replace("-", ""))
				.setOrganizationName(organizationName)
				.setOverrides(Arrays.asList(refEntry, inlineEntry));

		// call under test
		ColumnAnalyzerOverride created = columnAnalyzerOverrideDao.create(adminUserId, toCreate);

		assertNotNull(created.getId());
		List<ColumnAnalyzerOverrideEntry> overrides = created.getOverrides();
		assertEquals(2, overrides.size());

		com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
		try {
			assertEquals(mapper.readTree("{\"$ref\":\"org.sagebionetworks-SCIENTIFIC\"}"),
					mapper.readTree(String.valueOf(overrides.get(0).getAnalyzer())));
			assertEquals(mapper.readTree(inlineAnalyzer.toString()),
					mapper.readTree(String.valueOf(overrides.get(1).getAnalyzer())));
		} catch (java.io.IOException e) {
			throw new AssertionError(e);
		}

		// Re-fetch and verify the round-trip persists across DAO reads.
		Optional<ColumnAnalyzerOverride> fetched = columnAnalyzerOverrideDao.get(created.getId());
		assertTrue(fetched.isPresent());
		try {
			assertEquals(mapper.readTree(inlineAnalyzer.toString()),
					mapper.readTree(String.valueOf(fetched.get().getOverrides().get(1).getAnalyzer())));
		} catch (java.io.IOException e) {
			throw new AssertionError(e);
		}
	}

	@Test
	public void testGetNotFound() {
		// call under test
		Optional<ColumnAnalyzerOverride> result = columnAnalyzerOverrideDao.get("999999");
		assertFalse(result.isPresent());
	}

	@Test
	public void testCreateDuplicateNameInSameOrgThrows() {
		columnAnalyzerOverrideDao.create(adminUserId, newOverride("duplicate_name", "First"));

		ColumnAnalyzerOverride second = newOverride("duplicate_name", "Second");

		// call under test
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
			() -> columnAnalyzerOverrideDao.create(adminUserId, second));
		assertTrue(ex.getMessage().contains("same name already exists"));
	}

	@Test
	public void testCreateDuplicateNameInDifferentOrgSucceeds() {
		columnAnalyzerOverrideDao.create(adminUserId, newOverride("shared_name", "First"));

		ColumnAnalyzerOverride inOrg2 = new ColumnAnalyzerOverride();
		inOrg2.setName("shared_name");
		inOrg2.setOrganizationName(organizationName2);
		inOrg2.setOverrides(Arrays.asList(newEntry("col1")));

		// call under test
		ColumnAnalyzerOverride created = columnAnalyzerOverrideDao.create(adminUserId, inOrg2);
		assertNotNull(created.getId());
		assertEquals(organizationName2, created.getOrganizationName());
	}

	@Test
	public void testUpdatePersistsChangesAndRotatesEtag() {
		ColumnAnalyzerOverride created = columnAnalyzerOverrideDao.create(adminUserId, newOverride("test_update", "original"));
		String originalEtag = created.getEtag();

		created.setName("test_update_renamed");
		created.setDescription("updated");
		created.setOverrides(Arrays.asList(newEntry("new-col"), newEntry("another-col")));

		// call under test
		ColumnAnalyzerOverride updated = columnAnalyzerOverrideDao.update(adminUserId, created);

		assertEquals("test_update_renamed", updated.getName());
		assertEquals("updated", updated.getDescription());
		assertEquals(2, updated.getOverrides().size());
		assertNotEquals(originalEtag, updated.getEtag());
	}

	@Test
	public void testUpdateWithStaleEtagThrows() {
		ColumnAnalyzerOverride created = columnAnalyzerOverrideDao.create(adminUserId, newOverride("test_occ", null));

		// First update succeeds and rotates the etag
		created.setDescription("first update");
		columnAnalyzerOverrideDao.update(adminUserId, created);

		// Second update with the now-stale etag must fail
		created.setDescription("stale update");

		// call under test
		assertThrows(ConflictingUpdateException.class,
			() -> columnAnalyzerOverrideDao.update(adminUserId, created));
	}

	@Test
	public void testDelete() {
		ColumnAnalyzerOverride created = columnAnalyzerOverrideDao.create(adminUserId, newOverride("test_delete", null));

		assertTrue(columnAnalyzerOverrideDao.get(created.getId()).isPresent());

		// call under test
		columnAnalyzerOverrideDao.delete(created.getId());

		assertFalse(columnAnalyzerOverrideDao.get(created.getId()).isPresent());
	}

	@Test
	public void testListByOrganization() {
		ColumnAnalyzerOverride a = columnAnalyzerOverrideDao.create(adminUserId, newOverride("aaa", null));
		ColumnAnalyzerOverride b = columnAnalyzerOverrideDao.create(adminUserId, newOverride("bbb", null));
		ColumnAnalyzerOverride c = columnAnalyzerOverrideDao.create(adminUserId, newOverride("ccc", null));

		// call under test
		List<ColumnAnalyzerOverride> results = columnAnalyzerOverrideDao.list(organizationName, 100, 0);

		assertEquals(3, results.size());
		// Verify ordered by ID (ascending)
		assertTrue(Long.parseLong(results.get(0).getId()) < Long.parseLong(results.get(1).getId()));
		assertTrue(Long.parseLong(results.get(1).getId()) < Long.parseLong(results.get(2).getId()));
	}

	@Test
	public void testListAll() {
		columnAnalyzerOverrideDao.create(adminUserId, newOverride("in_org1", null));

		ColumnAnalyzerOverride inOrg2 = new ColumnAnalyzerOverride();
		inOrg2.setName("in_org2");
		inOrg2.setOrganizationName(organizationName2);
		inOrg2.setOverrides(Arrays.asList(newEntry("col1")));
		columnAnalyzerOverrideDao.create(adminUserId, inOrg2);

		// call under test
		List<ColumnAnalyzerOverride> results = columnAnalyzerOverrideDao.listAll(100, 0);

		assertEquals(2, results.size());
	}

	private ColumnAnalyzerOverride newOverride(String name, String description) {
		ColumnAnalyzerOverride override = new ColumnAnalyzerOverride();
		override.setName(name);
		override.setDescription(description);
		override.setOrganizationName(organizationName);
		override.setOverrides(Arrays.asList(newEntry("column1")));
		return override;
	}

	private ColumnAnalyzerOverrideEntry newEntry(String columnName) {
		ColumnAnalyzerOverrideEntry entry = new ColumnAnalyzerOverrideEntry();
		// Resolution-time helper: every entry's analyzer is the unified inline-or-$ref shape.
		// All canonical fixtures here use a $ref to a system analyzer.
		entry.setColumnName(columnName);
		entry.setAnalyzer(new org.json.JSONObject().put("$ref", "org.sagebionetworks-SCIENTIFIC"));
		return entry;
	}
}
