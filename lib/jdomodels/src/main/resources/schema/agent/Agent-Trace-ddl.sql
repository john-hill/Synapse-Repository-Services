CREATE TABLE IF NOT EXISTS `AGENT_TRACE` (
  `JOB_ID` BIGINT NOT NULL,
  `TIME_STAMP` BIGINT NOT NULL,
  `MESSAGE` TEXT NOT NULL,
  PRIMARY KEY (`JOB_ID`, `TIME_STAMP` ),
  CONSTRAINT `AGENT_TRACE_JOB` FOREIGN KEY (`JOB_ID`) REFERENCES `ASYNCH_JOB_STATUS` (`JOB_ID`) ON DELETE CASCADE
)