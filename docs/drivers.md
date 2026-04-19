# Drivers

entkt uses a pluggable `Driver` interface to abstract over storage
backends. All generated code talks to the driver through `Map<String, Any?>`
rows -- the driver handles SQL (or whatever storage you use), and the
generated entity classes provide the typed facade.

## The Driver Interface

```kotlin
interface Driver {
    fun register(schema: EntitySchema)
    fun insert(table: String, values: Map<String, Any?>): Map<String, Any?>
    fun update(table: String, id: Any, values: Map<String, Any?>): Map<String, Any?>?
    fun upsert(
        table: String,
        values: Map<String, Any?>,
        conflictColumns: List<String>,
        immutableColumns: List<String> = emptyList(),
    ): UpsertResult
    fun byId(table: String, id: Any): Map<String, Any?>?
    fun query(
        table: String,
        predicates: List<Predicate>,
        orderBy: List<OrderField>,
        limit: Int?,
        offset: Int?,
    ): List<Map<String, Any?>>
    fun delete(table: String, id: Any): Boolean
    fun insertMany(table: String, values: List<Map<String, Any?>>): List<Map<String, Any?>>
    fun updateMany(table: String, values: Map<String, Any?>, predicates: List<Predicate>): Int
    fun deleteMany(table: String, predicates: List<Predicate>): Int
    fun <T> withTransaction(block: (Driver) -> T): T
}
```

- `register()` is called once per entity schema, typically during repo
  construction. It should be idempotent.
- `insert()` returns the persisted row including any server-assigned values
  (auto-increment IDs, defaults).
- `update()` returns the updated row, or `null` if the row was not found.
- `upsert()` inserts or updates based on a unique constraint. Returns an
  `UpsertResult(row, inserted)` so callers know which path was taken. Columns
  in `immutableColumns` are included in the insert but excluded from the
  update-on-conflict set. Requires at least one conflict column.
- `insertMany()` batch-inserts multiple rows, returning all persisted rows
  with assigned IDs. PostgresDriver uses multi-row `INSERT ... VALUES`.
- `updateMany()` updates all rows matching the predicates with the same
  values. Returns the count of updated rows.
- `deleteMany()` deletes all rows matching the predicates. Returns the
  count of deleted rows.

These three bulk methods are low-level driver operations that do **not**
fire lifecycle hooks. The generated repo methods (`createMany`,
`deleteMany`) wrap them with hook support — see [Hooks](hooks.md).
- `withTransaction()` runs a block in a transaction. The block receives a
  transaction-scoped driver. If it completes normally, the transaction
  commits. If it throws, the transaction rolls back.

## InMemoryDriver

A thread-safe, in-process driver for testing and demos. No external
dependencies.

```kotlin
val client = EntClient(InMemoryDriver())
```

Features:

- `ConcurrentHashMap`-based storage with `AtomicLong` ID counters
- Full predicate support including edge traversal and M2M junction tables
- Snapshot-based transactions with rollback on exception
- Nested `withTransaction` calls reuse the existing transaction

Use `InMemoryDriver` for unit tests and demos. It evaluates predicates
in-process, so behavior matches SQL drivers for all supported predicate
types.

## PostgresDriver

JDBC-backed driver for PostgreSQL. Requires a `javax.sql.DataSource`.

```kotlin
val dataSource = PGSimpleDataSource().apply {
    setURL("jdbc:postgresql://localhost:5432/mydb")
    user = "myuser"
    password = "mypassword"
}

val client = EntClient(PostgresDriver(dataSource))
```

### DDL

`register()` issues `CREATE TABLE IF NOT EXISTS` with:

- Column definitions with appropriate Postgres types
- `PRIMARY KEY` on the ID column
- `NOT NULL` constraints on required columns
- `UNIQUE` constraints on unique columns
- `REFERENCES` for foreign key columns
- `CREATE INDEX` / `CREATE UNIQUE INDEX` for composite indexes

### Type Mapping

| FieldType | Postgres Type |
|-----------|--------------|
| `STRING`, `TEXT`, `ENUM` | `text` |
| `BOOL` | `boolean` |
| `INT` | `integer` |
| `LONG` | `bigint` |
| `FLOAT` | `real` |
| `DOUBLE` | `double precision` |
| `TIME` | `timestamptz` |
| `UUID` | `uuid` |
| `BYTES` | `bytea` |

ID strategies `AUTO_INT` and `AUTO_LONG` map to `serial` and `bigserial`
respectively.

### Query Lowering

The Postgres driver compiles the `Predicate` tree to parameterized SQL:

- `AND` / `OR` nest naturally
- Values are bound via `PreparedStatement` parameters (never string-concatenated)
- `IN` / `NOT_IN` expand to placeholder lists. Empty `IN` short-circuits to
  `FALSE`, empty `NOT_IN` to `TRUE`
- String ops (`CONTAINS`, `HAS_PREFIX`, `HAS_SUFFIX`) use `LIKE` with safely
  built patterns
- Edge predicates become `EXISTS (SELECT 1 FROM ...)` subqueries
- M2M edges include junction table joins in the subquery
- All identifiers are double-quoted

### Identifier Handling

Postgres truncates identifiers to 63 bytes (NAMEDATALEN - 1). entkt
normalizes generated index and constraint names to this limit, using a
hash suffix for disambiguation when truncation is needed:

```
idx_very_long_table_name_with_many_columns_col1_col2_col3_unique
  → idx_very_long_table_name_with_many_columns_col1_c_a1b2c3d4
```

This ensures that names generated by entkt match what Postgres actually
stores.

### Transactions

```kotlin
client.withTransaction { tx ->
    tx.users.create { name = "Alice"; email = "a@b.com" }.save()
    tx.posts.create { title = "Hello"; authorId = alice.id }.save()
    // Commits if block completes; rolls back on exception
}
```

Nested `withTransaction` calls reuse the existing transaction (no
savepoints).

### Testing with Testcontainers

The Postgres driver tests use Testcontainers to spin up a real
`postgres:16-alpine` instance:

```kotlin
@Testcontainers
class MyTest {
    companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:16-alpine")
    }

    private val dataSource by lazy {
        PGSimpleDataSource().apply {
            setURL(postgres.jdbcUrl)
            user = postgres.username
            password = postgres.password
        }
    }
}
```

Requires Docker to be running.

## Writing a Custom Driver

To support a new database, implement the `Driver` interface. The key
contract:

1. `register()` must be idempotent -- called on every repo construction
2. `insert()` must return the full row including server-assigned values
3. `upsert()` must return `UpsertResult(row, inserted)` with the correct
   `inserted` flag, exclude `immutableColumns` from the conflict-update set,
   and reject empty `conflictColumns`
4. `query()` must evaluate all `Predicate` types (including edge predicates)
5. `withTransaction()` must roll back on exception

For the migration system, you'll also need:

- A `TypeMapper` implementation (maps `FieldType` to your SQL types)
- A `DatabaseIntrospector` (for dev-mode introspection)
- A `MigrationSqlRenderer` (renders `MigrationOp` to your SQL dialect)
- A `MigrationExecutor` (executes SQL and tracks applied versions)
