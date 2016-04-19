CREATE TABLE IF NOT EXISTS `AUTHENTICATION_RECEIPT` (
  `ID` bigint(20) NOT NULL,
  `USER_ID` bigint(20) NOT NULL,
  `RECEIPT` CHAR(36) NOT NULL,
  `EXPIRATION` bigint(20) NOT NULL,
  PRIMARY KEY (`ID`),
  UNIQUE (`RECEIPT`),
  INDEX `AUTHENTICATION_RECEIPT_USER_ID_INDEX` (`USER_ID`)
)
