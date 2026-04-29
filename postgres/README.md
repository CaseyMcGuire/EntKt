# :postgres

JDBC driver for PostgreSQL with DDL emission, predicate-to-SQL lowering,
introspection, and migration rendering.

## DDL

By default, `register()` is metadata-only. When you explicitly opt in
with `PostgresDriver(dataSource, autoDdl = true)`, it issues
`CREATE TABLE IF NOT EXISTS` from `EntitySchema.columns`. Type mapping:
`STRING`/`TEXT`/`ENUM` -> `text`,
`BOOL` -> `boolean`, `INT` -> `integer`, `LONG` -> `bigint`, `FLOAT` -> `real`,
`DOUBLE` -> `double precision`, `TIME` -> `timestamptz`, `UUID` -> `uuid`,
`BYTES` -> `bytea`. Primary keys for `AUTO_INT`/`AUTO_LONG` become
`serial`/`bigserial`. Edge FK columns emit `REFERENCES target("id")
ON DELETE ...` constraints (CASCADE, SET_NULL, or RESTRICT — defaults
inferred from nullability). Unique fields and composite indexes emit `UNIQUE`
constraints and `CREATE INDEX` / `CREATE UNIQUE INDEX` statements.
Partial indexes append `WHERE predicate` when declared via `.where()`.

## Insert / update

`INSERT ... RETURNING *` and `UPDATE ... RETURNING *` with fully
parameterized bindings. Never rewrites the id through `update`.

## Query

Predicate tree lowered to SQL; `AND`/`OR` nest naturally,
leaves bind parameters through a type-aware `PreparedStatement.bind(...)`,
edges become `EXISTS (SELECT 1 FROM target ...)` subqueries walking
registered `EdgeMetadata` (including junction-table joins for M2M edges).
`IN`/`NOT_IN` expand to placeholder lists
(empty IN short-circuits to `FALSE`, empty NOT IN to `TRUE`). String ops
use `LIKE` with safely built patterns.

## Identifier quoting

All identifiers wrapped in `"..."`. Values are never string-concatenated
into SQL.

## Tests

`PostgresDriverTest` mirrors `InMemoryDriverTest` assertion for
assertion, running against `postgres:16-alpine` via Testcontainers 2.0.4.
Requires a running Docker daemon.
