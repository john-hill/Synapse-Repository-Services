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

public class DBOSynonymSet implements MigratableDatabaseObject<DBOSynonymSet, DBOSynonymSet> {

	private Long id;
	private String etag;
	private String organizationName;
	private String name;
	private String description;
	private String rules;
	private Long createdBy;
	private Timestamp createdOn;
	private Long modifiedBy;
	private Timestamp modifiedOn;

	private static FieldColumn[] FIELDS = new FieldColumn[] {
		new FieldColumn("id", "ID", true).withIsBackupId(true),
		new FieldColumn("etag", "ETAG").withIsEtag(true),
		new FieldColumn("organizationName", "ORGANIZATION_NAME"),
		new FieldColumn("name", "NAME"),
		new FieldColumn("description", "DESCRIPTION"),
		new FieldColumn("rules", "RULES"),
		new FieldColumn("createdBy", "CREATED_BY"),
		new FieldColumn("createdOn", "CREATED_ON"),
		new FieldColumn("modifiedBy", "MODIFIED_BY"),
		new FieldColumn("modifiedOn", "MODIFIED_ON")
	};

	private static final TableMapping<DBOSynonymSet> TABLE_MAPPING = new TableMapping<>() {
		@Override
		public DBOSynonymSet mapRow(ResultSet rs, int rowNum) throws SQLException {
			DBOSynonymSet dbo = new DBOSynonymSet();
			dbo.setId(rs.getLong("ID"));
			dbo.setEtag(rs.getString("ETAG"));
			dbo.setOrganizationName(rs.getString("ORGANIZATION_NAME"));
			dbo.setName(rs.getString("NAME"));
			dbo.setDescription(rs.getString("DESCRIPTION"));
			dbo.setRules(rs.getString("RULES"));
			dbo.setCreatedBy(rs.getLong("CREATED_BY"));
			dbo.setCreatedOn(rs.getTimestamp("CREATED_ON"));
			dbo.setModifiedBy(rs.getLong("MODIFIED_BY"));
			dbo.setModifiedOn(rs.getTimestamp("MODIFIED_ON"));
			return dbo;
		}

		@Override
		public String getTableName() {
			return "SYNONYM_SET";
		}

		@Override
		public String getDDLFileName() {
			return "schema/SynonymSet-ddl.sql";
		}

		@Override
		public FieldColumn[] getFieldColumns() {
			return FIELDS;
		}

		@Override
		public Class<? extends DBOSynonymSet> getDBOClass() {
			return DBOSynonymSet.class;
		}
	};

	private static final BasicMigratableTableTranslation<DBOSynonymSet> MIGRATION_TRANSLATOR = new BasicMigratableTableTranslation<>();

	@Override
	public TableMapping<DBOSynonymSet> getTableMapping() {
		return TABLE_MAPPING;
	}

	@Override
	public MigrationType getMigratableTableType() {
		return MigrationType.SYNONYM_SET;
	}

	@Override
	public MigratableTableTranslation<DBOSynonymSet, DBOSynonymSet> getTranslator() {
		return MIGRATION_TRANSLATOR;
	}

	@Override
	public Class<? extends DBOSynonymSet> getBackupClass() {
		return DBOSynonymSet.class;
	}

	@Override
	public Class<? extends DBOSynonymSet> getDatabaseObjectClass() {
		return DBOSynonymSet.class;
	}

	@Override
	public List<MigratableDatabaseObject<?, ?>> getSecondaryTypes() {
		return null;
	}

	public Long getId() {
		return id;
	}

	public DBOSynonymSet setId(Long id) {
		this.id = id;
		return this;
	}

	public String getEtag() {
		return etag;
	}

	public DBOSynonymSet setEtag(String etag) {
		this.etag = etag;
		return this;
	}

	public String getOrganizationName() {
		return organizationName;
	}

	public DBOSynonymSet setOrganizationName(String organizationName) {
		this.organizationName = organizationName;
		return this;
	}

	public String getName() {
		return name;
	}

	public DBOSynonymSet setName(String name) {
		this.name = name;
		return this;
	}

	public String getDescription() {
		return description;
	}

	public DBOSynonymSet setDescription(String description) {
		this.description = description;
		return this;
	}

	public String getRules() {
		return rules;
	}

	public DBOSynonymSet setRules(String rules) {
		this.rules = rules;
		return this;
	}

	public Long getCreatedBy() {
		return createdBy;
	}

	public DBOSynonymSet setCreatedBy(Long createdBy) {
		this.createdBy = createdBy;
		return this;
	}

	public Timestamp getCreatedOn() {
		return createdOn;
	}

	public DBOSynonymSet setCreatedOn(Timestamp createdOn) {
		this.createdOn = createdOn;
		return this;
	}

	public Long getModifiedBy() {
		return modifiedBy;
	}

	public DBOSynonymSet setModifiedBy(Long modifiedBy) {
		this.modifiedBy = modifiedBy;
		return this;
	}

	public Timestamp getModifiedOn() {
		return modifiedOn;
	}

	public DBOSynonymSet setModifiedOn(Timestamp modifiedOn) {
		this.modifiedOn = modifiedOn;
		return this;
	}

	@Override
	public int hashCode() {
		return Objects.hash(createdBy, createdOn, description, etag, id, modifiedBy, modifiedOn, name, organizationName, rules);
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj) {
			return true;
		}
		if (!(obj instanceof DBOSynonymSet)) {
			return false;
		}
		DBOSynonymSet other = (DBOSynonymSet) obj;
		return Objects.equals(createdBy, other.createdBy) && Objects.equals(createdOn, other.createdOn)
				&& Objects.equals(description, other.description) && Objects.equals(etag, other.etag)
				&& Objects.equals(id, other.id) && Objects.equals(modifiedBy, other.modifiedBy)
				&& Objects.equals(modifiedOn, other.modifiedOn) && Objects.equals(name, other.name)
				&& Objects.equals(organizationName, other.organizationName) && Objects.equals(rules, other.rules);
	}

	@Override
	public String toString() {
		return "DBOSynonymSet [id=" + id + ", etag=" + etag + ", organizationName=" + organizationName + ", name=" + name
				+ ", description=" + description + ", rules=" + rules + ", createdBy=" + createdBy + ", createdOn="
				+ createdOn + ", modifiedBy=" + modifiedBy + ", modifiedOn=" + modifiedOn + "]";
	}

}
