# :migrations

Driver-agnostic schema diffing and migration planning.

entkt plans migrations and emits reviewed SQL. It does **not** apply
migrations at runtime; clients are expected to use Flyway, Liquibase,
their deployment system, or another SQL migration runner.

## Planning mode

Diffs desired schemas against the latest committed `.schema.json`
snapshot in the migrations directory and generates a versioned SQL
migration file. Run via the Gradle task:

```bash
./gradlew generateMigrationFile -Pdescription="add user email"
```

The generated file contains auto-applicable DDL statements. Destructive
operations are written as comments requiring manual review.

## Optional bootstrap introspection

If no snapshot exists yet, a planner wired to a live database can
introspect the current schema and use that as the initial baseline:

```kotlin
val migrator = PostgresMigrator.plannerWithIntrospection(dataSource)
val plan = migrator.plan(EntClient.SCHEMAS, outputDir = Paths.get("db/migrations"))
```

This is for migration generation only. Applying the resulting SQL is
still the client's responsibility.
