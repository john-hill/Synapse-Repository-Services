package org.sagebionetworks.repo.model.dbo.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.sql.Timestamp;

import org.junit.jupiter.api.Test;
import org.sagebionetworks.util.TemporaryCode;

@TemporaryCode(author = "BryanFauble", comment = "Delete alongside the DBOSearchConfiguration bridge once legacy <defaultAnalyzer> / legacy JSON-column-shape backups can no longer arrive.")
public class DBOSearchConfigurationTranslatorTest {

	private static DBOSearchConfiguration legacyBackup() {
		return new DBOSearchConfiguration()
				.setId(42L)
				.setEtag("etag-1")
				.setOrganizationName("sage")
				.setName("default")
				.setDescription("Carried over from a pre-refactor stack.")
				.setDefaultAnalyzer("sage-STANDARD")
				.setSynonymSetsJson("[{\"ruleType\":\"EQUIVALENT\"}]")
				.setColumnAnalyzerOverridesJson("[{\"columnName\":\"x\",\"analyzer\":\"old\"}]")
				.setCreatedBy(1L)
				.setCreatedOn(new Timestamp(0L))
				.setModifiedBy(1L)
				.setModifiedOn(new Timestamp(0L));
	}

	@Test
	public void testCreateDatabaseObjectFromBackupWithLegacyShape() {
		DBOSearchConfiguration backup = legacyBackup();

		// call under test
		DBOSearchConfiguration result = new DBOSearchConfiguration().getTranslator().createDatabaseObjectFromBackup(backup);

		// Defaults are nullable; per-column resolution falls back to system defaults per data type.
		assertNull(result.getDefaultIndexAnalyzer());
		assertNull(result.getDefaultSearchAnalyzer());
		assertNull(result.getDefaultAnalyzer(), "bridge field should be cleared after translation");
		assertNull(result.getSynonymSetsJson(), "legacy interior shape must not be persisted");
		assertNull(result.getColumnAnalyzerOverridesJson(), "legacy interior shape must not be persisted");
		// identity / audit fields flow through untouched
		assertEquals(42L, result.getId());
		assertEquals("etag-1", result.getEtag());
		assertEquals("sage", result.getOrganizationName());
		assertEquals("default", result.getName());
	}

	@Test
	public void testCreateDatabaseObjectFromBackupWithNewShape() {
		// A row authored on the new stack — no legacy <defaultAnalyzer>, both new analyzers
		// populated. The translator must not overwrite either.
		DBOSearchConfiguration backup = legacyBackup()
				.setDefaultAnalyzer(null)
				.setDefaultIndexAnalyzer("sage-SCIENTIFIC")
				.setDefaultSearchAnalyzer("sage-SCIENTIFIC")
				.setSynonymSetsJson("[\"123\"]")
				.setColumnAnalyzerOverridesJson("[{\"columnName\":\"x\",\"analyzerQname\":\"sage-STANDARD\"}]");

		// call under test
		DBOSearchConfiguration result = new DBOSearchConfiguration().getTranslator().createDatabaseObjectFromBackup(backup);

		assertEquals("sage-SCIENTIFIC", result.getDefaultIndexAnalyzer());
		assertEquals("sage-SCIENTIFIC", result.getDefaultSearchAnalyzer());
		// New-shape rows still get the JSON columns reset because the translator cannot
		// distinguish legacy from new shape — curators on the source stack will not be
		// authoring against the new shape, so this is a safe blanket reset for the bridge
		// window. Documented as a limitation here.
		assertNull(result.getSynonymSetsJson());
		assertNull(result.getColumnAnalyzerOverridesJson());
	}

	@Test
	public void testCreateBackupFromDatabaseObjectIsIdentity() {
		DBOSearchConfiguration dbo = legacyBackup()
				.setDefaultAnalyzer(null)
				.setDefaultIndexAnalyzer("sage-STANDARD")
				.setDefaultSearchAnalyzer("sage-STANDARD")
				.setSynonymSetsJson(null)
				.setColumnAnalyzerOverridesJson(null);

		// call under test
		DBOSearchConfiguration result = new DBOSearchConfiguration().getTranslator().createBackupFromDatabaseObject(dbo);

		assertSame(dbo, result);
	}
}
