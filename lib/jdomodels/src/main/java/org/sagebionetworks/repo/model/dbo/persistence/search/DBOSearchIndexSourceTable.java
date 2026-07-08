package org.sagebionetworks.repo.model.dbo.persistence.search;

import static org.sagebionetworks.repo.model.query.jdo.SqlConstants.COL_SEARCH_INDEX_SOURCE_ETAG;
import static org.sagebionetworks.repo.model.query.jdo.SqlConstants.COL_SEARCH_INDEX_SOURCE_INDEX_ID;
import static org.sagebionetworks.repo.model.query.jdo.SqlConstants.COL_SEARCH_INDEX_SOURCE_TABLE_ID;
import static org.sagebionetworks.repo.model.query.jdo.SqlConstants.COL_SEARCH_INDEX_SOURCE_TABLE_VERSION;
import static org.sagebionetworks.repo.model.query.jdo.SqlConstants.DDL_SEARCH_INDEX_SOURCE_TABLE;
import static org.sagebionetworks.repo.model.query.jdo.SqlConstants.TABLE_SEARCH_INDEX_SOURCE_TABLE;

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

/**
 * Reverse-lookup edge mapping a source table/view ({@code SOURCE_TABLE_ID} /
 * {@code SOURCE_TABLE_VERSION}) to the SearchIndex that depends on it. A SearchIndex
 * references exactly one source entity, so this is keyed on the SearchIndex node id.
 * The row is rewritten when the SearchIndex schema is (re)registered and removed when the
 * node is deleted (via the {@code ON DELETE CASCADE} foreign key to {@code NODE}).
 */
public class DBOSearchIndexSourceTable
		implements MigratableDatabaseObject<DBOSearchIndexSourceTable, DBOSearchIndexSourceTable> {

	private static final MigratableTableTranslation<DBOSearchIndexSourceTable, DBOSearchIndexSourceTable> TRANSLATOR = new BasicMigratableTableTranslation<>();

	private static final FieldColumn[] FIELDS = new FieldColumn[] {
		new FieldColumn("searchIndexId", COL_SEARCH_INDEX_SOURCE_INDEX_ID, true).withIsBackupId(true),
		new FieldColumn("etag", COL_SEARCH_INDEX_SOURCE_ETAG).withIsEtag(true),
		new FieldColumn("sourceTableId", COL_SEARCH_INDEX_SOURCE_TABLE_ID),
		new FieldColumn("sourceTableVersion", COL_SEARCH_INDEX_SOURCE_TABLE_VERSION)
	};

	private static final TableMapping<DBOSearchIndexSourceTable> TABLE_MAPPER = new TableMapping<DBOSearchIndexSourceTable>() {

		@Override
		public DBOSearchIndexSourceTable mapRow(ResultSet rs, int rowNum) throws SQLException {
			DBOSearchIndexSourceTable dbo = new DBOSearchIndexSourceTable();
			dbo.setSearchIndexId(rs.getLong(COL_SEARCH_INDEX_SOURCE_INDEX_ID));
			dbo.setEtag(rs.getString(COL_SEARCH_INDEX_SOURCE_ETAG));
			dbo.setSourceTableId(rs.getLong(COL_SEARCH_INDEX_SOURCE_TABLE_ID));
			dbo.setSourceTableVersion(rs.getLong(COL_SEARCH_INDEX_SOURCE_TABLE_VERSION));
			return dbo;
		}

		@Override
		public String getTableName() {
			return TABLE_SEARCH_INDEX_SOURCE_TABLE;
		}

		@Override
		public FieldColumn[] getFieldColumns() {
			return FIELDS;
		}

		@Override
		public String getDDLFileName() {
			return DDL_SEARCH_INDEX_SOURCE_TABLE;
		}

		@Override
		public Class<? extends DBOSearchIndexSourceTable> getDBOClass() {
			return DBOSearchIndexSourceTable.class;
		}
	};

	private Long searchIndexId;
	private String etag;
	private Long sourceTableId;
	private Long sourceTableVersion;

	public DBOSearchIndexSourceTable() {}

	public Long getSearchIndexId() {
		return searchIndexId;
	}

	public void setSearchIndexId(Long searchIndexId) {
		this.searchIndexId = searchIndexId;
	}

	public String getEtag() {
		return etag;
	}

	public void setEtag(String etag) {
		this.etag = etag;
	}

	public Long getSourceTableId() {
		return sourceTableId;
	}

	public void setSourceTableId(Long sourceTableId) {
		this.sourceTableId = sourceTableId;
	}

	public Long getSourceTableVersion() {
		return sourceTableVersion;
	}

	public void setSourceTableVersion(Long sourceTableVersion) {
		this.sourceTableVersion = sourceTableVersion;
	}

	@Override
	public TableMapping<DBOSearchIndexSourceTable> getTableMapping() {
		return TABLE_MAPPER;
	}

	@Override
	public MigrationType getMigratableTableType() {
		return MigrationType.SEARCH_INDEX_SOURCE_TABLE;
	}

	@Override
	public MigratableTableTranslation<DBOSearchIndexSourceTable, DBOSearchIndexSourceTable> getTranslator() {
		return TRANSLATOR;
	}

	@Override
	public Class<? extends DBOSearchIndexSourceTable> getBackupClass() {
		return DBOSearchIndexSourceTable.class;
	}

	@Override
	public Class<? extends DBOSearchIndexSourceTable> getDatabaseObjectClass() {
		return DBOSearchIndexSourceTable.class;
	}

	@Override
	public List<MigratableDatabaseObject<?, ?>> getSecondaryTypes() {
		return null;
	}

	@Override
	public int hashCode() {
		return Objects.hash(etag, searchIndexId, sourceTableId, sourceTableVersion);
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj) {
			return true;
		}
		if (obj == null) {
			return false;
		}
		if (getClass() != obj.getClass()) {
			return false;
		}
		DBOSearchIndexSourceTable other = (DBOSearchIndexSourceTable) obj;
		return Objects.equals(etag, other.etag) && Objects.equals(searchIndexId, other.searchIndexId)
				&& Objects.equals(sourceTableId, other.sourceTableId)
				&& Objects.equals(sourceTableVersion, other.sourceTableVersion);
	}

	@Override
	public String toString() {
		return "DBOSearchIndexSourceTable [searchIndexId=" + searchIndexId + ", etag=" + etag + ", sourceTableId="
				+ sourceTableId + ", sourceTableVersion=" + sourceTableVersion + "]";
	}

}
