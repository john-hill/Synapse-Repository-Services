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

public class DBOSearchConfigColAnalyzer implements MigratableDatabaseObject<DBOSearchConfigColAnalyzer, DBOSearchConfigColAnalyzer> {

	private static final FieldColumn[] FIELDS = new FieldColumn[] {
			new FieldColumn("configId", "CONFIG_ID", true).withIsBackupId(true),
			new FieldColumn("ordinal", "ORDINAL", true),
			new FieldColumn("columnAnalyzerOverrideId", "COLUMN_ANALYZER_OVERRIDE_ID"),
	};

	private Long configId;
	private Integer ordinal;
	private Long columnAnalyzerOverrideId;

	private static final TableMapping<DBOSearchConfigColAnalyzer> TABLE_MAPPING = new TableMapping<>() {
		@Override
		public DBOSearchConfigColAnalyzer mapRow(ResultSet rs, int rowNum) throws SQLException {
			DBOSearchConfigColAnalyzer dbo = new DBOSearchConfigColAnalyzer();
			dbo.setConfigId(rs.getLong("CONFIG_ID"));
			dbo.setOrdinal(rs.getInt("ORDINAL"));
			dbo.setColumnAnalyzerOverrideId(rs.getLong("COLUMN_ANALYZER_OVERRIDE_ID"));
			return dbo;
		}

		@Override
		public String getTableName() {
			return "SEARCH_CONFIG_COL_ANALYZER";
		}

		@Override
		public String getDDLFileName() {
			return "schema/SearchConfigColAnalyzer-ddl.sql";
		}

		@Override
		public FieldColumn[] getFieldColumns() {
			return FIELDS;
		}

		@Override
		public Class<? extends DBOSearchConfigColAnalyzer> getDBOClass() {
			return DBOSearchConfigColAnalyzer.class;
		}
	};

	@Override
	public TableMapping<DBOSearchConfigColAnalyzer> getTableMapping() {
		return TABLE_MAPPING;
	}

	@Override
	public MigrationType getMigratableTableType() {
		return MigrationType.SEARCH_CONFIG_COL_ANALYZER;
	}

	private static final BasicMigratableTableTranslation<DBOSearchConfigColAnalyzer> MIGRATION_TRANSLATOR = new BasicMigratableTableTranslation<>();

	@Override
	public MigratableTableTranslation<DBOSearchConfigColAnalyzer, DBOSearchConfigColAnalyzer> getTranslator() {
		return MIGRATION_TRANSLATOR;
	}

	@Override
	public Class<? extends DBOSearchConfigColAnalyzer> getBackupClass() {
		return DBOSearchConfigColAnalyzer.class;
	}

	@Override
	public Class<? extends DBOSearchConfigColAnalyzer> getDatabaseObjectClass() {
		return DBOSearchConfigColAnalyzer.class;
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

	public Long getColumnAnalyzerOverrideId() {
		return columnAnalyzerOverrideId;
	}

	public void setColumnAnalyzerOverrideId(Long columnAnalyzerOverrideId) {
		this.columnAnalyzerOverrideId = columnAnalyzerOverrideId;
	}

	@Override
	public int hashCode() {
		return Objects.hash(configId, ordinal, columnAnalyzerOverrideId);
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj) {
			return true;
		}
		if (!(obj instanceof DBOSearchConfigColAnalyzer)) {
			return false;
		}
		DBOSearchConfigColAnalyzer other = (DBOSearchConfigColAnalyzer) obj;
		return Objects.equals(configId, other.configId) && Objects.equals(ordinal, other.ordinal) && Objects.equals(columnAnalyzerOverrideId, other.columnAnalyzerOverrideId);
	}
}
