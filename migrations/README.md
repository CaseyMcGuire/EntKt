# :migrations

Driver-agnostic schema diffing, migration planning (prod), and auto-apply (dev).

## Dev mode

Introspects the live database, diffs against the desired schemas, and applies
additive operations in a single transaction. No migration files or version
tracking — the DB is brought directly in sync with your schemas.

```kotlin
val migrator = PostgresMigrator.create(dataSource)
migrator.migrate(EntClient.SCHEMAS)
```

If destructive changes (column drops, type changes) are detected,
`migrate()` throws `ManualMigrationRequiredException` before applying
anything.

## Prod mode

Diffs desired schemas against a committed `.schema.json` snapshot and
generates a versioned SQL migration file. Run via the Gradle task:

```bash
./gradlew generateMigrationFile -Pdescription="add user email"
```

The generated file contains auto-applicable DDL statements. Destructive
operations are written as comments requiring manual review.

## MigrationRunner

Applies versioned migration files to a live database, tracking applied
versions in a `schema_migrations` table.

```kotlin
val runner = PostgresMigrator.runner(dataSource)
runner.run(migrationsDir)
```
