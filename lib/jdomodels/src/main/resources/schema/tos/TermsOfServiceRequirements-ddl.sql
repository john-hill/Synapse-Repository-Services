CREATE TABLE IF NOT EXISTS `TERMS_OF_SERVICE_REQUIREMENT` (
  `ID` BIGINT NOT NULL,
  `CREATED_BY` BIGINT NOT NULL,
  `CREATED_ON` TIMESTAMP(3) NOT NULL,
  `MIN_VERSION` VARCHAR(30) NOT NULL,
  `ENFORCED_ON` TIMESTAMP(3) NOT NULL,
  PRIMARY KEY (`ID`)
)