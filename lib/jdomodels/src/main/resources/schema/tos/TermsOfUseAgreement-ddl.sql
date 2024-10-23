CREATE TABLE IF NOT EXISTS `TERMS_OF_USE_AGREEMENT` (
  `PRINCIPAL_ID` bigint NOT NULL,
  `AGREES_TO_TERMS_OF_USE` bit(1) DEFAULT NULL,
  PRIMARY KEY (`PRINCIPAL_ID`),
  CONSTRAINT `TERMS_O_U_A_PRINCIPAL_ID_FK` FOREIGN KEY (`PRINCIPAL_ID`) REFERENCES `USER_GROUP` (`ID`) ON DELETE CASCADE
)