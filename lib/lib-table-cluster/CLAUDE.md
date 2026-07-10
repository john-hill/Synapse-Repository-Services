# lib/lib-table-cluster

Operations against the **index database** — the derived/computed MySQL instance that backs table queries, materialized views, entity replication, and search index builds. This is the "index database" that other modules' CLAUDE.md files refer to; it starts empty on a new stack and is rebuilt from change messages.

## Core types

- `TableIndexDAO` / `TableIndexDAOImpl` — the DAO for building and querying index tables, replication data, and per-table status.
- `SQLUtils` (~2000 lines) — the SQL-string builder for index tables (CREATE/ALTER/INSERT/SELECT generation from column models). **Reuse it; do not hand-build index SQL** — it encodes column-type→SQL mapping, list-column `JSON_TABLE` unnesting, and naming rules you would otherwise get subtly wrong.
- `description/IndexDescription` — describes a queryable object (table, view, materialized view, search index) and its dependencies.

## IndexDescription: don't override the hash defaults

`IndexDescription` provides `default` methods that compute a table's identity hash by walking its dependency graph:

- `recursiveAppendIdAndChangeNumber(StringBuilder)` — appends this object's id + change number, then recurses into each dependency.
- `getTableHash()` — the cache/staleness key, built from that recursive walk.

Implementors supply the leaf data (id, change number, dependencies) but **must not override these default methods** — a view's hash must be derived identically to every other object's, or cache invalidation and rebuild detection break.

## Database split

This module targets the **index database** only. The main (transactional) DB is handled by `lib/jdomodels`. The two use separate `DataSource`/`JdbcTemplate` beans (see `lib/lib-database-configuration`).

## Anti-Patterns — Do NOT

- **Do NOT batch-delete replication rows without sorting the IDs first.** `TableIndexDAOImpl.deleteObjectData` copies the id list and calls `Collections.sort` before the batch delete specifically to impose a consistent lock-acquisition order and prevent deadlocks between concurrent deletes (evidence: `TableIndexDAOImpl.java:787`, comment "sort to prevent deadlock"). Any new batch mutation over object IDs must do the same.
