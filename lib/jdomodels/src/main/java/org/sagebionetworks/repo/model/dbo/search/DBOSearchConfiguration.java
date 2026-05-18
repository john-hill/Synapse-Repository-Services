package org.sagebionetworks.repo.model.dbo.search;

import static org.sagebionetworks.repo.model.query.jdo.SqlConstants.COL_SEARCH_CONFIG_COL_ANALYZER_OVERRIDES;
import static org.sagebionetworks.repo.model.query.jdo.SqlConstants.COL_SEARCH_CONFIG_CREATED_BY;
import static org.sagebionetworks.repo.model.query.jdo.SqlConstants.COL_SEARCH_CONFIG_CREATED_ON;
import static org.sagebionetworks.repo.model.query.jdo.SqlConstants.COL_SEARCH_CONFIG_DEFAULT_INDEX_ANALYZER;
import static org.sagebionetworks.repo.model.query.jdo.SqlConstants.COL_SEARCH_CONFIG_DEFAULT_SEARCH_ANALYZER;
import static org.sagebionetworks.repo.model.query.jdo.SqlConstants.COL_SEARCH_CONFIG_SYNONYM_SETS;
import static org.sagebionetworks.repo.model.query.jdo.SqlConstants.COL_SEARCH_CONFIG_DESCRIPTION;
import static org.sagebionetworks.repo.model.query.jdo.SqlConstants.COL_SEARCH_CONFIG_ETAG;
import static org.sagebionetworks.repo.model.query.jdo.SqlConstants.COL_SEARCH_CONFIG_ID;
import static org.sagebionetworks.repo.model.query.jdo.SqlConstants.COL_SEARCH_CONFIG_MODIFIED_BY;
import static org.sagebionetworks.repo.model.query.jdo.SqlConstants.COL_SEARCH_CONFIG_MODIFIED_ON;
import static org.sagebionetworks.repo.model.query.jdo.SqlConstants.COL_SEARCH_CONFIG_NAME;
import static org.sagebionetworks.repo.model.query.jdo.SqlConstants.COL_SEARCH_CONFIG_ORGANIZATION_NAME;
import static org.sagebionetworks.repo.model.query.jdo.SqlConstants.DDL_SEARCH_CONFIGURATION;
import static org.sagebionetworks.repo.model.query.jdo.SqlConstants.TABLE_SEARCH_CONFIGURATION;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import org.sagebionetworks.repo.model.dbo.FieldColumn;
import org.sagebionetworks.repo.model.dbo.MigratableDatabaseObject;
import org.sagebionetworks.repo.model.dbo.TableMapping;
import org.sagebionetworks.repo.model.dbo.migration.MigratableTableTranslation;
import org.sagebionetworks.repo.model.migration.MigrationType;
import org.sagebionetworks.util.TemporaryCode;

public class DBOSearchConfiguration implements MigratableDatabaseObject<DBOSearchConfiguration, DBOSearchConfiguration> {

	private static final FieldColumn[] FIELDS = new FieldColumn[] {
			new FieldColumn("id", COL_SEARCH_CONFIG_ID, true).withIsBackupId(true),
			new FieldColumn("etag", COL_SEARCH_CONFIG_ETAG).withIsEtag(true),
			new FieldColumn("organizationName", COL_SEARCH_CONFIG_ORGANIZATION_NAME),
			new FieldColumn("name", COL_SEARCH_CONFIG_NAME),
			new FieldColumn("description", COL_SEARCH_CONFIG_DESCRIPTION),
			new FieldColumn("defaultIndexAnalyzer", COL_SEARCH_CONFIG_DEFAULT_INDEX_ANALYZER),
			new FieldColumn("defaultSearchAnalyzer", COL_SEARCH_CONFIG_DEFAULT_SEARCH_ANALYZER),
			new FieldColumn("synonymSetsJson", COL_SEARCH_CONFIG_SYNONYM_SETS),
			new FieldColumn("columnAnalyzerOverridesJson", COL_SEARCH_CONFIG_COL_ANALYZER_OVERRIDES),
			new FieldColumn("createdBy", COL_SEARCH_CONFIG_CREATED_BY),
			new FieldColumn("createdOn", COL_SEARCH_CONFIG_CREATED_ON),
			new FieldColumn("modifiedBy", COL_SEARCH_CONFIG_MODIFIED_BY),
			new FieldColumn("modifiedOn", COL_SEARCH_CONFIG_MODIFIED_ON),
	};

	private Long id;
	private String etag;
	private String organizationName;
	private String name;
	private String description;
	private String defaultIndexAnalyzer;
	private String defaultSearchAnalyzer;
	private String synonymSetsJson;
	private String columnAnalyzerOverridesJson;
	private Long createdBy;
	private Timestamp createdOn;
	private Long modifiedBy;
	private Timestamp modifiedOn;

	// PLFM-XXXXX bridge: production backups still serialize the legacy <defaultAnalyzer>
	// XML element (single qname). Caught here so deserialization does not fail. The
	// translator below discards the value — the new defaultIndexAnalyzer / defaultSearchAnalyzer
	// columns are nullable, and per-column resolution falls back to the system default for
	// each column's data type when both are null. No FieldColumn entry — never read from or
	// written to the database.
	@TemporaryCode(author = "BryanFauble", comment = "Remove after the new stack has rolled to prod and the next migration cycle has flushed legacy backup shapes.")
	private String defaultAnalyzer;

	private static final TableMapping<DBOSearchConfiguration> TABLE_MAPPING = new TableMapping<>() {
		@Override
		public DBOSearchConfiguration mapRow(ResultSet rs, int rowNum) throws SQLException {
			DBOSearchConfiguration dbo = new DBOSearchConfiguration();
			dbo.setId(rs.getLong(COL_SEARCH_CONFIG_ID));
			dbo.setEtag(rs.getString(COL_SEARCH_CONFIG_ETAG));
			dbo.setOrganizationName(rs.getString(COL_SEARCH_CONFIG_ORGANIZATION_NAME));
			dbo.setName(rs.getString(COL_SEARCH_CONFIG_NAME));
			dbo.setDescription(rs.getString(COL_SEARCH_CONFIG_DESCRIPTION));
			dbo.setDefaultIndexAnalyzer(rs.getString(COL_SEARCH_CONFIG_DEFAULT_INDEX_ANALYZER));
			dbo.setDefaultSearchAnalyzer(rs.getString(COL_SEARCH_CONFIG_DEFAULT_SEARCH_ANALYZER));
			dbo.setSynonymSetsJson(rs.getString(COL_SEARCH_CONFIG_SYNONYM_SETS));
			dbo.setColumnAnalyzerOverridesJson(rs.getString(COL_SEARCH_CONFIG_COL_ANALYZER_OVERRIDES));
			dbo.setCreatedBy(rs.getLong(COL_SEARCH_CONFIG_CREATED_BY));
			dbo.setCreatedOn(rs.getTimestamp(COL_SEARCH_CONFIG_CREATED_ON));
			dbo.setModifiedBy(rs.getLong(COL_SEARCH_CONFIG_MODIFIED_BY));
			dbo.setModifiedOn(rs.getTimestamp(COL_SEARCH_CONFIG_MODIFIED_ON));
			return dbo;
		}

		@Override
		public String getTableName() {
			return TABLE_SEARCH_CONFIGURATION;
		}

		@Override
		public String getDDLFileName() {
			return DDL_SEARCH_CONFIGURATION;
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

	@TemporaryCode(author = "BryanFauble", comment = "Replace with BasicMigratableTableTranslation after legacy <defaultAnalyzer> backups can no longer arrive.")
	private static final MigratableTableTranslation<DBOSearchConfiguration, DBOSearchConfiguration> MIGRATION_TRANSLATOR =
			new MigratableTableTranslation<DBOSearchConfiguration, DBOSearchConfiguration>() {
		@Override
		public DBOSearchConfiguration createDatabaseObjectFromBackup(DBOSearchConfiguration backup) {
			// Legacy <defaultAnalyzer> values are discarded. The new defaultIndexAnalyzer /
			// defaultSearchAnalyzer columns are nullable and per-column resolution falls back
			// to the system default analyzer for each column's data type.
			// The interior shape of these JSON columns also changed in this PR; null them out
			// rather than persisting an unreadable blob. Curators recreate via the REST API.
			backup.setSynonymSetsJson(null);
			backup.setColumnAnalyzerOverridesJson(null);
			backup.setDefaultAnalyzer(null);
			return backup;
		}

		@Override
		public DBOSearchConfiguration createBackupFromDatabaseObject(DBOSearchConfiguration dbo) {
			return dbo;
		}
	};

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
		return Collections.emptyList();
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

	public String getDefaultIndexAnalyzer() {
		return defaultIndexAnalyzer;
	}

	public DBOSearchConfiguration setDefaultIndexAnalyzer(String defaultIndexAnalyzer) {
		this.defaultIndexAnalyzer = defaultIndexAnalyzer;
		return this;
	}

	public String getDefaultSearchAnalyzer() {
		return defaultSearchAnalyzer;
	}

	public DBOSearchConfiguration setDefaultSearchAnalyzer(String defaultSearchAnalyzer) {
		this.defaultSearchAnalyzer = defaultSearchAnalyzer;
		return this;
	}

	@TemporaryCode(author = "BryanFauble", comment = "Remove after the new stack has rolled to prod and the next migration cycle has flushed legacy backup shapes.")
	public String getDefaultAnalyzer() {
		return defaultAnalyzer;
	}

	@TemporaryCode(author = "BryanFauble", comment = "Remove after the new stack has rolled to prod and the next migration cycle has flushed legacy backup shapes.")
	public DBOSearchConfiguration setDefaultAnalyzer(String defaultAnalyzer) {
		this.defaultAnalyzer = defaultAnalyzer;
		return this;
	}

	public String getSynonymSetsJson() {
		return synonymSetsJson;
	}

	public DBOSearchConfiguration setSynonymSetsJson(String synonymSetsJson) {
		this.synonymSetsJson = synonymSetsJson;
		return this;
	}

	public String getColumnAnalyzerOverridesJson() {
		return columnAnalyzerOverridesJson;
	}

	public DBOSearchConfiguration setColumnAnalyzerOverridesJson(String columnAnalyzerOverridesJson) {
		this.columnAnalyzerOverridesJson = columnAnalyzerOverridesJson;
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
				defaultIndexAnalyzer, defaultSearchAnalyzer, synonymSetsJson, columnAnalyzerOverridesJson,
				createdBy, createdOn, modifiedBy, modifiedOn);
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
				&& Objects.equals(defaultIndexAnalyzer, other.defaultIndexAnalyzer)
				&& Objects.equals(defaultSearchAnalyzer, other.defaultSearchAnalyzer)
				&& Objects.equals(synonymSetsJson, other.synonymSetsJson)
				&& Objects.equals(columnAnalyzerOverridesJson, other.columnAnalyzerOverridesJson)
				&& Objects.equals(createdBy, other.createdBy)
				&& Objects.equals(createdOn, other.createdOn)
				&& Objects.equals(modifiedBy, other.modifiedBy)
				&& Objects.equals(modifiedOn, other.modifiedOn);
	}

	@Override
	public String toString() {
		return "DBOSearchConfiguration [id=" + id + ", etag=" + etag + ", organizationName=" + organizationName
				+ ", name=" + name + ", description=" + description
				+ ", defaultIndexAnalyzer=" + defaultIndexAnalyzer + ", defaultSearchAnalyzer=" + defaultSearchAnalyzer
				+ ", synonymSetsJson=" + synonymSetsJson
				+ ", columnAnalyzerOverridesJson=" + columnAnalyzerOverridesJson
				+ ", createdBy=" + createdBy + ", createdOn=" + createdOn
				+ ", modifiedBy=" + modifiedBy + ", modifiedOn=" + modifiedOn + "]";
	}
}
