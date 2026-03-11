# lib/jdomodels

DAO implementations, DBO (Database Object) classes, and DDL for the main Synapse database. This is the largest library module — it contains the persistence layer for all core Synapse entities.

## Package Structure

```
org.sagebionetworks.repo.model
├── dbo/                        # Core DBO framework
│   ├── DatabaseObject<T>       # Base interface for all database objects
│   ├── MigratableDatabaseObject<D,B>  # Adds migration support
│   ├── DBOBasicDao             # Generic CRUD DAO (get, create, update, delete by PK)
│   ├── TableMapping<T>        # Maps DBO ↔ DB table (extends Spring RowMapper)
│   ├── FieldColumn            # Binds Java field name to DB column name
│   ├── persistence/           # Concrete DBO classes (e.g., DBOChange, DBONode)
│   ├── dao/                   # DAO implementations
│   ├── migration/             # Migration translation classes
│   └── ...                    # Feature-specific sub-packages
├── query/jdo/
│   └── SqlConstants           # Centralized table/column name constants + DDL paths
├── helper/                    # DAO helper classes
└── ...                        # Feature-specific packages
```

## DBO Pattern

Every database table is represented by a DBO class implementing `DatabaseObject<T>` or `MigratableDatabaseObject<D, B>`.

### Key Components

**`FieldColumn`** — maps a Java field to a database column:
```java
new FieldColumn("changeNumber", COL_CHANGES_CHANGE_NUM, true)  // isPrimaryKey
    .withIsBackupId(true)   // unique ID for migration backup
    .withIsEtag(true)       // etag column for change detection (primary tables only)
    .withIsSelfForeignKey(true)   // self-referencing FK (e.g., parentId)
    .withHasFileHandleRef(true)   // column stores file handle IDs
```

**`TableMapping<T>`** — returned by `getTableMapping()`, provides:
- `mapRow(ResultSet, int)` — Spring `RowMapper` implementation
- `getTableName()` — table name string
- `getDDLFileName()` — classpath path to DDL file (e.g., `"schema/Changes-ddl.sql"`)
- `getFieldColumns()` — array of `FieldColumn` definitions
- `getDBOClass()` — the DBO class

**`MigratableTableTranslation<D, B>`** — converts between DBO and backup object for migration. Use `BasicMigratableTableTranslation<D>` when the DBO and backup class are the same (most common case).

### Creating a New DBO

1. Define the DBO class implementing `MigratableDatabaseObject<D, B>`
2. Define `FieldColumn[]` matching the DDL columns exactly
3. Implement `getTableMapping()` with an anonymous `TableMapping` inner class
4. Implement `getMigratableTableType()` returning a `MigrationType` enum value
5. Add table/column constants to `SqlConstants`
6. Create DDL file in `src/main/resources/schema/`
7. Register in `src/main/resources/dbo-beans.spb.xml` (order matters — after dependencies)

### Primary vs Secondary Tables

- **Primary**: Has etag column (`withIsEtag(true)`), migrated independently, registered in `dbo-beans.spb.xml`
- **Secondary**: Owned by a primary table, discovered via `getSecondaryTypes()`, has FK to owner's backup ID, NOT registered in `dbo-beans.spb.xml`

## DAO Pattern

DAO interfaces live in `lib/models/` (`org.sagebionetworks.repo.model`). Implementations live here in `org.sagebionetworks.repo.model.dbo.dao`.

### Common Patterns

- **`DBOBasicDao`** — generic CRUD: `createNew()`, `createOrUpdate()`, `createBatch()`, `getObjectByPrimaryKey()`, `deleteObjectByPrimaryKey()`, `getCount()`
- **`JdbcTemplate` / `NamedParameterJdbcTemplate`** — for custom queries
- **`MapSqlParameterSource`** — for named parameters
- **`RowMapper<T>`** or `TableMapping<T>` — for result set mapping
- **No ORM** — all SQL is hand-written

### SQL Constants

All table names, column names, and DDL file paths are centralized in `SqlConstants`:
```java
public static final String TABLE_CHANGES = "CHANGES";
public static final String COL_CHANGES_CHANGE_NUM = "CHANGE_NUM";
public static final String DDL_CHANGES = "schema/Changes-ddl.sql";
```

Naming: `TABLE_` prefix for table names, `COL_` for columns, `DDL_` for DDL paths. All UPPER_SNAKE_CASE.

## DDL Files

- Location: `src/main/resources/schema/`
- Naming: `{EntityName}-ddl.sql` (e.g., `Changes-ddl.sql`, `Node-ddl.sql`)
- Loaded at runtime by `DDLUtilsImpl` using the classpath path from `TableMapping.getDDLFileName()`
- Must use `CREATE TABLE IF NOT EXISTS`

## Spring Configuration

- **`dbo-beans.spb.xml`** — registers primary migratable DBO types in migration order (dependencies first). This is the most important config file for migration.
- Additional `*-spb.xml` files for feature-specific beans

## Database Split

This module primarily targets the **main (transactional) database**. The index database is managed by `lib-table-cluster`. The two databases use separate `DataSource` / `JdbcTemplate` beans.

## Testing

- DAO unit tests mock `JdbcTemplate` / `NamedParameterJdbcTemplate`
- DAO integration tests use the real database (run via `integration-test` module)
- Migration test: `MigrationIntegrationAutowireTest` — extend when adding new migratable types
