CREATE TABLE IF NOT EXISTS `ACCESS_APPROVAL` (
  `ID` bigint(20) NOT NULL,
  `ETAG` char(36) NOT NULL,
  `CREATED_BY` bigint(20) NOT NULL,
  `CREATED_ON` bigint(20) NOT NULL,
  `MODIFIED_BY` bigint(20) NOT NULL,
  `MODIFIED_ON` bigint(20) NOT NULL,
  `REQUIREMENT_ID` bigint(20) NOT NULL,
  `REQUIREMENT_VERSION` bigint(20) NOT NULL DEFAULT 0,
  `SUBMITTER_ID` bigint(20) NOT NULL,
  `ACCESSOR_ID` bigint(20) NOT NULL,
  `EXPIRED_ON` bigint(20) NOT NULL DEFAULT 0,
  `SERIALIZED_ENTITY` mediumblob,
  PRIMARY KEY (`REQUIREMENT_ID`, `ACCESSOR_ID`, `REQUIREMENT_VERSION`, `SUBMITTER_ID`),
  UNIQUE KEY (`ID`),
  CONSTRAINT `ACCESS_APPROVAL_REQUIREMENT_ID_FK` FOREIGN KEY (`REQUIREMENT_ID`) REFERENCES `ACCESS_REQUIREMENT` (`ID`) ON DELETE CASCADE,
  CONSTRAINT `ACCESS_APPROVAL_CREATED_BY_FK` FOREIGN KEY (`CREATED_BY`) REFERENCES `JDOUSERGROUP` (`ID`),
  CONSTRAINT `ACCESS_APPROVAL_MODIFIED_BY_FK` FOREIGN KEY (`MODIFIED_BY`) REFERENCES `JDOUSERGROUP` (`ID`),
  CONSTRAINT `ACCESS_APPROVAL_SUBMITTER_ID_FK` FOREIGN KEY (`SUBMITTER_ID`) REFERENCES `JDOUSERGROUP` (`ID`),
  CONSTRAINT `ACCESS_APPROVAL_ACCESSOR_ID_FK` FOREIGN KEY (`ACCESSOR_ID`) REFERENCES `JDOUSERGROUP` (`ID`) ON DELETE CASCADE
)
