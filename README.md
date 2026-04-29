# entkt

A Kotlin port of [Ent](https://entgo.io/), Go's entity framework. Declare your
entities in a Kotlin DSL, run code generation, and get typed data classes,
query builders, and repositories that talk to a pluggable `Driver`.

This project is under active development — see the module READMEs for
details on each component, and [Roadmap](#roadmap) for what's missing.
For guides, see the [documentation](docs/index.md).

## Overview

```kotlin
// 1. Declare a schema (compile-time source of truth)
class User : EntSchema("users") {
    override fun id() = EntId.uuid()

    val name = string("name").minLen(1).maxLen(64)
    val email = string("email").unique()
    val age = int("age").optional().min(0).max(150)
    val active = bool("active").default(true)

    val posts = hasMany<Post>("posts")
}
```

```kotlin
// 2. Use the generated code
val client = EntClient(InMemoryDriver())  // or PostgresDriver(dataSource)

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
client.withTransaction { tx ->
    val bob = tx.users.create { name = "Bob"; email = "bob@example.com" }.save()
    tx.posts.create { title = "Hello"; authorId = bob.id }.save()
}
```

See [`example-demo/src/main/kotlin/example/demo/Demo.kt`](example-demo/src/main/kotlin/example/demo/Demo.kt)
for a full end-to-end tour, runnable with `./gradlew :example-demo:run`.

## Module layout

| Module | Description |
|---|---|
| [`:schema`](schema/README.md) | Declarative schema DSL — `EntSchema`, field/edge/index builders, `FieldType` |
| [`:runtime`](runtime/README.md) | `Driver` interface, `InMemoryDriver`, `EntitySchema`, query `Predicate` hierarchy |
| [`:codegen`](codegen/README.md) | KotlinPoet-based generator: entity classes, create/update/query builders, repos, `EntClient` |
| [`:migrations`](migrations/README.md) | Driver-agnostic schema diffing and migration planning |
| [`:gradle-plugin`](gradle-plugin/README.md) | `entkt` Gradle plugin registering `generateEntkt` and `generateMigrationFile` tasks |
| [`:postgres`](postgres/README.md) | JDBC driver for PostgreSQL with DDL emission, predicate-to-SQL lowering, introspection, and migration rendering |
| `:example-demo` | Executable demo of the full API against `InMemoryDriver` |
| [`:example-spring`](example-spring/README.md) | Spring Boot REST API example with Postgres, Flyway-applied SQL migrations, lifecycle hooks, and friendship management |

## Roadmap

Things that are **not yet implemented**, roughly in order of severity:

### Driver capabilities
- **More drivers.** Only `InMemoryDriver` and `PostgresDriver` exist today.
  No SQLite, MySQL, etc.
- **Observability.** No logging, metrics, or query-lifecycle hooks on the
  driver interface.

### Schema & DDL
- **Exotic column types.** No JSON/JSONB, arrays, enums (as PG enum types),
  hstore, or composites.

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
