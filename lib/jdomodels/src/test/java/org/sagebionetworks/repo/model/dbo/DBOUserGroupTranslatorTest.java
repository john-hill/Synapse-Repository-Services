package org.sagebionetworks.repo.model.dbo;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.sagebionetworks.repo.model.dbo.migration.MigratableTableTranslation;
import org.sagebionetworks.repo.model.dbo.persistence.DBOUserGroup;

class DBOUserGroupTranslatorTest {

	@Test
	void testTranslator() {
		MigratableTableTranslation<DBOUserGroup, DBOUserGroup> translator = (new DBOUserGroup()).getTranslator();

		DBOUserGroup dbo = new DBOUserGroup();
		// method under test
		DBOUserGroup backup = translator.createBackupFromDatabaseObject(dbo);
		assertEquals(0L, backup.getRealmId());
		
		backup = new DBOUserGroup();
		
		// method under test
		dbo = translator.createDatabaseObjectFromBackup(backup);
		
		assertEquals(0L, dbo.getRealmId());
	}

}
