package org.sagebionetworks.repo.model.dbo.curation;

import static org.sagebionetworks.repo.model.query.jdo.SqlConstants.COL_CURATION_TASK_CREATED_BY;
import static org.sagebionetworks.repo.model.query.jdo.SqlConstants.COL_CURATION_TASK_CREATED_ON;
import static org.sagebionetworks.repo.model.query.jdo.SqlConstants.COL_CURATION_TASK_ETAG;
import static org.sagebionetworks.repo.model.query.jdo.SqlConstants.COL_CURATION_TASK_ID;
import static org.sagebionetworks.repo.model.query.jdo.SqlConstants.COL_CURATION_TASK_MODIFIED_BY;
import static org.sagebionetworks.repo.model.query.jdo.SqlConstants.COL_CURATION_TASK_MODIFIED_ON;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import org.sagebionetworks.ids.IdGenerator;
import org.sagebionetworks.ids.IdType;
import org.sagebionetworks.repo.model.ConflictingUpdateException;
import org.sagebionetworks.repo.model.curation.CurationTask;
import org.sagebionetworks.repo.model.curation.CurationTaskProperties;
import org.sagebionetworks.repo.model.dbo.DBOBasicDao;
import org.sagebionetworks.repo.model.dbo.SinglePrimaryKeySqlParameterSource;
import org.sagebionetworks.repo.model.jdo.JDOSecondaryPropertyUtils;
import org.sagebionetworks.repo.model.jdo.KeyFactory;
import org.sagebionetworks.repo.model.query.jdo.SqlConstants;
import org.sagebionetworks.repo.transactions.WriteTransaction;
import org.sagebionetworks.repo.web.NotFoundException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Repository;

@Repository
public class CurationTaskDaoImpl implements CurationTaskDao {

    private final JdbcTemplate jdbcTemplate;
    private final IdGenerator idGenerator;
    private final DBOBasicDao basicDao;

    public CurationTaskDaoImpl(JdbcTemplate jdbcTemplate, IdGenerator idGenerator, DBOBasicDao basicDao) {
        this.jdbcTemplate = jdbcTemplate;
        this.idGenerator = idGenerator;
        this.basicDao = basicDao;
    }

    private static final RowMapper<DBOCurationTask> CURATION_TASK_ROW_MAPPER = (rs, rowNum) ->
            new DBOCurationTask().setId(rs.getLong(COL_CURATION_TASK_ID))
                    .setDataType(rs.getString(SqlConstants.COL_CURATION_TASK_DATA_TYPE))
                    .setProjectId(rs.getLong(SqlConstants.COL_CURATION_TASK_PROJECT_ID))
                    .setInstructions(rs.getString(SqlConstants.COL_CURATION_TASK_INSTRUCTIONS))
                    .setEtag(rs.getString(COL_CURATION_TASK_ETAG))
                    .setCreatedBy(rs.getLong(COL_CURATION_TASK_CREATED_BY))
                    .setCreatedOn(new Timestamp(rs.getTimestamp(COL_CURATION_TASK_CREATED_ON).getTime()))
                    .setModifiedBy(rs.getLong(COL_CURATION_TASK_MODIFIED_BY))
                    .setModifiedOn(new Timestamp(rs.getTimestamp(COL_CURATION_TASK_MODIFIED_ON).getTime()))
                    .setTaskPropertiesJson(rs.getString(SqlConstants.COL_CURATION_TASK_TASK_PROPERTIES));


    @Override
    @WriteTransaction
    public CurationTask createCurationTask(Long userId, CurationTask toCreate) {
        Instant now = Instant.now();
        toCreate.setTaskId(idGenerator.generateNewId(IdType.CURATION_TASK_ID))
                .setEtag(UUID.randomUUID().toString())
                .setCreatedBy(String.valueOf(userId))
                .setCreatedOn(Timestamp.from(now))
                .setModifiedBy(String.valueOf(userId))
                .setModifiedOn(Timestamp.from(now));

        DBOCurationTask dbo = mapToDbo(toCreate);

        try {
            basicDao.createNew(dbo);
        } catch (IllegalArgumentException e) {
            if (e.getCause() instanceof DuplicateKeyException) {
                throw new IllegalArgumentException("A curation task with the specified data type already exists in this project.", e);
            }
            throw e;
        }

        return getCurationTask(dbo.getId()).orElseThrow(() -> new IllegalStateException("The curation task was not created."));
    }

    @Override
    @WriteTransaction
    public CurationTask updateCurationTask(Long userId, CurationTask toUpdate) {
        CurationTask currentTask = getCurationTaskForUpdate(toUpdate.getTaskId())
                .orElseThrow(() -> new NotFoundException("A curation task with ID " + toUpdate.getTaskId() + " does not exist."));


        if (!currentTask.getEtag().equals(toUpdate.getEtag())) {
            throw new ConflictingUpdateException("The curation task was updated since you last fetched it, please fetch it again and reapply your changes.");
        }

        toUpdate.setCreatedBy(currentTask.getCreatedBy())
                .setCreatedOn(currentTask.getCreatedOn())
                .setEtag(UUID.randomUUID().toString())
                .setModifiedBy(userId.toString())
                .setModifiedOn(Timestamp.from(Instant.now()));

        DBOCurationTask dbo = mapToDbo(toUpdate);

        try {
            basicDao.update(dbo);
        } catch (IllegalArgumentException e) {
            handleUniquenessConstraintViolation(e);
        }
        return getCurationTask(toUpdate.getTaskId()).orElseThrow(() -> new IllegalStateException("The curation task was not updated."));
    }

    @Override
    public Optional<CurationTask> getCurationTask(Long taskId) {
        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("id", taskId);

        try {
            return basicDao.getObjectByPrimaryKey(DBOCurationTask.class, param).map(CurationTaskDaoImpl::mapToDto);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    @Override
    @WriteTransaction
    public void deleteCurationTask(Long taskId) {
        basicDao.deleteObjectByPrimaryKey(DBOCurationTask.class, new SinglePrimaryKeySqlParameterSource(taskId));
    }

    @Override
    public List<CurationTask> getCurationTasks(Long projectId, long limit, long offset) {
        String sql = "SELECT * FROM " + SqlConstants.TABLE_CURATION_TASK + " WHERE "
                + SqlConstants.COL_CURATION_TASK_PROJECT_ID + " = ? "
                + "ORDER BY " + SqlConstants.COL_CURATION_TASK_ID + " LIMIT ? OFFSET ?";

        List<DBOCurationTask> dbos = jdbcTemplate.query(sql, CURATION_TASK_ROW_MAPPER, projectId, limit, offset);
        return dbos.stream().map(CurationTaskDaoImpl::mapToDto).collect(Collectors.toList());
    }

    @WriteTransaction
    private Optional<CurationTask> getCurationTaskForUpdate(Long taskId) {
        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("id", taskId);

        try {
            return basicDao.getObjectByPrimaryKeyWithUpdateLock(DBOCurationTask.class, param).map(CurationTaskDaoImpl::mapToDto);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }

    }

    private static void handleUniquenessConstraintViolation(IllegalArgumentException e) {
        Throwable cause = e.getCause();
        if (cause instanceof DuplicateKeyException && cause.getMessage() != null && cause.getMessage().contains("CURATION_TASK_DATA_TYPE_PROJECT_ID")) {
            throw new IllegalArgumentException("A curation task with the specified data type already exists in this project.", e);
        }
        throw e;
    }

    private static DBOCurationTask mapToDbo(CurationTask dto) {
        return new DBOCurationTask()
                .setId(dto.getTaskId())
                .setDataType(dto.getDataType())
                .setProjectId(KeyFactory.stringToKey(dto.getProjectId()))
                .setInstructions(dto.getInstructions())
                .setEtag(dto.getEtag())
                .setCreatedBy(Long.parseLong(dto.getCreatedBy()))
                .setCreatedOn(new Timestamp(dto.getCreatedOn().getTime()))
                .setModifiedBy(Long.parseLong(dto.getModifiedBy()))
                .setModifiedOn(new Timestamp(dto.getModifiedOn().getTime()))
                .setTaskPropertiesJson(JDOSecondaryPropertyUtils.createJSONFromObject(dto.getTaskProperties()));
    }

    private static CurationTask mapToDto(DBOCurationTask dbo) {
        return new CurationTask()
                .setTaskId(dbo.getId())
                .setDataType(dbo.getDataType())
                .setProjectId(KeyFactory.keyToString(dbo.getProjectId()))
                .setInstructions(dbo.getInstructions())
                .setEtag(dbo.getEtag())
                .setCreatedBy(dbo.getCreatedBy().toString())
                .setCreatedOn(dbo.getCreatedOn())
                .setModifiedBy(dbo.getModifiedBy().toString())
                .setModifiedOn(dbo.getModifiedOn())
                .setTaskProperties(JDOSecondaryPropertyUtils.createObjectFromJSON(CurationTaskProperties.class, dbo.getTaskPropertiesJson()));
    }
}
