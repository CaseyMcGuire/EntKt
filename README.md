# entkt

A Kotlin port of [Ent](https://entgo.io/), Go's entity framework. Declare your
entities in a Kotlin DSL, run code generation, and get typed data classes,
query builders, and repositories that talk to a pluggable `Driver`.

This project is under active development — see [Status](#status) for what
works today and [Roadmap](#roadmap) for what's missing.

## Overview

```kotlin
// 1. Declare a schema (compile-time source of truth)
object User : EntSchema() {
    override fun id() = EntId.uuid()
    override fun mixins() = listOf(TimestampMixin)
    override fun fields() = fields {
        string("name").minLen(1).maxLen(64)
        string("email").unique()
        int("age").optional().min(0).max(150)
        bool("active").default(true)
    }
    override fun edges() = edges {
        to("posts", Post)
    }
}
```

```kotlin
// 2. Use the generated code
val client = EntClient(InMemoryDriver()) // or PostgresDriver(dataSource)

val alice = client.users.create {
    name = "Alice"
    email = "alice@example.com"
    age = 30
    active = true
}.save()

val adults = client.users.query {
    where(User.active eq true and (User.age gte 18))
    orderBy(User.age.desc())
}.all()

val authorsWithPublishedPosts = client.users.query {
    where(User.posts.has { where(Post.published eq true) })
}.all()
```

See [`example-demo/src/main/kotlin/example/demo/Demo.kt`](example-demo/src/main/kotlin/example/demo/Demo.kt)
for a full end-to-end tour, runnable with `./gradlew :example-demo:run`.

## Module layout

| Module | Contents |
|---|---|
| `:schema` | Declarative schema DSL — `EntSchema`, field/edge/index/mixin builders, `FieldType` |
| `:runtime` | `Driver` interface, `InMemoryDriver`, `EntitySchema`/`ColumnMetadata`/`EdgeMetadata`, query `Predicate` hierarchy, `Op` enum |
| `:codegen` | KotlinPoet-based generator: entity classes, create/update/query builders, repos, `EntClient` |
| `:gradle-plugin` | `entkt` Gradle plugin registering a `generateEntkt` task that wires codegen into `compileKotlin` |
| `:postgres` | JDBC driver for PostgreSQL with DDL emission and predicate-to-SQL lowering |
| `:example` | Sample schemas (`User`, `Post`, `Tag`, `TimestampMixin`) plus a `main()` that runs codegen as a CLI |
| `:example-demo` | Executable demo of the full API against `InMemoryDriver` |

## Status

### Schema DSL (`:schema`)

**Field types:** `STRING`, `TEXT`, `BOOL`, `INT`, `LONG`, `FLOAT`, `DOUBLE`,
`TIME` (`Instant`), `UUID`, `BYTES`, `ENUM`.

**Field modifiers:** `.optional()`, `.nillable()`, `.unique()`, `.immutable()`,
`.sensitive()`, `.comment(...)`, `.storageKey(...)`, `.default(...)`,
`.updateDefault(...)`.

**Type-specific validators:**
- Strings: `.minLen()`, `.maxLen()`, `.notEmpty()`, `.match(regex)`
- Numbers: `.min()`, `.max()`, `.positive()`, `.negative()`, `.nonNegative()`

**Id strategies** (`EntId.int()` / `.long()` / `.uuid()` / `.string()`):
`AUTO_INT`, `AUTO_LONG`, `CLIENT_UUID`, `EXPLICIT`.

**Edges:** `to(name, target)` (one-to-many), `from(name, target)` (inverse,
synthesizes FK on source). Modifiers: `.unique()`, `.required()`, `.ref(...)`,
`.field(...)`.

**Mixins:** any `EntMixin` contributing `fields()`, `edges()`, `indexes()`.

**Indexes:** field list + `.unique()` + `.storageKey()`.

### Codegen (`:codegen`)

For each schema the generator emits:

- **Entity data class** with typed properties, companion-object column refs
  (`User.name: StringColumn`, `User.age: NullableComparableColumn<Int>`), baked-in
  `EntitySchema` metadata, and a `fromRow()` row decoder.
- **`{Entity}Create` builder** — DSL setters + `.save()`. Mints client UUIDs
  when `IdStrategy.CLIENT_UUID`.
- **`{Entity}Update` builder** — DSL setters (immutable fields are elided) + `.save()`.
- **`{Entity}Query` builder** — `.where(...)`, `.orderBy(...)`, `.limit(...)`,
  `.offset(...)`, `.all()`, `.firstOrNull()`, plus edge traversal methods
  (e.g. `.queryPosts()`).
- **`{Entity}Repo`** — `.create { }`, `.update(entity) { }`, `.query { }`, `.byId(id)`.
  Registers the entity's `EntitySchema` with the driver on construction.
- **`EntClient`** — single entry point holding one repo per entity, constructed
  with a `Driver` for dependency injection.

### Runtime (`:runtime`)

**`Driver` interface (six methods):**

```kotlin
interface Driver {
    fun register(schema: EntitySchema)
    fun insert(table: String, values: Map<String, Any?>): Map<String, Any?>
    fun update(table: String, id: Any, values: Map<String, Any?>): Map<String, Any?>?
    fun byId(table: String, id: Any): Map<String, Any?>?
    fun query(
        table: String,
        predicates: List<Predicate>,
        orderBy: List<OrderField>,
        limit: Int?,
        offset: Int?,
    ): List<Map<String, Any?>>
    fun delete(table: String, id: Any): Boolean
}
```

Rows are plain `Map<String, Any?>` keyed by snake_case column name — the
driver layer speaks in these maps and the generated entity classes provide
the typed facade.

**Predicates:** sealed `Predicate` hierarchy —
`Leaf(field, op, value)`, `And`, `Or`, `HasEdge(edge)`, `HasEdgeWith(edge, inner)`.

**Ops:** `EQ`, `NEQ`, `GT`, `GTE`, `LT`, `LTE`, `IN`, `NOT_IN`, `IS_NULL`,
`IS_NOT_NULL`, `CONTAINS`, `HAS_PREFIX`, `HAS_SUFFIX`.

**`InMemoryDriver`:** thread-safe in-process storage using `ConcurrentHashMap`
and `AtomicLong` id counters. Edge predicates recursively scan related tables
via registered `EdgeMetadata`. Used by the demo and as the parity oracle for
the Postgres driver tests.

### Postgres driver (`:postgres`)

JDBC-backed `Driver` talking to real PostgreSQL.

- **DDL:** `register()` issues `CREATE TABLE IF NOT EXISTS` from
  `EntitySchema.columns`. Type mapping: `STRING`/`TEXT`/`ENUM` → `text`,
  `BOOL` → `boolean`, `INT` → `integer`, `LONG` → `bigint`, `FLOAT` → `real`,
  `DOUBLE` → `double precision`, `TIME` → `timestamptz`, `UUID` → `uuid`,
  `BYTES` → `bytea`. Primary keys for `AUTO_INT`/`AUTO_LONG` become
  `serial`/`bigserial`.
- **Insert/update:** `INSERT ... RETURNING *` and `UPDATE ... RETURNING *`
  with fully parameterized bindings. Never rewrites the id through `update`.
- **Query:** predicate tree lowered to SQL; `AND`/`OR` nest naturally,
  leaves bind parameters through a type-aware `PreparedStatement.bind(...)`,
  edges become `EXISTS (SELECT 1 FROM target ...)` subqueries walking
  registered `EdgeMetadata`. `IN`/`NOT_IN` expand to placeholder lists
  (empty IN short-circuits to `FALSE`, empty NOT IN to `TRUE`). String ops
  use `LIKE` with safely built patterns.
- **Identifier quoting:** all identifiers wrapped in `"..."`. Values are
  never string-concatenated into SQL.
- **Tests:** `PostgresDriverTest` mirrors `InMemoryDriverTest` assertion for
  assertion, running against `postgres:16-alpine` via **Testcontainers 2.0.4**.
  Requires a running Docker daemon.

### Gradle plugin (`:gradle-plugin`)

Applying `id("entkt")` in a consumer build:

1. Registers the `generateEntkt` task, which runs the codegen into
   `build/generated/entkt/`.
2. Adds that directory to the `main` source set.
3. Makes `compileKotlin`/`compileJava` depend on `generateEntkt` so codegen
   runs automatically before compilation.

Configuration lives under an `entkt { packageName = "..." }` extension.

> **Note:** the current `:example-demo` module sidesteps the plugin and
> invokes codegen via a hand-rolled `JavaExec` task against `:example`'s
> `main()`. The plugin and the CLI path both exist; `example-demo` exercises
> the CLI path today.

### Tests

- `:schema` — schema DSL shape tests
- `:runtime` — full `InMemoryDriver` coverage (CRUD, compound predicates,
  edge traversal, ordering, pagination)
- `:codegen` — per-generator unit tests for entity, create, update, query,
  repo, client, and edge codegen
- `:gradle-plugin` — end-to-end plugin invocation in a generated test build
- `:postgres` — full parity coverage against `InMemoryDriverTest`, 13 tests

## Roadmap

Things that are **not yet implemented**, roughly in order of severity:

### Driver capabilities
- **Transactions.** `Driver` has no `begin`/`commit`/`rollback`; every call
  borrows its own connection and runs one statement.
- **Bulk operations.** No `insertMany`, `updateMany`, or `deleteMany`.
- **Upsert.** No `INSERT ... ON CONFLICT` / `MERGE` path.
- **More drivers.** Only `InMemoryDriver` and `PostgresDriver` exist today.
  No SQLite, MySQL, etc.
- **Observability.** No logging, metrics, or query-lifecycle hooks on the
  driver interface.

### Schema & DDL
- **Migrations.** `register()` is `CREATE TABLE IF NOT EXISTS` and nothing
  more — no `ALTER TABLE`, no diffing, no drop-recreate, no migration
  history.
- **Unique / composite / partial indexes.** `field.unique()` and
  `index.unique()` are captured in the DSL but the Postgres driver does not
  emit `CREATE UNIQUE INDEX` for them. Indexes only support a simple field
  list today.
- **Exotic column types.** No JSON/JSONB, arrays, enums (as PG enum types),
  hstore, or composites.
- **Cascade delete / FK constraints.** Edge FKs are stored as plain nullable
  columns without `REFERENCES ... ON DELETE` clauses.

### DSL / codegen
- **Delete builder.** `Driver.delete(id)` works, but there's no generated
  `{Entity}Delete` DSL or `repo.delete(entity)` convenience.
- **Many-to-many via `through`.** `EdgeBuilder.through(...)` is declared but
  codegen does not emit junction-table support yet.
- **Lifecycle hooks.** No `beforeSave`, `afterLoad`, etc. Validators are
  checked in the generated create/update builders but don't translate into
  runtime `CHECK` constraints.
- **Self-referential edges.** No explicit handling or codegen tests.
- **Incremental codegen.** The Gradle plugin always regenerates the full
  tree; there's no per-schema caching or watch mode.

### Tooling
- **No published artifacts.** The plugin and runtime are not yet on any
  Maven repository — consumers would currently need a composite build or
  local publish.

## Building

```bash
./gradlew build          # compiles everything, runs all tests
./gradlew :postgres:test # runs the Testcontainers-backed parity tests
./gradlew :example-demo:run  # runs the InMemoryDriver demo end-to-end
```

Requires JDK 17+. Running `:postgres:test` requires Docker.
