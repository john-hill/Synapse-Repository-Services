package org.sagebionetworks.repo.model.dbo.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import org.sagebionetworks.repo.model.search.table.TextAnalyzer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(locations = { "classpath:jdomodels-test-context.xml" })
public class TextAnalyzerDaoImplAutowiredTest {

	@Autowired
	private TextAnalyzerDao textAnalyzerDao;

	@Autowired
	private OrganizationDao organizationDao;

	private Long adminUserId;
	private String organizationId;
	private String organizationName;

	@BeforeEach
	public void before() {
		adminUserId = AuthorizationConstants.BOOTSTRAP_PRINCIPAL.THE_ADMIN_USER.getPrincipalId();
		textAnalyzerDao.truncateAll();
		Organization org = organizationDao.createOrganization("test-org-" + UUID.randomUUID(), adminUserId);
		organizationId = org.getId();
		organizationName = org.getName();
	}

	@AfterEach
	public void after() {
		textAnalyzerDao.truncateAll();
		if (organizationId != null) {
			organizationDao.deleteOrganization(organizationId);
		}
	}

	@Test
	public void testCreateAndGet() {
		TextAnalyzer analyzer = newAnalyzer("test_create", "A test analyzer");

		TextAnalyzer created = textAnalyzerDao.create(analyzer, adminUserId);

		assertNotNull(created.getId());
		assertNotNull(created.getEtag());
		assertEquals("test_create", created.getName());
		assertEquals("A test analyzer", created.getDescription());
		assertNotNull(created.getCreatedOn());
		assertNotNull(created.getModifiedOn());
		assertEquals(adminUserId.toString(), created.getCreatedBy());
		assertEquals(adminUserId.toString(), created.getModifiedBy());
		assertNotNull(created.getSettings());

		// Verify get returns the same data
		Optional<TextAnalyzer> fetched = textAnalyzerDao.get(Long.parseLong(created.getId()));
		assertTrue(fetched.isPresent());
		assertEquals(created.getId(), fetched.get().getId());
		assertEquals(created.getEtag(), fetched.get().getEtag());
	}

	@Test
	public void testGetNotFound() {
		Optional<TextAnalyzer> result = textAnalyzerDao.get(999999L);
		assertFalse(result.isPresent());
	}

	@Test
	public void testCreateDuplicateNameInSameOrgThrows() {
		textAnalyzerDao.create(newAnalyzer("duplicate_name", "First"), adminUserId);

		TextAnalyzer analyzer2 = newAnalyzer("duplicate_name", "Second");
		assertThrows(IllegalArgumentException.class, () -> textAnalyzerDao.create(analyzer2, adminUserId));
	}

	@Test
	public void testUpdatePersistsChangesAndRotatesEtag() {
		TextAnalyzer created = textAnalyzerDao.create(newAnalyzer("test_update", "original"), adminUserId);
		String originalEtag = created.getEtag();

		created.setName("test_update_renamed");
		created.setDescription("updated");
		String newSettings = "{\"analyzer\":{\"default\":{\"type\":\"custom\",\"tokenizer\":\"whitespace\"}}}";
		created.setSettings(newSettings);

		TextAnalyzer updated = textAnalyzerDao.update(created, adminUserId);

		assertEquals("test_update_renamed", updated.getName());
		assertEquals("updated", updated.getDescription());
		// MySQL JSON columns may reformat whitespace on read, so compare semantically.
		com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
		try {
			assertEquals(mapper.readTree(newSettings), mapper.readTree(updated.getSettings()));
		} catch (java.io.IOException e) {
			throw new AssertionError(e);
		}
		assertNotEquals(originalEtag, updated.getEtag());
	}

	@Test
	public void testUpdateWithStaleEtagThrows() {
		TextAnalyzer created = textAnalyzerDao.create(newAnalyzer("test_occ", null), adminUserId);

		// First update succeeds and rotates the etag
		created.setDescription("first update");
		textAnalyzerDao.update(created, adminUserId);

		// Second update with the now-stale etag must fail
		created.setDescription("stale update");
		assertThrows(ConflictingUpdateException.class, () -> textAnalyzerDao.update(created, adminUserId));
	}

	@Test
	public void testDelete() {
		TextAnalyzer created = textAnalyzerDao.create(newAnalyzer("test_delete", null), adminUserId);
		Long id = Long.parseLong(created.getId());

		assertTrue(textAnalyzerDao.exists(id));
		textAnalyzerDao.delete(id);
		assertFalse(textAnalyzerDao.exists(id));
	}

	@Test
	public void testCreateOrUpdateSystemAnalyzerIsIdempotent() {
		// First call inserts
		textAnalyzerDao.createOrUpdateSystemAnalyzerForBootstrapOnly(1L, newAnalyzer("SCIENTIFIC", "V1"), organizationName, adminUserId);
		Optional<TextAnalyzer> first = textAnalyzerDao.get(1L);
		assertTrue(first.isPresent());
		assertEquals("V1", first.get().getDescription());
		String firstEtag = first.get().getEtag();

		// Second call with same ID updates in place
		textAnalyzerDao.createOrUpdateSystemAnalyzerForBootstrapOnly(1L, newAnalyzer("SCIENTIFIC", "V2"), organizationName, adminUserId);
		Optional<TextAnalyzer> second = textAnalyzerDao.get(1L);
		assertTrue(second.isPresent());
		assertEquals("V2", second.get().getDescription());
		assertNotEquals(firstEtag, second.get().getEtag());

		// Still only one row for this org
		assertEquals(1, textAnalyzerDao.listByOrganization(organizationName, 100, 0).size());
	}

	@Test
	public void testSettingsRoundTripThroughDatabase() {
		String settings = "{"
				+ "\"filter\":{"
				+ "\"english_stop\":{\"type\":\"stop\",\"stopwords\":\"_english_\"},"
				+ "\"english_stemmer\":{\"type\":\"stemmer\",\"language\":\"english\"}"
				+ "},"
				+ "\"analyzer\":{\"default\":{\"type\":\"custom\",\"tokenizer\":\"standard\","
				+ "\"filter\":[\"lowercase\",\"english_stop\",\"english_stemmer\"]}}"
				+ "}";

		TextAnalyzer analyzer = new TextAnalyzer()
				.setName("settings_roundtrip")
				.setOrganizationName(organizationName)
				.setSettings(settings);

		TextAnalyzer created = textAnalyzerDao.create(analyzer, adminUserId);
		TextAnalyzer fetched = textAnalyzerDao.get(Long.parseLong(created.getId())).get();

		// MySQL JSON columns may reformat whitespace on read, so compare semantically.
		com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
		try {
			assertEquals(mapper.readTree(settings), mapper.readTree(fetched.getSettings()));
		} catch (java.io.IOException e) {
			throw new AssertionError(e);
		}
	}

	private TextAnalyzer newAnalyzer(String name, String description) {
		return new TextAnalyzer()
				.setName(name)
				.setDescription(description)
				.setOrganizationName(organizationName)
				.setSettings("{\"analyzer\":{\"default\":{\"type\":\"custom\",\"tokenizer\":\"standard\","
						+ "\"filter\":[\"lowercase\"]}}}");
	}
}
