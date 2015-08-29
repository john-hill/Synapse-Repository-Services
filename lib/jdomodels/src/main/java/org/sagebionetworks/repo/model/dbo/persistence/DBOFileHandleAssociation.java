package org.sagebionetworks.repo.model.dbo.persistence;

import static org.sagebionetworks.repo.model.query.jdo.SqlConstants.COL_FILES_ID;
import static org.sagebionetworks.repo.model.query.jdo.SqlConstants.COL_FILE_ASSOC_ASSOC_OBJECT_ID;
import static org.sagebionetworks.repo.model.query.jdo.SqlConstants.COL_FILE_ASSOC_ASSOC_OBJECT_TYPE;
import static org.sagebionetworks.repo.model.query.jdo.SqlConstants.COL_FILE_ASSOC_FILE_HANDLE_ID;
import static org.sagebionetworks.repo.model.query.jdo.SqlConstants.TABLE_FILES;
import static org.sagebionetworks.repo.model.query.jdo.SqlConstants.TABLE_FILE_ASSOCIATIONS;

import java.util.List;

import org.sagebionetworks.repo.model.ObjectType;
import org.sagebionetworks.repo.model.dbo.AutoTableMapping;
import org.sagebionetworks.repo.model.dbo.Field;
import org.sagebionetworks.repo.model.dbo.ForeignKey;
import org.sagebionetworks.repo.model.dbo.MigratableDatabaseObject;
import org.sagebionetworks.repo.model.dbo.Table;
import org.sagebionetworks.repo.model.dbo.TableMapping;
import org.sagebionetworks.repo.model.dbo.migration.MigratableTableTranslation;
import org.sagebionetworks.repo.model.migration.MigrationType;

/**
 * Database object for for the file FILE_ASSOCIATIONS table which tracks the
 * association of files with other objects.
 * 
 * @author jhill
 *
 */
@Table(name = TABLE_FILE_ASSOCIATIONS)
public class DBOFileHandleAssociation
		implements
		MigratableDatabaseObject<DBOFileHandleAssociation, DBOFileHandleAssociation> {

	private static TableMapping<DBOFileHandleAssociation> tableMapping = AutoTableMapping
			.create(DBOFileHandleAssociation.class);

	@Field(name = COL_FILE_ASSOC_FILE_HANDLE_ID, nullable = false, primary = true, backupId = true)
	@ForeignKey(table = TABLE_FILES, field = COL_FILES_ID, cascadeDelete = true)
	private Long fileHandleId;

	@Field(name = COL_FILE_ASSOC_ASSOC_OBJECT_ID, nullable = false, primary = true)
	private Long associatedObjectId;

	@Field(name = COL_FILE_ASSOC_ASSOC_OBJECT_TYPE, nullable = false, primary = true)
	private ObjectType associatedObjectType;

	@Override
	public TableMapping<DBOFileHandleAssociation> getTableMapping() {
		return tableMapping;
	}

	@Override
	public MigrationType getMigratableTableType() {
		return MigrationType.FILE_HANDLE_ASSOCIATION;
	}

	@Override
	public MigratableTableTranslation<DBOFileHandleAssociation, DBOFileHandleAssociation> getTranslator() {
		return new MigratableTableTranslation<DBOFileHandleAssociation, DBOFileHandleAssociation>() {

			@Override
			public DBOFileHandleAssociation createDatabaseObjectFromBackup(
					DBOFileHandleAssociation backup) {
				return backup;
			}

			@Override
			public DBOFileHandleAssociation createBackupFromDatabaseObject(
					DBOFileHandleAssociation dbo) {
				return dbo;
			}
		};
	}

	@Override
	public Class<? extends DBOFileHandleAssociation> getBackupClass() {
		return DBOFileHandleAssociation.class;
	}

	@Override
	public Class<? extends DBOFileHandleAssociation> getDatabaseObjectClass() {
		return DBOFileHandleAssociation.class;
	}

	@Override
	public List<MigratableDatabaseObject> getSecondaryTypes() {
		return null;
	}

	public Long getFileHandleId() {
		return fileHandleId;
	}

	public void setFileHandleId(Long fileHandleId) {
		this.fileHandleId = fileHandleId;
	}

	public Long getAssociatedObjectId() {
		return associatedObjectId;
	}

	public void setAssociatedObjectId(Long associatedObjectId) {
		this.associatedObjectId = associatedObjectId;
	}

	public ObjectType getAssociatedObjectType() {
		return associatedObjectType;
	}

	public void setAssociatedObjectType(ObjectType associatedObjectType) {
		this.associatedObjectType = associatedObjectType;
	}

	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = prime
				* result
				+ ((associatedObjectId == null) ? 0 : associatedObjectId
						.hashCode());
		result = prime
				* result
				+ ((associatedObjectType == null) ? 0 : associatedObjectType
						.hashCode());
		result = prime * result
				+ ((fileHandleId == null) ? 0 : fileHandleId.hashCode());
		return result;
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (getClass() != obj.getClass())
			return false;
		DBOFileHandleAssociation other = (DBOFileHandleAssociation) obj;
		if (associatedObjectId == null) {
			if (other.associatedObjectId != null)
				return false;
		} else if (!associatedObjectId.equals(other.associatedObjectId))
			return false;
		if (associatedObjectType != other.associatedObjectType)
			return false;
		if (fileHandleId == null) {
			if (other.fileHandleId != null)
				return false;
		} else if (!fileHandleId.equals(other.fileHandleId))
			return false;
		return true;
	}

	@Override
	public String toString() {
		return "DBOFileHandleAssociation [fileHandleId=" + fileHandleId
				+ ", associatedObjectId=" + associatedObjectId
				+ ", associatedObjectType=" + associatedObjectType + "]";
	}

}
