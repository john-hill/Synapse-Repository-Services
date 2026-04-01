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
import org.sagebionetworks.repo.model.search.table.SynonymRule;
import org.sagebionetworks.repo.model.search.table.SynonymRuleType;
import org.sagebionetworks.repo.model.search.table.SynonymSet;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(locations = { "classpath:jdomodels-test-context.xml" })
public class SynonymSetDaoImplAutowiredTest {

	@Autowired
	private SynonymSetDao synonymSetDao;

	@Autowired
	private OrganizationDao organizationDao;

	private Long adminUserId;
	private String organizationId;
	private String organizationName;

	@BeforeEach
	public void before() {
		adminUserId = AuthorizationConstants.BOOTSTRAP_PRINCIPAL.THE_ADMIN_USER.getPrincipalId();
		synonymSetDao.truncateAll();
		Organization org = organizationDao.createOrganization("test-org-" + UUID.randomUUID(), adminUserId);
		organizationId = org.getId();
		organizationName = org.getName();
	}

	@AfterEach
	public void after() {
		synonymSetDao.truncateAll();
		if (organizationId != null) {
			organizationDao.deleteOrganization(organizationId);
		}
	}

	@Test
	public void testCreateAndGet() {
		SynonymSet created = synonymSetDao.create(adminUserId, newSynonymSet("test-create", "A test set"));

		assertNotNull(created.getId());
		assertNotNull(created.getEtag());
		assertEquals("test-create", created.getName());
		assertEquals("A test set", created.getDescription());
		assertEquals(organizationName, created.getOrganizationName());
		assertNotNull(created.getCreatedOn());
		assertNotNull(created.getModifiedOn());
		assertEquals(adminUserId.toString(), created.getCreatedBy());
		assertEquals(adminUserId.toString(), created.getModifiedBy());

		Optional<SynonymSet> fetched = synonymSetDao.get(created.getId());
		assertTrue(fetched.isPresent());
		assertEquals(created.getId(), fetched.get().getId());
		assertEquals(created.getEtag(), fetched.get().getEtag());
	}

	@Test
	public void testGetNotFound() {
		Optional<SynonymSet> result = synonymSetDao.get("999999");
		assertFalse(result.isPresent());
	}

	@Test
	public void testCreateDuplicateNameInSameOrgThrows() {
		synonymSetDao.create(adminUserId, newSynonymSet("duplicate-name", "First"));

		SynonymSet second = newSynonymSet("duplicate-name", "Second");
		assertThrows(IllegalArgumentException.class, () -> synonymSetDao.create(adminUserId, second));
	}

	@Test
	public void testUpdatePersistsChangesAndRotatesEtag() {
		SynonymSet created = synonymSetDao.create(adminUserId, newSynonymSet("test-update", "original"));
		String originalEtag = created.getEtag();

		created.setName("test-update-renamed");
		created.setDescription("updated");

		SynonymSet updated = synonymSetDao.update(adminUserId, created);

		assertEquals("test-update-renamed", updated.getName());
		assertEquals("updated", updated.getDescription());
		assertNotEquals(originalEtag, updated.getEtag());
	}

	@Test
	public void testUpdateWithStaleEtagThrows() {
		SynonymSet created = synonymSetDao.create(adminUserId, newSynonymSet("test-occ", null));

		// First update succeeds and rotates the etag
		created.setDescription("first update");
		synonymSetDao.update(adminUserId, created);

		// Second update with the now-stale etag must fail
		created.setDescription("stale update");
		assertThrows(ConflictingUpdateException.class, () -> synonymSetDao.update(adminUserId, created));
	}

	@Test
	public void testDelete() {
		SynonymSet created = synonymSetDao.create(adminUserId, newSynonymSet("test-delete", null));

		assertTrue(synonymSetDao.get(created.getId()).isPresent());
		synonymSetDao.delete(created.getId());
		assertFalse(synonymSetDao.get(created.getId()).isPresent());
	}

	@Test
	public void testGetByOrganizationAndName() {
		synonymSetDao.create(adminUserId, newSynonymSet("find-me", "target"));

		Optional<SynonymSet> found = synonymSetDao.getByOrganizationAndName(organizationName, "find-me");
		assertTrue(found.isPresent());
		assertEquals("find-me", found.get().getName());
		assertEquals("target", found.get().getDescription());
	}

	@Test
	public void testGetByOrganizationAndNameNotFound() {
		Optional<SynonymSet> result = synonymSetDao.getByOrganizationAndName(organizationName, "does-not-exist");
		assertFalse(result.isPresent());
	}

	@Test
	public void testRulesRoundTripThroughDatabase() {
		SynonymRule equivalentRule = new SynonymRule();
		equivalentRule.setRuleType(SynonymRuleType.EQUIVALENT);
		equivalentRule.setTerms(Arrays.asList("heart attack", "myocardial infarction", "MI"));

		SynonymRule explicitRule = new SynonymRule();
		explicitRule.setRuleType(SynonymRuleType.EXPLICIT);
		explicitRule.setTerms(Arrays.asList("AD", "Alzheimer's disease"));

		SynonymSet set = newSynonymSet("rules-roundtrip", "Rules test");
		set.setRules(Arrays.asList(equivalentRule, explicitRule));

		SynonymSet created = synonymSetDao.create(adminUserId, set);
		SynonymSet fetched = synonymSetDao.get(created.getId()).get();

		List<SynonymRule> fetchedRules = fetched.getRules();
		assertNotNull(fetchedRules);
		assertEquals(2, fetchedRules.size());

		assertEquals(SynonymRuleType.EQUIVALENT, fetchedRules.get(0).getRuleType());
		assertEquals(Arrays.asList("heart attack", "myocardial infarction", "MI"), fetchedRules.get(0).getTerms());

		assertEquals(SynonymRuleType.EXPLICIT, fetchedRules.get(1).getRuleType());
		assertEquals(Arrays.asList("AD", "Alzheimer's disease"), fetchedRules.get(1).getTerms());
	}

	@Test
	public void testListByOrganization() {
		synonymSetDao.create(adminUserId, newSynonymSet("set-a", null));
		synonymSetDao.create(adminUserId, newSynonymSet("set-b", null));

		List<SynonymSet> results = synonymSetDao.list(organizationName, 10, 0);
		assertEquals(2, results.size());
	}

	@Test
	public void testListAll() {
		synonymSetDao.create(adminUserId, newSynonymSet("set-a", null));
		synonymSetDao.create(adminUserId, newSynonymSet("set-b", null));

		List<SynonymSet> results = synonymSetDao.listAll(10, 0);
		assertTrue(results.size() >= 2);
	}

	private SynonymSet newSynonymSet(String name, String description) {
		SynonymSet set = new SynonymSet();
		set.setName(name);
		set.setDescription(description);
		set.setOrganizationName(organizationName);
		return set;
	}
}
