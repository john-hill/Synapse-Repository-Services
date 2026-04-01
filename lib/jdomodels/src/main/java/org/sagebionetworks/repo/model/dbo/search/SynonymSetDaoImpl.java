package org.sagebionetworks.repo.model.dbo.search;

import java.util.Date;
import java.util.List;
import java.util.Optional;

import org.sagebionetworks.ids.IdGenerator;
import org.sagebionetworks.ids.IdType;
import org.sagebionetworks.repo.model.ConflictingUpdateException;
import org.sagebionetworks.repo.model.jdo.JDOSecondaryPropertyUtils;
import org.sagebionetworks.repo.model.search.table.SynonymRule;
import org.sagebionetworks.repo.model.search.table.SynonymSet;
import org.sagebionetworks.repo.transactions.WriteTransaction;
import org.sagebionetworks.repo.web.NotFoundException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class SynonymSetDaoImpl implements SynonymSetDao {

	private static final String MSG_DUPLICATE_NAME = "A synonym set with the given name already exists in this organization.";

	private static final RowMapper<SynonymSet> SYNONYM_SET_ROW_MAPPER = (rs, rowNum) -> new SynonymSet()
		.setId(String.valueOf(rs.getLong("ID")))
		.setEtag(rs.getString("ETAG"))
		.setOrganizationName(rs.getString("ORGANIZATION_NAME"))
		.setName(rs.getString("NAME"))
		.setDescription(rs.getString("DESCRIPTION"))
		.setRules(JDOSecondaryPropertyUtils.readJsonToEntityList(rs.getString("RULES"), SynonymRule.class))
		.setCreatedBy(String.valueOf(rs.getLong("CREATED_BY")))
		.setCreatedOn(new Date(rs.getTimestamp("CREATED_ON").getTime()))
		.setModifiedBy(String.valueOf(rs.getLong("MODIFIED_BY")))
		.setModifiedOn(new Date(rs.getTimestamp("MODIFIED_ON").getTime()));

	private final JdbcTemplate jdbcTemplate;
	private final IdGenerator idGenerator;

	public SynonymSetDaoImpl(JdbcTemplate jdbcTemplate, IdGenerator idGenerator) {
		this.jdbcTemplate = jdbcTemplate;
		this.idGenerator = idGenerator;
	}

	@Override
	@WriteTransaction
	public SynonymSet create(Long createdBy, SynonymSet synonymSet) {
		Long id = idGenerator.generateNewId(IdType.SYNONYM_SET_ID);

		try {
			jdbcTemplate.update(
					"INSERT INTO SYNONYM_SET (ID, ETAG, ORGANIZATION_NAME, NAME, DESCRIPTION, RULES,"
					+ " CREATED_BY, CREATED_ON, MODIFIED_BY, MODIFIED_ON)"
					+ " VALUES (?, UUID(), ?, ?, ?, ?, ?, NOW(3), ?, NOW(3))",
					id,
					synonymSet.getOrganizationName(),
					synonymSet.getName(),
					synonymSet.getDescription(),
					synonymSet.getRules() == null ? "[]" : JDOSecondaryPropertyUtils.writeEntityListToJson(synonymSet.getRules()),
					createdBy,
					createdBy
			);
		} catch (DuplicateKeyException e) {
			throw new IllegalArgumentException(MSG_DUPLICATE_NAME, e);
		}

		return get(id.toString()).orElseThrow(() -> new IllegalStateException("The synonym set was not created."));
	}

	@Override
	public Optional<SynonymSet> get(String id) {
		try {
			return Optional.of(jdbcTemplate.queryForObject(
					"SELECT * FROM SYNONYM_SET WHERE ID = ?",
					SYNONYM_SET_ROW_MAPPER, Long.parseLong(id)));
		} catch (EmptyResultDataAccessException e) {
			return Optional.empty();
		}
	}

	@Override
	@WriteTransaction
	public SynonymSet update(Long modifiedBy, SynonymSet synonymSet) {
		// Optimistic concurrency check
		String currentEtag = getCurrentEtagForUpdate(Long.parseLong(synonymSet.getId()));
		if (!currentEtag.equals(synonymSet.getEtag())) {
			throw new ConflictingUpdateException("SynonymSet was updated since last fetched. Please re-fetch and try again.");
		}

		try {
			int updated = jdbcTemplate.update(
					"UPDATE SYNONYM_SET SET ETAG = UUID(), NAME = ?, DESCRIPTION = ?, RULES = ?,"
					+ " MODIFIED_BY = ?, MODIFIED_ON = NOW(3) WHERE ID = ?",
					synonymSet.getName(),
					synonymSet.getDescription(),
					synonymSet.getRules() == null ? "[]" : JDOSecondaryPropertyUtils.writeEntityListToJson(synonymSet.getRules()),
					modifiedBy,
					Long.parseLong(synonymSet.getId())
			);

			if (updated == 0) {
				throw new NotFoundException("SynonymSet with id '" + synonymSet.getId() + "' does not exist.");
			}
		} catch (DuplicateKeyException e) {
			throw new IllegalArgumentException(MSG_DUPLICATE_NAME, e);
		}

		return get(synonymSet.getId()).orElseThrow(() -> new IllegalStateException("The synonym set was not updated."));
	}

	@Override
	@WriteTransaction
	public void delete(String id) {
		try {
			jdbcTemplate.update("DELETE FROM SYNONYM_SET WHERE ID = ?", Long.parseLong(id));
		} catch (DataIntegrityViolationException e) {
			throw new IllegalArgumentException("Cannot delete synonym set '" + id + "' because it is still referenced.", e);
		}
	}

	@Override
	public List<SynonymSet> list(String organizationName, long limit, long offset) {
		return jdbcTemplate.query(
				"SELECT * FROM SYNONYM_SET WHERE ORGANIZATION_NAME = ? ORDER BY ID LIMIT ? OFFSET ?",
				SYNONYM_SET_ROW_MAPPER, organizationName, limit, offset);
	}

	@Override
	public List<SynonymSet> listAll(long limit, long offset) {
		return jdbcTemplate.query(
				"SELECT * FROM SYNONYM_SET ORDER BY ID LIMIT ? OFFSET ?",
				SYNONYM_SET_ROW_MAPPER, limit, offset);
	}

	@Override
	public Optional<SynonymSet> getByOrganizationAndName(String organizationName, String name) {
		try {
			return Optional.of(jdbcTemplate.queryForObject(
					"SELECT * FROM SYNONYM_SET WHERE ORGANIZATION_NAME = ? AND NAME = ?",
					SYNONYM_SET_ROW_MAPPER, organizationName, name));
		} catch (EmptyResultDataAccessException e) {
			return Optional.empty();
		}
	}

	@Override
	@WriteTransaction
	public void truncateAll() {
		jdbcTemplate.update("DELETE FROM SYNONYM_SET WHERE ID > -1");
	}

	private String getCurrentEtagForUpdate(Long id) {
		try {
			return jdbcTemplate.queryForObject(
					"SELECT ETAG FROM SYNONYM_SET WHERE ID = ? FOR UPDATE",
					String.class, id);
		} catch (EmptyResultDataAccessException e) {
			throw new NotFoundException("SynonymSet with id '" + id + "' does not exist.");
		}
	}

}
