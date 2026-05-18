package org.sagebionetworks.repo.model.dbo.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.sql.Timestamp;

import org.junit.jupiter.api.Test;
import org.sagebionetworks.util.TemporaryCode;

@TemporaryCode(author = "BryanFauble", comment = "Delete alongside the DBOSynonymSet bridge once legacy <rules> backups can no longer arrive.")
public class DBOSynonymSetTranslatorTest {

	private static DBOSynonymSet legacyBackup() {
		return new DBOSynonymSet()
				.setId(123L)
				.setEtag("etag-1")
				.setOrganizationName("sage")
				.setName("medical_terms")
				.setDescription("Carried over from a pre-refactor stack.")
				.setRules("[{\"ruleType\":\"EQUIVALENT\",\"terms\":[\"tumor\",\"neoplasm\"]}]")
				.setCreatedBy(1L)
				.setCreatedOn(new Timestamp(0L))
				.setModifiedBy(1L)
				.setModifiedOn(new Timestamp(0L));
	}

	@Test
	public void testCreateDatabaseObjectFromBackupWithLegacyRulesField() {
		DBOSynonymSet backup = legacyBackup();

		// call under test
		DBOSynonymSet result = new DBOSynonymSet().getTranslator().createDatabaseObjectFromBackup(backup);

		assertEquals(DBOSynonymSet.PLACEHOLDER_DEFINITION, result.getDefinition());
		assertNull(result.getRules(), "bridge field should be cleared after translation");
		// identity / audit fields flow through untouched
		assertEquals(123L, result.getId());
		assertEquals("etag-1", result.getEtag());
		assertEquals("sage", result.getOrganizationName());
		assertEquals("medical_terms", result.getName());
		assertEquals("Carried over from a pre-refactor stack.", result.getDescription());
	}

	@Test
	public void testCreateDatabaseObjectFromBackupWithDefinitionAlreadyPresent() {
		// Post-migration row (or a row authored on the new stack) — the translator must not
		// overwrite an existing definition with the placeholder.
		String existing = "{\"type\":\"synonym_graph\",\"synonyms\":[\"a, b\"]}";
		DBOSynonymSet backup = legacyBackup().setDefinition(existing).setRules(null);

		// call under test
		DBOSynonymSet result = new DBOSynonymSet().getTranslator().createDatabaseObjectFromBackup(backup);

		assertEquals(existing, result.getDefinition());
	}

	@Test
	public void testCreateBackupFromDatabaseObjectIsIdentity() {
		DBOSynonymSet dbo = legacyBackup().setDefinition("{\"type\":\"synonym_graph\",\"synonyms\":[]}").setRules(null);

		// call under test
		DBOSynonymSet result = new DBOSynonymSet().getTranslator().createBackupFromDatabaseObject(dbo);

		assertSame(dbo, result);
	}
}
