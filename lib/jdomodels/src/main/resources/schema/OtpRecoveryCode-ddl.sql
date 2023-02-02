CREATE TABLE IF NOT EXISTS `OTP_RECOVERY_CODE` (
  `SECRET_ID` BIGINT NOT NULL,
  `CODE_HASH` char(73) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `CREATED_ON` TIMESTAMP(3) NOT NULL,
  PRIMARY KEY (`SECRET_ID`, `CODE_HASH`),
  CONSTRAINT `OTP_RECOVERY_CODE_SECRET_ID_FK` FOREIGN KEY (`SECRET_ID`) REFERENCES `OTP_SECRET` (`ID`) ON DELETE CASCADE
)