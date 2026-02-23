package org.sagebionetworks.repo.model.dbo.persistence;

import org.sagebionetworks.repo.model.dbo.FieldColumn;
import org.sagebionetworks.repo.model.dbo.MigratableDatabaseObject;
import org.sagebionetworks.repo.model.dbo.TableMapping;
import org.sagebionetworks.repo.model.dbo.migration.MigratableTableTranslation;
import org.sagebionetworks.repo.model.migration.MigrationType;
import org.sagebionetworks.repo.model.query.jdo.SqlConstants;

import java.util.List;
import java.util.Objects;

public class DBOCertifiedUsers implements MigratableDatabaseObject<DBOCertifiedUsers, DBOCertifiedUsers> {

    private Long userId;


    private static FieldColumn[] FIELDS = new FieldColumn[]{
            new FieldColumn("userId", SqlConstants.COL_CERTIFIED_USERS_USER_ID, true).withIsBackupId(true)
    };

    @Override
    public TableMapping<DBOCertifiedUsers> getTableMapping() {
        return new TableMapping<DBOCertifiedUsers>() {

            @Override
            public DBOCertifiedUsers mapRow(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
                DBOCertifiedUsers dbo = new DBOCertifiedUsers();
                dbo.setUserId(rs.getLong(SqlConstants.COL_CERTIFIED_USERS_USER_ID));
                return dbo;
            }

            @Override
            public String getTableName() {
                return SqlConstants.TABLE_CERTIFIED_USERS;
            }

            @Override
            public String getDDLFileName() {
                return SqlConstants.DDL_FILE_CERTIFIED_USERS;
            }

            @Override
            public FieldColumn[] getFieldColumns() {
                return FIELDS;
            }

            @Override
            public Class<? extends DBOCertifiedUsers> getDBOClass() {
                return DBOCertifiedUsers.class;
            }
        };
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    @Override
    public MigrationType getMigratableTableType() {
        return MigrationType.CERTIFIED_USERS;
    }

    @Override
    public MigratableTableTranslation<DBOCertifiedUsers, DBOCertifiedUsers> getTranslator() {
        return null;
    }

    @Override
    public Class<? extends DBOCertifiedUsers> getBackupClass() {
        return null;
    }

    @Override
    public Class<? extends DBOCertifiedUsers> getDatabaseObjectClass() {
        return null;
    }

    @Override
    public List<MigratableDatabaseObject<?, ?>> getSecondaryTypes() {
        return null;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        DBOCertifiedUsers that = (DBOCertifiedUsers) o;
        return Objects.equals(userId, that.userId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(userId);
    }
}
