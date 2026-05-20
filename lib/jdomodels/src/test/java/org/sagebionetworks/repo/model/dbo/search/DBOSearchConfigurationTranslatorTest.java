package org.sagebionetworks.repo.model.dbo.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.sql.Timestamp;

import org.junit.jupiter.api.Test;
import org.sagebionetworks.util.TemporaryCode;

@TemporaryCode(author = "BryanFauble", comment = "Delete alongside the DBOSearchConfiguration bridge once legacy <synonymSetsJson> backups can no longer arrive.")
public class DBOSearchConfigurationTranslatorTest {

	private static DBOSearchConfiguration backupRow() {
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
		// A backup carrying the legacy <synonymSetsJson> XML element and an old-shape
		// COLUMN_ANALYZER_OVERRIDES JSON. The translator nulls both interior shapes.
		// The DEFAULT_ANALYZER column flows through unchanged — the legacy field name
		// matches the new column name, so production data is preserved across the
		// migration cycle rather than being thrown away.
		DBOSearchConfiguration backup = backupRow();

		// call under test
		DBOSearchConfiguration result = new DBOSearchConfiguration().getTranslator().createDatabaseObjectFromBackup(backup);

		assertEquals("sage-STANDARD", result.getDefaultAnalyzer(),
				"production-supplied default analyzer must survive the migration");
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
		// A row authored on the new stack — already in the new shape with all interior
		// blobs absent. Translator is a near-no-op for the analyzer field.
		DBOSearchConfiguration backup = backupRow()
				.setDefaultAnalyzer("sage-SCIENTIFIC")
				.setSynonymSetsJson(null)
				.setColumnAnalyzerOverridesJson(null);

		// call under test
		DBOSearchConfiguration result = new DBOSearchConfiguration().getTranslator().createDatabaseObjectFromBackup(backup);

		assertEquals("sage-SCIENTIFIC", result.getDefaultAnalyzer());
		assertNull(result.getSynonymSetsJson());
		assertNull(result.getColumnAnalyzerOverridesJson());
	}

	@Test
	public void testCreateBackupFromDatabaseObjectIsIdentity() {
		DBOSearchConfiguration dbo = backupRow()
				.setDefaultAnalyzer("sage-STANDARD")
				.setSynonymSetsJson(null)
				.setColumnAnalyzerOverridesJson(null);

		// call under test
		DBOSearchConfiguration result = new DBOSearchConfiguration().getTranslator().createBackupFromDatabaseObject(dbo);

		assertSame(dbo, result);
	}
}
