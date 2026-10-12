# EntKt

A Kotlin entity framework. Declare your entities in a Kotlin DSL, run code
generation, and get typed data classes, query builders, and repositories
that talk to a pluggable `DatabaseDriver`.

This project is under active development — see the module READMEs for
details on each component, and [Roadmap](#roadmap) for what's missing.
For guides, see the [documentation](docs/index.md).

Released artifacts are available from Maven Central. These docs describe the
current checkout (`0.1.0-alpha.3`); see [Getting Started](docs/01-getting-started.md#using-the-gradle-plugin)
for local publishing and consumer setup. Use a release's matching documentation
when depending on published artifacts.

## Overview

```kotlin
// 1. Declare a schema (compile-time source of truth)
class User : EntSchema("users", clientName = "users") {
    override fun id() = EntId.uuid()

    val name by string("name")
    val email by string("email").unique()
    val age by int("age").nullable()
    val active by bool("active").default(true)

    val posts by hasMany<Post>()
}
```

```kotlin
// 2. Register policies for the generated entities.
// These permissive policies are for this demo; replace them with your app's rules.
object UserPolicy : EntityPolicy<User, UserPolicyScope> {
    override fun configure(scope: UserPolicyScope) = scope.run {
        privacy {
            load(allowAll)
            create(allowAll)
            update(allowAll)
            delete(allowAll)
        }
    }
}

object PostPolicy : EntityPolicy<Post, PostPolicyScope> {
    override fun configure(scope: PostPolicyScope) = scope.run {
        privacy {
            load(allowAll)
            create(allowAll)
        }
    }
}

// 3. Use the generated code
val client = EntClient(PostgresDriver(dataSource)) {
    policies {
        users(UserPolicy)
        posts(PostPolicy)
    }
}
val viewerContext = ViewerContext(Viewer.User(currentUserId()))

val alice = client.users.create {
    name = "Alice"
    email = "alice@example.com"
    age = 30
    active = true
}.saveAndLoad(viewerContext).getOrThrow()   // save(viewerContext): MutationResult<Unit> when the entity isn't needed

val adults = client.users.query {
    where(User.active eq true and (User.age gte 18))
    orderBy(User.age.desc())
}.all(viewerContext).getOrThrow()

val authorsWithPublishedPosts = client.users.query {
    where(User.posts.has { where(Post.published eq true) })
}.all(viewerContext).getOrThrow()

// Eager loading
val usersWithPosts = client.users.query {
    where(User.active eq true)
    loadPosts {                        // batch-load posts for each user
        where(Post.published eq true)  // optional: filter the loaded edge
    }
}.all(viewerContext).getOrThrow()
usersWithPosts[0].edges.posts.requireLoaded()  // → List<Post> (throws EdgeNotLoadedException if loadPosts wasn't called)

// Every data operation returns an exhaustive result you can match on
// instead of projecting with getOrThrow():
when (val result = client.users.findById(viewerContext, alice.id)) {
    is ReadResult.Success -> result.value      // User? — null is authoritative absence
    is ReadResult.Failed -> result.exception   // typed: privacy denial, rejection, driver failure
}

// Delete by ID (true if this call deleted the row, false if already absent)
client.users.deleteById(viewerContext, alice.id).getOrThrow()

// Transactions
client.withTransaction { tx ->
    val bob = tx.users.create { name = "Bob"; email = "bob@example.com" }.saveAndLoad(viewerContext).orRollback()
    tx.posts.create { title = "Hello"; authorId = bob.id }.save(viewerContext).orRollback()
}.getOrThrow()
```

Privacy is fail-closed: ordinary viewers are denied unless a registered policy
allows the operation. `EntityPolicy` and `allowAll` are in
`entkt.runtime.privacy`; see [Privacy](docs/06-privacy.md) for application rules.

Field invariants belong in client-registered validation policies, not schema
declarations. Runtime helpers such as `minLength(UserWriteCandidate::name, 2)`
register through the existing validation DSL; see [Field Validation Rules](docs/07-validation.md#field-validation-rules).

See [`:example-spring`](example-spring/README.md) for a runnable Spring
Boot REST API example backed by Postgres.

## Module layout

| Module | Description |
|---|---|
| [`:schema`](schema/README.md) | Declarative schema DSL — `EntSchema`, field/edge/index builders, `FieldType` |
| [`:runtime`](runtime/README.md) | `DatabaseDriver` interface, `EntitySchema`, query `Predicate` hierarchy |
| [`:codegen`](codegen/README.md) | KotlinPoet-based generator: entity classes, create/update/query builders, repos, `EntClient` |
| [`:migrations`](migrations/README.md) | DatabaseDriver-agnostic schema diffing and migration planning |
| [`:gradle-plugin`](gradle-plugin/README.md) | EntKt Gradle plugin registering `generateEntkt` task |
| [`:postgres`](postgres/README.md) | JDBC driver for PostgreSQL with DDL emission, predicate-to-SQL lowering, introspection, and migration rendering |
| [`:example-spring`](example-spring/README.md) | Spring Boot REST API example with Postgres, Flyway-applied SQL migrations, lifecycle hooks, and friendship management |

## Roadmap

Things that are **not yet implemented**, roughly in order of severity:

### DatabaseDriver capabilities
- **More drivers.** Only `PostgresDriver` exists today. No SQLite,
  MySQL, etc.
- **Observability.** No logging, metrics, or query-lifecycle hooks on the
  driver interface.

### Schema & DDL
- **Exotic column types.** Typed JSON/JSONB is supported; native SQL arrays,
  enums (as PG enum types), hstore, and composites are not yet implemented.

## Building

```bash
./gradlew build          # compiles everything, runs all tests
./gradlew :postgres:test # runs the Testcontainers-backed Postgres tests
```

Requires JDK 17+. Running `:postgres:test` (and the integration test
suite) requires Docker.
