package org.sagebionetworks.repo.model.dbo.metadata.task;

import static org.sagebionetworks.repo.model.query.jdo.SqlConstants.COL_METADATA_TASK_CREATED_BY;
import static org.sagebionetworks.repo.model.query.jdo.SqlConstants.COL_METADATA_TASK_CREATED_ON;
import static org.sagebionetworks.repo.model.query.jdo.SqlConstants.COL_METADATA_TASK_ETAG;
import static org.sagebionetworks.repo.model.query.jdo.SqlConstants.COL_METADATA_TASK_ID;
import static org.sagebionetworks.repo.model.query.jdo.SqlConstants.COL_METADATA_TASK_MODIFIED_BY;
import static org.sagebionetworks.repo.model.query.jdo.SqlConstants.COL_METADATA_TASK_MODIFIED_ON;
import static org.sagebionetworks.repo.model.query.jdo.SqlConstants.COL_METADATA_TASK_TASK_TYPE;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.sagebionetworks.ids.IdGenerator;
import org.sagebionetworks.ids.IdType;
import org.sagebionetworks.repo.model.ConflictingUpdateException;
import org.sagebionetworks.repo.model.dbo.DBOBasicDao;
import org.sagebionetworks.repo.model.dbo.SinglePrimaryKeySqlParameterSource;
import org.sagebionetworks.repo.model.jdo.KeyFactory;
import org.sagebionetworks.repo.model.metadata.FileBasedMetadataTask;
import org.sagebionetworks.repo.model.metadata.MetadataTask;
import org.sagebionetworks.repo.model.metadata.RecordBasedMetadataTask;
import org.sagebionetworks.repo.model.query.jdo.SqlConstants;
import org.sagebionetworks.repo.transactions.WriteTransaction;
import org.sagebionetworks.repo.web.NotFoundException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class MetadataTaskDaoImpl implements MetadataTaskDao {

    private final JdbcTemplate jdbcTemplate;
    private final IdGenerator idGenerator;
    private final DBOBasicDao basicDao;

    public MetadataTaskDaoImpl(JdbcTemplate jdbcTemplate, IdGenerator idGenerator, DBOBasicDao basicDao) {
        this.jdbcTemplate = jdbcTemplate;
        this.idGenerator = idGenerator;
        this.basicDao = basicDao;
    }


    private static final RowMapper<MetadataTask> METADATA_TASK_ROW_MAPPER = (rs, rowNum) -> {
        MetadataTask task;
        MetadataTaskType objectType = MetadataTaskType.valueOf(rs.getString(COL_METADATA_TASK_TASK_TYPE));
        switch (objectType) {
            case FILE_BASED:
                FileBasedMetadataTask fileBasedTask = new FileBasedMetadataTask();
                fileBasedTask.setUploadFolderId(KeyFactory.keyToString(rs.getLong(SqlConstants.COL_METADATA_TASK_UPLOAD_FOLDER_ID)))
                        .setFileViewId(KeyFactory.keyToString(rs.getLong(SqlConstants.COL_METADATA_TASK_FILE_VIEW_ID)));
                task = fileBasedTask;
                break;
            case RECORD_BASED: {
                RecordBasedMetadataTask recordBasedTask = new RecordBasedMetadataTask();
                recordBasedTask.setRecordSetId(KeyFactory.keyToString(rs.getLong(SqlConstants.COL_METADATA_TASK_RECORD_SET_ID)));
                task = recordBasedTask;
                break;
            }
            default:
                throw new IllegalStateException("Unknown metadata task object type: " + objectType);
        }
        return task.setTaskId(String.valueOf(rs.getLong(COL_METADATA_TASK_ID)))
                .setDataType(rs.getString(SqlConstants.COL_METADATA_TASK_DATA_TYPE))
                .setProjectId(KeyFactory.keyToString(rs.getLong(SqlConstants.COL_METADATA_TASK_PROJECT_ID)))
                .setInstructions(rs.getString(SqlConstants.COL_METADATA_TASK_INSTRUCTIONS))
                .setEtag(rs.getString(COL_METADATA_TASK_ETAG))
                .setCreatedBy(rs.getString(COL_METADATA_TASK_CREATED_BY))
                .setCreatedOn(new Date(rs.getTimestamp(COL_METADATA_TASK_CREATED_ON).getTime()))
                .setModifiedBy(rs.getString(COL_METADATA_TASK_MODIFIED_BY))
                .setModifiedOn(new Date(rs.getTimestamp(COL_METADATA_TASK_MODIFIED_ON).getTime()));
    };


    @Override
    @WriteTransaction
    public MetadataTask createMetadataTask(Long userId, MetadataTask toCreate) {
        Instant now = Instant.now();

        DBOMetadataTask dbo = new DBOMetadataTask()
                .setId(idGenerator.generateNewId(IdType.METADATA_TASK_ID))
                .setDataType(toCreate.getDataType())
                .setProjectId(KeyFactory.stringToKey(toCreate.getProjectId()))
                .setInstructions(toCreate.getInstructions())
                .setEtag(UUID.randomUUID().toString())
                .setCreatedBy(userId)
                .setCreatedOn(Timestamp.from(now))
                .setModifiedBy(userId)
                .setModifiedOn(Timestamp.from(now));

        if (toCreate instanceof FileBasedMetadataTask) {
            dbo.setTaskType(MetadataTaskType.FILE_BASED.name());

            FileBasedMetadataTask fb = (FileBasedMetadataTask) toCreate;
            if (fb.getUploadFolderId() != null) {
                dbo.setUploadFolderId(KeyFactory.stringToKey(fb.getUploadFolderId()));
            }
            if (fb.getFileViewId() != null) {
                dbo.setFileViewId(KeyFactory.stringToKey(fb.getFileViewId()));
            }
        } else if (toCreate instanceof RecordBasedMetadataTask) {
            dbo.setTaskType(MetadataTaskType.RECORD_BASED.name());

            RecordBasedMetadataTask rb = (RecordBasedMetadataTask) toCreate;
            if (rb.getRecordSetId() != null) {
                dbo.setRecordSetId(KeyFactory.stringToKey(rb.getRecordSetId()));
            }
        }
        try {
            basicDao.createNew(dbo);
        } catch (IllegalArgumentException e) {
            if (e.getCause() instanceof DuplicateKeyException) {
                throw new IllegalArgumentException("A metadata task with the specified data type already exists in this project.", e);
            }
            throw e;
        }

        return getMetadataTask(dbo.getId().toString()).orElseThrow(() -> new IllegalStateException("The metadata task was not created."));
    }

    @Override
    @WriteTransaction
    public MetadataTask updateMetadataTask(Long userId, MetadataTask toUpdate) {
        MetadataTask currentTask = getMetadataTask(toUpdate.getTaskId()).orElseThrow(() -> new NotFoundException("No metadata task exists with id: " + toUpdate.getTaskId()));

        if (!currentTask.getEtag().equals(toUpdate.getEtag())) {
            throw new ConflictingUpdateException("The metadata task was updated since you last fetched it, please fetch it again and reapply your changes.");
        }
        if (!currentTask.getConcreteType().equals(toUpdate.getConcreteType())) {
            throw new IllegalArgumentException("The concrete type of a metadata task cannot be changed.");
        }

        Long uploadFolderId = null;
        Long fileViewId = null;
        Long recordSetId = null;
        if (toUpdate instanceof FileBasedMetadataTask) {
            uploadFolderId = KeyFactory.stringToKey(((FileBasedMetadataTask) toUpdate).getUploadFolderId());
            fileViewId = KeyFactory.stringToKey(((FileBasedMetadataTask) toUpdate).getUploadFolderId());
        } else if (toUpdate instanceof RecordBasedMetadataTask) {
            recordSetId = KeyFactory.stringToKey(((RecordBasedMetadataTask) toUpdate).getRecordSetId());
        }

        String sql = "UPDATE " + SqlConstants.TABLE_METADATA_TASK + " SET "
                + SqlConstants.COL_METADATA_TASK_DATA_TYPE + " = ?, "
                + SqlConstants.COL_METADATA_TASK_PROJECT_ID + " = ?, "
                + SqlConstants.COL_METADATA_TASK_INSTRUCTIONS + " = ?, "
                + SqlConstants.COL_METADATA_TASK_UPLOAD_FOLDER_ID + " = ?, "
                + SqlConstants.COL_METADATA_TASK_FILE_VIEW_ID + " = ?, "
                + SqlConstants.COL_METADATA_TASK_RECORD_SET_ID + " = ?, "
                + COL_METADATA_TASK_ETAG + " = UUID(), "
                + SqlConstants.COL_METADATA_TASK_MODIFIED_BY + " = ?, "
                + SqlConstants.COL_METADATA_TASK_MODIFIED_ON + " = NOW() "
                + "WHERE " + SqlConstants.COL_METADATA_TASK_ID + " = ?";

        jdbcTemplate.update(
                sql,
                toUpdate.getDataType(),
                KeyFactory.stringToKey(toUpdate.getProjectId()),
                toUpdate.getInstructions(),
                uploadFolderId,
                fileViewId,
                recordSetId,
                userId,
                toUpdate.getTaskId()
        );
        return getMetadataTask(toUpdate.getTaskId()).orElseThrow(() -> new IllegalStateException("The metadata task was not updated."));
    }

    @Override
    public Optional<MetadataTask> getMetadataTask(String taskId) {
        String sql = "SELECT * FROM " + SqlConstants.TABLE_METADATA_TASK + " WHERE "
                + SqlConstants.COL_METADATA_TASK_ID + " = ?";

        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject(sql, METADATA_TASK_ROW_MAPPER, Long.parseLong(taskId)));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    @Override
    @WriteTransaction
    public void deleteMetadataTask(String taskId) {
        basicDao.deleteObjectByPrimaryKey(DBOMetadataTask.class, new SinglePrimaryKeySqlParameterSource(Long.parseLong(taskId)));
    }

    @Override
    public List<MetadataTask> getMetadataTasks(Long projectId, long limit, long offset) {
        String sql = "SELECT * FROM " + SqlConstants.TABLE_METADATA_TASK + " WHERE "
                + SqlConstants.COL_METADATA_TASK_PROJECT_ID + " = ? "
                + "ORDER BY " + SqlConstants.COL_METADATA_TASK_ID + " LIMIT ? OFFSET ?";

        return jdbcTemplate.query(sql, METADATA_TASK_ROW_MAPPER, projectId, limit, offset);
    }
}
