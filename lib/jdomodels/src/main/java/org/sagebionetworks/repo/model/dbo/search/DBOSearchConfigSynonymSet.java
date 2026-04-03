package org.sagebionetworks.repo.model.dbo.search;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;

import org.sagebionetworks.repo.model.dbo.FieldColumn;
import org.sagebionetworks.repo.model.dbo.MigratableDatabaseObject;
import org.sagebionetworks.repo.model.dbo.TableMapping;
import org.sagebionetworks.repo.model.dbo.migration.BasicMigratableTableTranslation;
import org.sagebionetworks.repo.model.dbo.migration.MigratableTableTranslation;
import org.sagebionetworks.repo.model.migration.MigrationType;

public class DBOSearchConfigSynonymSet implements MigratableDatabaseObject<DBOSearchConfigSynonymSet, DBOSearchConfigSynonymSet> {

	private static final FieldColumn[] FIELDS = new FieldColumn[] {
			new FieldColumn("configId", "CONFIG_ID", true).withIsBackupId(true),
			new FieldColumn("ordinal", "ORDINAL", true),
			new FieldColumn("synonymSetId", "SYNONYM_SET_ID"),
	};

	private Long configId;
	private Integer ordinal;
	private Long synonymSetId;

	private static final TableMapping<DBOSearchConfigSynonymSet> TABLE_MAPPING = new TableMapping<>() {
		@Override
		public DBOSearchConfigSynonymSet mapRow(ResultSet rs, int rowNum) throws SQLException {
			DBOSearchConfigSynonymSet dbo = new DBOSearchConfigSynonymSet();
			dbo.setConfigId(rs.getLong("CONFIG_ID"));
			dbo.setOrdinal(rs.getInt("ORDINAL"));
			dbo.setSynonymSetId(rs.getLong("SYNONYM_SET_ID"));
			return dbo;
		}

		@Override
		public String getTableName() {
			return "SEARCH_CONFIG_SYNONYM_SET";
		}

		@Override
		public String getDDLFileName() {
			return "schema/SearchConfigSynonymSet-ddl.sql";
		}

		@Override
		public FieldColumn[] getFieldColumns() {
			return FIELDS;
		}

		@Override
		public Class<? extends DBOSearchConfigSynonymSet> getDBOClass() {
			return DBOSearchConfigSynonymSet.class;
		}
	};

	@Override
	public TableMapping<DBOSearchConfigSynonymSet> getTableMapping() {
		return TABLE_MAPPING;
	}

	@Override
	public MigrationType getMigratableTableType() {
		return MigrationType.SEARCH_CONFIG_SYNONYM_SET;
	}

	private static final BasicMigratableTableTranslation<DBOSearchConfigSynonymSet> MIGRATION_TRANSLATOR = new BasicMigratableTableTranslation<>();

	@Override
	public MigratableTableTranslation<DBOSearchConfigSynonymSet, DBOSearchConfigSynonymSet> getTranslator() {
		return MIGRATION_TRANSLATOR;
	}

	@Override
	public Class<? extends DBOSearchConfigSynonymSet> getBackupClass() {
		return DBOSearchConfigSynonymSet.class;
	}

	@Override
	public Class<? extends DBOSearchConfigSynonymSet> getDatabaseObjectClass() {
		return DBOSearchConfigSynonymSet.class;
	}

	@Override
	public List<MigratableDatabaseObject<?, ?>> getSecondaryTypes() {
		return null;
	}

	public Long getConfigId() {
		return configId;
	}

	public void setConfigId(Long configId) {
		this.configId = configId;
	}

	public Integer getOrdinal() {
		return ordinal;
	}

	public void setOrdinal(Integer ordinal) {
		this.ordinal = ordinal;
	}

	public Long getSynonymSetId() {
		return synonymSetId;
	}

	public void setSynonymSetId(Long synonymSetId) {
		this.synonymSetId = synonymSetId;
	}

	@Override
	public int hashCode() {
		return Objects.hash(configId, ordinal, synonymSetId);
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj) {
			return true;
		}
		if (!(obj instanceof DBOSearchConfigSynonymSet)) {
			return false;
		}
		DBOSearchConfigSynonymSet other = (DBOSearchConfigSynonymSet) obj;
		return Objects.equals(configId, other.configId) && Objects.equals(ordinal, other.ordinal) && Objects.equals(synonymSetId, other.synonymSetId);
	}
}
