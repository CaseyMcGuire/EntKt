# Getting Started

## Prerequisites

- JDK 17+
- Gradle 8+
- Docker (for Postgres tests only)

## Project Setup

entkt is organized as a multi-module Gradle project. A consumer application
typically depends on `:schema` (compile-time DSL), `:runtime` (driver
interface), and either `:postgres` or the in-memory driver for storage.

The `:gradle-plugin` module provides a Gradle plugin that wires code
generation into your build automatically.

### Using the Gradle plugin

The plugin ID is `"entkt"`:

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
    id("entkt") version "0.1.0-SNAPSHOT"
}

repositories {
    mavenLocal()
    mavenCentral()
}

entkt {
    packageName.set("com.example.ent")
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
  app/                   # applies id("entkt"), schemas(project(":schema"))
```

The plugin registers the following tasks:

- **`generateEntkt`** — Scans the `schemas` classpath for `EntSchema`
  classes, generates entity classes into `build/generated/entkt/`, adds
  them to the `main` source set, and runs automatically before
  `compileKotlin`.
- **`generateMigrationFile`** — Diffs schemas against the stored snapshot
  and writes a versioned SQL migration file. See [Migrations](migrations.md).
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
(`entkt.codegen.GenerateMainKt`). See `:example-demo`'s `build.gradle.kts`
for this approach — it registers a `JavaExec` task that scans the classpath
for `EntSchema` classes:

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

class User : EntSchema("users") {
    override fun id() = EntId.uuid()

    val name = string("name").minLen(1).maxLen(64)
    val email = string("email").unique()
    val age = int("age").optional().min(0).max(150)
    val active = bool("active").default(true)
}
```

This declares a `users` table with a UUID primary key, a required `name`
with length constraints, a unique `email`, an optional `age`, and a
boolean `active` that defaults to `true`.

## Using the Generated Code

After code generation, you get typed entity classes, builders, and
an `EntClient`:

```kotlin
import entkt.runtime.InMemoryDriver
import com.example.ent.*

fun main() {
    // Create a client with any Driver implementation
    val client = EntClient(InMemoryDriver())

    // Create
    val alice = client.users.create {
        name = "Alice"
        email = "alice@example.com"
        age = 30
        active = true
    }.save()

    // Query
    val adults = client.users.query {
        where(User.age gte 18)
        orderBy(User.age.desc())
    }.all()

    // Update
    val updated = client.users.update(alice) {
        age = 31
    }.save()

    // Delete
    client.users.delete(alice)
}
```

For a full working example, run `./gradlew :example-demo:run`.

## What Gets Generated

For each schema, the codegen emits:

| File | Purpose |
|------|---------|
| `User.kt` | Data class with typed properties, companion column refs (`User.name`, `User.age`), `SCHEMA` constant, `fromRow()` decoder |
| `UserMutation.kt` | Interface shared by Create and Update builders |
| `UserCreate.kt` | Builder with `.save()` for inserts |
| `UserUpdate.kt` | Builder with `.save()` for updates (omits immutable fields) |
| `UserQuery.kt` | Query builder with `.where()`, `.orderBy()`, `.all()`, edge traversal, eager loading |
| `UserRepo.kt` | Repository with `.create {}`, `.update() {}`, `.query {}`, `.byId()`, `.delete()` |
| `UserPrivacy.kt` | Privacy contexts, rules, WriteCandidate, and policy scope (see [Privacy](privacy.md)) |
| `EntClient.kt` | Single entry point holding all repos, constructed with a `Driver` |

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
  - Schema 'Post': reverse M2M edge 'users_posts' collides with a declared edge of the same name
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
