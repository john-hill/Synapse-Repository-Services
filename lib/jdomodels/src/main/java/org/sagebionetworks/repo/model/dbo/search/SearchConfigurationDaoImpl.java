package org.sagebionetworks.repo.model.dbo.search;

import java.sql.ResultSet;
import java.util.Date;
import java.util.List;
import java.util.Optional;

import org.sagebionetworks.ids.IdGenerator;
import org.sagebionetworks.ids.IdType;
import org.sagebionetworks.repo.model.ConflictingUpdateException;
import org.sagebionetworks.repo.model.search.table.SearchConfiguration;
import org.sagebionetworks.repo.transactions.WriteTransaction;
import org.sagebionetworks.repo.web.NotFoundException;
import org.sagebionetworks.util.ValidateArgument;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class SearchConfigurationDaoImpl implements SearchConfigurationDao {

	private static final String MSG_DUPLICATE_NAME = "A search configuration with the given name already exists in this organization.";

	private static final RowMapper<SearchConfiguration> ROW_MAPPER = (ResultSet rs, int rowNum) -> {
		SearchConfiguration config = new SearchConfiguration();
		config.setId(String.valueOf(rs.getLong("ID")));
		config.setEtag(rs.getString("ETAG"));
		config.setOrganizationName(rs.getString("ORGANIZATION_NAME"));
		config.setName(rs.getString("NAME"));
		config.setDescription(rs.getString("DESCRIPTION"));
		long defaultAnalyzerId = rs.getLong("DEFAULT_ANALYZER_ID");
		config.setDefaultAnalyzerId(rs.wasNull() ? null : String.valueOf(defaultAnalyzerId));
		config.setCreatedBy(String.valueOf(rs.getLong("CREATED_BY")));
		config.setCreatedOn(new Date(rs.getTimestamp("CREATED_ON").getTime()));
		config.setModifiedBy(String.valueOf(rs.getLong("MODIFIED_BY")));
		config.setModifiedOn(new Date(rs.getTimestamp("MODIFIED_ON").getTime()));
		return config;
	};

	private final JdbcTemplate jdbcTemplate;
	private final IdGenerator idGenerator;

	public SearchConfigurationDaoImpl(JdbcTemplate jdbcTemplate, IdGenerator idGenerator) {
		this.jdbcTemplate = jdbcTemplate;
		this.idGenerator = idGenerator;
	}

	@WriteTransaction
	@Override
	public SearchConfiguration create(Long createdBy, SearchConfiguration config) {
		ValidateArgument.required(createdBy, "createdBy");
		ValidateArgument.required(config, "config");
		ValidateArgument.required(config.getOrganizationName(), "config.organizationName");
		ValidateArgument.required(config.getName(), "config.name");

		Long id = idGenerator.generateNewId(IdType.SEARCH_CONFIGURATION_ID);

		try {
			jdbcTemplate.update(
					"INSERT INTO SEARCH_CONFIGURATION (ID, ETAG, ORGANIZATION_NAME, NAME, DESCRIPTION,"
					+ " DEFAULT_ANALYZER_ID, CREATED_BY, CREATED_ON, MODIFIED_BY, MODIFIED_ON)"
					+ " VALUES (?, UUID(), ?, ?, ?, ?, ?, NOW(3), ?, NOW(3))",
					id,
					config.getOrganizationName(),
					config.getName(),
					config.getDescription(),
					config.getDefaultAnalyzerId() != null ? Long.parseLong(config.getDefaultAnalyzerId()) : null,
					createdBy,
					createdBy
			);
		} catch (DuplicateKeyException e) {
			throw new IllegalArgumentException(MSG_DUPLICATE_NAME, e);
		}

		insertJunctionRows(id, config.getSynonymSetIds(), config.getColumnAnalyzerOverrideIds());

		return get(id.toString()).orElseThrow(() -> new IllegalStateException("Failed to create SearchConfiguration"));
	}

	@Override
	public Optional<SearchConfiguration> get(String id) {
		ValidateArgument.required(id, "id");
		try {
			SearchConfiguration result = jdbcTemplate.queryForObject(
					"SELECT * FROM SEARCH_CONFIGURATION WHERE ID = ?",
					ROW_MAPPER, Long.parseLong(id));
			populateJunctionData(result);
			return Optional.ofNullable(result);
		} catch (EmptyResultDataAccessException e) {
			return Optional.empty();
		}
	}

	@WriteTransaction
	@Override
	public SearchConfiguration update(Long modifiedBy, SearchConfiguration config) {
		ValidateArgument.required(modifiedBy, "modifiedBy");
		ValidateArgument.required(config, "config");
		ValidateArgument.required(config.getId(), "config.id");

		Long id = Long.parseLong(config.getId());

		String currentEtag = getCurrentEtagForUpdate(id);
		if (!currentEtag.equals(config.getEtag())) {
			throw new ConflictingUpdateException("SearchConfiguration was updated since last fetched. Please re-fetch and try again.");
		}

		int updated;
		try {
			updated = jdbcTemplate.update(
					"UPDATE SEARCH_CONFIGURATION SET ETAG = UUID(), NAME = ?, DESCRIPTION = ?,"
					+ " DEFAULT_ANALYZER_ID = ?, MODIFIED_BY = ?, MODIFIED_ON = NOW(3) WHERE ID = ?",
					config.getName(),
					config.getDescription(),
					config.getDefaultAnalyzerId() != null ? Long.parseLong(config.getDefaultAnalyzerId()) : null,
					modifiedBy,
					id
			);
		} catch (DuplicateKeyException e) {
			throw new IllegalArgumentException(MSG_DUPLICATE_NAME, e);
		}

		if (updated == 0) {
			throw new NotFoundException("SearchConfiguration with id '" + config.getId() + "' does not exist.");
		}

		// Replace junction rows
		jdbcTemplate.update("DELETE FROM SEARCH_CONFIG_SYNONYM_SET WHERE CONFIG_ID = ?", id);
		jdbcTemplate.update("DELETE FROM SEARCH_CONFIG_COL_ANALYZER WHERE CONFIG_ID = ?", id);
		insertJunctionRows(id, config.getSynonymSetIds(), config.getColumnAnalyzerOverrideIds());

		return get(config.getId()).orElseThrow(() -> new IllegalStateException("Failed to update SearchConfiguration"));
	}

	@WriteTransaction
	@Override
	public void delete(String id) {
		ValidateArgument.required(id, "id");
		try {
			jdbcTemplate.update("DELETE FROM SEARCH_CONFIGURATION WHERE ID = ?", Long.parseLong(id));
		} catch (DataIntegrityViolationException e) {
			throw new IllegalArgumentException("Cannot delete search configuration '" + id + "' because it is still referenced.", e);
		}
	}

	@Override
	public List<SearchConfiguration> list(String organizationName, long limit, long offset) {
		ValidateArgument.required(organizationName, "organizationName");
		List<SearchConfiguration> results = jdbcTemplate.query(
				"SELECT * FROM SEARCH_CONFIGURATION WHERE ORGANIZATION_NAME = ? ORDER BY NAME ASC LIMIT ? OFFSET ?",
				ROW_MAPPER, organizationName, limit, offset);
		results.forEach(this::populateJunctionData);
		return results;
	}

	@Override
	public List<SearchConfiguration> listAll(long limit, long offset) {
		List<SearchConfiguration> results = jdbcTemplate.query(
				"SELECT * FROM SEARCH_CONFIGURATION ORDER BY NAME ASC LIMIT ? OFFSET ?",
				ROW_MAPPER, limit, offset);
		results.forEach(this::populateJunctionData);
		return results;
	}

	@WriteTransaction
	@Override
	public void truncateAll() {
		jdbcTemplate.update("DELETE FROM SEARCH_CONFIG_SYNONYM_SET WHERE CONFIG_ID > -1");
		jdbcTemplate.update("DELETE FROM SEARCH_CONFIG_COL_ANALYZER WHERE CONFIG_ID > -1");
		jdbcTemplate.update("DELETE FROM SEARCH_CONFIGURATION WHERE ID > -1");
	}

	private void insertJunctionRows(Long configId, List<String> synonymSetIds, List<String> columnAnalyzerOverrideIds) {
		if (synonymSetIds != null) {
			for (String ssId : synonymSetIds) {
				jdbcTemplate.update(
						"INSERT INTO SEARCH_CONFIG_SYNONYM_SET (CONFIG_ID, SYNONYM_SET_ID) VALUES (?, ?)",
						configId, Long.parseLong(ssId));
			}
		}
		if (columnAnalyzerOverrideIds != null) {
			for (String caId : columnAnalyzerOverrideIds) {
				jdbcTemplate.update(
						"INSERT INTO SEARCH_CONFIG_COL_ANALYZER (CONFIG_ID, COLUMN_ANALYZER_OVERRIDE_ID) VALUES (?, ?)",
						configId, Long.parseLong(caId));
			}
		}
	}

	private void populateJunctionData(SearchConfiguration config) {
		if (config == null) {
			return;
		}
		Long id = Long.parseLong(config.getId());
		config.setSynonymSetIds(jdbcTemplate.queryForList(
				"SELECT SYNONYM_SET_ID FROM SEARCH_CONFIG_SYNONYM_SET WHERE CONFIG_ID = ?",
				String.class, id));
		config.setColumnAnalyzerOverrideIds(jdbcTemplate.queryForList(
				"SELECT COLUMN_ANALYZER_OVERRIDE_ID FROM SEARCH_CONFIG_COL_ANALYZER WHERE CONFIG_ID = ?",
				String.class, id));
	}

	private String getCurrentEtagForUpdate(Long id) {
		try {
			return jdbcTemplate.queryForObject(
					"SELECT ETAG FROM SEARCH_CONFIGURATION WHERE ID = ? FOR UPDATE",
					String.class, id);
		} catch (EmptyResultDataAccessException e) {
			throw new NotFoundException("SearchConfiguration with id '" + id + "' does not exist.");
		}
	}
}
