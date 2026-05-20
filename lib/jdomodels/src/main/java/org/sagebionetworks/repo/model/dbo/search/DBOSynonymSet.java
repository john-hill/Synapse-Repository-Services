package org.sagebionetworks.repo.model.dbo.search;

import static org.sagebionetworks.repo.model.query.jdo.SqlConstants.COL_SYNSET_CREATED_BY;
import static org.sagebionetworks.repo.model.query.jdo.SqlConstants.COL_SYNSET_CREATED_ON;
import static org.sagebionetworks.repo.model.query.jdo.SqlConstants.COL_SYNSET_DESCRIPTION;
import static org.sagebionetworks.repo.model.query.jdo.SqlConstants.COL_SYNSET_ETAG;
import static org.sagebionetworks.repo.model.query.jdo.SqlConstants.COL_SYNSET_ID;
import static org.sagebionetworks.repo.model.query.jdo.SqlConstants.COL_SYNSET_MODIFIED_BY;
import static org.sagebionetworks.repo.model.query.jdo.SqlConstants.COL_SYNSET_MODIFIED_ON;
import static org.sagebionetworks.repo.model.query.jdo.SqlConstants.COL_SYNSET_NAME;
import static org.sagebionetworks.repo.model.query.jdo.SqlConstants.COL_SYNSET_ORGANIZATION_NAME;
import static org.sagebionetworks.repo.model.query.jdo.SqlConstants.COL_SYNSET_DEFINITION;
import static org.sagebionetworks.repo.model.query.jdo.SqlConstants.DDL_SYNONYM_SET;
import static org.sagebionetworks.repo.model.query.jdo.SqlConstants.TABLE_SYNONYM_SET;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Objects;

import org.sagebionetworks.repo.model.dbo.FieldColumn;
import org.sagebionetworks.repo.model.dbo.MigratableDatabaseObject;
import org.sagebionetworks.repo.model.dbo.TableMapping;
import org.sagebionetworks.repo.model.dbo.migration.MigratableTableTranslation;
import org.sagebionetworks.repo.model.migration.MigrationType;
import org.sagebionetworks.util.TemporaryCode;

public class DBOSynonymSet implements MigratableDatabaseObject<DBOSynonymSet, DBOSynonymSet> {

	private Long id;
	private String etag;
	private String organizationName;
	private String name;
	private String description;
	private String definition;
	private Long createdBy;
	private Timestamp createdOn;
	private Long modifiedBy;
	private Timestamp modifiedOn;

	private static FieldColumn[] FIELDS = new FieldColumn[] {
		new FieldColumn("id", COL_SYNSET_ID, true).withIsBackupId(true),
		new FieldColumn("etag", COL_SYNSET_ETAG).withIsEtag(true),
		new FieldColumn("organizationName", COL_SYNSET_ORGANIZATION_NAME),
		new FieldColumn("name", COL_SYNSET_NAME),
		new FieldColumn("description", COL_SYNSET_DESCRIPTION),
		new FieldColumn("definition", COL_SYNSET_DEFINITION),
		new FieldColumn("createdBy", COL_SYNSET_CREATED_BY),
		new FieldColumn("createdOn", COL_SYNSET_CREATED_ON),
		new FieldColumn("modifiedBy", COL_SYNSET_MODIFIED_BY),
		new FieldColumn("modifiedOn", COL_SYNSET_MODIFIED_ON)
	};

	private static final TableMapping<DBOSynonymSet> TABLE_MAPPING = new TableMapping<>() {
		@Override
		public DBOSynonymSet mapRow(ResultSet rs, int rowNum) throws SQLException {
			DBOSynonymSet dbo = new DBOSynonymSet();
			dbo.setId(rs.getLong(COL_SYNSET_ID));
			dbo.setEtag(rs.getString(COL_SYNSET_ETAG));
			dbo.setOrganizationName(rs.getString(COL_SYNSET_ORGANIZATION_NAME));
			dbo.setName(rs.getString(COL_SYNSET_NAME));
			dbo.setDescription(rs.getString(COL_SYNSET_DESCRIPTION));
			dbo.setDefinition(rs.getString(COL_SYNSET_DEFINITION));
			dbo.setCreatedBy(rs.getLong(COL_SYNSET_CREATED_BY));
			dbo.setCreatedOn(rs.getTimestamp(COL_SYNSET_CREATED_ON));
			dbo.setModifiedBy(rs.getLong(COL_SYNSET_MODIFIED_BY));
			dbo.setModifiedOn(rs.getTimestamp(COL_SYNSET_MODIFIED_ON));
			return dbo;
		}

		@Override
		public String getTableName() {
			return TABLE_SYNONYM_SET;
		}

		@Override
		public String getDDLFileName() {
			return DDL_SYNONYM_SET;
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

	// Migration bridge:a valid (empty) OpenSearch synonym_graph token-filter definition.
	// Written into the DEFINITION column for any row whose backup carries the legacy
	// <rules> shape, so the NOT NULL constraint passes on restore. Curators are expected to
	// DELETE & re-POST these rows through the SynonymSet REST API after migration.
	@TemporaryCode(author = "BryanFauble", comment = "Remove after the new stack has rolled to prod and the next migration cycle has flushed legacy backup shapes.")
	static final String PLACEHOLDER_DEFINITION = "{\"type\":\"synonym_graph\",\"synonyms\":[]}";

	// Migration bridge:production backups still serialize the legacy <rules> XML element
	// (a JSON array of {ruleType, ...} entries). Catch it here so deserialization does not
	// fail. The translator below discards the value and writes PLACEHOLDER_DEFINITION into
	// the new DEFINITION column. No FieldColumn entry — this field is never read from or
	// written to the database.
	@TemporaryCode(author = "BryanFauble", comment = "Remove after the new stack has rolled to prod and the next migration cycle has flushed legacy backup shapes.")
	private String rules;

	@TemporaryCode(author = "BryanFauble", comment = "Replace with BasicMigratableTableTranslation after legacy <rules> backups can no longer arrive.")
	private static final MigratableTableTranslation<DBOSynonymSet, DBOSynonymSet> MIGRATION_TRANSLATOR =
			new MigratableTableTranslation<DBOSynonymSet, DBOSynonymSet>() {
		@Override
		public DBOSynonymSet createDatabaseObjectFromBackup(DBOSynonymSet backup) {
			if (backup.getDefinition() == null) {
				backup.setDefinition(PLACEHOLDER_DEFINITION);
			}
			backup.setRules(null);
			return backup;
		}

		@Override
		public DBOSynonymSet createBackupFromDatabaseObject(DBOSynonymSet dbo) {
			return dbo;
		}
	};

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

	public String getDefinition() {
		return definition;
	}

	public DBOSynonymSet setDefinition(String definition) {
		this.definition = definition;
		return this;
	}

	@TemporaryCode(author = "BryanFauble", comment = "Remove after the new stack has rolled to prod and the next migration cycle has flushed legacy backup shapes.")
	public String getRules() {
		return rules;
	}

	@TemporaryCode(author = "BryanFauble", comment = "Remove after the new stack has rolled to prod and the next migration cycle has flushed legacy backup shapes.")
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
		return Objects.hash(createdBy, createdOn, description, etag, id, modifiedBy, modifiedOn, name, organizationName, definition);
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
				&& Objects.equals(organizationName, other.organizationName) && Objects.equals(definition, other.definition);
	}

	@Override
	public String toString() {
		return "DBOSynonymSet [id=" + id + ", etag=" + etag + ", organizationName=" + organizationName + ", name=" + name
				+ ", description=" + description + ", definition=" + definition + ", createdBy=" + createdBy + ", createdOn="
				+ createdOn + ", modifiedBy=" + modifiedBy + ", modifiedOn=" + modifiedOn + "]";
	}

}
