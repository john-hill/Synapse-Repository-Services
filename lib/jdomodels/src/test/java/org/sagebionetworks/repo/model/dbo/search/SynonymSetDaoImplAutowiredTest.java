package org.sagebionetworks.repo.model.dbo.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import org.sagebionetworks.repo.model.search.table.SynonymSet;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(locations = { "classpath:jdomodels-test-context.xml" })
public class SynonymSetDaoImplAutowiredTest {

	private static final String EQUIVALENT_DEFINITION =
			"{\"type\":\"synonym_graph\",\"synonyms\":[\"cancer, tumor, neoplasm\"]}";
	private static final String EXPLICIT_DEFINITION =
			"{\"type\":\"synonym_graph\",\"synonyms\":[\"AD => Alzheimer's disease\"],\"expand\":true,\"lenient\":false}";

	@Autowired
	private SynonymSetDao synonymSetDao;

	@Autowired
	private OrganizationDao organizationDao;

	private Long adminUserId;
	private String org1Id;
	private String org1Name;
	private String org2Id;
	private String org2Name;

	@BeforeEach
	public void before() {
		adminUserId = AuthorizationConstants.BOOTSTRAP_PRINCIPAL.THE_ADMIN_USER.getPrincipalId();
		synonymSetDao.truncateAll();

		Organization org1 = organizationDao.createOrganization("test-org-" + UUID.randomUUID(), adminUserId);
		org1Id = org1.getId();
		org1Name = org1.getName();

		Organization org2 = organizationDao.createOrganization("test-org-" + UUID.randomUUID(), adminUserId);
		org2Id = org2.getId();
		org2Name = org2.getName();
	}

	@AfterEach
	public void after() {
		synonymSetDao.truncateAll();
		if (org1Id != null) {
			organizationDao.deleteOrganization(org1Id);
		}
		if (org2Id != null) {
			organizationDao.deleteOrganization(org2Id);
		}
	}

	@Test
	public void testCreateAndGetWithDefinition() {
		SynonymSet toCreate = newSynonymSet(org1Name, "test_create", "A test set")
				.setDefinition(EQUIVALENT_DEFINITION);

		// call under test
		SynonymSet created = synonymSetDao.create(adminUserId, toCreate);

		assertNotNull(created.getId());
		assertNotNull(created.getEtag());
		assertEquals("test_create", created.getName());
		assertEquals("A test set", created.getDescription());
		assertEquals(org1Name, created.getOrganizationName());
		assertNotNull(created.getCreatedOn());
		assertNotNull(created.getModifiedOn());
		assertEquals(adminUserId.toString(), created.getCreatedBy());
		assertEquals(adminUserId.toString(), created.getModifiedBy());
		assertEquals(EQUIVALENT_DEFINITION, created.getDefinition());

		// call under test
		Optional<SynonymSet> fetched = synonymSetDao.get(created.getId());

		assertTrue(fetched.isPresent());
		assertEquals(created, fetched.get());
	}

	@Test
	public void testGetWithNonExistentId() {
		// call under test
		Optional<SynonymSet> result = synonymSetDao.get("999999");

		assertFalse(result.isPresent());
	}

	@Test
	public void testUpdateWithModifiedDefinitionAndDescription() {
		SynonymSet toCreate = newSynonymSet(org1Name, "test_update", "original")
				.setDefinition(EQUIVALENT_DEFINITION);
		SynonymSet created = synonymSetDao.create(adminUserId, toCreate);
		String originalEtag = created.getEtag();

		created.setName("test_update_renamed");
		created.setDescription("updated");
		created.setDefinition(EXPLICIT_DEFINITION);

		// call under test
		SynonymSet updated = synonymSetDao.update(adminUserId, created);

		assertEquals("test_update_renamed", updated.getName());
		assertEquals("updated", updated.getDescription());
		assertNotEquals(originalEtag, updated.getEtag());
		assertEquals(EXPLICIT_DEFINITION, updated.getDefinition());
	}

	@Test
	public void testUpdateWithStaleEtagThrows() {
		SynonymSet created = synonymSetDao.create(adminUserId,
				newSynonymSet(org1Name, "test_occ", null).setDefinition(EQUIVALENT_DEFINITION));

		// First update succeeds and rotates the etag
		created.setDescription("first update");
		synonymSetDao.update(adminUserId, created);

		// Second update with the now-stale etag must fail
		created.setDescription("stale update");

		// call under test
		assertThrows(ConflictingUpdateException.class, () -> synonymSetDao.update(adminUserId, created));
	}

	@Test
	public void testDeleteWithExistingSet() {
		SynonymSet created = synonymSetDao.create(adminUserId,
				newSynonymSet(org1Name, "test_delete", null).setDefinition(EQUIVALENT_DEFINITION));
		assertTrue(synonymSetDao.get(created.getId()).isPresent());

		// call under test
		synonymSetDao.delete(created.getId());

		assertFalse(synonymSetDao.get(created.getId()).isPresent());
	}

	@Test
	public void testGetByOrganizationAndNameWithMatchingEntry() {
		SynonymSet toCreate = newSynonymSet(org1Name, "find_me", "target")
				.setDefinition(EQUIVALENT_DEFINITION);
		SynonymSet created = synonymSetDao.create(adminUserId, toCreate);

		// Create another in a different org to ensure filtering
		synonymSetDao.create(adminUserId,
				newSynonymSet(org2Name, "find_me", "decoy").setDefinition(EQUIVALENT_DEFINITION));

		// call under test
		Optional<SynonymSet> found = synonymSetDao.getByOrganizationAndName(org1Name, "find_me");

		assertTrue(found.isPresent());
		assertEquals(created, found.get());
	}

	@Test
	public void testGetByOrganizationAndNameWithNonExistentName() {
		// call under test
		Optional<SynonymSet> result = synonymSetDao.getByOrganizationAndName(org1Name, "does-not-exist");

		assertFalse(result.isPresent());
	}

	@Test
	public void testListWithMultipleOrganizations() {
		// Create 2 synonym sets in org1
		SynonymSet org1SetA = synonymSetDao.create(adminUserId,
				newSynonymSet(org1Name, "org1_set_a", "first").setDefinition(EQUIVALENT_DEFINITION));
		SynonymSet org1SetB = synonymSetDao.create(adminUserId,
				newSynonymSet(org1Name, "org1_set_b", "second").setDefinition(EXPLICIT_DEFINITION));

		// Create 2 synonym sets in org2
		SynonymSet org2SetA = synonymSetDao.create(adminUserId,
				newSynonymSet(org2Name, "org2_set_a", "third").setDefinition(EQUIVALENT_DEFINITION));
		SynonymSet org2SetB = synonymSetDao.create(adminUserId,
				newSynonymSet(org2Name, "org2_set_b", "fourth").setDefinition(EXPLICIT_DEFINITION));

		// call under test — list by org1
		List<SynonymSet> org1Results = synonymSetDao.list(org1Name, 10, 0);

		assertEquals(2, org1Results.size());
		assertEquals(org1SetA.getId(), org1Results.get(0).getId());
		assertEquals(org1SetB.getId(), org1Results.get(1).getId());

		// call under test — list by org2
		List<SynonymSet> org2Results = synonymSetDao.list(org2Name, 10, 0);

		assertEquals(2, org2Results.size());
		assertEquals(org2SetA.getId(), org2Results.get(0).getId());
		assertEquals(org2SetB.getId(), org2Results.get(1).getId());

		// call under test — list all
		List<SynonymSet> allResults = synonymSetDao.listAll(10, 0);

		assertEquals(4, allResults.size());
		// Ordered by ID, so org1 sets come first (created first)
		assertEquals(org1SetA.getId(), allResults.get(0).getId());
		assertEquals(org1SetB.getId(), allResults.get(1).getId());
		assertEquals(org2SetA.getId(), allResults.get(2).getId());
		assertEquals(org2SetB.getId(), allResults.get(3).getId());
	}

	private SynonymSet newSynonymSet(String organizationName, String name, String description) {
		return new SynonymSet()
				.setName(name)
				.setDescription(description)
				.setOrganizationName(organizationName);
	}
}
