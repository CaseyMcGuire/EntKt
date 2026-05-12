# :gradle-plugin

Gradle plugins that wire entkt code generation and migration planning
into your build.

## Plugins

| Plugin ID | Extension | Purpose |
|-----------|-----------|---------|
| `entkt` | `entkt { }` | Schema codegen + validation |
| `entkt.flyway` | `entktFlyway { }` | Flyway migration generation + drift detection |

## Setup

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
    id("entkt.flyway") version "0.1.0-SNAPSHOT" // optional
}

repositories {
    mavenLocal()
    mavenCentral()
}

entkt {
    packageName.set("com.example.ent")
}

entktFlyway {
    migrationsDirectory.set(layout.projectDirectory.dir("db/migrations"))
}

dependencies {
    schemas(project(":schema"))  // your schema module

    // Codegen + migration tooling (runs in a separate JVM)
    entktCodegen("io.entkt:codegen:0.1.0-SNAPSHOT")
    entktCodegen("io.entkt:postgres:0.1.0-SNAPSHOT")
    entktCodegen("io.entkt:flyway:0.1.0-SNAPSHOT") // only if using entkt.flyway

    // Runtime dependencies for your application
    implementation("io.entkt:runtime:0.1.0-SNAPSHOT")
    implementation("io.entkt:postgres:0.1.0-SNAPSHOT")
    implementation("io.entkt:migrations:0.1.0-SNAPSHOT")
}
```

**Schemas must live in a separate module.** The codegen task needs
compiled schema classes on its classpath before it can generate code.
If schemas are in the same module as the generated output, Gradle hits a
circular dependency (`compileKotlin` -> `generateEntkt` -> `compileKotlin`).

## Tasks

### Base plugin (`entkt`)

- **`generateEntkt`** -- Scans the `schemas` classpath for `EntSchema`
  objects, generates entity classes into `build/generated/entkt/`, adds
  them to the `main` source set, and runs automatically before
  `compileKotlin`.
- **`validateEntSchemas`** -- Validates the schema graph (finalization,
  cross-schema constraints).
- **`explainEntSchemas`** -- Prints the resolved relational shape of all
  schemas. Supports `-Pformat=text|json` and `-Pfilter=<table>`.

### Flyway plugin (`entkt.flyway`)

- **`generateFlywayMigration`** -- Diffs a shadow database against your
  schemas and writes the next Flyway migration file. Supports
  `-Pdescription=<desc>` and `-PmanualMode=FAIL|ACKNOWLEDGE_AND_ADVANCE`.
- **`validateFlywayMigrations`** -- Checks for schema drift between your
  committed Flyway migrations and your entkt schemas. Exits non-zero if
  drift is detected.

The flyway plugin auto-applies the base `entkt` plugin, so you don't
need to apply both explicitly.

See [migrations docs](../docs/09-migrations.md) for the full workflow.

## Architecture

Both plugins run tooling in a separate JVM via `JavaExec` tasks, using
an isolated `entktCodegen` configuration. This keeps entkt's Kotlin
runtime off Gradle's plugin classloader and avoids kotlin-reflect version
conflicts.

The plugins create the `entktCodegen` and `schemas` configurations but
do not add any dependencies to them — you must add the codegen and
runtime artifacts yourself.
