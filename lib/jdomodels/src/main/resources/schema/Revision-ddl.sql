CREATE TABLE IF NOT EXISTS `JDOREVISION` (
  `OWNER_NODE_ID` BIGINT NOT NULL,
  `NUMBER` BIGINT NOT NULL,
  `ACTIVITY_ID` BIGINT DEFAULT NULL,
  `ENTITY_PROPERTY_ANNOTATIONS` mediumblob,
  `USER_ANNOTATIONS` JSON,
  `COMMENT` varchar(256) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL,
  `LABEL` varchar(256) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `MODIFIED_BY` BIGINT NOT NULL,
  `MODIFIED_ON` BIGINT NOT NULL,
  `FILE_HANDLE_ID` BIGINT DEFAULT NULL,
  `COLUMN_MODEL_IDS` mediumblob,
  `SCOPE_IDS` blob,
  `ITEMS` JSON DEFAULT NULL,
  `SEARCH_ENABLED` BOOLEAN DEFAULT NULL,
  `REFERENCE` mediumblob,
  PRIMARY KEY (`OWNER_NODE_ID`,`NUMBER`),
  UNIQUE KEY `UNIQUE_REVISION_LABEL` (`OWNER_NODE_ID`,`LABEL`),
  KEY `JDOREVISION_KEY_OWN` (`OWNER_NODE_ID`),
  CONSTRAINT `REVISION_ACTIVITY_ID_FK` FOREIGN KEY (`ACTIVITY_ID`) REFERENCES `ACTIVITY` (`ID`) ON DELETE RESTRICT,
  CONSTRAINT `REVISION_OWNER_FK` FOREIGN KEY (`OWNER_NODE_ID`) REFERENCES `JDONODE` (`ID`) ON DELETE CASCADE,
  CONSTRAINT `FILE_HANDLE_FK` FOREIGN KEY (`FILE_HANDLE_ID`) REFERENCES `FILES` (`ID`)  ON DELETE RESTRICT,
  CONSTRAINT `REVISION_MODIFIED_BY_FK` FOREIGN KEY (`MODIFIED_BY`) REFERENCES `JDOUSERGROUP` (`ID`) ON DELETE RESTRICT
)
