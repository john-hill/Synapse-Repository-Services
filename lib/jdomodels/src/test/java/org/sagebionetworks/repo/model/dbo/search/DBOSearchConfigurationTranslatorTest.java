package org.sagebionetworks.repo.model.dbo.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.sql.Timestamp;

import org.junit.jupiter.api.Test;
import org.sagebionetworks.util.TemporaryCode;

@TemporaryCode(author = "BryanFauble", comment = "PLFM-9676: Delete alongside the DBOSearchConfiguration bridge once legacy <synonymSetsJson> backups can no longer arrive.")
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
		// A backup carrying the legacy <synonymSetsJson> XML element, an old-shape
		// COLUMN_ANALYZER_OVERRIDES JSON, and a bare-qname DEFAULT_ANALYZER varchar value.
		// The translator nulls all three: the column types changed (DEFAULT_ANALYZER is now
		// JSON, accepting a $ref-or-inline shape that the old qname doesn't satisfy), and
		// curators are expected to re-save SearchConfigurations on the new stack.
		DBOSearchConfiguration backup = backupRow();

		// call under test
		DBOSearchConfiguration result = new DBOSearchConfiguration().getTranslator().createDatabaseObjectFromBackup(backup);

		assertNull(result.getDefaultAnalyzer(),
				"legacy bare-qname default analyzer must be dropped — column is now JSON ref-or-inline");
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
		// A row authored on the new stack — DEFAULT_ANALYZER carries a $ref JSON object,
		// the legacy interior shapes are absent. The translator drops DEFAULT_ANALYZER
		// across the board for this stack (clean break); curators recreate via REST.
		DBOSearchConfiguration backup = backupRow()
				.setDefaultAnalyzer("{\"$ref\":\"sage-SCIENTIFIC\"}")
				.setSynonymSetsJson(null)
				.setColumnAnalyzerOverridesJson(null);

		// call under test
		DBOSearchConfiguration result = new DBOSearchConfiguration().getTranslator().createDatabaseObjectFromBackup(backup);

		assertNull(result.getDefaultAnalyzer());
		assertNull(result.getSynonymSetsJson());
		assertNull(result.getColumnAnalyzerOverridesJson());
	}

	@Test
	public void testCreateBackupFromDatabaseObjectIsIdentity() {
		DBOSearchConfiguration dbo = backupRow()
				.setDefaultAnalyzer("{\"$ref\":\"sage-STANDARD\"}")
				.setSynonymSetsJson(null)
				.setColumnAnalyzerOverridesJson(null);

		// call under test
		DBOSearchConfiguration result = new DBOSearchConfiguration().getTranslator().createBackupFromDatabaseObject(dbo);

		assertSame(dbo, result);
	}
}
