package org.sagebionetworks.repo.model.dbo.persistence;

import java.util.List;

import org.sagebionetworks.repo.model.dbo.AutoTableMapping;
import org.sagebionetworks.repo.model.dbo.MigratableDatabaseObject;
import org.sagebionetworks.repo.model.dbo.TableMapping;
import org.sagebionetworks.repo.model.dbo.migration.MigratableTableTranslation;
import org.sagebionetworks.repo.model.migration.MigrationType;

public class FileOwner implements MigratableDatabaseObject<FileOwner, FileOwner>{
	
	private static TableMapping<FileOwner> tableMapping = AutoTableMapping.create(FileOwner.class);

	@Override
	public TableMapping<FileOwner> getTableMapping() {
		return tableMapping;
	}

	@Override
	public MigrationType getMigratableTableType() {
		return MigrationType.FILE_OWNER;
	}

	@Override
	public MigratableTableTranslation<FileOwner, FileOwner> getTranslator() {
		return new MigratableTableTranslation<FileOwner, FileOwner>(){

			@Override
			public FileOwner createDatabaseObjectFromBackup(FileOwner backup) {
				return backup;
			}

			@Override
			public FileOwner createBackupFromDatabaseObject(FileOwner dbo) {
				return dbo;
			}};
	}

	@Override
	public Class<? extends FileOwner> getBackupClass() {
		return FileOwner.class;
	}

	@Override
	public Class<? extends FileOwner> getDatabaseObjectClass() {
		return FileOwner.class;
	}

	@Override
	public List<MigratableDatabaseObject> getSecondaryTypes() {
		return null;
	}

}
