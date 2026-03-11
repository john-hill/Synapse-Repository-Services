# Grid Snapshot Compaction — Review Fix Plan

Fixes for issues identified during code review of the grid snapshot compaction feature.

## Issues and Fixes

### 1. SQL query counts total patches, causing repeated compaction (Medium)

**Problem:** `ListSessionsNeedingCompaction.sql` counts *all* patches for a session via `COUNT(*)` on `GRID_PATCH`. The plan document acknowledges this as an intentional approximation, but the consequence is that once a session exceeds `maxPatchCount` (default 1000), it will be flagged for compaction on *every* worker run — even immediately after a snapshot was just created. This creates a loop of redundant snapshot exports.

**Fix:** Change the patch count subquery to only count patches created *after* the latest snapshot. This avoids the redundant compaction loop while keeping the query efficient:

**File:** `lib/jdomodels/src/main/resources/sql/grid/ListSessionsNeedingCompaction.sql`

```sql
SELECT gs.SESSION_ID
FROM GRID_SESSION gs
INNER JOIN GRID_CONNECTION gc
  ON gc.SESSION_ID = gs.SESSION_ID AND gc.SOURCE = 'INTERNAL'
LEFT JOIN (
  SELECT SESSION_ID, MAX(CREATED_ON) AS LATEST_SNAPSHOT_ON
  FROM GRID_SNAPSHOT
  GROUP BY SESSION_ID
) snap ON snap.SESSION_ID = gs.SESSION_ID
LEFT JOIN (
  SELECT gp.SESSION_ID, COUNT(*) AS PATCH_COUNT
  FROM GRID_PATCH gp
  LEFT JOIN (
    SELECT SESSION_ID, MAX(CREATED_ON) AS LATEST_SNAPSHOT_ON
    FROM GRID_SNAPSHOT
    GROUP BY SESSION_ID
  ) s ON s.SESSION_ID = gp.SESSION_ID
  WHERE s.LATEST_SNAPSHOT_ON IS NULL OR gp.CREATED_ON > s.LATEST_SNAPSHOT_ON
  GROUP BY gp.SESSION_ID
) pc ON pc.SESSION_ID = gs.SESSION_ID
WHERE
  (snap.LATEST_SNAPSHOT_ON IS NULL OR snap.LATEST_SNAPSHOT_ON < NOW() - INTERVAL ? SECOND)
  OR
  (pc.PATCH_COUNT > ?)
ORDER BY snap.LATEST_SNAPSHOT_ON ASC
LIMIT ?
```

**Also update:** The Javadoc on `GridDao.listSessionsNeedingCompaction` already says "patches since the latest snapshot", so it will match after this fix.

**Test updates:**
- `lib/jdomodels/src/test/java/org/sagebionetworks/repo/model/dbo/grid/GridDaoImplAutowiredTest.java` — add a test that creates a session with >maxPatchCount total patches, creates a snapshot, then verifies the session is *not* returned by `listSessionsNeedingCompaction`. Also add a test where patches exist after the snapshot and verify the session *is* returned once the post-snapshot count exceeds the threshold.

---

### 2. ~~`ProgressCallback` never invoked in compaction loop~~ (Non-issue)

**Original concern:** The review flagged that `ProgressCallback` is accepted but `callback.progressMade()` is never called, risking semaphore lock expiry during long compaction runs.

**Resolution:** This is **not an issue**. The `ProgressCallback` interface has no `progressMade()` method — it only defines `addProgressListener()`, `removeProgressListener()`, and `getLockTimeoutSeconds()`. The semaphore lock is refreshed automatically by the `AutoProgressingRunner` infrastructure: `SemaphoreGatedRunnerImpl` (line 57) wraps the worker in `AutoProgressingRunner`, which runs the worker on a separate thread and polls at `lockTimeoutSec / 3` frequency (100 seconds for the 300-second lock). On each poll timeout, it calls `SynchronizedProgressCallback.fireProgressMade()`, which triggers the registered `ProgressListener` that calls `semaphore.refreshLockTimeout()`. No fix needed.

---

### 3. Integration test doesn't verify compaction actually occurred (Medium)

**Problem:** `GridSnapshotCompactionWorkerIntegrationTest.testCompactionCreatesNewSnapshot()` has two issues:
1. The comment on line 90 claims "maxSnapshotAge=0 days" but there is no test override — the actual value from `stack.properties` is 30 days. A just-created session with a fresh snapshot and few patches won't meet either compaction criterion.
2. The assertions pass trivially: `assertTrue(latestSnapshot.isPresent())` is always true because the initial snapshot exists, and `getCreatedOn() >= initialSnapshot.getCreatedOn()` is always true for the same snapshot.

**Fix:** The test needs to either ensure compaction criteria are met, or assert that compaction actually produced a new snapshot. Two options:

**Option A (Preferred):** Assert `compacted > 0` and verify the snapshot changed.

**Option B:** Add test-only configuration that overrides the max age to 0.

Going with Option A — modify the test to assert meaningful results:

**File:** `services/workers/src/test/java/org/sagebionetworks/grid/workers/GridSnapshotCompactionWorkerIntegrationTest.java`

The fix depends on whether the session actually qualifies for compaction with default settings. Since the session was just created (snapshot is <30 days old) and has very few patches (<1000), compaction won't run with default settings. The test should either:

1. **Directly test the compaction path** by calling `compactSession()` on a session known to be eligible (e.g., by inserting enough patches to exceed the threshold, or by modifying the snapshot timestamp in the test DB), OR
2. **Override `StackConfiguration`** values for the test context to use `maxSnapshotAge=0`.

Recommended approach — update the test to explicitly verify the `compacted` count matches expectations:

```java
// With default settings (30 days, 1000 patches), a just-created session
// will NOT qualify for compaction
int compacted = compactionManager.compactSessions(callback);
assertEquals(0, compacted, "A freshly created session should not need compaction");

// The latest snapshot should be the same as the initial one (no new snapshot created)
Optional<GridSnapshot> latestSnapshot = gridDao.getLatestSnapshot(sessionId);
assertTrue(latestSnapshot.isPresent());
assertEquals(initialSnapshot.get().getSnapshotId(), latestSnapshot.get().getSnapshotId(),
    "No new snapshot should have been created");
```

Then add a **second test method** that forces compaction criteria to be met (e.g., by updating the snapshot's `CREATED_ON` timestamp to be >30 days ago via a test helper) and verifies:
- `compacted == 1`
- The latest snapshot has a *different* ID than the initial snapshot
- The latest snapshot's `createdOn` is after the initial snapshot's `createdOn`

**Fix the comment** on line 90 to remove the misleading "maxSnapshotAge=0 days" claim.

---

### 4. (Non-issue) `exportSnapshot` transaction consistency

The original review flagged that `exportSnapshot` might not run in a consistent transaction. After investigation, this is **not an issue**: `GridIndexManagerImpl` has a class-level `@GridTransaction(readOnly = true)` annotation with `Propagation.REQUIRED`, which means `exportSnapshot` inherits a single read-only transaction wrapping all its paginated reads. No fix needed.

---

### 5. (Minor) Possible duplicate session IDs from SQL query

**Problem:** If multiple `GRID_CONNECTION` rows exist with `SOURCE = 'INTERNAL'` for the same session, the `INNER JOIN` could produce duplicate session IDs. The `getSingletonConnection` call in `compactSession` handles this gracefully but wastes work.

**Fix:** Add `DISTINCT` to the SQL query:

**File:** `lib/jdomodels/src/main/resources/sql/grid/ListSessionsNeedingCompaction.sql`

```sql
SELECT DISTINCT gs.SESSION_ID
```

---

## Implementation Order

| Step | Severity | Description | Files |
|------|----------|-------------|-------|
| 1 | Medium | Fix SQL to count patches since last snapshot + add DISTINCT | `ListSessionsNeedingCompaction.sql`, `GridDaoImplAutowiredTest.java` |
| 2 | Medium | Fix integration test assertions and add meaningful test | `GridSnapshotCompactionWorkerIntegrationTest.java` |

## Verification

After implementing all fixes:

```bash
# DAO integration tests
mvn test -pl lib/jdomodels -Dtest=GridDaoImplAutowiredTest

# Worker integration test
mvn test -pl services/workers -Dtest=GridSnapshotCompactionWorkerIntegrationTest
```
