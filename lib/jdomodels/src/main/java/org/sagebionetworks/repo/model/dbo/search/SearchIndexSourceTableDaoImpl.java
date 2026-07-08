package org.sagebionetworks.repo.model.dbo.search;

import static org.sagebionetworks.repo.model.dbo.persistence.table.DBOMaterializedViewId.DEFAULT_VERSION;

import java.util.List;
import java.util.Optional;

import org.sagebionetworks.repo.model.entity.IdAndVersion;
import org.sagebionetworks.repo.model.entity.IdAndVersionBuilder;
import org.sagebionetworks.repo.transactions.WriteTransaction;
import org.sagebionetworks.util.ValidateArgument;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class SearchIndexSourceTableDaoImpl implements SearchIndexSourceTableDao {

	private final JdbcTemplate jdbcTemplate;

	public SearchIndexSourceTableDaoImpl(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	@Override
	@WriteTransaction
	public void setSourceTable(IdAndVersion searchIndexId, IdAndVersion sourceTableId) {
		ValidateArgument.required(searchIndexId, "searchIndexId");
		ValidateArgument.required(sourceTableId, "sourceTableId");

		Long sourceId = sourceTableId.getId();
		Long sourceVersion = sourceTableId.getVersion().orElse(DEFAULT_VERSION);

		jdbcTemplate.update(
				"INSERT INTO SEARCH_INDEX_SOURCE_TABLE (SEARCH_INDEX_ID, ETAG, SOURCE_TABLE_ID, SOURCE_TABLE_VERSION)"
						+ " VALUES (?, UUID(), ?, ?) ON DUPLICATE KEY UPDATE ETAG = UUID(), SOURCE_TABLE_ID = ?,"
						+ " SOURCE_TABLE_VERSION = ?",
				searchIndexId.getId(), sourceId, sourceVersion, sourceId, sourceVersion);
	}

	@Override
	public List<Long> getDependentSearchIndexIds(IdAndVersion sourceTableId) {
		ValidateArgument.required(sourceTableId, "sourceTableId");

		return jdbcTemplate.queryForList(
				"SELECT SEARCH_INDEX_ID FROM SEARCH_INDEX_SOURCE_TABLE WHERE SOURCE_TABLE_ID = ?"
						+ " AND SOURCE_TABLE_VERSION = ? ORDER BY SEARCH_INDEX_ID",
				Long.class, sourceTableId.getId(), sourceTableId.getVersion().orElse(DEFAULT_VERSION));
	}

	@Override
	public Optional<IdAndVersion> getSourceTable(IdAndVersion searchIndexId) {
		ValidateArgument.required(searchIndexId, "searchIndexId");

		try {
			return Optional.of(jdbcTemplate.queryForObject(
					"SELECT SOURCE_TABLE_ID, SOURCE_TABLE_VERSION FROM SEARCH_INDEX_SOURCE_TABLE"
							+ " WHERE SEARCH_INDEX_ID = ?",
					(rs, rowNum) -> {
						IdAndVersionBuilder builder = IdAndVersion.newBuilder()
								.setId(rs.getLong("SOURCE_TABLE_ID"));
						long version = rs.getLong("SOURCE_TABLE_VERSION");
						if (!DEFAULT_VERSION.equals(version)) {
							builder.setVersion(version);
						}
						return builder.build();
					},
					searchIndexId.getId()));
		} catch (EmptyResultDataAccessException e) {
			return Optional.empty();
		}
	}

	@Override
	@WriteTransaction
	public void delete(IdAndVersion searchIndexId) {
		ValidateArgument.required(searchIndexId, "searchIndexId");

		jdbcTemplate.update("DELETE FROM SEARCH_INDEX_SOURCE_TABLE WHERE SEARCH_INDEX_ID = ?", searchIndexId.getId());
	}

}
