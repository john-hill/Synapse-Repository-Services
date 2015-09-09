package org.sagebionetworks.table.worker;

import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.sagebionetworks.repo.manager.migration.TableFileMigration;
import org.sagebionetworks.repo.model.dbo.persistence.table.DBOTableRowChange;
import org.sagebionetworks.workers.util.progress.ProgressCallback;
import org.sagebionetworks.workers.util.progress.ProgressingRunner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

public class TemporaryTableFileMigration implements ProgressingRunner<Void> {
	
	private static final Logger log = LogManager
			.getLogger(TemporaryTableFileMigration.class);

	enum State {
		SUCCESS, FAILURE
	}

	private static String TEMP_TABLE_DDL = "CREATE TABLE IF NOT EXISTS `MIGRATED_TABLE_ROW_CHANGE` ( `TABLE_ID` bigint(20) NOT NULL,"
			+ "`ROW_VERSION` bigint(20) NOT NULL,"
			+ " `STATE` ENUM('SUCCESS', 'FAILURE'),"
			+ " `ETAG` char(36) NOT NULL,"
			+ " PRIMARY KEY (`TABLE_ID`,`ROW_VERSION`),"
			+ " CONSTRAINT `TABLE_ID_FK` FOREIGN KEY (`TABLE_ID`) REFERENCES `TABLE_ID_SEQUENCE` (`TABLE_ID`) ON DELETE CASCADE)";

	RowMapper<DBOTableRowChange> mapper = new DBOTableRowChange()
			.getTableMapping();

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private TableFileMigration tableFileMigration;

	@Override
	public void run(ProgressCallback<Void> progressCallback) throws Exception {
		// List all changes that need to migrate
		List<DBOTableRowChange> toMigrate = listChangesToMigrate();
		for (DBOTableRowChange change : toMigrate) {
			State state = null;
			try {
				progressCallback.progressMade(null);
				tableFileMigration.attemptTableFileMigration(change);
				state = State.SUCCESS;
			} catch (Throwable e) {
				state = State.FAILURE;
				log.error("Failed on: "+change, e);
			}
			updateState(change.getTableId(), change.getRowVersion(), state, change.getEtag());
		}

	}

	public void initialize() {
		jdbcTemplate.update(TEMP_TABLE_DDL);
	}

	List<DBOTableRowChange> listChangesToMigrate() {
		// Find all changes that need to be migrated.
		return jdbcTemplate
				.query("SELECT * FROM TABLE_ROW_CHANGE WHERE ETAG NOT IN (SELECT ETAG FROM MIGRATED_TABLE_ROW_CHANGE) LIMIT 10000",
						mapper);
	}

	private void updateState(Long tableId, Long rowVersion, State state,
			String etag) {
		jdbcTemplate
				.update("INSERT INTO MIGRATED_TABLE_ROW_CHANGE (TABLE_ID, ROW_VERSION, STATE, ETAG) VALUES (?,?,?,?) ON DUPLICATE KEY UPDATE STATE = ? ETAG = ? ",
						tableId, rowVersion, state.name(), etag, state.name(), etag);
	}

}
