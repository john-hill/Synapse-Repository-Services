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

public class DBOTextAnalyzer implements MigratableDatabaseObject<DBOTextAnalyzer, DBOTextAnalyzer> {

	private static final FieldColumn[] FIELDS = new FieldColumn[] {
			new FieldColumn("id", COL_TEXT_ANALYZER_ID, true).withIsBackupId(true),
			new FieldColumn("etag", COL_TEXT_ANALYZER_ETAG).withIsEtag(true),
			new FieldColumn("name", COL_TEXT_ANALYZER_NAME),
			new FieldColumn("description", COL_TEXT_ANALYZER_DESCRIPTION),
			new FieldColumn("organizationId", COL_TEXT_ANALYZER_ORGANIZATION_ID),
			new FieldColumn("settings", COL_TEXT_ANALYZER_SETTINGS),
			new FieldColumn("createdBy", COL_TEXT_ANALYZER_CREATED_BY),
			new FieldColumn("createdOn", COL_TEXT_ANALYZER_CREATED_ON),
			new FieldColumn("modifiedBy", COL_TEXT_ANALYZER_MODIFIED_BY),
			new FieldColumn("modifiedOn", COL_TEXT_ANALYZER_MODIFIED_ON),
	};

	private Long id;
	private String etag;
	private String name;
	private String description;
	private Long organizationId;
	private String settings;
	private Long createdBy;
	private Timestamp createdOn;
	private Long modifiedBy;
	private Timestamp modifiedOn;

	private static final TableMapping<DBOTextAnalyzer> TABLE_MAPPING = new TableMapping<>() {
		@Override
		public DBOTextAnalyzer mapRow(ResultSet rs, int rowNum) throws SQLException {
			DBOTextAnalyzer dbo = new DBOTextAnalyzer();
			dbo.setId(rs.getLong(COL_TEXT_ANALYZER_ID));
			dbo.setEtag(rs.getString(COL_TEXT_ANALYZER_ETAG));
			dbo.setName(rs.getString(COL_TEXT_ANALYZER_NAME));
			dbo.setDescription(rs.getString(COL_TEXT_ANALYZER_DESCRIPTION));
			long orgId = rs.getLong(COL_TEXT_ANALYZER_ORGANIZATION_ID);
			dbo.setOrganizationId(rs.wasNull() ? null : orgId);
			dbo.setSettings(rs.getString(COL_TEXT_ANALYZER_SETTINGS));
			dbo.setCreatedBy(rs.getLong(COL_TEXT_ANALYZER_CREATED_BY));
			dbo.setCreatedOn(rs.getTimestamp(COL_TEXT_ANALYZER_CREATED_ON));
			dbo.setModifiedBy(rs.getLong(COL_TEXT_ANALYZER_MODIFIED_BY));
			dbo.setModifiedOn(rs.getTimestamp(COL_TEXT_ANALYZER_MODIFIED_ON));
			return dbo;
		}

		@Override
		public String getTableName() {
			return TABLE_TEXT_ANALYZER;
		}

		@Override
		public String getDDLFileName() {
			return DDL_TEXT_ANALYZER;
		}

		@Override
		public FieldColumn[] getFieldColumns() {
			return FIELDS;
		}

		@Override
		public Class<? extends DBOTextAnalyzer> getDBOClass() {
			return DBOTextAnalyzer.class;
		}
	};

	@Override
	public TableMapping<DBOTextAnalyzer> getTableMapping() {
		return TABLE_MAPPING;
	}

	@Override
	public MigrationType getMigratableTableType() {
		return MigrationType.TEXT_ANALYZER;
	}

	private static final BasicMigratableTableTranslation<DBOTextAnalyzer> MIGRATION_TRANSLATOR = new BasicMigratableTableTranslation<>();

	@Override
	public MigratableTableTranslation<DBOTextAnalyzer, DBOTextAnalyzer> getTranslator() {
		return MIGRATION_TRANSLATOR;
	}

	@Override
	public Class<? extends DBOTextAnalyzer> getBackupClass() {
		return DBOTextAnalyzer.class;
	}

	@Override
	public Class<? extends DBOTextAnalyzer> getDatabaseObjectClass() {
		return DBOTextAnalyzer.class;
	}

	@Override
	public List<MigratableDatabaseObject<?, ?>> getSecondaryTypes() {
		return null;
	}

	public Long getId() {
		return id;
	}

	public DBOTextAnalyzer setId(Long id) {
		this.id = id;
		return this;
	}

	public String getEtag() {
		return etag;
	}

	public DBOTextAnalyzer setEtag(String etag) {
		this.etag = etag;
		return this;
	}

	public String getName() {
		return name;
	}

	public DBOTextAnalyzer setName(String name) {
		this.name = name;
		return this;
	}

	public String getDescription() {
		return description;
	}

	public DBOTextAnalyzer setDescription(String description) {
		this.description = description;
		return this;
	}

	public Long getOrganizationId() {
		return organizationId;
	}

	public DBOTextAnalyzer setOrganizationId(Long organizationId) {
		this.organizationId = organizationId;
		return this;
	}

	public String getSettings() {
		return settings;
	}

	public DBOTextAnalyzer setSettings(String settings) {
		this.settings = settings;
		return this;
	}

	public Long getCreatedBy() {
		return createdBy;
	}

	public DBOTextAnalyzer setCreatedBy(Long createdBy) {
		this.createdBy = createdBy;
		return this;
	}

	public Timestamp getCreatedOn() {
		return createdOn;
	}

	public DBOTextAnalyzer setCreatedOn(Timestamp createdOn) {
		this.createdOn = createdOn;
		return this;
	}

	public Long getModifiedBy() {
		return modifiedBy;
	}

	public DBOTextAnalyzer setModifiedBy(Long modifiedBy) {
		this.modifiedBy = modifiedBy;
		return this;
	}

	public Timestamp getModifiedOn() {
		return modifiedOn;
	}

	public DBOTextAnalyzer setModifiedOn(Timestamp modifiedOn) {
		this.modifiedOn = modifiedOn;
		return this;
	}

	@Override
	public int hashCode() {
		return Objects.hash(id, etag, name, description, organizationId, settings, createdBy, createdOn, modifiedBy, modifiedOn);
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj) {
			return true;
		}
		if (!(obj instanceof DBOTextAnalyzer)) {
			return false;
		}
		DBOTextAnalyzer other = (DBOTextAnalyzer) obj;
		return Objects.equals(id, other.id)
				&& Objects.equals(etag, other.etag)
				&& Objects.equals(name, other.name)
				&& Objects.equals(description, other.description)
				&& Objects.equals(organizationId, other.organizationId)
				&& Objects.equals(settings, other.settings)
				&& Objects.equals(createdBy, other.createdBy)
				&& Objects.equals(createdOn, other.createdOn)
				&& Objects.equals(modifiedBy, other.modifiedBy)
				&& Objects.equals(modifiedOn, other.modifiedOn);
	}

	@Override
	public String toString() {
		return "DBOTextAnalyzer [id=" + id + ", etag=" + etag + ", name=" + name + ", description=" + description
				+ ", organizationId=" + organizationId + ", settings=" + settings
				+ ", createdBy=" + createdBy + ", createdOn=" + createdOn
				+ ", modifiedBy=" + modifiedBy + ", modifiedOn=" + modifiedOn + "]";
	}
}
