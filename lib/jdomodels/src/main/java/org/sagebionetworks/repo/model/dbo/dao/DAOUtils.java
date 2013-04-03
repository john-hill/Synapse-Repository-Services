package org.sagebionetworks.repo.model.dbo.dao;

import static org.sagebionetworks.repo.model.query.jdo.SqlConstants.COL_FILES_ETAG;
import static org.sagebionetworks.repo.model.query.jdo.SqlConstants.COL_FILES_ID;
import static org.sagebionetworks.repo.model.query.jdo.SqlConstants.TABLE_FILES;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

import org.sagebionetworks.repo.model.MigratableObjectStatus;
import org.sagebionetworks.repo.model.MigratableObjectType;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.simple.SimpleJdbcTemplate;

/**
 * Utility for common DAO operations.
 * @author jmhill
 *
 */
public class DAOUtils {

	private static final String ID_LIST = "IDLIST";
	private static final String SQL_LIST_OBJECT_STATUS = "SELECT %1$s, %2$s FROM %3$s WHERE %1$s IN (:"+ID_LIST+")";
	

	public static List<MigratableObjectStatus> listObjectStatus(SimpleJdbcTemplate simpleJdbcTemplate, List<String> ids, String tableName, String idColumn, String etagColumn, final MigratableObjectType type) {
		if(ids == null) throw new IllegalArgumentException("ID list cannot be null");
		MapSqlParameterSource param = new MapSqlParameterSource();
		param.addValue(ID_LIST, ids);
		return simpleJdbcTemplate.query(String.format(SQL_LIST_OBJECT_STATUS, idColumn, etagColumn, tableName),  new RowMapper<MigratableObjectStatus>(){
			@Override
			public MigratableObjectStatus mapRow(ResultSet rs, int rowNum)
					throws SQLException {
				MigratableObjectStatus status = new MigratableObjectStatus();
				status.setType(type);
				status.setEtag(rs.getString(COL_FILES_ETAG));
				status.setId(""+rs.getLong(COL_FILES_ID));
				return status;
		}}, param );
	}
}
