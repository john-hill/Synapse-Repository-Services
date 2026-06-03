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
		org.json.JSONObject newSettings = new org.json.JSONObject().put(
				"analyzer", new org.json.JSONObject().put(
						"default", new org.json.JSONObject()
								.put("type", "custom")
								.put("tokenizer", "whitespace")));
		created.setSettings(newSettings);

		TextAnalyzer updated = textAnalyzerDao.update(created, adminUserId);

		assertEquals("test_update_renamed", updated.getName());
		assertEquals("updated", updated.getDescription());
		// settings is opaque-Object; compare semantically via Jackson on both sides.
		com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
		try {
			assertEquals(mapper.readTree(newSettings.toString()),
					mapper.readTree(String.valueOf(updated.getSettings())));
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
		// Curator-style settings: pasted directly as JSON objects, not stringified JSON.
		org.json.JSONObject settings = new org.json.JSONObject()
				.put("filter", new org.json.JSONObject()
						.put("english_stop", new org.json.JSONObject()
								.put("type", "stop")
								.put("stopwords", "_english_"))
						.put("english_stemmer", new org.json.JSONObject()
								.put("type", "stemmer")
								.put("language", "english")))
				.put("analyzer", new org.json.JSONObject()
						.put("default", new org.json.JSONObject()
								.put("type", "custom")
								.put("tokenizer", "standard")
								.put("filter", new org.json.JSONArray()
										.put("lowercase").put("english_stop").put("english_stemmer"))));

		TextAnalyzer analyzer = new TextAnalyzer()
				.setName("settings_roundtrip")
				.setOrganizationName(organizationName)
				.setSettings(settings);

		TextAnalyzer created = textAnalyzerDao.create(analyzer, adminUserId);
		TextAnalyzer fetched = textAnalyzerDao.get(Long.parseLong(created.getId())).get();

		// Compare semantically via Jackson; JSONObject.equals isn't value-based.
		com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
		try {
			assertEquals(mapper.readTree(settings.toString()),
					mapper.readTree(String.valueOf(fetched.getSettings())));
		} catch (java.io.IOException e) {
			throw new AssertionError(e);
		}
	}

	@Test
	public void testSettingsAcceptsNativeJavaInputsWithoutEscaping() throws Exception {
		// Java callers must be able to pass native Java values directly to the setter —
		// no manual stringification, no escape ceremonies. The codec turns them into
		// canonical JSON for the MySQL JSON column and the round-trip surfaces a
		// structurally equivalent value on the way back out.

		// Case 1: a deeply nested java.util.Map (Map / List / scalar).
		java.util.Map<String, Object> mapSettings = java.util.Map.of(
				"analyzer", java.util.Map.of(
						"default", java.util.Map.of(
								"type", "custom",
								"tokenizer", "standard",
								"filter", java.util.List.of("lowercase", "asciifolding"))));

		TextAnalyzer mapAnalyzer = new TextAnalyzer()
				.setName("native_map_input")
				.setOrganizationName(organizationName)
				.setSettings(mapSettings);

		TextAnalyzer createdFromMap = textAnalyzerDao.create(mapAnalyzer, adminUserId);
		TextAnalyzer fetchedFromMap = textAnalyzerDao.get(Long.parseLong(createdFromMap.getId())).get();

		com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
		assertEquals(mapper.valueToTree(mapSettings),
				mapper.readTree(String.valueOf(fetchedFromMap.getSettings())),
				"Map input must round-trip structurally without manual stringification");

		// Case 2: a native org.json.JSONObject input.
		org.json.JSONObject jsonObjectSettings = new org.json.JSONObject()
				.put("analyzer", new org.json.JSONObject()
						.put("default", new org.json.JSONObject()
								.put("type", "custom")
								.put("tokenizer", "whitespace")
								.put("filter", new org.json.JSONArray().put("lowercase"))));

		TextAnalyzer jsonAnalyzer = new TextAnalyzer()
				.setName("native_jsonobject_input")
				.setOrganizationName(organizationName)
				.setSettings(jsonObjectSettings);

		TextAnalyzer createdFromJson = textAnalyzerDao.create(jsonAnalyzer, adminUserId);
		TextAnalyzer fetchedFromJson = textAnalyzerDao.get(Long.parseLong(createdFromJson.getId())).get();

		// Re-parse the JSONObject input as a Jackson tree to compare structurally without
		// relying on JSONObject.equals (identity-based) or toString() ordering.
		assertEquals(mapper.readTree(jsonObjectSettings.toString()),
				mapper.readTree(String.valueOf(fetchedFromJson.getSettings())),
				"JSONObject input must round-trip structurally without manual stringification");
	}

	private TextAnalyzer newAnalyzer(String name, String description) {
		return new TextAnalyzer()
				.setName(name)
				.setDescription(description)
				.setOrganizationName(organizationName)
				.setSettings(new org.json.JSONObject()
						.put("analyzer", new org.json.JSONObject()
								.put("default", new org.json.JSONObject()
										.put("type", "custom")
										.put("tokenizer", "standard")
										.put("filter", new org.json.JSONArray().put("lowercase")))));
	}
}
