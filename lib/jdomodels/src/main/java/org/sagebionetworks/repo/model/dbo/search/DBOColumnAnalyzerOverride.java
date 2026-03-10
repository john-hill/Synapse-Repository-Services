package org.sagebionetworks.repo.model.dbo.search;

import static org.sagebionetworks.repo.model.query.jdo.SqlConstants.*;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Objects;

import org.sagebionetworks.repo.model.dbo.FieldColumn;
import org.sagebionetworks.repo.model.dbo.MigratableDatabaseObject;
import org.sagebionetworks.repo.model.dbo.TableMapping;
import org.sagebionetworks.repo.model.dbo.migration.BasicMigratableTableTranslation;
import org.sagebionetworks.repo.model.dbo.migration.MigratableTableTranslation;
import org.sagebionetworks.repo.model.migration.MigrationType;

public class DBOColumnAnalyzerOverride implements MigratableDatabaseObject<DBOColumnAnalyzerOverride, DBOColumnAnalyzerOverride> {

	private Long id;
	private String etag;
	private Long organizationId;
	private String name;
	private String description;
	private String overrides;
	private Long createdBy;
	private Timestamp createdOn;
	private Long modifiedBy;
	private Timestamp modifiedOn;

	private static FieldColumn[] FIELDS = new FieldColumn[] {
		new FieldColumn("id", COL_COLUMN_ANALYZER_OVERRIDE_ID, true).withIsBackupId(true),
		new FieldColumn("etag", COL_COLUMN_ANALYZER_OVERRIDE_ETAG).withIsEtag(true),
		new FieldColumn("organizationId", COL_COLUMN_ANALYZER_OVERRIDE_ORGANIZATION_ID),
		new FieldColumn("name", COL_COLUMN_ANALYZER_OVERRIDE_NAME),
		new FieldColumn("description", COL_COLUMN_ANALYZER_OVERRIDE_DESCRIPTION),
		new FieldColumn("overrides", COL_COLUMN_ANALYZER_OVERRIDE_OVERRIDES),
		new FieldColumn("createdBy", COL_COLUMN_ANALYZER_OVERRIDE_CREATED_BY),
		new FieldColumn("createdOn", COL_COLUMN_ANALYZER_OVERRIDE_CREATED_ON),
		new FieldColumn("modifiedBy", COL_COLUMN_ANALYZER_OVERRIDE_MODIFIED_BY),
		new FieldColumn("modifiedOn", COL_COLUMN_ANALYZER_OVERRIDE_MODIFIED_ON)
	};

	private static final TableMapping<DBOColumnAnalyzerOverride> TABLE_MAPPING = new TableMapping<>() {
		@Override
		public DBOColumnAnalyzerOverride mapRow(ResultSet rs, int rowNum) throws SQLException {
			DBOColumnAnalyzerOverride dbo = new DBOColumnAnalyzerOverride();
			dbo.setId(rs.getLong(COL_COLUMN_ANALYZER_OVERRIDE_ID));
			dbo.setEtag(rs.getString(COL_COLUMN_ANALYZER_OVERRIDE_ETAG));
			dbo.setOrganizationId(rs.getLong(COL_COLUMN_ANALYZER_OVERRIDE_ORGANIZATION_ID));
			dbo.setName(rs.getString(COL_COLUMN_ANALYZER_OVERRIDE_NAME));
			dbo.setDescription(rs.getString(COL_COLUMN_ANALYZER_OVERRIDE_DESCRIPTION));
			dbo.setOverrides(rs.getString(COL_COLUMN_ANALYZER_OVERRIDE_OVERRIDES));
			dbo.setCreatedBy(rs.getLong(COL_COLUMN_ANALYZER_OVERRIDE_CREATED_BY));
			dbo.setCreatedOn(rs.getTimestamp(COL_COLUMN_ANALYZER_OVERRIDE_CREATED_ON));
			dbo.setModifiedBy(rs.getLong(COL_COLUMN_ANALYZER_OVERRIDE_MODIFIED_BY));
			dbo.setModifiedOn(rs.getTimestamp(COL_COLUMN_ANALYZER_OVERRIDE_MODIFIED_ON));
			return dbo;
		}

		@Override
		public String getTableName() {
			return TABLE_COLUMN_ANALYZER_OVERRIDE;
		}

		@Override
		public String getDDLFileName() {
			return DDL_COLUMN_ANALYZER_OVERRIDE;
		}

		@Override
		public FieldColumn[] getFieldColumns() {
			return FIELDS;
		}

		@Override
		public Class<? extends DBOColumnAnalyzerOverride> getDBOClass() {
			return DBOColumnAnalyzerOverride.class;
		}
	};

	private static final BasicMigratableTableTranslation<DBOColumnAnalyzerOverride> MIGRATION_TRANSLATOR = new BasicMigratableTableTranslation<>();

	@Override
	public TableMapping<DBOColumnAnalyzerOverride> getTableMapping() {
		return TABLE_MAPPING;
	}

	@Override
	public MigrationType getMigratableTableType() {
		return MigrationType.COLUMN_ANALYZER_OVERRIDE;
	}

	@Override
	public MigratableTableTranslation<DBOColumnAnalyzerOverride, DBOColumnAnalyzerOverride> getTranslator() {
		return MIGRATION_TRANSLATOR;
	}

	@Override
	public Class<? extends DBOColumnAnalyzerOverride> getBackupClass() {
		return DBOColumnAnalyzerOverride.class;
	}

	@Override
	public Class<? extends DBOColumnAnalyzerOverride> getDatabaseObjectClass() {
		return DBOColumnAnalyzerOverride.class;
	}

	@Override
	public List<MigratableDatabaseObject<?, ?>> getSecondaryTypes() {
		return null;
	}

	public Long getId() {
		return id;
	}

	public DBOColumnAnalyzerOverride setId(Long id) {
		this.id = id;
		return this;
	}

	public String getEtag() {
		return etag;
	}

	public DBOColumnAnalyzerOverride setEtag(String etag) {
		this.etag = etag;
		return this;
	}

	public Long getOrganizationId() {
		return organizationId;
	}

	public DBOColumnAnalyzerOverride setOrganizationId(Long organizationId) {
		this.organizationId = organizationId;
		return this;
	}

	public String getName() {
		return name;
	}

	public DBOColumnAnalyzerOverride setName(String name) {
		this.name = name;
		return this;
	}

	public String getDescription() {
		return description;
	}

	public DBOColumnAnalyzerOverride setDescription(String description) {
		this.description = description;
		return this;
	}

	public String getOverrides() {
		return overrides;
	}

	public DBOColumnAnalyzerOverride setOverrides(String overrides) {
		this.overrides = overrides;
		return this;
	}

	public Long getCreatedBy() {
		return createdBy;
	}

	public DBOColumnAnalyzerOverride setCreatedBy(Long createdBy) {
		this.createdBy = createdBy;
		return this;
	}

	public Timestamp getCreatedOn() {
		return createdOn;
	}

	public DBOColumnAnalyzerOverride setCreatedOn(Timestamp createdOn) {
		this.createdOn = createdOn;
		return this;
	}

	public Long getModifiedBy() {
		return modifiedBy;
	}

	public DBOColumnAnalyzerOverride setModifiedBy(Long modifiedBy) {
		this.modifiedBy = modifiedBy;
		return this;
	}

	public Timestamp getModifiedOn() {
		return modifiedOn;
	}

	public DBOColumnAnalyzerOverride setModifiedOn(Timestamp modifiedOn) {
		this.modifiedOn = modifiedOn;
		return this;
	}

	@Override
	public int hashCode() {
		return Objects.hash(createdBy, createdOn, description, etag, id, modifiedBy, modifiedOn, name, organizationId, overrides);
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj) {
			return true;
		}
		if (!(obj instanceof DBOColumnAnalyzerOverride)) {
			return false;
		}
		DBOColumnAnalyzerOverride other = (DBOColumnAnalyzerOverride) obj;
		return Objects.equals(createdBy, other.createdBy) && Objects.equals(createdOn, other.createdOn)
				&& Objects.equals(description, other.description) && Objects.equals(etag, other.etag)
				&& Objects.equals(id, other.id) && Objects.equals(modifiedBy, other.modifiedBy)
				&& Objects.equals(modifiedOn, other.modifiedOn) && Objects.equals(name, other.name)
				&& Objects.equals(organizationId, other.organizationId) && Objects.equals(overrides, other.overrides);
	}

	@Override
	public String toString() {
		return "DBOColumnAnalyzerOverride [id=" + id + ", etag=" + etag + ", organizationId=" + organizationId + ", name=" + name
				+ ", description=" + description + ", overrides=" + overrides + ", createdBy=" + createdBy + ", createdOn="
				+ createdOn + ", modifiedBy=" + modifiedBy + ", modifiedOn=" + modifiedOn + "]";
	}

}
