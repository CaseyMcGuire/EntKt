# EntKt

A Kotlin entity framework. Declare your entities in a Kotlin DSL, run code
generation, and get typed data classes, query builders, and repositories
that talk to a pluggable `Driver`.

This project is under active development — see the module READMEs for
details on each component, and [Roadmap](#roadmap) for what's missing.
For guides, see the [documentation](docs/index.md).

## Overview

```kotlin
// 1. Declare a schema (compile-time source of truth)
class User : EntSchema("users", clientName = "users") {
    override fun id() = EntId.uuid()

    val name by string("name").minLength(1).maxLength(64)
    val email by string("email").unique()
    val age by int("age").nullable().min(0).max(150)
    val active by bool("active").default(true)

    val posts by hasMany<Post>("posts")
}
```

```kotlin
// 2. Use the generated code
val client = EntClient(PostgresDriver(dataSource))

val alice = client.users.create {
    name = "Alice"
    email = "alice@example.com"
    age = 30
    active = true
}.saveAndLoad().getOrThrow()   // save(): MutationResult<Unit> when the entity isn't needed

val adults = client.users.query {
    where(User.active eq true and (User.age gte 18))
    orderBy(User.age.desc())
}.all().getOrThrow()

val authorsWithPublishedPosts = client.users.query {
    where(User.posts.has { where(Post.published eq true) })
}.all().getOrThrow()

// Eager loading
val usersWithPosts = client.users.query {
    where(User.active eq true)
    loadPosts {                        // batch-load posts for each user
        where(Post.published eq true)  // optional: filter the loaded edge
    }
}.all().getOrThrow()
usersWithPosts[0].edges.posts.requireLoaded()  // → List<Post> (throws EdgeNotLoadedException if loadPosts wasn't called)

// Every data operation returns an exhaustive result you can match on
// instead of projecting with getOrThrow():
when (val result = client.users.findById(alice.id)) {
    is ReadResult.Success -> result.value      // User? — null is authoritative absence
    is ReadResult.Failed -> result.exception   // typed: privacy denial, rejection, driver failure
}

// Delete (idempotent: success means the row is absent afterward)
client.users.delete(alice).getOrThrow()  // or client.users.deleteById(alice.id).getOrThrow()

// Transactions
client.withTransaction { tx ->
    val bob = tx.users.create { name = "Bob"; email = "bob@example.com" }.saveAndLoad().orRollback()
    tx.posts.create { title = "Hello"; authorId = bob.id }.save().orRollback()
}.getOrThrow()
```

See [`:example-spring`](example-spring/README.md) for a runnable Spring
Boot REST API example backed by Postgres.

## Module layout

| Module | Description |
|---|---|
| [`:schema`](schema/README.md) | Declarative schema DSL — `EntSchema`, field/edge/index builders, `FieldType` |
| [`:runtime`](runtime/README.md) | `Driver` interface, `EntitySchema`, query `Predicate` hierarchy |
| [`:codegen`](codegen/README.md) | KotlinPoet-based generator: entity classes, create/update/query builders, repos, `EntClient` |
| [`:migrations`](migrations/README.md) | Driver-agnostic schema diffing and migration planning |
| [`:gradle-plugin`](gradle-plugin/README.md) | EntKt Gradle plugin registering `generateEntkt` task |
| [`:postgres`](postgres/README.md) | JDBC driver for PostgreSQL with DDL emission, predicate-to-SQL lowering, introspection, and migration rendering |
| [`:example-spring`](example-spring/README.md) | Spring Boot REST API example with Postgres, Flyway-applied SQL migrations, lifecycle hooks, and friendship management |

## Roadmap

Things that are **not yet implemented**, roughly in order of severity:

### Driver capabilities
- **More drivers.** Only `PostgresDriver` exists today. No SQLite,
  MySQL, etc.
- **Observability.** No logging, metrics, or query-lifecycle hooks on the
  driver interface.

### Schema & DDL
- **Exotic column types.** Typed JSON/JSONB is supported; native SQL arrays,
  enums (as PG enum types), hstore, and composites are not yet implemented.

### Tooling
- **No published artifacts.** The plugin and runtime are not yet on any
  Maven repository — consumers would currently need a composite build or
  local publish.

## Building

```bash
./gradlew build          # compiles everything, runs all tests
./gradlew :postgres:test # runs the Testcontainers-backed Postgres tests
```

Requires JDK 17+. Running `:postgres:test` (and the integration test
suite) requires Docker.
