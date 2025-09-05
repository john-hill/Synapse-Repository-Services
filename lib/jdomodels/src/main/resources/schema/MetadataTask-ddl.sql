CREATE TABLE IF NOT EXISTS `METADATA_TASK`
(
    `ID`               BIGINT                                                        NOT NULL,
    `DATA_TYPE`        varchar(256) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
    `PROJECT_ID`       BIGINT                                                        NOT NULL,
    `INSTRUCTIONS`     MEDIUMTEXT DEFAULT NULL,
    `ETAG`             char(36)                                                      NOT NULL,
    `CREATED_BY`       BIGINT                                                        NOT NULL,
    `CREATED_ON`       TIMESTAMP(3)                                                  NOT NULL,
    `MODIFIED_BY`      BIGINT                                                        NOT NULL,
    `MODIFIED_ON`      TIMESTAMP(3)                                                  NOT NULL,
    `UPLOAD_FOLDER_ID` BIGINT     DEFAULT NULL,
    `FILE_VIEW_ID`     BIGINT     DEFAULT NULL,
    `RECORD_SET_ID`    BIGINT     DEFAULT NULL,
    `TASK_TYPE`        ENUM ('FILE_BASED', 'RECORD_BASED')                           NOT NULL,
    PRIMARY KEY (`ID`),
    CONSTRAINT UNIQUE (`DATA_TYPE`, `PROJECT_ID`),
    CONSTRAINT `METADATA_TASK_PROJECT_FK` FOREIGN KEY (`PROJECT_ID`) REFERENCES `NODE` (`ID`) ON DELETE CASCADE,
    CONSTRAINT `METADATA_TASK_UPLOAD_FOLDER_FK` FOREIGN KEY (`UPLOAD_FOLDER_ID`) REFERENCES `NODE` (`ID`) ON DELETE SET NULL,
    CONSTRAINT `METADATA_TASK_FILE_VIEW_FK` FOREIGN KEY (`FILE_VIEW_ID`) REFERENCES `NODE` (`ID`) ON DELETE SET NULL,
    CONSTRAINT `METADATA_TASK_RECORD_SET_FK` FOREIGN KEY (`RECORD_SET_ID`) REFERENCES `NODE` (`ID`) ON DELETE SET NULL
)
