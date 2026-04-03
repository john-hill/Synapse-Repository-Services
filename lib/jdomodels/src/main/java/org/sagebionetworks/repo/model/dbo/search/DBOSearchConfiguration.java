package org.sagebionetworks.repo.model.dbo.search;

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

public class DBOSearchConfiguration implements MigratableDatabaseObject<DBOSearchConfiguration, DBOSearchConfiguration> {

	private static final FieldColumn[] FIELDS = new FieldColumn[] {
			new FieldColumn("id", "ID", true).withIsBackupId(true),
			new FieldColumn("etag", "ETAG").withIsEtag(true),
			new FieldColumn("organizationName", "ORGANIZATION_NAME"),
			new FieldColumn("name", "NAME"),
			new FieldColumn("description", "DESCRIPTION"),
			new FieldColumn("defaultAnalyzerId", "DEFAULT_ANALYZER_ID"),
			new FieldColumn("createdBy", "CREATED_BY"),
			new FieldColumn("createdOn", "CREATED_ON"),
			new FieldColumn("modifiedBy", "MODIFIED_BY"),
			new FieldColumn("modifiedOn", "MODIFIED_ON"),
	};

	private Long id;
	private String etag;
	private String organizationName;
	private String name;
	private String description;
	private Long defaultAnalyzerId;
	private Long createdBy;
	private Timestamp createdOn;
	private Long modifiedBy;
	private Timestamp modifiedOn;

	private static final TableMapping<DBOSearchConfiguration> TABLE_MAPPING = new TableMapping<>() {
		@Override
		public DBOSearchConfiguration mapRow(ResultSet rs, int rowNum) throws SQLException {
			DBOSearchConfiguration dbo = new DBOSearchConfiguration();
			dbo.setId(rs.getLong("ID"));
			dbo.setEtag(rs.getString("ETAG"));
			dbo.setOrganizationName(rs.getString("ORGANIZATION_NAME"));
			dbo.setName(rs.getString("NAME"));
			dbo.setDescription(rs.getString("DESCRIPTION"));
			long defAnalyzerId = rs.getLong("DEFAULT_ANALYZER_ID");
			dbo.setDefaultAnalyzerId(rs.wasNull() ? null : defAnalyzerId);
			dbo.setCreatedBy(rs.getLong("CREATED_BY"));
			dbo.setCreatedOn(rs.getTimestamp("CREATED_ON"));
			dbo.setModifiedBy(rs.getLong("MODIFIED_BY"));
			dbo.setModifiedOn(rs.getTimestamp("MODIFIED_ON"));
			return dbo;
		}

		@Override
		public String getTableName() {
			return "SEARCH_CONFIGURATION";
		}

		@Override
		public String getDDLFileName() {
			return "schema/SearchConfiguration-ddl.sql";
		}

		@Override
		public FieldColumn[] getFieldColumns() {
			return FIELDS;
		}

		@Override
		public Class<? extends DBOSearchConfiguration> getDBOClass() {
			return DBOSearchConfiguration.class;
		}
	};

	@Override
	public TableMapping<DBOSearchConfiguration> getTableMapping() {
		return TABLE_MAPPING;
	}

	@Override
	public MigrationType getMigratableTableType() {
		return MigrationType.SEARCH_CONFIGURATION;
	}

	private static final BasicMigratableTableTranslation<DBOSearchConfiguration> MIGRATION_TRANSLATOR = new BasicMigratableTableTranslation<>();

	@Override
	public MigratableTableTranslation<DBOSearchConfiguration, DBOSearchConfiguration> getTranslator() {
		return MIGRATION_TRANSLATOR;
	}

	@Override
	public Class<? extends DBOSearchConfiguration> getBackupClass() {
		return DBOSearchConfiguration.class;
	}

	@Override
	public Class<? extends DBOSearchConfiguration> getDatabaseObjectClass() {
		return DBOSearchConfiguration.class;
	}

	@Override
	public List<MigratableDatabaseObject<?, ?>> getSecondaryTypes() {
		return null;
	}

	public Long getId() {
		return id;
	}

	public DBOSearchConfiguration setId(Long id) {
		this.id = id;
		return this;
	}

	public String getEtag() {
		return etag;
	}

	public DBOSearchConfiguration setEtag(String etag) {
		this.etag = etag;
		return this;
	}

	public String getOrganizationName() {
		return organizationName;
	}

	public DBOSearchConfiguration setOrganizationName(String organizationName) {
		this.organizationName = organizationName;
		return this;
	}

	public String getName() {
		return name;
	}

	public DBOSearchConfiguration setName(String name) {
		this.name = name;
		return this;
	}

	public String getDescription() {
		return description;
	}

	public DBOSearchConfiguration setDescription(String description) {
		this.description = description;
		return this;
	}

	public Long getDefaultAnalyzerId() {
		return defaultAnalyzerId;
	}

	public DBOSearchConfiguration setDefaultAnalyzerId(Long defaultAnalyzerId) {
		this.defaultAnalyzerId = defaultAnalyzerId;
		return this;
	}

	public Long getCreatedBy() {
		return createdBy;
	}

	public DBOSearchConfiguration setCreatedBy(Long createdBy) {
		this.createdBy = createdBy;
		return this;
	}

	public Timestamp getCreatedOn() {
		return createdOn;
	}

	public DBOSearchConfiguration setCreatedOn(Timestamp createdOn) {
		this.createdOn = createdOn;
		return this;
	}

	public Long getModifiedBy() {
		return modifiedBy;
	}

	public DBOSearchConfiguration setModifiedBy(Long modifiedBy) {
		this.modifiedBy = modifiedBy;
		return this;
	}

	public Timestamp getModifiedOn() {
		return modifiedOn;
	}

	public DBOSearchConfiguration setModifiedOn(Timestamp modifiedOn) {
		this.modifiedOn = modifiedOn;
		return this;
	}

	@Override
	public int hashCode() {
		return Objects.hash(id, etag, organizationName, name, description,
				defaultAnalyzerId, createdBy, createdOn, modifiedBy, modifiedOn);
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj) {
			return true;
		}
		if (!(obj instanceof DBOSearchConfiguration)) {
			return false;
		}
		DBOSearchConfiguration other = (DBOSearchConfiguration) obj;
		return Objects.equals(id, other.id)
				&& Objects.equals(etag, other.etag)
				&& Objects.equals(organizationName, other.organizationName)
				&& Objects.equals(name, other.name)
				&& Objects.equals(description, other.description)
				&& Objects.equals(defaultAnalyzerId, other.defaultAnalyzerId)
				&& Objects.equals(createdBy, other.createdBy)
				&& Objects.equals(createdOn, other.createdOn)
				&& Objects.equals(modifiedBy, other.modifiedBy)
				&& Objects.equals(modifiedOn, other.modifiedOn);
	}

	@Override
	public String toString() {
		return "DBOSearchConfiguration [id=" + id + ", etag=" + etag + ", organizationName=" + organizationName
				+ ", name=" + name + ", description=" + description + ", defaultAnalyzerId="
				+ defaultAnalyzerId + ", createdBy=" + createdBy + ", createdOn=" + createdOn
				+ ", modifiedBy=" + modifiedBy + ", modifiedOn=" + modifiedOn + "]";
	}
}
