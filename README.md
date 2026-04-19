# entkt

A Kotlin port of [Ent](https://entgo.io/), Go's entity framework. Declare your
entities in a Kotlin DSL, run code generation, and get typed data classes,
query builders, and repositories that talk to a pluggable `Driver`.

This project is under active development — see [Status](#status) for what
works today and [Roadmap](#roadmap) for what's missing. For detailed
guides, see the [documentation](docs/index.md).

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
val client = EntClient(InMemoryDriver()) {  // or PostgresDriver(dataSource)
    hooks {
        users {
            beforeSave { it.updatedAt = Instant.now() }
            beforeCreate { it.createdAt = Instant.now() }
        }
    }
}

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

// Upsert — insert or update on conflict
client.users.upsert(User.email) {
    name = "Alice"
    email = "alice@example.com"
}

// Delete
client.users.delete(alice)       // or client.users.deleteById(alice.id)

// Transactions — hooks are automatically inherited
client.withTransaction { tx ->
    tx.users.create { name = "Bob"; email = "bob@example.com" }.save()
    tx.posts.create { title = "Hello"; authorId = bob.id }.save()
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
| `:migrations` | Driver-agnostic schema diffing, migration planning (prod), auto-apply (dev), `MigrationRunner` |
| `:gradle-plugin` | `entkt` Gradle plugin registering a `generateEntkt` task that wires codegen into `compileKotlin` |
| `:postgres` | JDBC driver for PostgreSQL with DDL emission, predicate-to-SQL lowering, introspection, and migration rendering |
| `:example-spring:schema` | Shared schema definitions (`User`, `Post`, `Tag`, `Friendship`, `TimestampMixin`) used by both example modules |
| `:example-demo` | Executable demo of the full API against `InMemoryDriver` |
| `:example-spring` | Spring Boot REST API example with Postgres, dev-mode migrations, lifecycle hooks, and friendship management |

## Status

### Schema DSL (`:schema`)

**Field types:** `STRING`, `TEXT`, `BOOL`, `INT`, `LONG`, `FLOAT`, `DOUBLE`,
`TIME` (`Instant`), `UUID`, `BYTES`, `ENUM` (untyped string values or
typed Kotlin enum classes via `enum<E>()`).

**Typed enums:** `enum<MyStatus>("status")` binds the field to a Kotlin enum
class — entity properties, builders, query predicates, and defaults are all
fully typed. Defaults must be constants from the correct enum class.
Stored as strings in the database.

**Field modifiers:** `.optional()`, `.nillable()`, `.unique()`, `.immutable()`,
`.sensitive()`, `.comment(...)`, `.storageKey(...)`, `.default(...)`,
`.updateDefault(...)`.

**Type-specific validators** (enforced as inline checks in generated `save()` methods):
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
- **`{Entity}Create` builder** — DSL setters + `.save()` / `.upsert(onConflict)`.
  Mints client UUIDs when `IdStrategy.CLIENT_UUID`. Implements `{Entity}Mutation`.
- **`{Entity}Update` builder** — DSL setters (immutable fields are elided) + `.save()`.
  Implements `{Entity}Mutation`. Exposes `entity` for hooks to inspect current state.
- **`{Entity}Query` builder** — `.where(...)`, `.orderBy(...)`, `.limit(...)`,
  `.offset(...)`, `.all()`, `.firstOrNull()`, edge traversal methods
  (e.g. `.queryPosts()`), and eager loading methods (e.g. `.withPosts { }`).
- **`{Entity}Repo`** — `.create { }`, `.update(entity) { }`, `.query { }`,
  `.byId(id)`, `.upsert(onConflict) { }`, `.delete(entity)`, `.deleteById(id)`.
  Registers the entity's `EntitySchema` with the driver on construction.
- **`EntClient`** — single entry point holding one repo per entity, constructed
  with a `Driver` and an optional configuration lambda for lifecycle hooks.
- **Hooks DSL classes** — `EntClientConfig`, `EntClientHooks`, and per-entity
  `{Entity}Hooks` classes that provide a structured DSL for registering
  lifecycle hooks at client construction time.

### Runtime (`:runtime`)

**`Driver` interface (ten methods):**

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
    fun count(table: String, predicates: List<Predicate>): Long
    fun exists(table: String, predicates: List<Predicate>): Boolean
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

Hooks are registered once at client construction time via a structured DSL and
automatically inherited by transactional clients. They receive the actual
generated entity/builder types — not raw maps.

| Hook | Signature | When |
|------|-----------|------|
| `beforeSave` | `(UserMutation) -> Unit` | Both create & update, before validation |
| `beforeCreate` | `(UserCreate) -> Unit` | Create only, after beforeSave |
| `afterCreate` | `(User) -> Unit` | After successful insert |
| `beforeUpdate` | `(UserUpdate) -> Unit` | Update only, after beforeSave |
| `afterUpdate` | `(User) -> Unit` | After successful update |
| `beforeDelete` | `(User) -> Unit` | Before driver delete |
| `afterDelete` | `(User) -> Unit` | After successful delete |

```kotlin
val client = EntClient(driver) {
    hooks {
        users {
            beforeSave { it.updatedAt = Instant.now() }
            beforeCreate { it.createdAt = Instant.now() }
            beforeUpdate { update ->
                if (update.name != update.entity.name) println("name changed!")
            }
            afterCreate { user -> println("Created: ${user.name}") }
            beforeDelete { user -> println("Deleting: ${user.name}") }
        }
        posts {
            beforeSave { it.updatedAt = Instant.now() }
        }
    }
}
```

`beforeSave` accepts the shared `{Entity}Mutation` interface so the same
hook works for both creates and updates. Hooks are declared once and
automatically apply within transactions — no re-registration needed.

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
- **Insert/update/upsert:** `INSERT ... RETURNING *`, `UPDATE ... RETURNING *`,
  and `INSERT ... ON CONFLICT ... DO UPDATE SET ... RETURNING *` with fully
  parameterized bindings. Never rewrites the id through `update`. Upsert uses
  PostgreSQL's `xmax` system column to detect insert vs conflict-update, so
  lifecycle hooks fire correctly.
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

> **Note:** the `:example-demo` module sidesteps the plugin and invokes
> codegen via a hand-rolled `JavaExec` task against the CLI entry point
> (`entkt.codegen.GenerateMainKt`). The plugin and the CLI path both
> exist; `example-demo` exercises the CLI path today.

### Tests

202 tests across all modules:

- `:schema` — schema DSL shape tests (13)
- `:runtime` — full `InMemoryDriver` coverage including CRUD, compound
  predicates, edge traversal, M2M junction tables, transactions, ordering,
  pagination (21)
- `:codegen` — per-generator unit tests for entity, mutation, create, update,
  query, repo, client, edge codegen, lifecycle hooks, eager loading, field
  validation, and M2M disambiguation (137)
- `:gradle-plugin` — end-to-end plugin invocation in a generated test build (1)
- `:postgres` — full parity coverage against `InMemoryDriverTest` including
  M2M and transactions, running against `postgres:16-alpine` via
  Testcontainers (30)

## Roadmap

Things that are **not yet implemented**, roughly in order of severity:

### Driver capabilities
- **Bulk operations.** No `insertMany`, `updateMany`, or `deleteMany`.
- **More drivers.** Only `InMemoryDriver` and `PostgresDriver` exist today.
  No SQLite, MySQL, etc.
- **Observability.** No logging, metrics, or query-lifecycle hooks on the
  driver interface.

### Schema & DDL
- **Partial indexes.** Only simple and composite indexes are supported;
  no partial / conditional indexes.
- **Exotic column types.** No JSON/JSONB, arrays, enums (as PG enum types),
  hstore, or composites.
- **Cascade delete.** Edge FK columns emit `ON DELETE SET NULL` (nullable)
  or `ON DELETE RESTRICT` (required) via the migration renderer, but there
  is no `ON DELETE CASCADE` option and `register()` does not emit `ON DELETE`
  clauses.

### DSL / codegen
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
