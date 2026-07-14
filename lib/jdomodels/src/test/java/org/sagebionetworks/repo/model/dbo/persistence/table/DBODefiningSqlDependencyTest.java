package org.sagebionetworks.repo.model.dbo.persistence.table;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;
import org.sagebionetworks.repo.model.EntityType;
import org.sagebionetworks.repo.model.dbo.migration.MigratableTableTranslation;

public class DBODefiningSqlDependencyTest {

	private final MigratableTableTranslation<DBODefiningSqlDependency, DBODefiningSqlDependency> translator = new DBODefiningSqlDependency()
			.getTranslator();

	@Test
	public void testCreateDatabaseObjectFromBackupWithLegacyMaterializedViewFields() {
		// A backup from before the rename: only the legacy materializedView* fields and no objectType.
		DBODefiningSqlDependency backup = new DBODefiningSqlDependency();
		backup.setMaterializedViewId(123L);
		backup.setMaterializedViewVersion(2L);
		backup.setSourceTableId(456L);
		backup.setSourceTableVersion(-1L);

		// Call under test
		DBODefiningSqlDependency result = translator.createDatabaseObjectFromBackup(backup);

		assertEquals(123L, result.getObjectId());
		assertEquals(2L, result.getObjectVersion());
		assertEquals(EntityType.materializedview.name(), result.getObjectType());
		assertEquals(456L, result.getSourceTableId());
		assertEquals(-1L, result.getSourceTableVersion());
	}

	@Test
	public void testCreateDatabaseObjectFromBackupDefaultsObjectTypeWhenNull() {
		// A row already using the new object fields but predating the objectType column defaults to MV.
		DBODefiningSqlDependency backup = new DBODefiningSqlDependency();
		backup.setObjectId(123L);
		backup.setObjectVersion(1L);
		backup.setObjectType(null);
		backup.setSourceTableId(456L);
		backup.setSourceTableVersion(-1L);

		// Call under test
		DBODefiningSqlDependency result = translator.createDatabaseObjectFromBackup(backup);

		assertEquals(EntityType.materializedview.name(), result.getObjectType());
		// The new fields are preserved and the legacy bridge is not consulted.
		assertEquals(123L, result.getObjectId());
		assertEquals(1L, result.getObjectVersion());
		assertNull(result.getMaterializedViewId());
	}

	@Test
	public void testCreateDatabaseObjectFromBackupPreservesExplicitObjectType() {
		// A new-format search index backup keeps its own objectType and object fields untouched.
		DBODefiningSqlDependency backup = new DBODefiningSqlDependency();
		backup.setObjectId(789L);
		backup.setObjectVersion(-1L);
		backup.setObjectType(EntityType.searchindex.name());
		backup.setSourceTableId(456L);
		backup.setSourceTableVersion(3L);

		// Call under test
		DBODefiningSqlDependency result = translator.createDatabaseObjectFromBackup(backup);

		assertEquals(789L, result.getObjectId());
		assertEquals(-1L, result.getObjectVersion());
		assertEquals(EntityType.searchindex.name(), result.getObjectType());
		assertEquals(456L, result.getSourceTableId());
		assertEquals(3L, result.getSourceTableVersion());
	}

}
