package org.sagebionetworks.repo.model.dbo.search;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.sagebionetworks.repo.model.AuthorizationConstants;
import org.sagebionetworks.repo.model.ConflictingUpdateException;
import org.sagebionetworks.repo.model.table.search.TextAnalyzer;
import org.sagebionetworks.repo.model.table.search.TextAnalyzerSettings;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(locations = { "classpath:jdomodels-test-context.xml" })
public class TextAnalyzerDaoImplAutowiredTest {

	@Autowired
	private TextAnalyzerDao textAnalyzerDao;

	private Long adminUserId;

	@BeforeEach
	public void before() {
		adminUserId = AuthorizationConstants.BOOTSTRAP_PRINCIPAL.THE_ADMIN_USER.getPrincipalId();
		textAnalyzerDao.truncateAll();
	}

	@AfterEach
	public void after() {
		textAnalyzerDao.truncateAll();
	}

	@Test
	public void testCreateAndGet() {
		TextAnalyzer analyzer = newAnalyzer("test-create", "A test analyzer");

		TextAnalyzer created = textAnalyzerDao.create(analyzer, adminUserId);

		assertNotNull(created.getId());
		assertNotNull(created.getEtag());
		assertEquals("test-create", created.getName());
		assertEquals("A test analyzer", created.getDescription());
		assertNotNull(created.getCreatedOn());
		assertNotNull(created.getModifiedOn());
		assertEquals(adminUserId.toString(), created.getCreatedBy());
		assertEquals(adminUserId.toString(), created.getModifiedBy());
		assertEquals("standard", created.getSettings().getTokenizer());

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
		textAnalyzerDao.create(newAnalyzer("duplicate-name", "First"), adminUserId);

		TextAnalyzer analyzer2 = newAnalyzer("duplicate-name", "Second");
		assertThrows(IllegalArgumentException.class, () -> textAnalyzerDao.create(analyzer2, adminUserId));
	}

	@Test
	public void testUpdatePersistsChangesAndRotatesEtag() {
		TextAnalyzer created = textAnalyzerDao.create(newAnalyzer("test-update", "original"), adminUserId);
		String originalEtag = created.getEtag();

		created.setName("test-update-renamed");
		created.setDescription("updated");
		TextAnalyzerSettings newSettings = new TextAnalyzerSettings();
		newSettings.setTokenizer("whitespace");
		created.setSettings(newSettings);

		TextAnalyzer updated = textAnalyzerDao.update(created, adminUserId);

		assertEquals("test-update-renamed", updated.getName());
		assertEquals("updated", updated.getDescription());
		assertEquals("whitespace", updated.getSettings().getTokenizer());
		assertNotEquals(originalEtag, updated.getEtag());
	}

	@Test
	public void testUpdateWithStaleEtagThrows() {
		TextAnalyzer created = textAnalyzerDao.create(newAnalyzer("test-occ", null), adminUserId);

		// First update succeeds and rotates the etag
		created.setDescription("first update");
		textAnalyzerDao.update(created, adminUserId);

		// Second update with the now-stale etag must fail
		created.setDescription("stale update");
		assertThrows(ConflictingUpdateException.class, () -> textAnalyzerDao.update(created, adminUserId));
	}

	@Test
	public void testDelete() {
		TextAnalyzer created = textAnalyzerDao.create(newAnalyzer("test-delete", null), adminUserId);
		Long id = Long.parseLong(created.getId());

		assertTrue(textAnalyzerDao.exists(id));
		textAnalyzerDao.delete(id);
		assertFalse(textAnalyzerDao.exists(id));
	}

	@Test
	public void testListSystemReturnsOnlySystemAnalyzers() {
		textAnalyzerDao.createOrUpdateSystemAnalyzer(1L, newAnalyzer("SYS_A", "System A"), adminUserId);
		textAnalyzerDao.createOrUpdateSystemAnalyzer(2L, newAnalyzer("SYS_B", "System B"), adminUserId);

		List<TextAnalyzer> systemAnalyzers = textAnalyzerDao.listSystem();

		assertEquals(2, systemAnalyzers.size());
		// Ordered by ID ascending
		assertEquals("SYS_A", systemAnalyzers.get(0).getName());
		assertEquals("SYS_B", systemAnalyzers.get(1).getName());
		assertNull(systemAnalyzers.get(0).getOrganizationId());
		assertNull(systemAnalyzers.get(1).getOrganizationId());
	}

	@Test
	public void testCreateOrUpdateSystemAnalyzerIsIdempotent() {
		// First call inserts
		textAnalyzerDao.createOrUpdateSystemAnalyzer(1L, newAnalyzer("SCIENTIFIC", "V1"), adminUserId);
		Optional<TextAnalyzer> first = textAnalyzerDao.get(1L);
		assertTrue(first.isPresent());
		assertEquals("V1", first.get().getDescription());
		String firstEtag = first.get().getEtag();

		// Second call with same ID updates in place
		textAnalyzerDao.createOrUpdateSystemAnalyzer(1L, newAnalyzer("SCIENTIFIC", "V2"), adminUserId);
		Optional<TextAnalyzer> second = textAnalyzerDao.get(1L);
		assertTrue(second.isPresent());
		assertEquals("V2", second.get().getDescription());
		assertNotEquals(firstEtag, second.get().getEtag());

		// Still only one row
		assertEquals(1, textAnalyzerDao.listSystem().size());
	}

	@Test
	public void testSettingsRoundTripThroughDatabase() {
		TextAnalyzerSettings settings = new TextAnalyzerSettings();
		settings.setTokenizer("standard");
		settings.setSynonymAware(true);
		settings.setFilterOrder(Arrays.asList("lowercase", "english_stop", "english_stemmer"));
		Map<String, String> tokenFilters = new HashMap<>();
		tokenFilters.put("english_stop", "{\"type\":\"stop\",\"stopwords\":\"_english_\"}");
		tokenFilters.put("english_stemmer", "{\"type\":\"stemmer\",\"language\":\"english\"}");
		settings.setTokenFilters(tokenFilters);

		TextAnalyzer analyzer = new TextAnalyzer();
		analyzer.setName("settings-roundtrip");
		analyzer.setSettings(settings);

		TextAnalyzer created = textAnalyzerDao.create(analyzer, adminUserId);
		TextAnalyzer fetched = textAnalyzerDao.get(Long.parseLong(created.getId())).get();

		TextAnalyzerSettings fetchedSettings = fetched.getSettings();
		assertEquals("standard", fetchedSettings.getTokenizer());
		assertTrue(fetchedSettings.getSynonymAware());
		assertEquals(Arrays.asList("lowercase", "english_stop", "english_stemmer"), fetchedSettings.getFilterOrder());
		assertTrue(fetchedSettings.getTokenFilters().containsKey("english_stop"));
		assertTrue(fetchedSettings.getTokenFilters().containsKey("english_stemmer"));
	}

	private TextAnalyzer newAnalyzer(String name, String description) {
		TextAnalyzer analyzer = new TextAnalyzer();
		analyzer.setName(name);
		analyzer.setDescription(description);
		TextAnalyzerSettings settings = new TextAnalyzerSettings();
		settings.setTokenizer("standard");
		settings.setFilterOrder(Arrays.asList("lowercase"));
		analyzer.setSettings(settings);
		return analyzer;
	}
}
