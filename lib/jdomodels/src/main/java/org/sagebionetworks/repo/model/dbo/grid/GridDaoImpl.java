package org.sagebionetworks.repo.model.dbo.grid;

import static org.sagebionetworks.repo.model.query.jdo.SqlConstants.COL_GRID_SESSION_CREATED_BY;
import static org.sagebionetworks.repo.model.query.jdo.SqlConstants.COL_GRID_SESSION_CREATED_ON;
import static org.sagebionetworks.repo.model.query.jdo.SqlConstants.COL_GRID_SESSION_ETAG;
import static org.sagebionetworks.repo.model.query.jdo.SqlConstants.COL_GRID_SESSION_ID;
import static org.sagebionetworks.repo.model.query.jdo.SqlConstants.COL_GRID_SESSION_MODIFIED_ON;
import static org.sagebionetworks.repo.model.query.jdo.SqlConstants.COL_GRID_SESSION_REPLICA_SEQ_INT;
import static org.sagebionetworks.repo.model.query.jdo.SqlConstants.COL_GRID_SESSION_REPLICA_SEQ_WEB;
import static org.sagebionetworks.repo.model.query.jdo.SqlConstants.COL_GRID_SESSION_SESSION_ID;
import static org.sagebionetworks.repo.model.query.jdo.SqlConstants.DDL_GRID_SESSION;
import static org.sagebionetworks.repo.model.query.jdo.SqlConstants.TABLE_GRID_SESSION;

import static org.sagebionetworks.repo.model.query.jdo.SqlConstants.COL_GRID_REPLICA_CREATE_BY;
import static org.sagebionetworks.repo.model.query.jdo.SqlConstants.COL_GRID_REPLICA_CREATE_ON;
import static org.sagebionetworks.repo.model.query.jdo.SqlConstants.COL_GRID_REPLICA_ID;
import static org.sagebionetworks.repo.model.query.jdo.SqlConstants.COL_GRID_REPLICA_IS_AGENT;
import static org.sagebionetworks.repo.model.query.jdo.SqlConstants.COL_GRID_REPLICA_REPLICA_ID;
import static org.sagebionetworks.repo.model.query.jdo.SqlConstants.COL_GRID_REPLICA_SESSION_ID;
import static org.sagebionetworks.repo.model.query.jdo.SqlConstants.DDL_GRID_REPLICA;
import static org.sagebionetworks.repo.model.query.jdo.SqlConstants.TABLE_GRID_REPLICA;

import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.util.Base64;

import org.sagebionetworks.ids.IdGenerator;
import org.sagebionetworks.ids.IdType;

import org.sagebionetworks.repo.model.dbo.DBOBasicDao;
import org.sagebionetworks.repo.model.grid.EventSource;
import org.sagebionetworks.repo.model.grid.GridConstants;
import org.sagebionetworks.repo.model.grid.GridReplica;
import org.sagebionetworks.repo.model.grid.GridSession;
import org.sagebionetworks.repo.transactions.WriteTransaction;
import org.sagebionetworks.util.ValidateArgument;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class GridDaoImpl implements GridDao {



	private final IdGenerator idGenerator;
	private final JdbcTemplate jdbcTemplate;
	private final DBOBasicDao basicDao;

	private final RowMapper<GridSession> SESSION_MAPPER = (ResultSet rs, int rowNum) -> {
		return new GridSession().setSessionId(rs.getString(COL_GRID_SESSION_SESSION_ID))
				.setStartedOn(rs.getTimestamp(COL_GRID_SESSION_CREATED_ON))
				.setStartedBy(rs.getString(COL_GRID_SESSION_CREATED_BY));

	};

	public GridDaoImpl(IdGenerator idGenerator, JdbcTemplate jdbcTemplate, DBOBasicDao basicDao) {
		super();
		this.idGenerator = idGenerator;
		this.jdbcTemplate = jdbcTemplate;
		this.basicDao = basicDao;
	}

	@WriteTransaction
	@Override
	public GridSession createGridSession(Long userId) {
		ValidateArgument.required(userId, "userId");
		Long id = idGenerator.generateNewId(IdType.GRID_SESSION_ID);
		String sessionId = Base64.getEncoder().encodeToString(id.toString().getBytes(StandardCharsets.UTF_8));
		long seqWeb = GridConstants.START_REPLICA_ID_USER;
		long seqInt = GridConstants.START_REPLICA_ID_INTERNAL;
		jdbcTemplate.update(
				"INSERT INTO GRID_SESSION (ID, ETAG, CREATED_BY, CREATED_ON, MODIFIED_ON, SESSION_ID, REPLICA_SEQ_INT, REPLICA_SEQ_WEB)"
						+ " VALUES(?,UUID(),?,NOw(),NOW(),?,?,?)",
				id, userId, sessionId, seqInt, seqWeb);
		return geGridSession(sessionId);
	}

	@Override
	public Long getGridSessionStartedBy(String gridSessionId) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public GridSession geGridSession(String gridSessionId) {
		ValidateArgument.required(gridSessionId, "gridSessionId");
		return jdbcTemplate.queryForObject(
				"SELECT SESSION_ID, CREATED_ON, CREATED_BY"
						+ "  FROM GRID_SESSION WHERE SESSION_ID = ?",
				SESSION_MAPPER, gridSessionId);
	}

	@WriteTransaction
	@Override
	public GridReplica createReplica(Long userId, String gridSessionId, boolean isAgent, EventSource source) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public GridReplica getGridReplica(String sessionId, Long replicaId) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public Long getReplicaCreatedBy(String sessionId, Long replicaId, boolean isAgentReplica) {
		// TODO Auto-generated method stub
		return null;
	}

}
