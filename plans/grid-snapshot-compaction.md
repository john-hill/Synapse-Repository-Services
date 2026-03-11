# Grid Snapshot Compaction

## Overview

Create a periodic worker that scans for grid sessions needing snapshot compaction, exports the INTERNAL replica's current CRDT state from the index database into a CBOR snapshot file, uploads it to S3, and records it in the main database. New replicas connecting after the snapshot will receive it instead of replaying potentially thousands of patches.

## Problem Statement

After a grid session is created with an initial snapshot, all subsequent changes are stored as individual patches in S3 (with a 120-day lifecycle policy) and recorded in the `GRID_PATCH` table (with a 119-day soft expiry). There is currently no mechanism to create new snapshots after session initialization:

1. **Performance**: New replicas connecting to a long-lived session must replay all patches since the initial snapshot. This can be thousands of patches, causing slow initialization.
2. **Data loss risk**: After 119 days, patches expire. If no new snapshot has been created, new replicas would only see the initial snapshot state, losing all changes made after session creation.

## Architecture Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Snapshot export approach | **Read raw nodes from index DB, write via `IndexedModelEncoder`** | Preserves CRDT identity, requires no type translation, symmetric inverse of `applySnapshot()`, stays cleanly within `lib-grid-db` |
| Worker type | **Timer-based `ProgressingRunner`** | Simple, predictable, follows existing patterns (`TrashWorker`, `MultipartCleanupWorker`). Explicit control over frequency. |
| Trigger criteria | **Combination: time + count** | Snapshot if latest snapshot >30 days old OR >1000 patches since last snapshot. Both configurable via `StackConfiguration`. |
| Cleanup scope | **Snapshot compaction only** | No cleanup of old patches/snapshots. Cleanup is a separate concern. |
| EventSource for reading | **Use INTERNAL** | The INTERNAL replica's index DB is the authoritative read view. Reading from it to create a snapshot doesn't violate the "no writing patches" constraint. No new EventSource needed. |
| Replica sync check | **Verify fully synchronized** | Skip sessions where the INTERNAL replica has outstanding patches. They'll be picked up next run. |
| Session scanning | **Paginated DB-side filtering** | A single query finds sessions needing compaction, limited to a small batch per run. |

## Risks and Mitigations

| Risk | Impact | Mitigation |
|------|--------|------------|
| **Snapshot export during active editing** -- Users may be sending patches while the snapshot is being created | Low. The snapshot captures whatever state the INTERNAL replica has applied at the time. The clock table accurately reflects what's captured. Subsequent patches are delivered normally. | Verify the INTERNAL replica is fully caught up before starting. The snapshot's clock table ensures correctness. |
| **Large grids causing memory pressure** -- Grids can have up to 10M cells. Loading all nodes at once could OOM | Medium. Each constant/object/vector/value node is a small object, but millions of them add up. | Stream nodes in paginated batches (e.g., 1000 at a time) through the `IndexedModelEncoder`. Write to a temp file, not memory. |
| **Index DB read load** -- Bulk reads during snapshot export could impact real-time operations | Low. The index DB is a separate database from the main transactional DB. The timer worker runs with `semaphoreMaxLockCount=1` (singleton), so only one compaction runs at a time across the cluster. | Use a singleton semaphore lock. Process one session at a time. Add progress callbacks to avoid holding the semaphore too long. |
| **INTERNAL replica not connected** -- Some sessions (e.g., from `EmptyCreateGridHandler`) may not have an INTERNAL replica/connection | Low. The worker should skip these sessions gracefully. | Check for INTERNAL connection existence before attempting export. |
| **Concurrent snapshot creation** -- Two worker instances could attempt to create snapshots for the same session | Low. `SemaphoreGatedWorkerStack` with `maxLockCount=1` prevents concurrent runs. | Singleton semaphore lock. |
| **Root value node (0,0)** -- Must be excluded from the snapshot export since `applySnapshot` re-creates it | Medium. Including it would cause duplicate nodes on import. | Filter out the `(0,0)` ValueNode during export. |
| **Tombstoned RGA elements** -- Should be included in the snapshot to preserve full CRDT merge semantics | Low. This is consistent with how `applySnapshot` imports arrays. | Include tombstones (use `includeTombstones=true` when reading arrays). |

## Implementation Steps (Test-First)

### Phase 1: Snapshot Export from Index DB (`lib-grid-db`)

This is the core new capability: reading all CRDT nodes from the index database and writing them to a CBOR snapshot file.

#### 1.1 Add bulk-read DAO methods to `GridIndexDao` / `GridIndexDaoImpl`

**Tests first:** `GridIndexDaoImplTest` (unit) and `GridIndexDaoImplAutowiredTest` (integration)

Add the following methods to `GridIndexDao`:

```java
// Stream all constant nodes for a session/replica (paginated)
List<ConstantNode> streamConstants(String sessionId, Long replicaId, long limit, long offset);

// Stream all object nodes for a session/replica (paginated)
List<ObjectNode> streamObjects(String sessionId, Long replicaId, long limit, long offset);

// Stream all value nodes for a session/replica, EXCLUDING the root (0,0) node (paginated)
List<ValueNode> streamValues(String sessionId, Long replicaId, long limit, long offset);

// Stream all vector nodes for a session/replica (paginated)
List<VectorNode> streamVectors(String sessionId, Long replicaId, long limit, long offset);

// Get all array IDs for a session/replica (from GRID_REPLICA_INDEX WHERE KIND = 'arr')
List<LogicalTimestamp> getAllArrayIds(String sessionId, Long replicaId);
```

**Files to modify:**
- `lib/lib-grid-db/src/main/java/org/sagebionetworks/grid/db/GridIndexDao.java` -- add interface methods
- `lib/lib-grid-db/src/main/java/org/sagebionetworks/grid/db/GridIndexDaoImpl.java` -- add implementations
- `lib/lib-grid-db/src/test/java/org/sagebionetworks/grid/db/GridIndexDaoImplTest.java` -- unit tests
- `lib/lib-grid-db/src/test/java/org/sagebionetworks/grid/db/GridIndexDaoImplAutowiredTest.java` -- integration tests

**SQL patterns:**
```sql
-- Constants (paginated)
SELECT CON_REP, CON_SEQ, CON_VAL FROM GRID_REPLICA_CON
WHERE SESSION_ID = ? AND REPLICA_ID = ?
ORDER BY CON_SEQ, CON_REP LIMIT ? OFFSET ?

-- Objects (paginated)
SELECT OBJ_REP, OBJ_SEQ, OBJ_VAL FROM GRID_REPLICA_OBJ
WHERE SESSION_ID = ? AND REPLICA_ID = ?
ORDER BY OBJ_SEQ, OBJ_REP LIMIT ? OFFSET ?

-- Values (excluding root 0,0; paginated)
SELECT VAL_REP, VAL_SEQ, VAL_REF FROM GRID_REPLICA_VAL
WHERE SESSION_ID = ? AND REPLICA_ID = ? AND NOT (VAL_REP = 0 AND VAL_SEQ = 0)
ORDER BY VAL_SEQ, VAL_REP LIMIT ? OFFSET ?

-- Vectors (paginated)
SELECT VEC_REP, VEC_SEQ, VEC_VAL FROM GRID_REPLICA_VEC
WHERE SESSION_ID = ? AND REPLICA_ID = ?
ORDER BY VEC_SEQ, VEC_REP LIMIT ? OFFSET ?

-- Array IDs (from index)
SELECT NODE_REP, NODE_SEQ FROM GRID_REPLICA_INDEX
WHERE SESSION_ID = ? AND REPLICA_ID = ? AND KIND = 'arr'
```

The existing `getArrayNode(sessionId, replicaId, arrayId, includeTombstones, limit, offset)` method already supports reading full arrays with tombstones.

#### 1.2 Add `exportSnapshot()` to `GridIndexManager` / `GridIndexManagerImpl`

**Tests first:** `GridIndexManagerImplTest` (unit) and `GridIndexManagerImplAutowiredTest` (integration -- write a round-trip test: `applySnapshot -> exportSnapshot -> verify files are equivalent`)

Add to `GridIndexManager`:
```java
/**
 * Export the current state of a replica as a CBOR snapshot file.
 * @param sessionId The grid session ID
 * @param replicaId The replica ID to export
 * @param snapshotFile The file to write the snapshot to
 * @return The ClockTable representing the exported state
 */
ClockTable exportSnapshot(String sessionId, Long replicaId, Path snapshotFile);
```

**Implementation in `GridIndexManagerImpl`:**

1. Get root object: `dao.getRootObject(sessionId, replicaId)` -- extract the root `ObjectNode`'s ID
2. Create `IndexedModelEncoder(outputStream, rootNodeId)`
3. Stream and write all constants (paginated, e.g., 1000 per batch)
4. Stream and write all objects (paginated)
5. Stream and write all values, excluding `(0,0)` (paginated)
6. Stream and write all vectors (paginated)
7. Get all array IDs, then for each array, read via `dao.getArrayNode(includeTombstones=true)` and write
8. Close encoder (writes clock table and root reference)
9. Return `encoder.getClockTable()`

**Files to modify:**
- `lib/lib-grid-db/src/main/java/org/sagebionetworks/grid/db/GridIndexManager.java` -- add interface method
- `lib/lib-grid-db/src/main/java/org/sagebionetworks/grid/db/GridIndexManagerImpl.java` -- add implementation
- `lib/lib-grid-db/src/test/java/org/sagebionetworks/grid/db/GridIndexManagerImplTest.java` -- unit test with mocked DAO
- `lib/lib-grid-db/src/test/java/org/sagebionetworks/grid/db/GridIndexManagerImplAutowiredTest.java` -- **round-trip integration test**

**Key round-trip test design:**
```java
// 1. Apply a known snapshot to replica A
gridIndexManager.applySnapshot(sessionId, replicaA, originalSnapshotPath);
// 2. Export replica A's state to a new file
ClockTable clock = gridIndexManager.exportSnapshot(sessionId, replicaA, exportedSnapshotPath);
// 3. Apply the exported snapshot to replica B
gridIndexManager.applySnapshot(sessionId, replicaB, exportedSnapshotPath);
// 4. Compare replica A and replica B -- all nodes, clocks should be identical
```

### Phase 2: Compaction Query in `GridDao` (`lib/jdomodels`)

#### 2.1 Add `listSessionsNeedingCompaction()` to `GridDao` / `GridDaoImpl`

**Tests first:** `GridDaoImplTest` (unit) and `GridDaoImplAutowiredTest` (integration)

Add to `GridDao`:
```java
/**
 * Find grid sessions that need snapshot compaction.
 * A session needs compaction if:
 *   - Its latest snapshot is older than maxSnapshotAge, OR
 *   - It has more than maxPatchCount patches since the latest snapshot's clock
 * Only sessions with an INTERNAL connection are returned.
 * @param maxSnapshotAge Maximum age of the latest snapshot
 * @param maxPatchCount Maximum number of patches since the latest snapshot
 * @param limit Maximum number of sessions to return
 * @return List of session IDs needing compaction
 */
List<String> listSessionsNeedingCompaction(Duration maxSnapshotAge, int maxPatchCount, int limit);
```

**SQL design (single efficient query):**

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
  SELECT SESSION_ID, COUNT(*) AS PATCH_COUNT
  FROM GRID_PATCH
  GROUP BY SESSION_ID
) pc ON pc.SESSION_ID = gs.SESSION_ID
WHERE
  -- No snapshot at all, OR snapshot is older than threshold
  (snap.LATEST_SNAPSHOT_ON IS NULL OR snap.LATEST_SNAPSHOT_ON < NOW() - INTERVAL ? SECOND)
  OR
  -- Patch count exceeds threshold
  (pc.PATCH_COUNT > ?)
ORDER BY snap.LATEST_SNAPSHOT_ON ASC NULLS FIRST
LIMIT ?
```

> **Note:** The query uses total patch count as a rough proxy for "patches since last snapshot." An exact count would require joining the snapshot's clock table against patches, which is complex. For a background compaction worker, this approximation is sufficient -- sessions with many total patches are good compaction candidates regardless. The exact deduplication happens implicitly during the snapshot export (since the export captures the full state and the clock table in the new snapshot covers all existing patches).

**Files to modify:**
- `lib/jdomodels/src/main/java/org/sagebionetworks/repo/model/dbo/grid/GridDao.java` -- add method
- `lib/jdomodels/src/main/java/org/sagebionetworks/repo/model/dbo/grid/GridDaoImpl.java` -- add implementation
- `lib/jdomodels/src/main/resources/sql/grid/ListSessionsNeedingCompaction.sql` -- the SQL file
- `lib/jdomodels/src/test/java/org/sagebionetworks/repo/model/dbo/grid/GridDaoImplTest.java` -- unit tests
- `lib/jdomodels/src/test/java/org/sagebionetworks/repo/model/dbo/grid/GridDaoImplAutowiredTest.java` -- integration tests

### Phase 3: Compaction Manager (`services/repository-managers`)

#### 3.1 Create `GridSnapshotCompactionManager` interface and implementation

**Tests first:** `GridSnapshotCompactionManagerImplTest` (unit with mocks)

**New files:**
- `services/repository-managers/src/main/java/org/sagebionetworks/repo/manager/grid/GridSnapshotCompactionManager.java`
- `services/repository-managers/src/main/java/org/sagebionetworks/repo/manager/grid/GridSnapshotCompactionManagerImpl.java`
- `services/repository-managers/src/test/java/org/sagebionetworks/repo/manager/grid/GridSnapshotCompactionManagerImplTest.java`

**Interface:**
```java
public interface GridSnapshotCompactionManager {
    /**
     * Scan for sessions needing compaction and create snapshots.
     * @return The number of sessions compacted in this run.
     */
    int compactSessions(ProgressCallback callback);
}
```

**Implementation logic (`@Service` annotated, constructor injection):**

1. Read configurable thresholds from `StackConfiguration`:
   - `getGridSnapshotMaxAgeDays()` -- default 30
   - `getGridSnapshotMaxPatchCount()` -- default 1000
   - `getGridSnapshotCompactionBatchSize()` -- default 10

2. Call `gridDao.listSessionsNeedingCompaction(maxAge, maxPatchCount, batchSize)`

3. For each session:
   a. Get the INTERNAL connection: `gridDao.getSingletonConnection(sessionId, EventSource.INTERNAL)`
      - Skip if no INTERNAL connection (session is empty or not fully initialized)
   b. Check synchronization: `patchBuilderManager.getCurrentClockIfAllPatchesApplied(sessionId, replicaId)`
      - Skip if not fully synchronized (will be picked up next run)
   c. Create a temp file for the snapshot
   d. Call `gridIndexManager.exportSnapshot(sessionId, replicaId, tempFile)` -- returns `ClockTable`
   e. Call `snapshotStore.saveSnapshot(sessionId, clockTable, createdByUserId, tempFile)` -- uploads to S3 and records in DB (reusing the existing `SnapshotStore` / `GridManagerImpl.saveSnapshot()`)
   f. Delete the temp file
   g. Report progress via `callback.progressMade()` (refreshes semaphore lock)

4. Return the count of sessions compacted

**Dependencies:**
- `GridDao` (main DB queries)
- `GridIndexManager` (index DB export via `GridIndexDao`)
- `GridReplicaPatchBuilderManager` (sync check via `getCurrentClockIfAllPatchesApplied`)
- `SnapshotStore` (S3 upload + DB save -- implemented by `GridManagerImpl`)
- `StackConfiguration` (configurable thresholds)

### Phase 4: Compaction Worker (`services/workers`)

#### 4.1 Create `GridSnapshotCompactionWorker`

**Tests first:** `GridSnapshotCompactionWorkerTest` (unit)

**New file:** `services/workers/src/main/java/org/sagebionetworks/grid/workers/GridSnapshotCompactionWorker.java`

```java
@Component
public class GridSnapshotCompactionWorker implements ProgressingRunner {
    
    private final GridSnapshotCompactionManager compactionManager;
    
    public GridSnapshotCompactionWorker(GridSnapshotCompactionManager compactionManager) {
        this.compactionManager = compactionManager;
    }
    
    @Override
    public void run(ProgressCallback callback) throws Exception {
        compactionManager.compactSessions(callback);
    }
}
```

#### 4.2 Register in `TimerWorkersConfig`

Add a `@Bean` method to `services/workers/src/main/java/org/sagebionetworks/worker/config/TimerWorkersConfig.java`:

```java
@Bean
public SimpleTriggerFactoryBean gridSnapshotCompactionWorkerTrigger(
        GridSnapshotCompactionWorker worker) {
    SemaphoreGatedWorkerStackConfiguration config = new SemaphoreGatedWorkerStackConfiguration();
    config.setSemaphoreLockKey("gridSnapshotCompactionWorker");
    config.setProgressingRunner(worker);
    config.setSemaphoreMaxLockCount(1);  // singleton across cluster
    config.setSemaphoreLockTimeoutSec(300);  // 5-minute lock timeout
    config.setGate(stackStatusGate);  // only run when stack is read-write
    
    return new WorkerTriggerBuilder()
            .withStack(new SemaphoreGatedWorkerStack(countingSemaphore, config))
            .withRepeatInterval(30 * 60 * 1000)  // every 30 minutes
            .withStartDelay(5 * 60 * 1000)  // 5-minute delay on startup
            .build();
}
```

#### 4.3 Register trigger in main scheduler

Add the trigger bean reference to `services/workers/src/main/resources/main-scheduler-spb.xml` in the `workerTriggersList`.

**Files to modify/create:**
- `services/workers/src/main/java/org/sagebionetworks/grid/workers/GridSnapshotCompactionWorker.java` -- new worker
- `services/workers/src/test/java/org/sagebionetworks/grid/workers/GridSnapshotCompactionWorkerTest.java` -- unit test
- `services/workers/src/main/java/org/sagebionetworks/worker/config/TimerWorkersConfig.java` -- add bean
- `services/workers/src/main/resources/main-scheduler-spb.xml` -- register trigger

### Phase 5: Configuration (`lib/stackConfiguration`)

#### 5.1 Add configuration properties

**Files to modify:**
- `lib/stackConfiguration/src/main/java/org/sagebionetworks/StackConfiguration.java` -- add getter methods
- `lib/stackConfiguration/src/main/resources/stack.properties` (or equivalent) -- add defaults

```java
// In StackConfiguration:

/** Maximum age (in days) before a grid session needs a new snapshot. Default: 30 */
public int getGridSnapshotMaxAgeDays();

/** Maximum patch count before a grid session needs a new snapshot. Default: 1000 */
public int getGridSnapshotMaxPatchCount();

/** Maximum sessions to compact per worker run. Default: 10 */
public int getGridSnapshotCompactionBatchSize();
```

### Phase 6: Integration Test

#### 6.1 End-to-end integration test

**New file:** `services/workers/src/test/java/org/sagebionetworks/grid/workers/GridSnapshotCompactionWorkerIntegrationTest.java`

**Test scenario:**

1. Create a grid session from a query (generates initial snapshot + INTERNAL replica)
2. Wait for the INTERNAL replica to be fully synchronized
3. Send multiple patches through a WebSocket replica (simulating user edits)
4. Wait for patches to be applied to the INTERNAL replica
5. Run the compaction manager's `compactSessions()` method
6. Verify a new snapshot was created in the `GRID_SNAPSHOT` table with a timestamp after the patches
7. Create a **new** replica with an empty clock
8. Verify the new replica receives the **new** snapshot (not the original one)
9. Verify the new replica's state after applying the snapshot matches the expected state (original data + user edits)

This test validates the complete round-trip: patches -> compaction -> new snapshot -> replica initialization from new snapshot.

## Implementation Order

| Step | Phase | Description | Test File(s) |
|------|-------|-------------|--------------|
| 1 | 1.1 | Bulk-read DAO methods | `GridIndexDaoImplTest`, `GridIndexDaoImplAutowiredTest` |
| 2 | 1.2 | `exportSnapshot()` in `GridIndexManager` | `GridIndexManagerImplTest`, `GridIndexManagerImplAutowiredTest` |
| 3 | 2.1 | `listSessionsNeedingCompaction()` in `GridDao` | `GridDaoImplTest`, `GridDaoImplAutowiredTest` |
| 4 | 5.1 | `StackConfiguration` properties | N/A (simple getters) |
| 5 | 3.1 | `GridSnapshotCompactionManager` | `GridSnapshotCompactionManagerImplTest` |
| 6 | 4.1-4.3 | Worker + registration | `GridSnapshotCompactionWorkerTest` |
| 7 | 6.1 | Integration test | `GridSnapshotCompactionWorkerIntegrationTest` |

Each step writes tests **before** the implementation, ensuring a tight feedback loop throughout.

## Key Files Reference

| File | Role |
|------|------|
| `lib/lib-grid-db/src/main/java/org/sagebionetworks/grid/db/GridIndexDao.java` | Index DB DAO interface (add bulk-read methods) |
| `lib/lib-grid-db/src/main/java/org/sagebionetworks/grid/db/GridIndexDaoImpl.java` | Index DB DAO implementation |
| `lib/lib-grid-db/src/main/java/org/sagebionetworks/grid/db/GridIndexManager.java` | Index DB manager interface (add `exportSnapshot`) |
| `lib/lib-grid-db/src/main/java/org/sagebionetworks/grid/db/GridIndexManagerImpl.java` | Index DB manager implementation (has `applySnapshot` for reference) |
| `lib/lib-grid/src/main/java/org/sagebionetworks/repo/model/grid/encoding/IndexedModelEncoder.java` | CBOR encoder (reuse for export) |
| `lib/jdomodels/src/main/java/org/sagebionetworks/repo/model/dbo/grid/GridDao.java` | Main DB DAO (add compaction query) |
| `lib/jdomodels/src/main/java/org/sagebionetworks/repo/model/dbo/grid/GridDaoImpl.java` | Main DB DAO implementation |
| `services/repository-managers/src/main/java/org/sagebionetworks/repo/manager/grid/GridManagerImpl.java` | Implements `SnapshotStore.saveSnapshot()` (reuse for S3 upload) |
| `services/repository-managers/src/main/java/org/sagebionetworks/repo/manager/grid/SnapshotStore.java` | Interface for saving snapshots to S3 + DB |
| `services/repository-managers/src/main/java/org/sagebionetworks/repo/manager/grid/internal/replica/change/GridReplicaPatchBuilderManagerImpl.java` | Has `getCurrentClockIfAllPatchesApplied()` for sync check |
| `services/workers/src/main/java/org/sagebionetworks/worker/config/TimerWorkersConfig.java` | Timer worker registration (add compaction worker bean) |
| `services/workers/src/main/resources/main-scheduler-spb.xml` | Scheduler trigger list (register compaction trigger) |
| `lib/stackConfiguration/src/main/java/org/sagebionetworks/StackConfiguration.java` | Configuration (add threshold properties) |
