# Drivers

entkt uses a pluggable `DatabaseDriver` interface to abstract over storage
backends. All generated code talks to the driver through `Map<String, Any?>`
rows -- the driver handles SQL (or whatever storage you use), and the
generated entity classes provide the typed facade.

## The DatabaseDriver Interface

```kotlin
interface DatabaseDriver {
    // Both are required. The generated client calls registerAll() once
    // with the complete schema set; register() is the single-entity form.
    fun registerAll(schemas: List<EntitySchema>)
    fun register(schema: EntitySchema)
    fun registeredIdColumn(table: String): String
    fun <T> copyJsonValue(table: String, column: String, value: T): T

    fun insert(table: String, values: Map<String, Any?>): Map<String, Any?>
    fun update(table: String, id: Any, values: Map<String, Any?>): Map<String, Any?>?
    fun byId(table: String, id: Any): Map<String, Any?>?
    fun delete(table: String, id: Any): Boolean

    fun query(
        table: String,
        predicates: List<Predicate<*>>,
        orderBy: List<OrderField<*>>,
        limit: Int?,
        offset: Int?,
    ): List<Map<String, Any?>>
    fun count(table: String, predicates: List<Predicate<*>>): Long
    fun exists(table: String, predicates: List<Predicate<*>>): Boolean

    // Native per-parent windows for direct to-many eager loads.
    // Both are abstract and forward as one unit through decorators.
    fun directToManyWindowCapability(): DirectToManyWindowCapability
    fun queryDirectToMany(query: DirectToManyQuery): RelatedRows

    fun insertMany(table: String, values: List<Map<String, Any?>>): List<Map<String, Any?>>
    fun updateMany(table: String, values: Map<String, Any?>, predicates: List<Predicate<*>>): Int
    fun deleteMany(table: String, predicates: List<Predicate<*>>): Int
    fun deleteManyByIds(
        table: String,
        idColumn: String,
        ids: List<Any>,
        predicates: List<Predicate<*>>,
    ): List<Any>

    fun <T> withTransaction(block: (DatabaseDriver) -> T): DriverTransactionResult<T>
    val inTransaction: Boolean

    fun classifyMutationException(
        exception: Exception,
        entity: String,
        operation: EntOperation,
    ): EntMutationException?

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
- `registeredIdColumn(table)` returns the primary-key column captured during
  registration and rejects an unregistered table. It is abstract: every
  `DatabaseDriver` implementation must provide this metadata lookup.
- `copyJsonValue(table, column, value)` returns a detached value of the same
  declared Kotlin type using the driver's configured JSON mapper. Generated
  privacy and validation contexts use it to isolate mutable typed-JSON graphs
  between rules and from pending writes. `null` returns unchanged; the default
  rejects non-null values, so every driver that advertises typed JSON support
  must override it. Postgres performs an encode/decode round trip through its
  configured `JsonColumnCodec`. A custom codec's `decode()` must return a fresh
  graph on every call; a codec that caches decoded objects must override
  `copyValue()` to allocate the detached lifecycle snapshot explicitly.
- `insert()` returns the persisted row including any server-assigned values
  (auto-increment IDs, defaults).
- `update()` returns the updated row, or `null` if the row was not found.
- Postgres raw row maps may use any `Number` subtype for numeric columns.
  `INT`/`LONG` values must be finite whole numbers inside the target range;
  fractional and overflowing values fail before SQL rather than truncating or
  wrapping. `FLOAT`/`DOUBLE` retain normal floating-point rounding, but a finite
  non-zero input that would overflow to infinity or underflow to zero is
  rejected. Generated repositories already enforce the usual Kotlin numeric
  types at compile time.
- `count()` / `exists()` evaluate the same predicate tree as `query()`;
  drivers may short-circuit `exists()` (Postgres uses `SELECT EXISTS(...)`
  / `LIMIT 1`).
- `insertMany()` accepts one logical batch and returns exactly one persisted
  row per input, with assigned IDs **in input order**. Generated `createMany()`
  finishes its full pre-write lifecycle, calls this method once, then hydrates
  the returned rows and runs post-write callbacks. PostgresDriver uses multi-row
  `INSERT ... VALUES` and enforces the ordering by correlating `RETURNING`
  rows to inputs by id — reserving ids from the sequence up front for
  multi-row auto-id batches (adding `OVERRIDING SYSTEM VALUE` for
  `GENERATED ALWAYS AS IDENTITY` columns) — rather than relying on
  `RETURNING` output order, which PostgreSQL does not specify. The one
  shape with no possible correlation key — ids filled by a database
  default the driver knows nothing about (no serial/identity sequence) —
  falls back to per-row statements, which are inherently unambiguous
  (one row in, one row out) at the cost of the batching. Tables whose
  triggers rewrite ids during insert are outside the correlation
  contract. Because Postgres reserves the whole batch's sequence values before
  inserting and then supplies those IDs explicitly, the normal ID default is
  bypassed. Other defaults or triggers that rely on row-local `currval()` or
  `lastval()` progression are therefore unsupported for this optimized path:
  they can observe the last reserved value for every row. The database role
  must also have `INSERT` privilege on the ID column, even if scalar inserts
  normally omit it. Drivers may chunk physically for parameter limits, but
  every chunk must stay inside the surrounding transaction and lifecycle
  callbacks still observe the complete logical batch.
- `updateMany()` updates all rows matching the predicates with the same
  values. Returns the count of updated rows. This remains a low-level,
  lifecycle-free method; there is no generated `updateMany()` terminal.
- `deleteMany()` deletes all rows matching the predicates. Returns the
  count of deleted rows. Generated `deleteMany()` does not call it directly;
  it is the correctness fallback used by `deleteManyByIds()`.
- `deleteManyByIds()` deletes only distinct supplied IDs that still match all
  supplied predicates and returns the unique IDs actually removed; return order
  is unspecified. It rejects an `idColumn` different from
  `registeredIdColumn(table)`. The default calls predicate-based `deleteMany()`
  once per distinct ID, preserving correctness but not set-based performance.
  PostgresDriver overrides it with one logical
  `DELETE ... WHERE id IN (...) AND <predicates> RETURNING id` operation.
  Generated callers always run it inside a transaction; direct low-level callers
  that require all-or-nothing behavior must do the same.

All driver bulk methods are low-level operations and do **not** run generated
hooks, privacy, or validation themselves. Generated `createMany()` and
`deleteMany()` perform their lifecycle phases first and then use
`insertMany()` and `deleteManyByIds()` respectively. For delete, the generated
repo passes the exact effective caller-plus-interceptor predicates captured
during candidate selection; drivers must apply them again with the approved ID
set rather than rerunning or reconstructing query policy. See
[Operation Lifecycle](operation-lifecycle.md#bulk-operations).

A multi-input logical batch may use several physical statements. If a later
statement throws inside a caller-owned transaction, an earlier input may
already be staged; generated code therefore reports that batch-level failure
conservatively as `TransactionPending` and marks the transaction rollback-only.
A one-input batch can retain an exact statement-level classification because
there is no earlier input to account for. An EntKt-owned batch can report
`NotPersisted` after rollback is confirmed. Drivers must let cancellation and
JVM errors reach the transaction boundary so it can perform the same rollback
discipline.

- `directToManyWindowCapability()` reports whether the driver can push
  a direct to-many eager edge's per-parent ordering, offset, and limit
  into storage (`NATIVE`) or the runtime should retain the emulated
  fallback — one ordinary `query()` with the window applied in memory
  (`EMULATED`). The runtime samples it once per eager step, before the
  interceptor chain, and that one sample drives both the structural
  bind budgeting and the fetch routing — implementations must answer
  purely (no I/O) and stably, like every other capability accessor.
  An emulated driver accepts every eager query it accepted before the
  capability existed.
- `queryDirectToMany(query)` executes one logical direct to-many
  relationship read with the window applied in storage. Only called
  when the capability is `NATIVE`; emulated drivers implement it as a
  throwing stub. The `DirectToManyQuery` plan carries the parent keys
  and target FK column separately from the remaining frozen target
  predicates — the driver lowers the relationship constraint itself
  (PostgreSQL: one typed-array `= ANY(?)` parameter) so parent
  cardinality never consumes scalar binds. Rows return in the
  canonical effective order as `RelatedRows`, each row paired with its
  decoded source key; synthetic driver columns (ranking aliases) never
  reach the row maps, and the one-statement lowering keeps every
  physical read on one database snapshot. Both members are
  deliberately abstract — they forward as one unit, so a hand-written
  decorator cannot silently downgrade a native driver or forward the
  capability without the operation. The plan and envelope types are
  `@EntktInternal` cross-module SPI.
- `withTransaction()` runs a block in a transaction and reports the
  outcome structurally as `DriverTransactionResult<T>`. The block
  receives a transaction-scoped driver. `Success(value)` is returned
  only after commit is confirmed. If the block throws an ordinary
  exception and rollback is confirmed, the driver returns
  `Failed(exception, NotCommitted)`; a failed rollback returns
  `OutcomeUnknown`, and a failed commit returns `OutcomeUnknown` even
  if a later rollback appears to succeed (the commit may already have
  reached the database). A `CancellationException` is rethrown only
  after confirmed rollback; commit-time cancellation or cancellation
  followed by an unconfirmed rollback returns
  `Failed(cancellation, OutcomeUnknown)`. JVM `Error`s still rethrow.
  Calling `withTransaction()`
  on an already-transactional driver throws
  `NestedTransactionUnsupportedException` before entering the block.
- `classifyMutationException()` maps a low-level exception from a **mutation**
  into a state-bearing `EntMutationException?` — the returned
  exception's own `writeState` is the classification, with no parallel
  state field. E.g. a
  recognized constraint violation becomes
  `EntConstraintViolationException` with `NotPersisted`. Returning
  `null` means "no more precise classification"; generated mutation
  code then falls back to its phase-derived write state (using
  `PersistenceUnknown` for an unclassified write exception, never
  optimistically `NotPersisted`). Read execution never consults it —
  canonical reads store the original exception directly in
  `ReadResult.Failed`.
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
- `CREATE INDEX` / `CREATE UNIQUE INDEX` for composite indexes

Foreign keys are then added as separate
`ALTER TABLE ... ADD CONSTRAINT fk_<table>_<column> FOREIGN KEY ...
ON DELETE ...` statements (action from schema metadata, or inferred from
nullability), matching the naming the migration path uses.

They are split out of `CREATE TABLE` so that ordering within a batch
doesn't matter. An inline `REFERENCES` requires the target table to
already exist, which is impossible to arrange for mutually-referencing
entities:

```kotlin
class User : EntSchema("users", clientName = "users") {
    override fun id() = EntId.long()
    val pinnedPost by belongsTo<Post>("pinned_post").nullable()
}

class Post : EntSchema("posts", clientName = "posts") {
    override fun id() = EntId.long()
    val author by belongsTo<User>("author")
}
```

Whichever table is created first would reference one that doesn't exist
yet. Instead, `registerAll()` runs two passes over the batch: every
table, then every constraint.

**Pre-existing tables are reconciled, not trusted.** `CREATE TABLE IF
NOT EXISTS` is a silent no-op on an existing table whatever its shape,
so before creating anything auto-DDL introspects the tables that
already exist (resolved through the connection's `search_path`, the
same way the DDL itself resolves names) and compares their body —
columns, types, nullability, primary key — against the requested
schema, using the same normalizer and differ the migration engine
uses. `GENERATED AS IDENTITY` id columns count as equivalent to the
`serial`/`bigserial` the schema declares. Present and equivalent →
no-op; present and different → registration fails naming the drift,
with nothing cached (the batch stays retryable). Auto-DDL never alters
an existing table — reconciling drift is a migration's job. Column
`DEFAULT`s are not compared: the runtime schema carries no default
expressions, so a live default (including one the migration path
itself created) is not drift. Indexes and foreign keys are reconciled
separately by their derived names, each with absent → create /
present-and-different → fail semantics; constraints under other names
are not examined.

### Registration

`DatabaseDriver.registerAll(schemas)` is the entry point. The generated
`EntClient` calls it once with the complete `SCHEMAS` set while
initializing its driver property — before any repo is constructed — so a
driver that materializes storage always sees the whole set at once:

```kotlin
val client = EntClient(PostgresDriver(dataSource, autoDdl = true))
// registerAll(EntClient.SCHEMAS) has already run by the time this returns
```

Registering by hand works the same way:

```kotlin
PostgresDriver(dataSource, autoDdl = true).registerAll(EntClient.SCHEMAS)
```

`register(schema)` handles a single entity and is equivalent to
`registerAll(listOf(schema))`. It can only resolve foreign keys whose
target table already exists; a cyclic or otherwise unresolved target
fails with an error naming the constraint, the missing table, and
`registerAll` as the fix. Nothing is ever skipped silently.

Re-registering an unchanged set is free — no connection, no catalog lookup,
no DDL. That matters because `withTransaction` builds a client *inside* the
open transaction and registers the same schemas there.

Registering a *different* schema for a table already registered is an
error rather than a silent no-op.

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

PostgreSQL's protocol accepts at most 65,535 bind parameters per
statement. Operations whose parameter count is data-dependent
(`query`, `count`, `exists`, `aggregate`, `updateMany`, `deleteMany`)
count the rendered statement's final parameters — relationship IDs,
predicates, and ordering operands all share the budget — and reject
anything over the limit with `PostgresBindLimitException` before the
statement is prepared or sent, instead of the JDBC driver's opaque
protocol error. An oversized `IN` list is rejected from its projected
size before being copied or expanded at all, so even an absurdly
large list cannot exhaust memory on the way to the error. Generated
read terminals also call `DatabaseDriver.requireBindCapacity` at entry — a
deliberately abstract member both PostgreSQL facades implement — with
a conservative minimum computed from the lists' O(1) sizes, so the
rejection happens before the runtime takes any defensive snapshot of
the operands and before any interceptor runs. Direct to-many eager
loads no longer trigger the limit: their parent keys travel as one
typed array (see below). The other relationship shapes (belongs-to,
has-one, many-to-many) and any caller-supplied `IN` list still bind
one scalar per value and are not yet chunked, so reduce the root
result size or split the query when they overflow. `insertMany` and
`deleteManyByIds` already chunk physical statements and stay under the
limit by construction.

#### Native direct to-many windows

`PostgresDriver` reports `DirectToManyWindowCapability.NATIVE` and
lowers `queryDirectToMany` as one statement. The parent keys bind as
a single typed PostgreSQL array (`fk = ANY(?)` — `integer`/`bigint`/
`text`/`uuid`, from the FK column's registered type), so parent
cardinality never consumes scalar binds. With a finite per-parent
`limit` or a positive `offset`, the ranked form applies the window in
storage:

```sql
SELECT t1."id", t1."title", ... FROM (
    SELECT t0."id", t0."title", ..., ROW_NUMBER() OVER (
        PARTITION BY t0."author_id"
        ORDER BY t0."created_at" DESC, t0."id" ASC
    ) AS "__entkt_rank"
    FROM "posts" AS t0
    WHERE t0."author_id" = ANY(?) AND (...)
) AS t1
WHERE t1."__entkt_rank" > ? AND t1."__entkt_rank" <= ?
ORDER BY t1."created_at" DESC, t1."id" ASC
```

Both select lists enumerate the registered columns rather than using
`t0.*`: registration is metadata-only under `autoDdl = false`, so a
hand-managed table may carry unregistered physical columns, and the
explicit lists keep them out of the derived table where one could
otherwise make the ranking alias ambiguous.

Every frozen predicate applies before `ROW_NUMBER()` is assigned, so
a filter can never make a window return fewer rows while later
matches exist. The rank bounds bind as BIGINT parameters computed in
`Long`, so extreme `offset`/`limit` values cannot overflow. The rank
alias is allocated to dodge every registered storage column and the
outer SELECT lists only registered columns, so the synthetic column
never reaches entity decoding. Without a finite window the statement
drops `ROW_NUMBER()` but keeps the typed-array relationship
predicate. One statement means every physical read shares one
database snapshot.

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
val viewerContext = ViewerContext(Viewer.User(currentUserId()))
client.withTransaction { tx ->
    val alice = tx.users.create { name = "Alice"; email = "a@b.com" }
        .saveAndLoad(viewerContext)
        .orRollback()
    tx.posts.create { title = "Hello"; authorId = alice.id }.save(viewerContext).orRollback()
    // Commits if the block completes without a recorded mutation failure;
    // rolls back on orRollback() or an exception
}
```

The client-level `withTransaction` returns `TransactionResult<T>`
(project with `.getOrThrow()`); a mutation failure produced through
`tx` marks the scope rollback-only even if its result is ignored.
After confirmed rollback, `.getOrThrow()` rethrows the stored exception
directly; an unknown transaction outcome throws
`EntTransactionOutcomeUnknownException` instead.
The generated `tx` is an `EntTransactionClient`, which has no
`withTransaction` member, so client-level nesting does not compile.
Both it and the root `EntClient` implement `EntClientScope`; shared
repository helpers should accept that interface. Hook contexts also expose
`EntClientScope`, preventing `ctx.client` from restoring the root-only
transaction entry point.
Do not capture and use the root `client` inside the block. EntKt rejects
same-root reads and mutations before privacy-context providers, hooks,
privacy, validation, interceptors, or database I/O; calling the captured
root's `withTransaction` throws `NestedTransactionUnsupportedException`
before the nested block runs. The PostgreSQL driver applies the same
execution-local backstop to direct root-driver I/O. This guard is local to
the current synchronous execution, so unrelated work on another thread or
through a different root driver remains usable.

A canonical root read stores `RootOperationInsideTransactionException`
directly in `ReadResult.Failed`. A root mutation stores an
`EntUnexpectedMutationException(NotPersisted)` whose cause is that exception.

Calling `withTransaction` on the transaction-scoped driver also throws
`NestedTransactionUnsupportedException` before the nested block runs.

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
  `pg_advisory_xact_lock(...)` keyed by the table name's `hashCode` and
  the id's `hashCode` (two `int4` args), then performs a
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

## Writing a Custom DatabaseDriver

To support a new database, implement the `DatabaseDriver` interface. The key
contract:

1. `registerAll()`, `register()`, and `registeredIdColumn()` are abstract and
   must be implemented — a driver that omits any of them won't compile.
   `registerAll()` receives the complete schema set and is called once
   per client construction, before any repo exists; `register()` handles
   a single entity and is called from each repo's initializer. Both must
   be idempotent, and both must do no I/O when everything they're handed
   is already registered — clients are constructed constantly
   (`withTransaction` builds one inside the open transaction), so any
   work here lands on that hot path.

   If the driver materializes storage, `registerAll()` must create every
   table before adding any constraint that spans tables. Foreign keys
   between mutually-referencing entities have no valid
   one-schema-at-a-time ordering, so the batch is what makes those
   schemas expressible.

   A driver that wraps or decorates another must forward `registerAll()`
   explicitly. There is deliberately no `schemas.forEach(::register)`
   default: inheriting one would dissolve the batch back into
   one-at-a-time calls and silently drop the ordering guarantee.

   `registeredIdColumn(table)` must return the registered schema's exact ID
   column and reject an unregistered table. Decorating and transaction-scoped
   drivers must forward this lookup as well. It is used to validate the raw
   identifier accepted by the default ID-scoped bulk delete.

   `requireBindCapacity(minimumParameters, table)` must reject a minimum
   bind count the backend cannot satisfy in one statement, and must be an
   explicit no-op for a backend with no declared limit. It is deliberately
   abstract for the same reason `registerAll()` has no default: generated
   read paths call it before taking defensive snapshots of predicate
   operands, so a hand-written decorator that inherited a no-op would
   silently let huge operands materialize again on the way to the
   backend's eventual rejection. Decorating and transaction-scoped
   drivers must forward it (Kotlin `by`-delegating wrappers do so
   automatically).

   `directToManyWindowCapability()` and `queryDirectToMany(query)` are
   likewise abstract and forward as one unit. A driver without native
   per-parent windows returns
   `DirectToManyWindowCapability.EMULATED` and implements
   `queryDirectToMany` as a throwing stub — the runtime then keeps
   direct to-many eager loads on ordinary `query()` calls with the
   window applied in memory, so nothing the driver accepted before is
   rejected. A `NATIVE` driver must apply the per-parent window in
   storage, return rows in the supplied effective order with each
   row's decoded source key, keep synthetic columns out of the row
   maps, keep every physical read on one database snapshot, and never
   spend one scalar bind per parent key. The relationship-plan types
   are `@EntktInternal`; implementing the member is an explicit
   framework-wiring opt-in.
2. `insert()` must return the full row including server-assigned values.
   `insertMany()` must preserve positional input/result correlation, keep any
   physical chunks inside the surrounding transaction, and never commit
   internally.
3. `query()` must evaluate all `Predicate` types (including edge predicates).
   The default `deleteManyByIds()` is correct but issues one `deleteMany()` per
   distinct ID; override it when the backend can combine the approved IDs and
   frozen effective predicates into a set-based returning delete. The override
   must return only unique supplied IDs that were actually deleted.
4. A driver that reports typed JSON support must implement
   `copyJsonValue(table, column, value)` by producing a detached graph through
   the same mapper configuration used for storage. It must preserve `null` and
   the column's declared Kotlin type; returning the input object is not valid
   for mutable JSON values. Decorating and transaction-scoped drivers must
   forward this operation.
5. `withTransaction()` must honor the write-certainty contract:
   `DriverTransactionResult.Success` only after confirmed commit;
   `Failed(exception, NotCommitted)` only after confirmed rollback;
   `OutcomeUnknown` for rollback or commit failures (a failed commit
   stays `OutcomeUnknown` even if a later rollback appears to
   succeed); cleanup failures after a confirmed commit must not turn
   success into failure. Block-time cancellation is rethrown only after
   confirmed rollback; commit-time cancellation or an unconfirmed rollback is
   `OutcomeUnknown`. JVM errors are rolled back best-effort and rethrown. The
   inner driver must report `inTransaction = true`,
   and a nested call must throw
   `NestedTransactionUnsupportedException` before running the block.
6. Optional: implement the RFC #4 lock capabilities. If the backend
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
The flags advertise driver-family ability, not instance-level
ability — see the RFC #4 capability section above. Optional capability
methods on `DatabaseDriver` retain safe `false` / throwing defaults, but the three
registration/metadata methods above are required.

For migration planning, you'll also need:

- A `TypeMapper` implementation (maps `FieldType` to your SQL types)
- A `DatabaseIntrospector` (for optional bootstrap introspection when no
  snapshot exists yet)
- A `MigrationSqlRenderer` (renders `MigrationOp` to your SQL dialect)
