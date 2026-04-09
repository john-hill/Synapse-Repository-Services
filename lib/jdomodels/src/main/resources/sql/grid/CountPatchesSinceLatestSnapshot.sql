-- The most recent snapshot's clock table for the given session
WITH LATEST_SNAP AS (
  SELECT s.CLOCK_TABLE
  FROM GRID_SNAPSHOT s
  INNER JOIN (
    SELECT MAX(CREATED_ON) AS MAX_CREATED_ON
    FROM GRID_SNAPSHOT
    WHERE SESSION_ID = ?
  ) latest ON s.CREATED_ON = latest.MAX_CREATED_ON
  WHERE s.SESSION_ID = ?
)
SELECT COUNT(*)
FROM GRID_PATCH gp
LEFT JOIN LATEST_SNAP ls ON TRUE
-- Parse the snapshot's clock table to compare each patch against the last-seen sequence number
LEFT JOIN JSON_TABLE(
  IFNULL(ls.CLOCK_TABLE, JSON_ARRAY()), '$[*]' COLUMNS (
    REPLICA_ID BIGINT PATH '$[0]',
    SEQUENCE_NUMBER BIGINT PATH '$[1]'
  )
) ct ON ct.REPLICA_ID = gp.PATCH_ID_REP
-- Include patches that are newer than the snapshot (or all patches if no snapshot exists)
WHERE gp.SESSION_ID = ?
  AND (
    ls.CLOCK_TABLE IS NULL
    OR ct.REPLICA_ID IS NULL
    OR gp.PATCH_ID_SEQ >= ct.SEQUENCE_NUMBER
  )
