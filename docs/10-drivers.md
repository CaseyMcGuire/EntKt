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
    fun byId(table: String, id: Any): Map<String, Any?>?
    fun delete(table: String, id: Any): Boolean

    fun query(
        table: String,
        predicates: List<Predicate>,
        orderBy: List<OrderField>,
        limit: Int?,
        offset: Int?,
    ): List<Map<String, Any?>>
    fun count(table: String, predicates: List<Predicate>): Long
    fun exists(table: String, predicates: List<Predicate>): Boolean

    fun insertMany(table: String, values: List<Map<String, Any?>>): List<Map<String, Any?>>
    fun updateMany(table: String, values: Map<String, Any?>, predicates: List<Predicate>): Int
    fun deleteMany(table: String, predicates: List<Predicate>): Int

    fun explainQuery(
        table: String,
        predicates: List<Predicate>,
        orderBy: List<OrderField>,
        limit: Int?,
        offset: Int?,
    ): QueryExplanation
    fun explainCount(table: String, predicates: List<Predicate>): QueryExplanation

    fun <T> withTransaction(block: (Driver) -> T): T
    val inTransaction: Boolean

    // Owner-row locking capabilities (RFC #4).
    val supportsReadRowForUpdate: Boolean
    fun readRowForUpdate(table: String, id: Any): Map<String, Any?>?

    val supportsOwnerEdgeSerialization: Boolean
    fun serializeOwnerEdgeAndRead(table: String, id: Any): Map<String, Any?>?

    fun requireTransactionForLocking(method: String)
}
```

- `register()` is called once per entity schema, typically during repo
  construction. It should be idempotent.
- `insert()` returns the persisted row including any server-assigned values
  (auto-increment IDs, defaults).
- `update()` returns the updated row, or `null` if the row was not found.
- `count()` / `exists()` evaluate the same predicate tree as `query()`;
  drivers may short-circuit `exists()` (Postgres uses `SELECT EXISTS(...)`
  / `LIMIT 1`).
- `insertMany()` batch-inserts multiple rows, returning all persisted rows
  with assigned IDs. PostgresDriver uses multi-row `INSERT ... VALUES`.
- `updateMany()` updates all rows matching the predicates with the same
  values. Returns the count of updated rows.
- `deleteMany()` deletes all rows matching the predicates. Returns the
  count of deleted rows.

These three bulk methods are low-level driver operations that do **not**
fire lifecycle hooks. The generated repo methods (`createMany`,
`deleteMany`) wrap them with hook support — see [Hooks](05-hooks.md).
- `explainQuery()` / `explainCount()` return a `QueryExplanation` for the
  SELECT / COUNT the driver *would* run, without executing it. Defaults
  to `UnsupportedQueryExplanation`; PostgresDriver returns SQL + bind
  args. Used by the generated query builder's `explainRaw()` /
  `explainRawCount()` and per-terminal `explain*()` methods.
- `withTransaction()` runs a block in a transaction. The block receives a
  transaction-scoped driver. If it completes normally, the transaction
  commits. If it throws, the transaction rolls back.
- `inTransaction` is `false` on the root client driver and `true` on the
  driver passed inside `withTransaction { tx -> ... }`. Generated
  `save()` paths use this to enforce
  `TransactionRequirement` at save-start (RFC #4).

### Owner-row locking capabilities (RFC #4)

Generated saves use these for two distinct purposes that need different
lock semantics:

| Capability | Purpose | When generated saves call it |
|---|---|---|
| `supportsReadRowForUpdate` / `readRowForUpdate(table, id)` | True owner-row lock that blocks concurrent `UPDATE`/`DELETE` of the same row | `update(id, consistency = UpdateConsistency.Pessimistic) { ... }` — replaces the default `byId` current-row read |
| `supportsOwnerEdgeSerialization` / `serializeOwnerEdgeAndRead(table, id)` | Cooperative serialization token (e.g. advisory lock) that pairs with a current-row read | Reserved for link-table M2M edge writes (deferred) |

Both flags advertise **driver-family** support, not instance-level
ability. A driver may report `true` while still throwing
`IllegalStateException` from the corresponding method when called
outside a transaction (the root client driver is not transactional).
Generated saves preflight `inTransaction` before calling, so they
never hit that throw — but direct callers should preflight too. Use
`requireTransactionForLocking("methodName")` to produce the
canonical error message.

A driver that supports `readRowForUpdate` does **not** automatically
satisfy `supportsOwnerEdgeSerialization` and vice versa. Drivers that
support neither leave both flags at the interface defaults
(`false` / throwing default methods).

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

By default, `register()` is metadata-only. It caches the schema needed
for persistence and query lowering, but does not mutate the database.

When you explicitly opt in with `PostgresDriver(dataSource, autoDdl = true)`,
`register()` issues `CREATE TABLE IF NOT EXISTS` with:

- Column definitions with appropriate Postgres types
- `PRIMARY KEY` on the ID column
- `NOT NULL` constraints on required columns
- `UNIQUE` constraints on unique columns
- `REFERENCES ... ON DELETE` for foreign key columns (action from schema
  metadata, or inferred from nullability)
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
| `PGVECTOR` | `vector(n)` (pgvector) |
| `JSON` | `jsonb` (typed JSON) |

ID strategies `AUTO_INT` and `AUTO_LONG` map to `serial` and `bigserial`
respectively.

`PGVECTOR` is a native-storage column type carried by `ColumnStorage.Native`
rather than a portable `FieldType`. A driver advertises native support via
`supportsNativeStorage(codec)` (PostgresDriver returns `true` for
`"postgres.vector"`); a driver that does not rejects a vector schema at
`register()` with `UnsupportedDriverCapabilityException`. See
[Schema -> Native Column Types](02-schema.md#native-column-types-postgres-pgvector).

`JSON` carries serialization metadata (`JsonColumnMetadata`: the Kotlin class,
full `KType`, target mapper id, and — for the kotlinx mapper — its serializer).
A driver advertises support via `supportsTypedJson()` (PostgresDriver returns
`true` and encodes/decodes `jsonb` through its configured `JsonColumnCodec` —
kotlinx by default, Jackson via `io.entkt:jackson`); a driver that does not
rejects a typed JSON schema at `register()`, and a codec whose id doesn't match
the metadata's mapper is rejected there too. See
[Schema -> Typed JSON Fields](02-schema.md#typed-json-fields-postgres-jsonb).

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

### Locking (RFC #4)

`PostgresDriver` reports both
`supportsReadRowForUpdate = true` and
`supportsOwnerEdgeSerialization = true` — these are
*driver-family* claims. The methods themselves only execute on the
transactional driver passed inside `withTransaction { tx -> ... }`:

- `readRowForUpdate(table, id)` issues
  `SELECT ... FROM <table> WHERE id = ? FOR UPDATE` — a true row-level
  lock.
- `serializeOwnerEdgeAndRead(table, id)` takes a transaction-scoped
  `pg_advisory_xact_lock(...)` keyed by table OID + id, then performs a
  current-row read.

Both methods throw `IllegalStateException` (via
`requireTransactionForLocking`) if called on the root client driver,
since the lock would not be tied to a containing transaction's commit.
Generated saves preflight `inTransaction` so they never trigger that
throw — direct callers must do the same.

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
3. `query()` must evaluate all `Predicate` types (including edge predicates)
4. `withTransaction()` must roll back on exception, and the inner
   driver must report `inTransaction = true`
5. Optional: implement the RFC #4 lock capabilities. If the backend
   supports a true row lock that survives until transaction commit
   (e.g. `SELECT ... FOR UPDATE` in SQL or an equivalent), set
   `supportsReadRowForUpdate = true` and implement `readRowForUpdate`.
   If it supports a cooperative serialization token (e.g. an advisory
   lock keyed by table + id), also set
   `supportsOwnerEdgeSerialization = true` and implement
   `serializeOwnerEdgeAndRead`. Both methods must throw if invoked
   without a containing transaction — call
   `requireTransactionForLocking("methodName")` for the canonical
   error.
6. Optional: override `explainQuery` / `explainCount` to surface a
   driver-specific `QueryExplanation` (default returns
   `UnsupportedQueryExplanation`).

The flags advertise driver-family ability, not instance-level
ability — see the RFC #4 capability section above. The default
methods on `Driver` return `false` / throwing implementations, so a
new driver gets safe defaults if it does nothing.

For migration planning, you'll also need:

- A `TypeMapper` implementation (maps `FieldType` to your SQL types)
- A `DatabaseIntrospector` (for optional bootstrap introspection when no
  snapshot exists yet)
- A `MigrationSqlRenderer` (renders `MigrationOp` to your SQL dialect)
