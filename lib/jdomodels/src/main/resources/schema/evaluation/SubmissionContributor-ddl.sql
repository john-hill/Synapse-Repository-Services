CREATE TABLE IF NOT EXISTS`SUBMISSION_CONTRIBUTOR` (
  `ID` bigint NOT NULL,
  `ETAG` char(36) NOT NULL,
  `SUBMISSION_ID` bigint NOT NULL,
  `PRINCIPAL_ID` bigint NOT NULL,
  `CREATED_ON` TIMESTAMP(3) NOT NULL,
  PRIMARY KEY (`ID`),
  UNIQUE KEY `SUBMISSION_ID` (`SUBMISSION_ID`,`PRINCIPAL_ID`),
  KEY `SUBMISSION_C_PRINCIPAL_ID_FK` (`PRINCIPAL_ID`),
  CONSTRAINT `SUBMISSION_C_PRINCIPAL_ID_FK` FOREIGN KEY (`PRINCIPAL_ID`) REFERENCES `USER_GROUP` (`ID`) ON DELETE RESTRICT,
  CONSTRAINT `SUBMISSION_C_SUBMISSION_ID_FK` FOREIGN KEY (`SUBMISSION_ID`) REFERENCES `EVALUATION_SUBMISSION` (`ID`) ON DELETE CASCADE
)