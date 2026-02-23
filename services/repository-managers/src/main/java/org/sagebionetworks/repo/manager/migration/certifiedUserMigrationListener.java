package org.sagebionetworks.repo.manager.migration;

import org.sagebionetworks.repo.model.AuthorizationConstants;
import org.sagebionetworks.repo.model.dbo.DatabaseObject;
import org.sagebionetworks.repo.model.dbo.migration.MigratableTableDAO;
import org.sagebionetworks.repo.model.dbo.persistence.DBOCertifiedUsers;
import org.sagebionetworks.repo.model.dbo.persistence.DBOGroupMembers;
import org.sagebionetworks.repo.model.migration.MigrationType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

@Component
public class certifiedUserMigrationListener implements MigrationTypeListener<DBOGroupMembers> {


    private final MigratableTableDAO migratableTableDAO;

    public certifiedUserMigrationListener(MigratableTableDAO migratableTableDAO) {
        this.migratableTableDAO = migratableTableDAO;
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

        List<DatabaseObject<?>> certifiedUserDbos = certifiedUsers.stream()
                .map(cu -> (DatabaseObject<?>) cu)
                .collect(Collectors.toList());

        // Add the member to the certified users group
        migratableTableDAO.createOrUpdate(MigrationType.CERTIFIED_USERS, certifiedUserDbos);

    }
}
