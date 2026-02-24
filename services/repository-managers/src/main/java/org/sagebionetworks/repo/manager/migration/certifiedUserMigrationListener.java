package org.sagebionetworks.repo.manager.migration;

import org.sagebionetworks.repo.model.AuthorizationConstants;
import org.sagebionetworks.repo.model.dbo.migration.MigratableTableDAO;
import org.sagebionetworks.repo.model.dbo.persistence.DBOCertifiedUsers;
import org.sagebionetworks.repo.model.dbo.persistence.DBOGroupMembers;
import org.sagebionetworks.repo.model.migration.MigrationType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

@Component
public class certifiedUserMigrationListener implements MigrationTypeListener<DBOGroupMembers> {


    private final MigratableTableDAO migratableTableDAO;
    private final NamedParameterJdbcTemplate namedParameterJdbcTemplate;

    public certifiedUserMigrationListener(MigratableTableDAO migratableTableDAO, NamedParameterJdbcTemplate namedParameterJdbcTemplate) {
        this.migratableTableDAO = migratableTableDAO;
        this.namedParameterJdbcTemplate = namedParameterJdbcTemplate;
    }

    @Override
    public boolean supports(MigrationType type) {
        return MigrationType.GROUP_MEMBERS.equals(type);
    }

    @Override
    public void beforeCreateOrUpdate(JdbcTemplate migrationJdbcTemplate, List<DBOGroupMembers> batch) {
    }

    @Override
    public void afterCreateOrUpdate(JdbcTemplate migrationJdbcTemplate, List<DBOGroupMembers> batch) {
        List<DBOCertifiedUsers> certifiedUsers = batch.stream()
                .filter(dboGroupMembers ->
                        dboGroupMembers.getGroupId()
                                .equals(AuthorizationConstants.BOOTSTRAP_PRINCIPAL.CERTIFIED_USERS.getPrincipalId()))
                .map(dboGroupMembers -> {
                    DBOCertifiedUsers certifiedUser = new DBOCertifiedUsers();
                    certifiedUser.setUserId(dboGroupMembers.getMemberId());
                    return certifiedUser;
                })
                .collect(Collectors.toList());


        try {
            migratableTableDAO.runWithKeyChecksIgnored(() -> {
                createOrUpdate(certifiedUsers);
                return null;
            });
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void createOrUpdate(List<DBOCertifiedUsers> batch) {
        if (batch == null || batch.isEmpty()) {
            return;
        }
        String sql = "INSERT IGNORE INTO CERTIFIED_USERS (USER_ID) VALUES (:userId)";
        SqlParameterSource[] batchArgs = batch.stream()
                .map(cu -> {
                    MapSqlParameterSource param = new MapSqlParameterSource();
                    param.addValue("userId", cu.getUserId());
                    return param;
                })
                .toArray(SqlParameterSource[]::new);
        namedParameterJdbcTemplate.batchUpdate(sql, batchArgs);
    }
}
