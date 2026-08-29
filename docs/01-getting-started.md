# Getting Started

## Prerequisites

- JDK 17+
- Gradle 8+
- Docker (for Postgres tests only)

## Project Setup

entkt is organized as a multi-module Gradle project. A consumer application
typically depends on `:schema` (compile-time DSL), `:runtime` (driver
interface), and `:postgres` for storage.

The `:gradle-plugin` module provides a Gradle plugin that wires code
generation into your build automatically.

### Using the Gradle plugin

The plugin ID is `"io.entkt"`:

```kotlin
// settings.gradle.kts
pluginManagement {
    repositories {
        mavenLocal()
        gradlePluginPortal()
    }
}
```

```kotlin
// build.gradle.kts
plugins {
    id("io.entkt") version "0.1.0-SNAPSHOT"
}

repositories {
    mavenLocal()
    mavenCentral()
}

entkt {
    packageName.set("com.example.ent")
    // Optional: JSON mapper for typed json(...) columns — "kotlinx" (default)
    // or "jackson" (pair with io.entkt:jackson's JacksonJsonCodec on the driver).
    // jsonMapper.set("jackson")
    // Optional: generate the read-only ent viewer bridge (see the Ent Viewer
    // guide); requires io.entkt:ent-viewer-core on the implementation classpath
    // (plus io.entkt:ent-viewer-spring for Spring Boot auto-mounting).
    // viewer.set(true)
}

dependencies {
    schemas(project(":schema"))  // your schema module
    implementation("io.entkt:runtime:0.1.0-SNAPSHOT")
    implementation("io.entkt:postgres:0.1.0-SNAPSHOT")
    implementation("io.entkt:migrations:0.1.0-SNAPSHOT")
}
```

The `schemas` configuration is created by the plugin. It puts your schema
classes on the codegen classpath and also adds them to `implementation`
so generated code can reference schema types (e.g. enum classes).

**Schemas must live in a separate module.** The codegen task needs
compiled schema classes on its classpath before it can generate code.
If schemas are in the same module as the generated output, Gradle hits a
circular dependency (`compileKotlin` → `generateEntkt` → `compileKotlin`).
A typical project structure:

```
my-project/
  schema/                # EntSchema classes + entkt:schema dependency
  app/                   # applies id("io.entkt"), schemas(project(":schema"))
```

The plugin registers the following tasks:

- **`generateEntkt`** — Scans the `schemas` classpath for `EntSchema`
  classes, generates entity classes into `build/generated/entkt/`, adds
  them to the `main` source set, and runs automatically before
  `compileKotlin`.
- **`validateEntSchemas`** — Validates the schema graph (finalization,
  cross-schema constraints, relation-name uniqueness) and prints
  structured diagnostics. See [Schema Inspection](#schema-inspection).
- **`explainEntSchemas`** — Prints the resolved relational shape of all
  schemas. Supports `-Pformat=text|json|sql` and `-Pfilter=`. See
  [Schema Inspection](#schema-inspection).

entkt generates migration SQL but does not apply it. Use Flyway,
Liquibase, or your deployment system to execute the generated files.

### Without the plugin

You can also invoke codegen directly via the CLI entry point
(`entkt.codegen.GenerateMainKt`). See `:integration-tests`'s
`build.gradle.kts` for this approach — it registers a `JavaExec` task
that scans the classpath for `EntSchema` classes:

```kotlin
val generateEntkt = tasks.register<JavaExec>("generateEntkt") {
    classpath = codegenRunner
    mainClass.set("entkt.codegen.GenerateMainKt")
    args("com.example.ent", generatedDir.get().asFile.absolutePath)
}
```

## Defining Your First Schema

Create a Kotlin class that extends `EntSchema`:

```kotlin
import entkt.schema.*

class User : EntSchema("users", clientName = "users") {
    override fun id() = EntId.uuid()

    val name by string("name").minLength(1).maxLength(64)
    val email by string("email").unique()
    val age by int("age").nullable().min(0).max(150)
    val active by bool("active").default(true)
}
```

This declares a `users` table with a UUID primary key, a required `name`
with length constraints, a unique `email`, a nullable `age`, and a
boolean `active` that defaults to `true`.

## Using the Generated Code

After code generation, you get typed entity classes, mutation drafts, and
an `EntClient`:

```kotlin
import com.example.ent.*
import entkt.postgres.PostgresDriver
import org.postgresql.ds.PGSimpleDataSource

fun main() {
    // Create a client with any DatabaseDriver implementation
    val dataSource = PGSimpleDataSource().apply {
        setURL("jdbc:postgresql://localhost:5432/mydb")
        user = "myuser"
        password = "mypassword"
    }
    val client = EntClient(PostgresDriver(dataSource))
    val viewerContext = ViewerContext(Viewer.User(currentUserId()))

    // Create — saveAndLoad(viewerContext) returns MutationResult<User>
    val alice = client.users.create {
        name = "Alice"
        email = "alice@example.com"
        age = 30
        active = true
    }.saveAndLoad(viewerContext).getOrThrow()

    // Query — all(viewerContext) returns ReadResult<List<User>>
    val adults = client.users.query {
        where(User.age gte 18)
        orderBy(User.age.desc())
    }.all(viewerContext).getOrThrow()

    // Update — save(viewerContext) returns MutationResult<Unit>
    client.users.update(alice.id) {
        age = 31
    }.save(viewerContext).getOrThrow()

    // Delete — idempotent; MutationResult<Unit>
    client.users.delete(viewerContext, alice).getOrThrow()
}
```

`create { ... }` and `update(id) { ... }` return single-use mutation objects.
You can retain either operation and apply more draft changes before choosing a
terminal:

```kotlin
val creation = client.users.create {
    name = "Alice"
    email = "alice@example.com"
}

if (includeAge) {
    creation.configure { age = 30 }
}

val alice = creation.saveAndLoad(viewerContext).getOrThrow()

val update = client.users.update(alice.id) { age = 31 }
if (renameAlice) {
    update.configure { name = "Alice Smith" }
}
update.save(viewerContext).getOrThrow()
```

Create-draft fields are nullable while the input is incomplete, so reading an
unspecified field returns `null`. Use `isSet(User.age)` when application logic
must distinguish an omitted value from an explicit `age = null`. The first
`save(viewerContext)` or `saveAndLoad(viewerContext)` consumes the mutation;
later configuration or save attempts throw
`EntMutationAlreadyConsumedException`.

Every data operation returns an exhaustive result — `ReadResult<T>` for
reads, `MutationResult<T>` for writes — that callers either match with
`when` (`Success` / `Failed`) or project with `.getOrThrow()`, which
throws the stored typed exception. See [Queries](04-queries.md) and
[Privacy](06-privacy.md) for the full contract.

For a full working example wired up with Postgres, Flyway-applied
migrations, and lifecycle hooks, see [`:example-spring`](../example-spring/README.md)
— a runnable Spring Boot REST API.

## Generated API

For a `User` schema, entkt generates the public types you use to read and
write users:

| Surface | Purpose |
|---------|---------|
| `User` | Typed entity properties and query columns such as `User.name` and `User.age` |
| `UserCreateDraft` | Mutable create input returned through `client.users.create { ... }`; the resulting `CreateMutation` supplies `configure`, `save`, and `saveAndLoad` |
| `UserUpdateDraft` | Mutable update input configured through `client.users.update(id) { ... }`; the resulting `UpdateMutation` supplies `configure`, `save`, and `saveAndLoad` |
| `UserQuery` | Filtering, ordering, pagination, traversal, edge loading, and result-bearing read terminals |
| `UserRepo` | Entry points such as `create`, `update`, `query`, `findById`, and the delete methods |
| Privacy and validation rule types | Typed contexts and scopes for application policies |
| `EntClient` | The application entry point containing every generated repository |

The generated file layout and storage adapters are implementation details;
application code should use these public types through `EntClient`.

## Schema Inspection

The `validateEntSchemas` and `explainEntSchemas` tasks let you inspect
the resolved relational shape of your schema graph without running
codegen or connecting to a database.

### Validate

```bash
./gradlew validateEntSchemas
```

Runs the full validation pipeline — finalization, cross-schema
constraints, member-name collisions, relation-name uniqueness — and
prints structured diagnostics:

```
Schema validation passed (4 schemas)
```

On failure, each error is listed:

```
Schema validation failed:
  - Schema 'Post': edge 'author' references unregistered target schema 'User'
```

### Explain

```bash
./gradlew explainEntSchemas
```

Prints the resolved shape of every schema — columns, foreign keys,
edges, and indexes (including synthesized ones):

```
Schema: Post
Table: posts
Id: LONG (AUTO_LONG)

Fields:
| Name       | Type   | Attributes              |
|------------|--------|-------------------------|
| title      | STRING | NOT NULL                |
| published  | BOOL   | NOT NULL, DEFAULT false |

Foreign Keys:
| Column    | References | Nullable | On Delete | Source Edge |
|-----------|------------|----------|-----------|-------------|
| author_id | users.id   | NOT NULL | RESTRICT  | author      |

Edges:
| Name   | Kind      | Target | Details                     |
|--------|-----------|--------|-----------------------------|
| author | belongsTo | User   | fk=author_id, inverse=posts |
```

#### Output formats

Use `-Pformat=` to choose the output format:

```bash
./gradlew explainEntSchemas -Pformat=text    # default, human-readable tables
./gradlew explainEntSchemas -Pformat=json    # deterministic JSON, suitable for diffing
./gradlew explainEntSchemas -Pformat=sql     # full DDL (CREATE TABLE, indexes, FKs)
```

The SQL format renders all CREATE TABLE statements first, then indexes,
then foreign key constraints, so the output is directly runnable against
a fresh database.

#### Filtering

Use `-Pfilter=` to show only schemas matching a name or table
(case-insensitive substring match):

```bash
./gradlew explainEntSchemas -Pfilter=Post
./gradlew explainEntSchemas -Pformat=json -Pfilter=user
```

When `--filter` is combined with `--format=sql`, the output includes a
warning that it is a partial DDL excerpt and may reference tables not
shown.
