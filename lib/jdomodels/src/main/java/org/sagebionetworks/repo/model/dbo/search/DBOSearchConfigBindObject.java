package org.sagebionetworks.repo.model.dbo.search;

import static org.sagebionetworks.repo.model.query.jdo.SqlConstants.COL_SCOB_BIND_ID;
import static org.sagebionetworks.repo.model.query.jdo.SqlConstants.COL_SCOB_CREATED_BY;
import static org.sagebionetworks.repo.model.query.jdo.SqlConstants.COL_SCOB_CREATED_ON;
import static org.sagebionetworks.repo.model.query.jdo.SqlConstants.COL_SCOB_OBJECT_ID;
import static org.sagebionetworks.repo.model.query.jdo.SqlConstants.COL_SCOB_OBJECT_TYPE;
import static org.sagebionetworks.repo.model.query.jdo.SqlConstants.COL_SCOB_SEARCH_CONFIG_ID;
import static org.sagebionetworks.repo.model.query.jdo.SqlConstants.DDL_SEARCH_CONFIG_OBJECT_BINDING;
import static org.sagebionetworks.repo.model.query.jdo.SqlConstants.TABLE_SEARCH_CONFIG_OBJECT_BINDING;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import org.sagebionetworks.repo.model.dbo.FieldColumn;
import org.sagebionetworks.repo.model.dbo.MigratableDatabaseObject;
import org.sagebionetworks.repo.model.dbo.TableMapping;
import org.sagebionetworks.repo.model.dbo.migration.BasicMigratableTableTranslation;
import org.sagebionetworks.repo.model.dbo.migration.MigratableTableTranslation;
import org.sagebionetworks.repo.model.migration.MigrationType;

public class DBOSearchConfigBindObject
		implements MigratableDatabaseObject<DBOSearchConfigBindObject, DBOSearchConfigBindObject> {

	private static final FieldColumn[] FIELDS = new FieldColumn[] {
			new FieldColumn("bindId", COL_SCOB_BIND_ID, true).withIsBackupId(true),
			new FieldColumn("searchConfigId", COL_SCOB_SEARCH_CONFIG_ID),
			new FieldColumn("objectId", COL_SCOB_OBJECT_ID),
			new FieldColumn("objectType", COL_SCOB_OBJECT_TYPE),
			new FieldColumn("createdBy", COL_SCOB_CREATED_BY),
			new FieldColumn("createdOn", COL_SCOB_CREATED_ON),
	};

	private Long bindId;
	private Long searchConfigId;
	private Long objectId;
	private String objectType;
	private Long createdBy;
	private Timestamp createdOn;

	private static final TableMapping<DBOSearchConfigBindObject> TABLE_MAPPING = new TableMapping<DBOSearchConfigBindObject>() {
		@Override
		public DBOSearchConfigBindObject mapRow(ResultSet rs, int rowNum) throws SQLException {
			DBOSearchConfigBindObject dbo = new DBOSearchConfigBindObject();
			dbo.setBindId(rs.getLong(COL_SCOB_BIND_ID));
			dbo.setSearchConfigId(rs.getLong(COL_SCOB_SEARCH_CONFIG_ID));
			dbo.setObjectId(rs.getLong(COL_SCOB_OBJECT_ID));
			dbo.setObjectType(rs.getString(COL_SCOB_OBJECT_TYPE));
			dbo.setCreatedBy(rs.getLong(COL_SCOB_CREATED_BY));
			dbo.setCreatedOn(rs.getTimestamp(COL_SCOB_CREATED_ON));
			return dbo;
		}

		@Override
		public String getTableName() {
			return TABLE_SEARCH_CONFIG_OBJECT_BINDING;
		}

		@Override
		public String getDDLFileName() {
			return DDL_SEARCH_CONFIG_OBJECT_BINDING;
		}

		@Override
		public FieldColumn[] getFieldColumns() {
			return FIELDS;
		}

		@Override
		public Class<? extends DBOSearchConfigBindObject> getDBOClass() {
			return DBOSearchConfigBindObject.class;
		}
	};

	@Override
	public TableMapping<DBOSearchConfigBindObject> getTableMapping() {
		return TABLE_MAPPING;
	}

	@Override
	public MigrationType getMigratableTableType() {
		return MigrationType.SEARCH_CONFIG_OBJECT_BINDING;
	}

	private static final MigratableTableTranslation<DBOSearchConfigBindObject, DBOSearchConfigBindObject> TRANSLATOR =
			new BasicMigratableTableTranslation<>();

	@Override
	public MigratableTableTranslation<DBOSearchConfigBindObject, DBOSearchConfigBindObject> getTranslator() {
		return TRANSLATOR;
	}

	@Override
	public Class<? extends DBOSearchConfigBindObject> getBackupClass() {
		return DBOSearchConfigBindObject.class;
	}

	@Override
	public Class<? extends DBOSearchConfigBindObject> getDatabaseObjectClass() {
		return DBOSearchConfigBindObject.class;
	}

	@Override
	public List<MigratableDatabaseObject<?, ?>> getSecondaryTypes() {
		return Collections.emptyList();
	}

	public Long getBindId() {
		return bindId;
	}

	public void setBindId(Long bindId) {
		this.bindId = bindId;
	}

	public Long getSearchConfigId() {
		return searchConfigId;
	}

	public void setSearchConfigId(Long searchConfigId) {
		this.searchConfigId = searchConfigId;
	}

	public Long getObjectId() {
		return objectId;
	}

	public void setObjectId(Long objectId) {
		this.objectId = objectId;
	}

	public String getObjectType() {
		return objectType;
	}

	public void setObjectType(String objectType) {
		this.objectType = objectType;
	}

	public Long getCreatedBy() {
		return createdBy;
	}

	public void setCreatedBy(Long createdBy) {
		this.createdBy = createdBy;
	}

	public Timestamp getCreatedOn() {
		return createdOn;
	}

	public void setCreatedOn(Timestamp createdOn) {
		this.createdOn = createdOn;
	}

	@Override
	public int hashCode() {
		return Objects.hash(bindId, searchConfigId, objectId, objectType, createdBy, createdOn);
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj) {
			return true;
		}
		if (!(obj instanceof DBOSearchConfigBindObject)) {
			return false;
		}
		DBOSearchConfigBindObject other = (DBOSearchConfigBindObject) obj;
		return Objects.equals(bindId, other.bindId)
				&& Objects.equals(searchConfigId, other.searchConfigId)
				&& Objects.equals(objectId, other.objectId)
				&& Objects.equals(objectType, other.objectType)
				&& Objects.equals(createdBy, other.createdBy)
				&& Objects.equals(createdOn, other.createdOn);
	}

	@Override
	public String toString() {
		return "DBOSearchConfigBindObject [bindId=" + bindId + ", searchConfigId=" + searchConfigId
				+ ", objectId=" + objectId + ", objectType=" + objectType
				+ ", createdBy=" + createdBy + ", createdOn=" + createdOn + "]";
	}
}
