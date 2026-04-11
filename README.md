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

// Eager loading
val usersWithPosts = client.users.query {
    where(User.active eq true)
    withPosts {                        // batch-load posts for each user
        where(Post.published eq true)  // optional: filter the loaded edge
    }
}.all()
usersWithPosts[0].edges.posts          // → List<Post> (loaded, or null if withPosts wasn't called)

// Delete
client.users.delete(alice)       // or client.users.deleteById(alice.id)

// Transactions
client.driver.withTransaction { txDriver ->
    val txClient = EntClient(txDriver)
    txClient.users.create { name = "Bob"; email = "bob@example.com" }.save()
    txClient.posts.create { title = "Hello"; authorId = bob.id }.save()
}
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
`.field(...)`, `.through(junctionTable, sourceCol, targetCol)` (many-to-many
via junction table).

**Mixins:** any `EntMixin` contributing `fields()`, `edges()`, `indexes()`.

**Indexes:** field list + `.unique()` + `.storageKey()`.

### Codegen (`:codegen`)

For each schema the generator emits:

- **Entity data class** with typed properties, companion-object column refs
  (`User.name: StringColumn`, `User.age: NullableComparableColumn<Int>`), baked-in
  `EntitySchema` metadata, a `fromRow()` row decoder, and a nested `Edges`
  data class for eagerly loaded relationships.
- **`{Entity}Mutation` interface** — shared interface implemented by both
  Create and Update builders, with `var` properties for all mutable fields.
  Enables shared validators via `onBeforeSave`.
- **`{Entity}Create` builder** — DSL setters + `.save()`. Mints client UUIDs
  when `IdStrategy.CLIENT_UUID`. Implements `{Entity}Mutation`.
- **`{Entity}Update` builder** — DSL setters (immutable fields are elided) + `.save()`.
  Implements `{Entity}Mutation`. Exposes `entity` for hooks to inspect current state.
- **`{Entity}Query` builder** — `.where(...)`, `.orderBy(...)`, `.limit(...)`,
  `.offset(...)`, `.all()`, `.firstOrNull()`, edge traversal methods
  (e.g. `.queryPosts()`), and eager loading methods (e.g. `.withPosts { }`).
- **`{Entity}Repo`** — `.create { }`, `.update(entity) { }`, `.query { }`,
  `.byId(id)`, `.delete(entity)`, `.deleteById(id)`. Registers the entity's
  `EntitySchema` with the driver on construction. Supports typed lifecycle
  hooks via chainable registration methods (see below).
- **`EntClient`** — single entry point holding one repo per entity, constructed
  with a `Driver` for dependency injection.

### Runtime (`:runtime`)

**`Driver` interface (seven methods):**

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
    fun <T> withTransaction(block: (Driver) -> T): T
}
```

Rows are plain `Map<String, Any?>` keyed by snake_case column name — the
driver layer speaks in these maps and the generated entity classes provide
the typed facade.

**Predicates:** sealed `Predicate` hierarchy —
`Leaf(field, op, value)`, `And`, `Or`, `HasEdge(edge)`, `HasEdgeWith(edge, inner)`.

**Ops:** `EQ`, `NEQ`, `GT`, `GTE`, `LT`, `LTE`, `IN`, `NOT_IN`, `IS_NULL`,
`IS_NOT_NULL`, `CONTAINS`, `HAS_PREFIX`, `HAS_SUFFIX`.

**Transactions:** `driver.withTransaction { txDriver -> ... }` runs a block
inside a transaction. The block receives a transaction-scoped driver; if it
completes normally the transaction commits, if it throws the transaction rolls
back. Nested `withTransaction` calls reuse the existing transaction.

**`InMemoryDriver`:** thread-safe in-process storage using `ConcurrentHashMap`
and `AtomicLong` id counters. Edge predicates recursively scan related tables
via registered `EdgeMetadata`. Supports transactions with snapshot isolation
and rollback on error. Used by the demo and as the parity oracle for the
Postgres driver tests.

### Lifecycle hooks

Generated repos support typed lifecycle hooks for cross-cutting concerns like
timestamps, audit logging, validation, and cache invalidation. Hooks receive
the actual generated entity/builder types — not raw maps.

| Hook | Signature | When |
|------|-----------|------|
| `onBeforeSave` | `(UserMutation) -> Unit` | Both create & update, before validation |
| `onBeforeCreate` | `(UserCreate) -> Unit` | Create only, after beforeSave |
| `onAfterCreate` | `(User) -> Unit` | After successful insert |
| `onBeforeUpdate` | `(UserUpdate) -> Unit` | Update only, after beforeSave |
| `onAfterUpdate` | `(User) -> Unit` | After successful update |
| `onBeforeDelete` | `(User) -> Unit` | Before driver delete |
| `onAfterDelete` | `(User) -> Unit` | After successful delete |

```kotlin
client.users
    .onBeforeSave { it.updatedAt = Instant.now() }
    .onBeforeCreate { it.createdAt = Instant.now() }
    .onBeforeUpdate { update ->
        if (update.name != update.entity.name) println("name changed!")
    }
    .onAfterCreate { user -> println("Created: ${user.name}") }
    .onBeforeDelete { user -> println("Deleting: ${user.name}") }
```

Registration methods return `this` for chaining. `onBeforeSave` accepts the
shared `{Entity}Mutation` interface so the same validator works for both
creates and updates.

### Eager loading

Query builders support eager loading of related entities via `with{Edge}()`
methods. This avoids N+1 queries by batch-loading edges using `IN` predicates
after the main query.

```kotlin
val users = client.users.query {
    where(User.active eq true)
    withPosts {                          // load posts for each user
        where(Post.published eq true)    // optional: filter/order the edge
        orderBy(Post.createdAt.desc())
    }
}.all()

users[0].edges.posts  // → List<Post> (loaded)
users[0].edges.posts  // → null if withPosts() wasn't called
```

Each entity with edges gets a nested `Edges` data class with nullable
properties — `null` means not loaded, `emptyList()` means loaded but empty.

Supports all edge types (to-one, to-many, M2M via junction table) and
nested eager loading:

```kotlin
val owners = client.owners.query {
    withPets {
        withOwner()  // nested: also load each pet's owner
    }
}.all()
```

### Postgres driver (`:postgres`)

JDBC-backed `Driver` talking to real PostgreSQL.

- **DDL:** `register()` issues `CREATE TABLE IF NOT EXISTS` from
  `EntitySchema.columns`. Type mapping: `STRING`/`TEXT`/`ENUM` → `text`,
  `BOOL` → `boolean`, `INT` → `integer`, `LONG` → `bigint`, `FLOAT` → `real`,
  `DOUBLE` → `double precision`, `TIME` → `timestamptz`, `UUID` → `uuid`,
  `BYTES` → `bytea`. Primary keys for `AUTO_INT`/`AUTO_LONG` become
  `serial`/`bigserial`. Edge FK columns emit `REFERENCES target("id")`
  constraints. Unique fields and composite indexes emit `UNIQUE`
  constraints and `CREATE INDEX` / `CREATE UNIQUE INDEX` statements.
- **Insert/update:** `INSERT ... RETURNING *` and `UPDATE ... RETURNING *`
  with fully parameterized bindings. Never rewrites the id through `update`.
- **Query:** predicate tree lowered to SQL; `AND`/`OR` nest naturally,
  leaves bind parameters through a type-aware `PreparedStatement.bind(...)`,
  edges become `EXISTS (SELECT 1 FROM target ...)` subqueries walking
  registered `EdgeMetadata` (including junction-table joins for M2M edges).
  `IN`/`NOT_IN` expand to placeholder lists
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

172 tests across all modules:

- `:schema` — schema DSL shape tests (13)
- `:runtime` — full `InMemoryDriver` coverage including CRUD, compound
  predicates, edge traversal, M2M junction tables, transactions, ordering,
  pagination (21)
- `:codegen` — per-generator unit tests for entity, mutation, create, update,
  query, repo, client, edge codegen, lifecycle hooks, and eager loading (107)
- `:gradle-plugin` — end-to-end plugin invocation in a generated test build (1)
- `:postgres` — full parity coverage against `InMemoryDriverTest` including
  M2M and transactions, running against `postgres:16-alpine` via
  Testcontainers (30)

## Roadmap

Things that are **not yet implemented**, roughly in order of severity:

### Driver capabilities
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
- **Partial indexes.** Only simple and composite indexes are supported;
  no partial / conditional indexes.
- **Exotic column types.** No JSON/JSONB, arrays, enums (as PG enum types),
  hstore, or composites.
- **Cascade delete.** Edge FK columns have `REFERENCES` constraints but no
  `ON DELETE CASCADE` / `SET NULL` clauses.

### DSL / codegen
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
