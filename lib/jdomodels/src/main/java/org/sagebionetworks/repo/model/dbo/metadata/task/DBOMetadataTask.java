package org.sagebionetworks.repo.model.dbo.metadata.task;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Objects;

import org.sagebionetworks.repo.model.dbo.FieldColumn;
import org.sagebionetworks.repo.model.dbo.MigratableDatabaseObject;
import org.sagebionetworks.repo.model.dbo.TableMapping;
import org.sagebionetworks.repo.model.dbo.migration.BasicMigratableTableTranslation;
import org.sagebionetworks.repo.model.dbo.migration.MigratableTableTranslation;
import org.sagebionetworks.repo.model.migration.MigrationType;
import org.sagebionetworks.repo.model.query.jdo.SqlConstants;

public class DBOMetadataTask implements MigratableDatabaseObject<DBOMetadataTask, DBOMetadataTask> {

    private static final FieldColumn[] FIELDS = new FieldColumn[]{
            new FieldColumn("id", SqlConstants.COL_METADATA_TASK_ID, true).withIsBackupId(true),
            new FieldColumn("etag", SqlConstants.COL_METADATA_TASK_ETAG).withIsEtag(true),
            new FieldColumn("dataType", SqlConstants.COL_METADATA_TASK_DATA_TYPE),
            new FieldColumn("projectId", SqlConstants.COL_METADATA_TASK_PROJECT_ID),
            new FieldColumn("instructions", SqlConstants.COL_METADATA_TASK_INSTRUCTIONS),
            new FieldColumn("createdBy", SqlConstants.COL_METADATA_TASK_CREATED_BY),
            new FieldColumn("createdOn", SqlConstants.COL_METADATA_TASK_CREATED_ON),
            new FieldColumn("modifiedBy", SqlConstants.COL_METADATA_TASK_MODIFIED_BY),
            new FieldColumn("modifiedOn", SqlConstants.COL_METADATA_TASK_MODIFIED_ON),
            new FieldColumn("uploadFolderId", SqlConstants.COL_METADATA_TASK_UPLOAD_FOLDER_ID),
            new FieldColumn("fileViewId", SqlConstants.COL_METADATA_TASK_FILE_VIEW_ID),
            new FieldColumn("recordSetId", SqlConstants.COL_METADATA_TASK_RECORD_SET_ID),
            new FieldColumn("taskType", SqlConstants.COL_METADATA_TASK_TASK_TYPE)
    };

    private static final TableMapping<DBOMetadataTask> TABLE_MAPPING = new TableMapping<>() {

        @Override
        public DBOMetadataTask mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new DBOMetadataTask()
                    .setId(rs.getLong(SqlConstants.COL_METADATA_TASK_ID))
                    .setDataType(rs.getString(SqlConstants.COL_METADATA_TASK_DATA_TYPE))
                    .setProjectId(rs.getLong(SqlConstants.COL_METADATA_TASK_PROJECT_ID))
                    .setInstructions(rs.getString(SqlConstants.COL_METADATA_TASK_INSTRUCTIONS))
                    .setUploadFolderId(rs.getLong(SqlConstants.COL_METADATA_TASK_UPLOAD_FOLDER_ID))
                    .setFileViewId(rs.getLong(SqlConstants.COL_METADATA_TASK_FILE_VIEW_ID))
                    .setRecordSetId(rs.getLong(SqlConstants.COL_METADATA_TASK_RECORD_SET_ID))
                    .setEtag(rs.getString(SqlConstants.COL_METADATA_TASK_ETAG))
                    .setCreatedBy(rs.getLong(SqlConstants.COL_METADATA_TASK_CREATED_BY))
                    .setCreatedOn(rs.getTimestamp(SqlConstants.COL_METADATA_TASK_CREATED_ON))
                    .setModifiedBy(rs.getLong(SqlConstants.COL_METADATA_TASK_MODIFIED_BY))
                    .setModifiedOn(rs.getTimestamp(SqlConstants.COL_METADATA_TASK_MODIFIED_ON))
                    .setTaskType(rs.getString(SqlConstants.COL_METADATA_TASK_TASK_TYPE));
        }

        @Override
        public String getTableName() {
            return SqlConstants.TABLE_METADATA_TASK;
        }

        @Override
        public FieldColumn[] getFieldColumns() {
            return FIELDS;
        }

        @Override
        public String getDDLFileName() {
            return SqlConstants.DDL_METADATA_TASK;
        }

        @Override
        public Class<? extends DBOMetadataTask> getDBOClass() {
            return DBOMetadataTask.class;
        }
    };

    private Long id;
    private String dataType;
    private Long projectId;
    private String instructions;
    private String etag;
    private Long createdBy;
    private Timestamp createdOn;
    private Long modifiedBy;
    private Timestamp modifiedOn;
    private Long uploadFolderId;
    private Long fileViewId;
    private Long recordSetId;
    private String taskType;

    public DBOMetadataTask() {
    }

    public Long getId() {
        return id;
    }

    public DBOMetadataTask setId(Long id) {
        this.id = id;
        return this;
    }

    public String getDataType() {
        return dataType;
    }

    public DBOMetadataTask setDataType(String dataType) {
        this.dataType = dataType;
        return this;
    }

    public Long getProjectId() {
        return projectId;
    }

    public DBOMetadataTask setProjectId(Long projectId) {
        this.projectId = projectId;
        return this;
    }

    public String getInstructions() {
        return instructions;
    }

    public DBOMetadataTask setInstructions(String instructions) {
        this.instructions = instructions;
        return this;
    }

    public String getEtag() {
        return etag;
    }

    public DBOMetadataTask setEtag(String etag) {
        this.etag = etag;
        return this;
    }

    public Long getCreatedBy() {
        return createdBy;
    }

    public DBOMetadataTask setCreatedBy(Long createdBy) {
        this.createdBy = createdBy;
        return this;
    }

    public Date getCreatedOn() {
        return createdOn;
    }

    public DBOMetadataTask setCreatedOn(Timestamp createdOn) {
        this.createdOn = createdOn;
        return this;
    }

    public Long getModifiedBy() {
        return modifiedBy;
    }

    public DBOMetadataTask setModifiedBy(Long modifiedBy) {
        this.modifiedBy = modifiedBy;
        return this;
    }

    public Date getModifiedOn() {
        return modifiedOn;
    }

    public DBOMetadataTask setModifiedOn(Timestamp modifiedOn) {
        this.modifiedOn = modifiedOn;
        return this;
    }

    public Long getUploadFolderId() {
        return uploadFolderId;
    }

    public DBOMetadataTask setUploadFolderId(Long uploadFolderId) {
        this.uploadFolderId = uploadFolderId;
        return this;
    }

    public Long getFileViewId() {
        return fileViewId;
    }

    public DBOMetadataTask setFileViewId(Long fileViewId) {
        this.fileViewId = fileViewId;
        return this;
    }

    public Long getRecordSetId() {
        return recordSetId;
    }

    public DBOMetadataTask setRecordSetId(Long recordSetId) {
        this.recordSetId = recordSetId;
        return this;
    }

    public String getTaskType() {
        return taskType;
    }

    public DBOMetadataTask setTaskType(String taskType) {
        this.taskType = taskType;
        return this;
    }
    @Override
    public TableMapping<DBOMetadataTask> getTableMapping() {
        return TABLE_MAPPING;
    }

    @Override
    public MigrationType getMigratableTableType() {
        return MigrationType.METADATA_TASK;
    }

    @Override
    public MigratableTableTranslation<DBOMetadataTask, DBOMetadataTask> getTranslator() {
        return new BasicMigratableTableTranslation<>();
    }

    @Override
    public Class<? extends DBOMetadataTask> getBackupClass() {
        return DBOMetadataTask.class;
    }

    @Override
    public Class<? extends DBOMetadataTask> getDatabaseObjectClass() {
        return DBOMetadataTask.class;
    }

    @Override
    public List<MigratableDatabaseObject<?, ?>> getSecondaryTypes() {
        return Collections.emptyList();
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, dataType, projectId, instructions, etag, createdBy, createdOn, modifiedBy, modifiedOn, uploadFolderId, fileViewId, recordSetId, taskType);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof DBOMetadataTask)) {
            return false;
        }
        DBOMetadataTask other = (DBOMetadataTask) obj;
        return Objects.equals(id, other.id) &&
                Objects.equals(dataType, other.dataType) &&
                Objects.equals(projectId, other.projectId) &&
                Objects.equals(instructions, other.instructions) &&
                Objects.equals(etag, other.etag) &&
                Objects.equals(createdBy, other.createdBy) &&
                Objects.equals(createdOn, other.createdOn) &&
                Objects.equals(modifiedBy, other.modifiedBy) && Objects.equals(modifiedOn, other.modifiedOn) &&
                Objects.equals(uploadFolderId, other.uploadFolderId) &&
                Objects.equals(fileViewId, other.fileViewId) &&
                Objects.equals(recordSetId, other.recordSetId) &&
                Objects.equals(taskType, other.taskType);
    }

    @Override
    public String toString() {
        return "DBOMetadataTask{" +
                "id=" + id +
                ", dataType='" + dataType + '\'' +
                ", projectId=" + projectId +
                ", instructions='" + instructions + '\'' +
                ", etag='" + etag + '\'' +
                ", createdBy=" + createdBy +
                ", createdOn=" + createdOn +
                ", modifiedBy=" + modifiedBy +
                ", modifiedOn=" + modifiedOn +
                ", uploadFolderId=" + uploadFolderId +
                ", fileViewId=" + fileViewId +
                ", recordSetId=" + recordSetId +
                ", taskType=" + taskType +
                '}';
    }

}
