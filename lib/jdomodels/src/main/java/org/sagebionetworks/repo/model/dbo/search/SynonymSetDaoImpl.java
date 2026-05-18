package org.sagebionetworks.repo.model.dbo.search;

import static org.sagebionetworks.repo.model.query.jdo.SqlConstants.COL_SYNSET_CREATED_BY;
import static org.sagebionetworks.repo.model.query.jdo.SqlConstants.COL_SYNSET_CREATED_ON;
import static org.sagebionetworks.repo.model.query.jdo.SqlConstants.COL_SYNSET_DESCRIPTION;
import static org.sagebionetworks.repo.model.query.jdo.SqlConstants.COL_SYNSET_ETAG;
import static org.sagebionetworks.repo.model.query.jdo.SqlConstants.COL_SYNSET_ID;
import static org.sagebionetworks.repo.model.query.jdo.SqlConstants.COL_SYNSET_MODIFIED_BY;
import static org.sagebionetworks.repo.model.query.jdo.SqlConstants.COL_SYNSET_MODIFIED_ON;
import static org.sagebionetworks.repo.model.query.jdo.SqlConstants.COL_SYNSET_NAME;
import static org.sagebionetworks.repo.model.query.jdo.SqlConstants.COL_SYNSET_ORGANIZATION_NAME;
import static org.sagebionetworks.repo.model.query.jdo.SqlConstants.COL_SYNSET_DEFINITION;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.sagebionetworks.ids.IdGenerator;
import org.sagebionetworks.ids.IdType;
import org.sagebionetworks.repo.model.ConflictingUpdateException;
import org.sagebionetworks.repo.model.search.table.SynonymSet;
import org.sagebionetworks.repo.transactions.WriteTransaction;
import org.sagebionetworks.repo.web.NotFoundException;
import org.sagebionetworks.util.ValidateArgument;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class SynonymSetDaoImpl implements SynonymSetDao {

	private static final RowMapper<SynonymSet> SYNONYM_SET_ROW_MAPPER = (rs, rowNum) -> new SynonymSet()
		.setId(String.valueOf(rs.getLong(COL_SYNSET_ID)))
		.setEtag(rs.getString(COL_SYNSET_ETAG))
		.setOrganizationName(rs.getString(COL_SYNSET_ORGANIZATION_NAME))
		.setName(rs.getString(COL_SYNSET_NAME))
		.setDescription(rs.getString(COL_SYNSET_DESCRIPTION))
		.setDefinition(rs.getString(COL_SYNSET_DEFINITION))
		.setCreatedBy(String.valueOf(rs.getLong(COL_SYNSET_CREATED_BY)))
		.setCreatedOn(new Date(rs.getTimestamp(COL_SYNSET_CREATED_ON).getTime()))
		.setModifiedBy(String.valueOf(rs.getLong(COL_SYNSET_MODIFIED_BY)))
		.setModifiedOn(new Date(rs.getTimestamp(COL_SYNSET_MODIFIED_ON).getTime()));

	private final JdbcTemplate jdbcTemplate;
	private final IdGenerator idGenerator;

	public SynonymSetDaoImpl(JdbcTemplate jdbcTemplate, IdGenerator idGenerator) {
		this.jdbcTemplate = jdbcTemplate;
		this.idGenerator = idGenerator;
	}

	@Override
	@WriteTransaction
	public SynonymSet create(Long createdBy, SynonymSet synonymSet) {
		ValidateArgument.required(synonymSet, "synonymSet");
		ValidateArgument.required(synonymSet.getDefinition(), "synonymSet.definition");
		Long id = idGenerator.generateNewId(IdType.SYNONYM_SET_ID);

		try {
			jdbcTemplate.update(
					"INSERT INTO SYNONYM_SET (ID, ETAG, ORGANIZATION_NAME, NAME, DESCRIPTION, DEFINITION,"
					+ " CREATED_BY, CREATED_ON, MODIFIED_BY, MODIFIED_ON)"
					+ " VALUES (?, UUID(), ?, ?, ?, ?, ?, NOW(3), ?, NOW(3))",
					id,
					synonymSet.getOrganizationName(),
					synonymSet.getName(),
					synonymSet.getDescription(),
					synonymSet.getDefinition(),
					createdBy,
					createdBy
			);
		} catch (DataIntegrityViolationException e) {
			throw new IllegalArgumentException("A synonym set with the same name already exists in this organization.", e);
		}

		return get(id.toString()).orElseThrow(() -> new IllegalStateException("The synonym set was not created."));
	}

	@Override
	public Optional<SynonymSet> get(String id) {
		try {
			return Optional.ofNullable(jdbcTemplate.queryForObject(
					"SELECT * FROM SYNONYM_SET WHERE ID = ?",
					SYNONYM_SET_ROW_MAPPER, Long.parseLong(id)));
		} catch (EmptyResultDataAccessException e) {
			return Optional.empty();
		}
	}

	@Override
	@WriteTransaction
	public SynonymSet update(Long modifiedBy, SynonymSet synonymSet) {
		ValidateArgument.required(synonymSet, "synonymSet");
		ValidateArgument.required(synonymSet.getDefinition(), "synonymSet.definition");

		// Optimistic concurrency check
		String currentEtag = getCurrentEtagForUpdate(Long.parseLong(synonymSet.getId()));
		if (!currentEtag.equals(synonymSet.getEtag())) {
			throw new ConflictingUpdateException("SynonymSet was updated since last fetched. Please re-fetch and try again.");
		}

		int updated;
		try {
			updated = jdbcTemplate.update(
					"UPDATE SYNONYM_SET SET ETAG = UUID(), NAME = ?, DESCRIPTION = ?, DEFINITION = ?,"
					+ " MODIFIED_BY = ?, MODIFIED_ON = NOW(3) WHERE ID = ?",
					synonymSet.getName(),
					synonymSet.getDescription(),
					synonymSet.getDefinition(),
					modifiedBy,
					Long.parseLong(synonymSet.getId())
			);
		} catch (DataIntegrityViolationException e) {
			throw new IllegalArgumentException("A synonym set with the same name already exists in this organization.", e);
		}

		if (updated == 0) {
			throw new NotFoundException("SynonymSet with id '" + synonymSet.getId() + "' does not exist.");
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
			return Optional.ofNullable(jdbcTemplate.queryForObject(
					"SELECT * FROM SYNONYM_SET WHERE ORGANIZATION_NAME = ? AND NAME = ?",
					SYNONYM_SET_ROW_MAPPER, organizationName, name));
		} catch (EmptyResultDataAccessException e) {
			return Optional.empty();
		}
	}

	@Override
	public List<String> findNonExistentNames(List<String> qualifiedNames) {
		if (qualifiedNames == null || qualifiedNames.isEmpty()) {
			return Collections.emptyList();
		}
		StringBuilder sql = new StringBuilder(
				"SELECT CONCAT(ORGANIZATION_NAME, '-', NAME) FROM SYNONYM_SET WHERE (ORGANIZATION_NAME, NAME) IN (");
		List<Object> params = new ArrayList<>();
		for (int i = 0; i < qualifiedNames.size(); i++) {
			if (i > 0) {
				sql.append(", ");
			}
			sql.append("(?, ?)");
			String qualifiedName = qualifiedNames.get(i);
			int dashIndex = qualifiedName.indexOf('-');
			params.add(qualifiedName.substring(0, dashIndex));
			params.add(qualifiedName.substring(dashIndex + 1));
		}
		sql.append(")");
		Set<String> existingNames = new HashSet<>(jdbcTemplate.queryForList(sql.toString(), String.class, params.toArray()));
		List<String> missing = new ArrayList<>();
		for (String qualifiedName : qualifiedNames) {
			if (!existingNames.contains(qualifiedName)) {
				missing.add(qualifiedName);
			}
		}
		return missing;
	}

	@Override
	public Map<String, SynonymSet> getByQualifiedNames(List<String> qualifiedNames) {
		if (qualifiedNames == null || qualifiedNames.isEmpty()) {
			return Collections.emptyMap();
		}
		StringBuilder sql = new StringBuilder("SELECT * FROM SYNONYM_SET WHERE (ORGANIZATION_NAME, NAME) IN (");
		List<Object> params = new ArrayList<>();
		for (int i = 0; i < qualifiedNames.size(); i++) {
			if (i > 0) {
				sql.append(", ");
			}
			sql.append("(?, ?)");
			String qualifiedName = qualifiedNames.get(i);
			int dashIndex = qualifiedName.indexOf('-');
			params.add(qualifiedName.substring(0, dashIndex));
			params.add(qualifiedName.substring(dashIndex + 1));
		}
		sql.append(")");
		List<SynonymSet> sets = jdbcTemplate.query(sql.toString(), SYNONYM_SET_ROW_MAPPER, params.toArray());
		Map<String, SynonymSet> result = new HashMap<>();
		for (SynonymSet s : sets) {
			result.put(s.getOrganizationName() + "-" + s.getName(), s);
		}
		return result;
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
