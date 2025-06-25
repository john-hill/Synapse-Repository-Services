package org.sagebionetworks.grid.db;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import org.apache.commons.io.IOUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.sagebionetworks.repo.model.grid.GridUtils;
import org.sagebionetworks.repo.model.grid.node.ConstantNode;
import org.sagebionetworks.repo.model.grid.node.IndexNode;
import org.sagebionetworks.repo.model.grid.node.IndexType;
import org.sagebionetworks.repo.model.grid.node.ObjectNode;
import org.sagebionetworks.repo.model.grid.patch.LogicalTimestamp;
import org.sagebionetworks.repo.model.grid.patch.operation.NewConstant;
import org.sagebionetworks.repo.model.grid.patch.operation.NewObject;
import org.sagebionetworks.repo.model.grid.patch.operation.NewVector;
import org.sagebionetworks.util.ValidateArgument;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Transactional(readOnly = false, propagation = Propagation.REQUIRED, isolation = Isolation.READ_COMMITTED)
public class GridIndexDaoImpl implements GridIndexDao {

	private static final Logger log = LogManager.getLogger(GridIndexDaoImpl.class);

	private final JdbcTemplate jdbcTempalte;
	private final NamedParameterJdbcTemplate namedTemplate;

	private static RowMapper<IndexNode> INDEX_NODE_MAPPER = (ResultSet rs, int rowNum) -> {
		return new IndexNode().setType(IndexType.valueOf(rs.getString("KIND"))).setId(
				new LogicalTimestamp().setReplicaId(rs.getLong("NODE_REP")).setSequenceNumber(rs.getLong("NODE_SEQ")));
	};

	public GridIndexDaoImpl(JdbcTemplate gridDatabaseJdbcTempalte,
			NamedParameterJdbcTemplate gridDatabaseNamedParameterJdbcTempalte) {
		super();
		this.jdbcTempalte = gridDatabaseJdbcTempalte;
		this.namedTemplate = gridDatabaseNamedParameterJdbcTempalte;
		createTables(List.of("schema/Grid-Replica-ddl.sql", "schema/Grid-Clock-ddl.sql", "schema/Grid-Index-ddl.sql",
				"schema/Grid-Array-ddl.sql", "schema/Grid-Vector-ddl.sql", "schema/Grid-Object-ddl.sql",
				"schema/Grid-Constant-ddl.sql"));
	}

	/**
	 * Create all of the tables from the classpath.
	 * 
	 * @param tables
	 */
	private void createTables(List<String> tables) {
		tables.forEach(t -> {
			try (InputStream in = GridIndexDaoImpl.class.getClassLoader().getResourceAsStream(t)) {
				if (in == null) {
					throw new IllegalArgumentException("Cannot find file " + t + " on classpath.");
				}
				String ddl = IOUtils.toString(in, StandardCharsets.UTF_8);
				log.info("Running: {}", t);
				this.jdbcTempalte.update(ddl);
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
		});
	}

	Long validateReplica(String sessionId, Long replicaId) {
		ValidateArgument.required(sessionId, "sessionId");
		ValidateArgument.required(replicaId, "repicaId");
		return GridUtils.gridSessionIdAsLong(sessionId);
	}

	@Transactional(readOnly = false)
	@Override
	public void createReplicaIfNotExists(String sessionIdString, Long replicaId) {
		Long sessionId = validateReplica(sessionIdString, replicaId);

		jdbcTempalte.update("INSERT IGNORE INTO GRID_REPLICA (SESSION_ID, REPLICA_ID, CREATED_ON) VALUES (?,?,NOW())",
				sessionId, replicaId);

	}

	@Transactional(readOnly = false)
	@Override
	public void deleteReplica(String sessionIdString, Long replicaId) {
		Long sessionId = validateReplica(sessionIdString, replicaId);
		jdbcTempalte.update("DELETE FROM GRID_REPLICA WHERE SESSION_ID = ? AND REPLICA_ID = ?", sessionId, replicaId);
	}

	@Override
	public Optional<Timestamp> getReplciaCreatedOn(String sessionIdString, Long replicaId) {
		Long sessionId = validateReplica(sessionIdString, replicaId);
		try {
			return Optional.of(jdbcTempalte.queryForObject(
					"SELECT CREATED_ON FROM GRID_REPLICA WHERE SESSION_ID = ? AND REPLICA_ID = ?", Timestamp.class,
					sessionId, replicaId));
		} catch (EmptyResultDataAccessException e) {
			return Optional.empty();
		}
	}

	@Override
	public List<LogicalTimestamp> getClock(String sessionIdString, Long replicaId) {
		Long sessionId = validateReplica(sessionIdString, replicaId);
		return null;
	}

	@Override
	public Optional<LogicalTimestamp> getClock(String sessionIdString, Long replicaId, Long patchReplicaId) {
		Long sessionId = validateReplica(sessionIdString, replicaId);
		return Optional.empty();
	}

	@Transactional(readOnly = false)
	@Override
	public void saveIndex(String sessionIdString, Long replicaId, IndexType type, List<LogicalTimestamp> batch) {
		Long sessionId = validateReplica(sessionIdString, replicaId);
		if (batch == null || batch.isEmpty()) {
			// Nothing to save, so we can return early.
			return;
		}
		SqlParameterSource[] batchArgs = batch.stream().map(ts -> new MapSqlParameterSource()
				// session
				.addValue("sessionId", sessionId)
				// rep
				.addValue("replicaId", replicaId)
				// batch rep
				.addValue("nodeRep", ts.getReplicaId())
				// batch seq
				.addValue("nodeSeq", ts.getSequenceNumber())
				// kind
				.addValue("kind", type.name())).toArray(SqlParameterSource[]::new);

		// Execute the batch update. Spring handles the iteration and statement
		// preparation.
		namedTemplate.batchUpdate("INSERT INTO GRID_INDEX (SESSION_ID, REPLICA_ID, NODE_REP, NODE_SEQ, KIND) "
				+ "VALUES (:sessionId, :replicaId, :nodeRep, :nodeSeq, :kind)", batchArgs);
	}

	@Override
	public List<IndexNode> getIndices(String sessionIdString, Long replicaId, List<LogicalTimestamp> ids) {
		Long sessionId = validateReplica(sessionIdString, replicaId);
		if (ids == null || ids.isEmpty()) {
			return List.of(); // Return an empty list if there's nothing to find.
		}

		List<Object[]> idTuples = ids.stream().map(ts -> new Object[] { ts.getReplicaId(), ts.getSequenceNumber() })
				.collect(Collectors.toList());

		// Create the parameter map for the query.
		MapSqlParameterSource params = new MapSqlParameterSource();
		params.addValue("sessionId", sessionId);
		params.addValue("replicaId", replicaId);
		params.addValue("ids", idTuples);

		return namedTemplate.query("SELECT NODE_REP, NODE_SEQ, KIND FROM GRID_INDEX "
				+ "WHERE SESSION_ID = :sessionId AND REPLICA_ID = :replicaId AND (NODE_REP, NODE_SEQ) IN (:timestamps)",
				params, INDEX_NODE_MAPPER);
	}

	@Override
	public void saveNewObjects(String sessionIdString, Long replicaId, List<NewObject> batch) {
		Long sessionId = validateReplica(sessionIdString, replicaId);
	}

	@Override
	public void saveNewVectors(String sessionIdString, Long replicaId, List<NewVector> batch) {
		Long sessionId = validateReplica(sessionIdString, replicaId);
	}

	@Override
	public void setClock(String sessionIdString, Long replicaId, LogicalTimestamp clock) {
		Long sessionId = validateReplica(sessionIdString, replicaId);
	}

	@Override
	public ObjectNode getObject(String sessionIdString, Long replicaId, String key) {
		Long sessionId = validateReplica(sessionIdString, replicaId);
		return null;
	}

	@Override
	public List<ConstantNode> getConstants(String sessionIdString, Long replicaId, List<LogicalTimestamp> ids) {
		Long sessionId = validateReplica(sessionIdString, replicaId);
		return null;
	}

	@Override
	public void saveNewConstants(String sessionIdString, Long replicaId, List<NewConstant> batch) {
		Long sessionId = validateReplica(sessionIdString, replicaId);

	}

	@Transactional(readOnly = false)
	@Override
	public void truncateAll() {
		jdbcTempalte.update("DELETE FROM GRID_REPLICA WHERE SESSION_ID > -1 AND REPLICA_ID > -1");
	}
}