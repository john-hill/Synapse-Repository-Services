package org.sagebionetworks.table.cluster.search;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.util.Date;
import java.util.Optional;

import javax.sql.DataSource;

import org.sagebionetworks.repo.model.jdo.KeyFactory;
import org.sagebionetworks.repo.model.search.table.SearchIndexState;
import org.sagebionetworks.repo.model.search.table.SearchIndexStatus;
import org.sagebionetworks.table.cluster.SQLUtils;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class SearchIndexStatusDaoImpl implements SearchIndexStatusDao {

	private static final String DDL = SQLUtils.loadSQLFromClasspath("schema/SearchIndexStatus.sql");

	private JdbcTemplate template;
	private TransactionTemplate writeTransactionTemplate;
	private TransactionTemplate readTransactionTemplate;

	@Override
	public void setDataSource(DataSource dataSource) {
		DataSourceTransactionManager transactionManager = new DataSourceTransactionManager(dataSource);
		this.writeTransactionTemplate = createTransactionTemplate(transactionManager, false);
		this.readTransactionTemplate = createTransactionTemplate(transactionManager, true);
		this.template = new JdbcTemplate(dataSource);
	}

	private static TransactionTemplate createTransactionTemplate(DataSourceTransactionManager transactionManager, boolean readOnly) {
		DefaultTransactionDefinition transactionDef = new DefaultTransactionDefinition();
		transactionDef.setIsolationLevel(Connection.TRANSACTION_READ_COMMITTED);
		transactionDef.setReadOnly(readOnly);
		transactionDef.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
		transactionDef.setName("SearchIndexStatusDaoImpl");
		return new TransactionTemplate(transactionManager, transactionDef);
	}

	@Override
	public void createTableIfDoesNotExist() {
		template.update(DDL);
	}

	@Override
	public void createOrUpdate(Long searchIndexId, SearchIndexState state, String errorMessage,
			String appliedConfigurationJson) {
		writeTransactionTemplate.executeWithoutResult(txStatus -> {
			String stateName = state.name();
			String activeName = SearchIndexState.ACTIVE.name();
			template.update(
					"INSERT INTO SEARCH_INDEX_STATUS (SEARCH_INDEX_ID, STATE, LAST_BUILD_ON, ERROR_MESSAGE, APPLIED_CONFIGURATION, CHANGED_ON)"
					+ " VALUES (?, ?, CASE WHEN ? = ? THEN NOW(3) ELSE NULL END, ?, ?, NOW(3))"
					+ " ON DUPLICATE KEY UPDATE STATE = ?, LAST_BUILD_ON = CASE WHEN ? = ? THEN NOW(3) ELSE LAST_BUILD_ON END,"
					+ " ERROR_MESSAGE = ?, APPLIED_CONFIGURATION = ?, CHANGED_ON = NOW(3)",
					searchIndexId, stateName, stateName, activeName, errorMessage, appliedConfigurationJson,
					stateName, stateName, activeName, errorMessage, appliedConfigurationJson);
		});
	}

	@Override
	public Optional<String> getAppliedConfiguration(Long searchIndexId) {
		return readTransactionTemplate.execute(txStatus -> {
			try {
				String json = template.queryForObject(
						"SELECT APPLIED_CONFIGURATION FROM SEARCH_INDEX_STATUS WHERE SEARCH_INDEX_ID = ?",
						String.class, searchIndexId);
				return Optional.ofNullable(json);
			} catch (EmptyResultDataAccessException e) {
				return Optional.empty();
			}
		});
	}

	@Override
	public Optional<SearchIndexState> getState(Long searchIndexId) {
		return readTransactionTemplate.execute(txStatus -> {
			try {
				String stateStr = template.queryForObject(
						"SELECT STATE FROM SEARCH_INDEX_STATUS WHERE SEARCH_INDEX_ID = ?",
						String.class, searchIndexId);
				return Optional.of(SearchIndexState.valueOf(stateStr));
			} catch (EmptyResultDataAccessException e) {
				return Optional.empty();
			}
		});
	}

	@Override
	public Optional<SearchIndexStatus> getStatus(Long searchIndexId) {
		return readTransactionTemplate.execute(txStatus -> {
			try {
				SearchIndexStatus status = template.queryForObject(
						"SELECT SEARCH_INDEX_ID, STATE, LAST_BUILD_ON, ERROR_MESSAGE, APPLIED_CONFIGURATION, CHANGED_ON"
						+ " FROM SEARCH_INDEX_STATUS WHERE SEARCH_INDEX_ID = ?",
						(ResultSet rs, int rowNum) -> {
							SearchIndexStatus s = new SearchIndexStatus();
							s.setSearchIndexId(KeyFactory.keyToString(rs.getLong("SEARCH_INDEX_ID")));
							s.setState(SearchIndexState.valueOf(rs.getString("STATE")));
							Timestamp lastBuild = rs.getTimestamp("LAST_BUILD_ON");
							if (lastBuild != null) {
								s.setLastBuildOn(new Date(lastBuild.getTime()));
							}
							s.setErrorMessage(rs.getString("ERROR_MESSAGE"));
							s.setAppliedConfiguration(rs.getString("APPLIED_CONFIGURATION"));
							Timestamp changedOn = rs.getTimestamp("CHANGED_ON");
							if (changedOn != null) {
								s.setChangedOn(new Date(changedOn.getTime()));
							}
							return s;
						}, searchIndexId);
				return Optional.ofNullable(status);
			} catch (EmptyResultDataAccessException e) {
				return Optional.empty();
			}
		});
	}

	@Override
	public boolean exists(Long searchIndexId) {
		return Boolean.TRUE.equals(readTransactionTemplate.execute(txStatus -> {
			Long count = template.queryForObject(
					"SELECT COUNT(*) FROM SEARCH_INDEX_STATUS WHERE SEARCH_INDEX_ID = ?",
					Long.class, searchIndexId);
			return count != null && count > 0;
		}));
	}

	@Override
	public void delete(Long searchIndexId) {
		writeTransactionTemplate.executeWithoutResult(txStatus ->
			template.update("DELETE FROM SEARCH_INDEX_STATUS WHERE SEARCH_INDEX_ID = ?", searchIndexId)
		);
	}

	@Override
	public void truncateAll() {
		writeTransactionTemplate.executeWithoutResult(txStatus ->
			template.update("DELETE FROM SEARCH_INDEX_STATUS WHERE SEARCH_INDEX_ID > -1")
		);
	}
}
