CREATE TABLE IF NOT EXISTS `PRINCIPAL_PREFIX` (
  `TOKEN` varchar(256) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `PRINCIPAL_ID` bigint NOT NULL,
  PRIMARY KEY (`TOKEN`,`PRINCIPAL_ID`),
  KEY `PREFIX_USR_ID_FK` (`PRINCIPAL_ID`),
  KEY `PREFIX_TOKEN_INDEX` (`TOKEN`),
  CONSTRAINT `PREFIX_USR_ID_FK` FOREIGN KEY (`PRINCIPAL_ID`) REFERENCES `USER_GROUP` (`ID`) ON DELETE CASCADE
)