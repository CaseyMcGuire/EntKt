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

```kotlin
// build.gradle.kts
plugins {
    id("entkt")
}

entkt {
    packageName = "com.example.ent"
}

dependencies {
    implementation(project(":runtime"))
    implementation(project(":schema"))
    // For Postgres:
    implementation(project(":postgres"))
}
```

The plugin registers a `generateEntkt` task that:

1. Scans the classpath for `EntSchema` objects
2. Generates entity classes, builders, repos, and an `EntClient` into
   `build/generated/entkt/`
3. Adds that directory to the `main` source set
4. Runs automatically before `compileKotlin`

### Without the plugin

You can also invoke codegen directly via the CLI entry point
(`entkt.codegen.GenerateMainKt`). See `:example-demo`'s `build.gradle.kts`
for this approach — it registers a `JavaExec` task that scans the classpath
for `EntSchema` objects:

```kotlin
val generateEntkt = tasks.register<JavaExec>("generateEntkt") {
    classpath = codegenRunner
    mainClass.set("entkt.codegen.GenerateMainKt")
    args("com.example.ent", generatedDir.get().asFile.absolutePath)
}
```

## Defining Your First Schema

Create a Kotlin object that extends `EntSchema`:

```kotlin
import entkt.schema.*

object User : EntSchema() {
    override fun id() = EntId.uuid()

    override fun fields() = fields {
        string("name").minLen(1).maxLen(64)
        string("email").unique()
        int("age").optional().min(0).max(150)
        bool("active").default(true)
    }
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
| `EntClient.kt` | Single entry point holding all repos, constructed with a `Driver` |
