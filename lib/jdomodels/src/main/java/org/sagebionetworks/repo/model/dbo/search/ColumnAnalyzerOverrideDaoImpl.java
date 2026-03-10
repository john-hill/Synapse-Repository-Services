package org.sagebionetworks.repo.model.dbo.search;

import static org.sagebionetworks.repo.model.query.jdo.SqlConstants.*;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.json.JSONArray;
import org.json.JSONObject;
import org.sagebionetworks.ids.IdGenerator;
import org.sagebionetworks.ids.IdType;
import org.sagebionetworks.repo.model.table.search.ColumnAnalyzerOverride;
import org.sagebionetworks.repo.model.table.search.ColumnAnalyzerOverrideEntry;
import org.sagebionetworks.repo.transactions.WriteTransaction;
import org.sagebionetworks.repo.web.NotFoundException;
import org.sagebionetworks.schema.adapter.JSONObjectAdapterException;
import org.sagebionetworks.schema.adapter.org.json.JSONObjectAdapterImpl;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class ColumnAnalyzerOverrideDaoImpl implements ColumnAnalyzerOverrideDao {

	private final JdbcTemplate jdbcTemplate;
	private final IdGenerator idGenerator;

	public ColumnAnalyzerOverrideDaoImpl(JdbcTemplate jdbcTemplate, IdGenerator idGenerator) {
		this.jdbcTemplate = jdbcTemplate;
		this.idGenerator = idGenerator;
	}

	private static final RowMapper<ColumnAnalyzerOverride> ROW_MAPPER = (rs, rowNum) -> {
		ColumnAnalyzerOverride dto = new ColumnAnalyzerOverride();
		dto.setId(String.valueOf(rs.getLong(COL_COLUMN_ANALYZER_OVERRIDE_ID)));
		dto.setEtag(rs.getString(COL_COLUMN_ANALYZER_OVERRIDE_ETAG));
		dto.setOrganizationId(String.valueOf(rs.getLong(COL_COLUMN_ANALYZER_OVERRIDE_ORGANIZATION_ID)));
		dto.setName(rs.getString(COL_COLUMN_ANALYZER_OVERRIDE_NAME));
		dto.setDescription(rs.getString(COL_COLUMN_ANALYZER_OVERRIDE_DESCRIPTION));
		dto.setOverrides(overridesFromJson(rs.getString(COL_COLUMN_ANALYZER_OVERRIDE_OVERRIDES)));
		dto.setCreatedBy(String.valueOf(rs.getLong(COL_COLUMN_ANALYZER_OVERRIDE_CREATED_BY)));
		dto.setCreatedOn(new Date(rs.getTimestamp(COL_COLUMN_ANALYZER_OVERRIDE_CREATED_ON).getTime()));
		dto.setModifiedBy(String.valueOf(rs.getLong(COL_COLUMN_ANALYZER_OVERRIDE_MODIFIED_BY)));
		dto.setModifiedOn(new Date(rs.getTimestamp(COL_COLUMN_ANALYZER_OVERRIDE_MODIFIED_ON).getTime()));
		return dto;
	};

	@Override
	@WriteTransaction
	public ColumnAnalyzerOverride create(Long createdBy, ColumnAnalyzerOverride override) {
		Long id = idGenerator.generateNewId(IdType.COLUMN_ANALYZER_OVERRIDE_ID);
		String etag = UUID.randomUUID().toString();
		Timestamp now = new Timestamp(System.currentTimeMillis());

		String sql = "INSERT INTO " + TABLE_COLUMN_ANALYZER_OVERRIDE + " ("
				+ COL_COLUMN_ANALYZER_OVERRIDE_ID + ", "
				+ COL_COLUMN_ANALYZER_OVERRIDE_ETAG + ", "
				+ COL_COLUMN_ANALYZER_OVERRIDE_ORGANIZATION_ID + ", "
				+ COL_COLUMN_ANALYZER_OVERRIDE_NAME + ", "
				+ COL_COLUMN_ANALYZER_OVERRIDE_DESCRIPTION + ", "
				+ COL_COLUMN_ANALYZER_OVERRIDE_OVERRIDES + ", "
				+ COL_COLUMN_ANALYZER_OVERRIDE_CREATED_BY + ", "
				+ COL_COLUMN_ANALYZER_OVERRIDE_CREATED_ON + ", "
				+ COL_COLUMN_ANALYZER_OVERRIDE_MODIFIED_BY + ", "
				+ COL_COLUMN_ANALYZER_OVERRIDE_MODIFIED_ON
				+ ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

		try {
			jdbcTemplate.update(sql,
					id,
					etag,
					Long.parseLong(override.getOrganizationId()),
					override.getName(),
					override.getDescription(),
					overridesToJson(override.getOverrides()),
					createdBy,
					now,
					createdBy,
					now
			);
		} catch (DuplicateKeyException e) {
			handleDuplicateKeyException(e);
		}

		return get(String.valueOf(id))
				.orElseThrow(() -> new IllegalStateException("The column analyzer override was not created."));
	}

	@Override
	public Optional<ColumnAnalyzerOverride> get(String id) {
		String sql = "SELECT * FROM " + TABLE_COLUMN_ANALYZER_OVERRIDE + " WHERE " + COL_COLUMN_ANALYZER_OVERRIDE_ID + " = ?";
		try {
			return Optional.ofNullable(jdbcTemplate.queryForObject(sql, ROW_MAPPER, Long.parseLong(id)));
		} catch (EmptyResultDataAccessException e) {
			return Optional.empty();
		}
	}

	@Override
	@WriteTransaction
	public ColumnAnalyzerOverride update(Long modifiedBy, ColumnAnalyzerOverride override) {
		String currentEtag = getEtagForUpdate(override.getId());

		if (!currentEtag.equals(override.getEtag())) {
			throw new IllegalArgumentException(
					"The column analyzer override was updated since you last fetched it, please fetch it again and reapply your changes.");
		}

		String sql = "UPDATE " + TABLE_COLUMN_ANALYZER_OVERRIDE + " SET "
				+ COL_COLUMN_ANALYZER_OVERRIDE_ETAG + " = UUID(), "
				+ COL_COLUMN_ANALYZER_OVERRIDE_NAME + " = ?, "
				+ COL_COLUMN_ANALYZER_OVERRIDE_DESCRIPTION + " = ?, "
				+ COL_COLUMN_ANALYZER_OVERRIDE_OVERRIDES + " = ?, "
				+ COL_COLUMN_ANALYZER_OVERRIDE_MODIFIED_BY + " = ?, "
				+ COL_COLUMN_ANALYZER_OVERRIDE_MODIFIED_ON + " = NOW(3) "
				+ "WHERE " + COL_COLUMN_ANALYZER_OVERRIDE_ID + " = ?";

		try {
			jdbcTemplate.update(sql,
					override.getName(),
					override.getDescription(),
					overridesToJson(override.getOverrides()),
					modifiedBy,
					Long.parseLong(override.getId())
			);
		} catch (DuplicateKeyException e) {
			handleDuplicateKeyException(e);
		}

		return get(override.getId())
				.orElseThrow(() -> new IllegalStateException("The column analyzer override was not updated."));
	}

	@Override
	@WriteTransaction
	public void delete(String id) {
		String sql = "DELETE FROM " + TABLE_COLUMN_ANALYZER_OVERRIDE + " WHERE " + COL_COLUMN_ANALYZER_OVERRIDE_ID + " = ?";
		try {
			jdbcTemplate.update(sql, Long.parseLong(id));
		} catch (DataIntegrityViolationException e) {
			throw new IllegalArgumentException("Cannot delete column analyzer override '" + id + "' because it is still referenced.", e);
		}
	}

	@Override
	public List<ColumnAnalyzerOverride> list(String organizationId, long limit, long offset) {
		String sql = "SELECT * FROM " + TABLE_COLUMN_ANALYZER_OVERRIDE
				+ " WHERE " + COL_COLUMN_ANALYZER_OVERRIDE_ORGANIZATION_ID + " = ?"
				+ " ORDER BY " + COL_COLUMN_ANALYZER_OVERRIDE_ID
				+ " LIMIT ? OFFSET ?";
		return jdbcTemplate.query(sql, ROW_MAPPER, Long.parseLong(organizationId), limit, offset);
	}

	@Override
	public List<ColumnAnalyzerOverride> listAll(long limit, long offset) {
		String sql = "SELECT * FROM " + TABLE_COLUMN_ANALYZER_OVERRIDE
				+ " ORDER BY " + COL_COLUMN_ANALYZER_OVERRIDE_ID
				+ " LIMIT ? OFFSET ?";
		return jdbcTemplate.query(sql, ROW_MAPPER, limit, offset);
	}

	@Override
	@WriteTransaction
	public void truncateAll() {
		jdbcTemplate.update("DELETE FROM " + TABLE_COLUMN_ANALYZER_OVERRIDE);
	}

	private String getEtagForUpdate(String id) {
		String sql = "SELECT " + COL_COLUMN_ANALYZER_OVERRIDE_ETAG + " FROM " + TABLE_COLUMN_ANALYZER_OVERRIDE
				+ " WHERE " + COL_COLUMN_ANALYZER_OVERRIDE_ID + " = ? FOR UPDATE";
		try {
			return jdbcTemplate.queryForObject(sql, String.class, Long.parseLong(id));
		} catch (EmptyResultDataAccessException e) {
			throw new NotFoundException("A column analyzer override with ID " + id + " does not exist.");
		}
	}

	private static void handleDuplicateKeyException(DuplicateKeyException e) {
		if (e.getMessage() != null && e.getMessage().contains("UNIQUE_CAO_ORG_NAME")) {
			throw new IllegalArgumentException(
					"A column analyzer override with the same name already exists in this organization.", e);
		}
		throw e;
	}

	static String overridesToJson(List<ColumnAnalyzerOverrideEntry> entries) {
		if (entries == null) {
			return "[]";
		}
		JSONArray jsonArray = new JSONArray();
		for (ColumnAnalyzerOverrideEntry entry : entries) {
			try {
				JSONObjectAdapterImpl adapter = new JSONObjectAdapterImpl();
				entry.writeToJSONObject(adapter);
				jsonArray.put(new JSONObject(adapter.toJSONString()));
			} catch (JSONObjectAdapterException e) {
				throw new RuntimeException(e);
			}
		}
		return jsonArray.toString();
	}

	static List<ColumnAnalyzerOverrideEntry> overridesFromJson(String json) {
		if (json == null || json.isEmpty()) {
			return new ArrayList<>();
		}
		JSONArray jsonArray = new JSONArray(json);
		List<ColumnAnalyzerOverrideEntry> entries = new ArrayList<>();
		for (int i = 0; i < jsonArray.length(); i++) {
			try {
				JSONObject obj = jsonArray.getJSONObject(i);
				JSONObjectAdapterImpl adapter = new JSONObjectAdapterImpl(obj);
				ColumnAnalyzerOverrideEntry entry = new ColumnAnalyzerOverrideEntry();
				entry.initializeFromJSONObject(adapter);
				entries.add(entry);
			} catch (JSONObjectAdapterException e) {
				throw new RuntimeException(e);
			}
		}
		return entries;
	}

}
