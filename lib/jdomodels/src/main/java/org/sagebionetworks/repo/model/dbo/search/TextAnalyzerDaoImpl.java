package org.sagebionetworks.repo.model.dbo.search;

import static org.sagebionetworks.repo.model.query.jdo.SqlConstants.*;

import java.sql.Timestamp;
import java.util.Date;
import java.util.List;
import java.util.Optional;

import org.sagebionetworks.ids.IdGenerator;
import org.sagebionetworks.ids.IdType;
import org.sagebionetworks.repo.model.ConflictingUpdateException;
import org.sagebionetworks.repo.model.jdo.JDOSecondaryPropertyUtils;
import org.sagebionetworks.repo.model.table.search.TextAnalyzer;
import org.sagebionetworks.repo.model.table.search.TextAnalyzerSettings;
import org.sagebionetworks.repo.transactions.WriteTransaction;
import org.sagebionetworks.repo.web.NotFoundException;
import org.sagebionetworks.util.ValidateArgument;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class TextAnalyzerDaoImpl implements TextAnalyzerDao {

	private static final String MSG_DUPLICATE_NAME = "A text analyzer with the given name already exists in this organization.";

	private static final String SQL_INSERT = "INSERT INTO " + TABLE_TEXT_ANALYZER + " ("
			+ COL_TEXT_ANALYZER_ID + ", " + COL_TEXT_ANALYZER_ETAG + ", "
			+ COL_TEXT_ANALYZER_NAME + ", " + COL_TEXT_ANALYZER_DESCRIPTION + ", "
			+ COL_TEXT_ANALYZER_ORGANIZATION_ID + ", " + COL_TEXT_ANALYZER_SETTINGS + ", "
			+ COL_TEXT_ANALYZER_CREATED_BY + ", " + COL_TEXT_ANALYZER_CREATED_ON + ", "
			+ COL_TEXT_ANALYZER_MODIFIED_BY + ", " + COL_TEXT_ANALYZER_MODIFIED_ON
			+ ") VALUES (?, UUID(), ?, ?, ?, ?, ?, ?, ?, ?)";

	private static final String SQL_SELECT_BY_ID = "SELECT * FROM " + TABLE_TEXT_ANALYZER
			+ " WHERE " + COL_TEXT_ANALYZER_ID + " = ?";

	private static final String SQL_UPDATE = "UPDATE " + TABLE_TEXT_ANALYZER + " SET "
			+ COL_TEXT_ANALYZER_ETAG + " = UUID(), "
			+ COL_TEXT_ANALYZER_NAME + " = ?, "
			+ COL_TEXT_ANALYZER_DESCRIPTION + " = ?, "
			+ COL_TEXT_ANALYZER_SETTINGS + " = ?, "
			+ COL_TEXT_ANALYZER_MODIFIED_BY + " = ?, "
			+ COL_TEXT_ANALYZER_MODIFIED_ON + " = NOW(3)"
			+ " WHERE " + COL_TEXT_ANALYZER_ID + " = ?";

	private static final String SQL_DELETE = "DELETE FROM " + TABLE_TEXT_ANALYZER
			+ " WHERE " + COL_TEXT_ANALYZER_ID + " = ?";

	private static final String SQL_LIST_BY_ORG = "SELECT * FROM " + TABLE_TEXT_ANALYZER
			+ " WHERE " + COL_TEXT_ANALYZER_ORGANIZATION_ID
			+ " = ? ORDER BY " + COL_TEXT_ANALYZER_NAME + " ASC LIMIT ? OFFSET ?";

	private static final String SQL_LIST_ALL = "SELECT * FROM " + TABLE_TEXT_ANALYZER
			+ " ORDER BY " + COL_TEXT_ANALYZER_NAME + " ASC LIMIT ? OFFSET ?";

	private static final String SQL_EXISTS = "SELECT COUNT(*) FROM " + TABLE_TEXT_ANALYZER
			+ " WHERE " + COL_TEXT_ANALYZER_ID + " = ?";

	private static final String SQL_UPSERT_SYSTEM = "INSERT INTO " + TABLE_TEXT_ANALYZER + " ("
			+ COL_TEXT_ANALYZER_ID + ", " + COL_TEXT_ANALYZER_ETAG + ", "
			+ COL_TEXT_ANALYZER_NAME + ", " + COL_TEXT_ANALYZER_DESCRIPTION + ", "
			+ COL_TEXT_ANALYZER_ORGANIZATION_ID + ", " + COL_TEXT_ANALYZER_SETTINGS + ", "
			+ COL_TEXT_ANALYZER_CREATED_BY + ", " + COL_TEXT_ANALYZER_CREATED_ON + ", "
			+ COL_TEXT_ANALYZER_MODIFIED_BY + ", " + COL_TEXT_ANALYZER_MODIFIED_ON
			+ ") VALUES (?, UUID(), ?, ?, ?, ?, ?, ?, ?, ?)"
			+ " ON DUPLICATE KEY UPDATE "
			+ COL_TEXT_ANALYZER_ETAG + " = UUID(), "
			+ COL_TEXT_ANALYZER_NAME + " = VALUES(" + COL_TEXT_ANALYZER_NAME + "), "
			+ COL_TEXT_ANALYZER_DESCRIPTION + " = VALUES(" + COL_TEXT_ANALYZER_DESCRIPTION + "), "
			+ COL_TEXT_ANALYZER_SETTINGS + " = VALUES(" + COL_TEXT_ANALYZER_SETTINGS + "), "
			+ COL_TEXT_ANALYZER_MODIFIED_BY + " = VALUES(" + COL_TEXT_ANALYZER_MODIFIED_BY + "), "
			+ COL_TEXT_ANALYZER_MODIFIED_ON + " = NOW(3)";

	private static final String SQL_TRUNCATE = "DELETE FROM " + TABLE_TEXT_ANALYZER
			+ " WHERE " + COL_TEXT_ANALYZER_ID + " > -1";

	private static final RowMapper<TextAnalyzer> ROW_MAPPER = (rs, rowNum) -> {
		TextAnalyzer analyzer = new TextAnalyzer();
		analyzer.setId(String.valueOf(rs.getLong(COL_TEXT_ANALYZER_ID)));
		analyzer.setEtag(rs.getString(COL_TEXT_ANALYZER_ETAG));
		analyzer.setName(rs.getString(COL_TEXT_ANALYZER_NAME));
		analyzer.setDescription(rs.getString(COL_TEXT_ANALYZER_DESCRIPTION));
		analyzer.setOrganizationId(String.valueOf(rs.getLong(COL_TEXT_ANALYZER_ORGANIZATION_ID)));
		analyzer.setSettings(JDOSecondaryPropertyUtils.createObjectFromJSON(TextAnalyzerSettings.class, rs.getString(COL_TEXT_ANALYZER_SETTINGS)));
		analyzer.setCreatedBy(String.valueOf(rs.getLong(COL_TEXT_ANALYZER_CREATED_BY)));
		analyzer.setCreatedOn(new Date(rs.getTimestamp(COL_TEXT_ANALYZER_CREATED_ON).getTime()));
		analyzer.setModifiedBy(String.valueOf(rs.getLong(COL_TEXT_ANALYZER_MODIFIED_BY)));
		analyzer.setModifiedOn(new Date(rs.getTimestamp(COL_TEXT_ANALYZER_MODIFIED_ON).getTime()));
		return analyzer;
	};

	private final JdbcTemplate jdbcTemplate;
	private final IdGenerator idGenerator;

	public TextAnalyzerDaoImpl(JdbcTemplate jdbcTemplate, IdGenerator idGenerator) {
		this.jdbcTemplate = jdbcTemplate;
		this.idGenerator = idGenerator;
	}

	@WriteTransaction
	@Override
	public TextAnalyzer create(TextAnalyzer analyzer, Long userId) {
		ValidateArgument.required(analyzer, "analyzer");
		ValidateArgument.required(analyzer.getName(), "analyzer.name");
		ValidateArgument.required(analyzer.getOrganizationId(), "analyzer.organizationId");
		ValidateArgument.required(analyzer.getSettings(), "analyzer.settings");
		ValidateArgument.required(userId, "userId");

		Long id = idGenerator.generateNewId(IdType.TEXT_ANALYZER_ID);
		Timestamp now = new Timestamp(System.currentTimeMillis());

		try {
			jdbcTemplate.update(SQL_INSERT,
					id,
					analyzer.getName(),
					analyzer.getDescription(),
					Long.parseLong(analyzer.getOrganizationId()),
					JDOSecondaryPropertyUtils.createJSONFromObject(analyzer.getSettings()),
					userId,
					now,
					userId,
					now
			);
		} catch (DuplicateKeyException e) {
			throw new IllegalArgumentException(MSG_DUPLICATE_NAME, e);
		}

		return get(id).orElseThrow(() -> new IllegalStateException("Failed to create TextAnalyzer"));
	}

	@Override
	public Optional<TextAnalyzer> get(Long id) {
		ValidateArgument.required(id, "id");
		try {
			TextAnalyzer result = jdbcTemplate.queryForObject(SQL_SELECT_BY_ID, ROW_MAPPER, id);
			return Optional.ofNullable(result);
		} catch (EmptyResultDataAccessException e) {
			return Optional.empty();
		}
	}

	@WriteTransaction
	@Override
	public TextAnalyzer update(TextAnalyzer analyzer, Long userId) {
		ValidateArgument.required(analyzer, "analyzer");
		ValidateArgument.required(analyzer.getId(), "analyzer.id");
		ValidateArgument.required(userId, "userId");

		Long id = Long.parseLong(analyzer.getId());

		String currentEtag = getCurrentEtagForUpdate(id);
		if (!currentEtag.equals(analyzer.getEtag())) {
			throw new ConflictingUpdateException("TextAnalyzer was updated since last fetched. Please re-fetch and try again.");
		}

		int updated;
		try {
			updated = jdbcTemplate.update(SQL_UPDATE,
					analyzer.getName(),
					analyzer.getDescription(),
					JDOSecondaryPropertyUtils.createJSONFromObject(analyzer.getSettings()),
					userId,
					id
			);
		} catch (DuplicateKeyException e) {
			throw new IllegalArgumentException(MSG_DUPLICATE_NAME, e);
		}

		if (updated == 0) {
			throw new NotFoundException("TextAnalyzer with id '" + analyzer.getId() + "' does not exist.");
		}

		return get(id).orElseThrow(() -> new IllegalStateException("Failed to update TextAnalyzer"));
	}

	@WriteTransaction
	@Override
	public void delete(Long id) {
		ValidateArgument.required(id, "id");
		jdbcTemplate.update(SQL_DELETE, id);
	}

	@Override
	public List<TextAnalyzer> listByOrganization(Long organizationId, long limit, long offset) {
		ValidateArgument.required(organizationId, "organizationId");
		return jdbcTemplate.query(SQL_LIST_BY_ORG, ROW_MAPPER, organizationId, limit, offset);
	}

	@Override
	public List<TextAnalyzer> listAll(long limit, long offset) {
		return jdbcTemplate.query(SQL_LIST_ALL, ROW_MAPPER, limit, offset);
	}

	@Override
	public boolean exists(Long id) {
		ValidateArgument.required(id, "id");
		Integer count = jdbcTemplate.queryForObject(SQL_EXISTS, Integer.class, id);
		return count > 0;
	}

	@WriteTransaction
	@Override
	public void createOrUpdateSystemAnalyzerForBootstrapOnly(Long id, TextAnalyzer analyzer, Long organizationId, Long userId) {
		ValidateArgument.required(id, "id");
		ValidateArgument.required(analyzer, "analyzer");
		ValidateArgument.required(analyzer.getName(), "analyzer.name");
		ValidateArgument.required(analyzer.getSettings(), "analyzer.settings");
		ValidateArgument.required(organizationId, "organizationId");
		ValidateArgument.required(userId, "userId");

		Timestamp now = new Timestamp(System.currentTimeMillis());

		jdbcTemplate.update(SQL_UPSERT_SYSTEM,
				id,
				analyzer.getName(),
				analyzer.getDescription(),
				organizationId,
				JDOSecondaryPropertyUtils.createJSONFromObject(analyzer.getSettings()),
				userId,
				now,
				userId,
				now
		);
	}

	@WriteTransaction
	@Override
	public void truncateAll() {
		jdbcTemplate.update(SQL_TRUNCATE);
	}

	private String getCurrentEtagForUpdate(Long id) {
		String sql = "SELECT " + COL_TEXT_ANALYZER_ETAG + " FROM " + TABLE_TEXT_ANALYZER
				+ " WHERE " + COL_TEXT_ANALYZER_ID + " = ? FOR UPDATE";
		try {
			return jdbcTemplate.queryForObject(sql, String.class, id);
		} catch (EmptyResultDataAccessException e) {
			throw new NotFoundException("TextAnalyzer with id '" + id + "' does not exist.");
		}
	}
}
