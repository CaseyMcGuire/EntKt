# :gradle-plugin

`entkt` Gradle plugin that wires code generation and migration planning
into your build.

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

**Schemas must live in a separate module.** The codegen task needs
compiled schema classes on its classpath before it can generate code.
If schemas are in the same module as the generated output, Gradle hits a
circular dependency (`compileKotlin` -> `generateEntkt` -> `compileKotlin`).

## Tasks

- **`generateEntkt`** — Scans the `schemas` classpath for `EntSchema`
  objects, generates entity classes into `build/generated/entkt/`, adds
  them to the `main` source set, and runs automatically before
  `compileKotlin`.

- **`generateMigrationFile`** — Diffs schemas against the stored snapshot
  and writes a versioned SQL migration file.
  ```bash
  ./gradlew generateMigrationFile -Pdescription="add user email"
  ```

## Architecture

The plugin runs codegen and migration planning in a separate JVM via
`JavaExec` tasks, using an isolated `entktCodegen` configuration. This
keeps entkt's Kotlin runtime off Gradle's plugin classloader and avoids
kotlin-reflect version conflicts. The plugin auto-adds `io.entkt:codegen`
and `io.entkt:postgres` to `entktCodegen` at the matching plugin version.
