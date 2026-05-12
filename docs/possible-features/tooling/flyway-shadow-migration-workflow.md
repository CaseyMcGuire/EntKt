# RFC: Flyway Shadow Migration Workflow

## Status

Implemented in the `:flyway` module. See `FlywayMigrationWorkflow` for
the API and `FlywayMain` for the CLI entry point.

## Summary

Add a Flyway-backed implementation of a broader shadow-database
migration planning architecture. In this workflow, entkt starts a
disposable Docker-backed Postgres database, replays Flyway migrations
into it, and generates the next Flyway migration by diffing that
migrated shadow database against the current entkt schema model.

The workflow:

```text
existing Flyway migrations
        |
        v
apply to shadow database
        |
        v
introspect migrated database state
        |
        v
diff against entkt schema state
        |
        v
write next Flyway migration file
        |
        v
optionally apply generated migration and verify no drift remains
```

This restores dev convenience without reintroducing runtime schema
application into `PostgresDriver` or `EntClient`, and establishes the
shape for future backends such as Liquibase or Atlas.

## Motivation

The current snapshot-based planner is the wrong long-term baseline for
projects that use an external migration runner. In this workflow, the
authoritative history is the runner's committed migration directory, and
the baseline is:

```text
the database produced by applying every committed Flyway migration
```

This workflow lets entkt answer:

```text
What SQL must be added so the migration-runner-produced database matches my entkt schemas?
```

This Flyway-backed workflow is a good first backend for applications that want:

- Flyway as the migration runner
- generated migration SQL for additive schema changes
- explicit review of generated SQL before commit
- drift detection against the actual migration history
- no hidden schema mutation during application startup

The broader architectural idea is not Flyway-specific. It is:

- replay committed migration history into a disposable database
- introspect the resulting schema
- diff that schema against the entkt desired schema
- generate the next migration artifact in the runner's native format

## Module Shape

Create a separate module, tentatively:

```text
:flyway
```

or, if the module also owns a Gradle plugin:

```text
:flyway-gradle-plugin
```

This module can depend on Flyway and Postgres-specific migration tooling
without pulling those dependencies into `:migrations`, `:runtime`, or the
base Gradle plugin.

Core module responsibilities stay separate:

- `:migrations` owns schema diffing and migration operation models
- `:postgres` owns Postgres introspection and SQL rendering
- the Flyway workflow owns applying existing Flyway migrations to a
  shadow database and writing Flyway-compatible files

Longer term, a runner-agnostic shadow-planning core could sit underneath
multiple backend-specific modules:

- `:flyway`
- later `:liquibase`
- later `:atlas`

## Non-Goals

- Do not apply migrations to the application database.
- Do not bring back runtime auto-migration.
- Do not make `PostgresDriver.register()` mutate the database by default.
- Do not use committed `.schema.json` snapshots in this workflow.
- Do not make Flyway the only supported long-term backend.
- Do not support every Flyway feature in V1.
- Do not support destructive changes without explicit manual review.
- Do not infer or create production database URLs.

## Terminology

**Application database**: the database the app uses at runtime.

**Shadow database**: a disposable database used by the workflow to apply
Flyway migrations and inspect the resulting schema.

**Desired schema**: the normalized schema derived from entkt schema
classes.

**Current schema**: the normalized schema introspected from the shadow
database after committed Flyway migrations have run.

## Proposed Gradle Surface

Possible extension:

```kotlin
entktFlyway {
    schemaPackage.set("com.example.schema")
    migrationsDirectory.set(layout.projectDirectory.dir("db/migrations"))
    excludeTables.add("flyway_schema_history")

    shadowDocker {
        image.set("postgres:16")
        databaseName.set("entkt_shadow")
        user.set("postgres")
        password.set("postgres")
    }

    verifyGeneratedMigration.set(true)
}
```

Possible CLI usage:

```bash
./gradlew generateFlywayMigration -Pdescription=add_posts
./gradlew validateFlywayMigrations
```

For CI, Docker/image settings should also be configurable through
environment variables:

```text
ENTKT_FLYWAY_POSTGRES_IMAGE
ENTKT_FLYWAY_POSTGRES_DB
ENTKT_FLYWAY_POSTGRES_USER
ENTKT_FLYWAY_POSTGRES_PASSWORD
```

V1 manages all non-Flyway tables in PostgreSQL's `public` schema unless
explicitly excluded. V1 should treat `excludeTables` as exact
table-name matches only. Pattern, glob, or regex matching can be a
later extension.

## Tasks

### `validateFlywayMigrations`

Runs all existing Flyway migrations against the shadow database,
introspects the result, diffs it against entkt schemas, and fails on
schema graph errors, Flyway execution errors, or any drift (additive or
manual).

Expected use:

```bash
./gradlew validateFlywayMigrations
```

This task should not write migration files.

### `generateFlywayMigration`

Runs existing Flyway migrations against the shadow database, diffs the
result against entkt schemas, and writes the next Flyway migration file
when changes are detected.

Expected use:

```bash
./gradlew generateFlywayMigration -Pdescription=add_posts
```

If no changes are detected, the task should report that the schema is up
to date and write nothing.

Manual/destructive drift fails generation by default
(`ManualMode.FAIL`). Pass `--manual-mode=ACKNOWLEDGE_AND_ADVANCE` to
write a migration file that includes a manual checklist and a failing
SQL statement that blocks Flyway application until the user completes
the manual steps.

## Workflow

### 1. Validate Ent Schemas

Before touching the shadow database:

1. scan schema classes
2. finalize schema declarations
3. run `SchemaInspector.validate()`
4. fail on schema errors

This catches DSL and graph issues before Flyway runs.

### 2. Prepare Shadow Database

V1 should use a disposable Docker-backed Postgres database for every
run. The workflow should:

- start a fresh container from a configured Postgres image
- create or configure the target database inside that container
- wait for readiness before running Flyway
- destroy the container after validation or generation completes

This avoids `flyway.clean()` against a persistent database and removes
the need for database-name heuristics or disposable-database override
flags in the default path.

### 3. Run Existing Flyway Migrations

Configure Flyway with:

- the JDBC URL exposed by the Docker-backed Postgres container
- the configured user/password
- `locations`

Then:

```text
flyway.migrate()
```

V1 should not support reusing a persistent shadow database. Each run
starts from a fresh container so diff and verification output stay
deterministic.

### 4. Introspect Shadow Database

Build the managed table set from PostgreSQL's `public` schema plus
optional exclusions:

- start with all tables in `public`
- remove the Flyway history table
- remove any `excludeTables`

```text
managedTables =
  introspectTableNames(schema = public)
    .minus(flywayHistoryTable)
    .minus(excludeTables)
```

Then call `PostgresIntrospector.introspect(managedTables)`.

This keeps removed entkt tables visible without reintroducing committed
schema snapshots. If a table still exists in `public` but is no longer
present in the desired entkt schema, it shows up as
destructive/manual drift.

V1 should assume entkt-managed tables live in PostgreSQL's `public`
schema. Tables outside `public` are ignored by the planner.

### 5. Diff Desired Against Current

Use the existing migration diff:

```text
current = introspected shadow schema
desired = NormalizedSchema.fromEntitySchemas(...)
diff = SchemaDiffer.diff(desired, current)
```

The workflow should preserve the current additive/manual split:

- additive ops can be rendered into SQL
- destructive or complex ops require manual migration review

For this Flyway workflow, additive ops are the default generation
surface. Destructive/manual drift is ignored unless the user explicitly
opts into a stricter destructive-drift mode.

### 6. Write Next Flyway File

Write the next versioned Flyway migration in the configured migrations
directory:

```text
V{next}__{description}.sql
```

V1 can support numeric sequential versions:

```text
V1__initial.sql
V2__add_posts.sql
V3__add_comments.sql
```

Open question:

- Should timestamp versions be supported as a strategy?

Possible later extension:

```kotlin
versionStrategy.set(FlywayVersionStrategy.Sequential)
versionStrategy.set(FlywayVersionStrategy.Timestamp)
```

### 7. Verify Generated Migration

When `verifyGeneratedMigration` is enabled on
`generateFlywayMigration`:

1. run Flyway migrate again so the new file is applied
2. re-introspect the shadow database
3. diff desired against current
4. fail if any additive drift remains

This is the main quality bar for the workflow. Generated SQL should not
just look plausible; it should actually bring the migrated database to
the desired schema state for the additive surface being generated.

Verification proves that:

- the generated migration closes all additive drift
- the migrated shadow database matches the desired schema after the
  new migration is applied

## Manual Operations

Default behavior (`ManualMode.FAIL`):

```text
manual ops detected -> generation fails with ManualMigrationRequiredException
```

Optional behavior (`ManualMode.ACKNOWLEDGE_AND_ADVANCE`):

The generated Flyway file includes a manual checklist and a failing SQL
statement that prevents Flyway from applying the migration until the
user completes the manual steps:

```sql
-- !! MANUAL STEPS REQUIRED !!
-- [ ] DropColumn: posts.legacy_field
DO $$BEGIN RAISE EXCEPTION 'entkt: manual migration steps have not been completed'; END$$;
```

The user must replace the `RAISE EXCEPTION` statement with their manual
migration SQL before applying.

## Drift Semantics

`validateFlywayMigrations` fails when any drift is detected:

- committed Flyway migrations produce a table not matching entkt schema
- entkt schema has additive changes not represented in Flyway yet
- schema graph validation fails

The output distinguishes:

- additive missing migration operations
- manual operations requiring user-authored SQL
- invalid entkt schema declarations
- Flyway execution errors

## Supported Flyway Features In V1

Support:

- filesystem migration locations
- versioned SQL migrations
- default Flyway history table

Defer:

- Java-based Flyway migrations
- repeatable migrations
- SQL callbacks
- placeholders
- multiple database schemas
- undo migrations
- non-Postgres databases

Repeatable migrations are especially important to defer because they
can change the post-migration schema without producing a simple next
version number story.

V1 rejects unsupported Flyway inputs before starting the shadow
database:

- fail if the migrations directory contains repeatable migrations
  (`R__*.sql`)
- fail if the migrations directory contains SQL callbacks
  (`beforeMigrate.sql`, `afterMigrate.sql`, etc.)
- fail if Docker is unavailable or the Postgres container cannot be
  started cleanly

## Failure Modes

The task should fail before writing a migration file when:

- schema validation fails
- Docker is unavailable
- shadow container startup fails
- unsupported Flyway artifact types are present (repeatable migrations,
  SQL callbacks)
- Flyway migration fails
- introspection fails
- manual ops exist and `ManualMode.FAIL` is active (the default)

The task should delete a partially written generated file if final
verification fails.

## Relationship To Existing Planner

This workflow replaces snapshot-based migration planning.

The architectural center should be shadow-database planning itself
rather than Flyway as a product. Flyway is the first backend, not the
final abstraction.

They can share:

- schema scanning
- `SchemaInspector.validate()`
- `NormalizedSchema.fromEntitySchemas(...)`
- `SchemaDiffer`
- `PostgresSqlRenderer`
- `PostgresIntrospector`

## Long-Term Direction

The migration-generation story should be:

- migration-runner history is the authoritative baseline
- entkt validates the schema graph and derives the desired schema model
- planning diffs the desired schema against the schema produced by
  replaying committed migrations in the shadow database

Flyway is the first concrete backend for that model. Future backends may
replay Liquibase changelogs, Atlas migration directories, or another
runner's history, but the planning architecture stays the same.

Committed `.schema.json` snapshots are removed from the main migration
workflow in favor of schema diffing against the migrated shadow database
state.

## Test Requirements

Before implementation, add tests for:

- task fails when schema validation fails
- task fails when Docker is unavailable
- task fails when the Postgres shadow container cannot be started
- existing Flyway migrations are applied to the shadow DB
- no drift produces no migration file
- additive drift writes the next Flyway migration
- generated migration version increments from existing files
- manual drift fails generation by default (ManualMode.FAIL)
- ACKNOWLEDGE_AND_ADVANCE writes a manual checklist with RAISE EXCEPTION
- verification applies the generated migration and confirms no additive drift
- verification failure deletes or marks the generated file as failed
- Flyway history table is ignored during entkt diffing
- excludeTables narrows the managed table set
- dropped entkt tables are detected from the managed `public` schema

## Open Questions

- Should this live in `:flyway` or a Gradle-plugin-specific module?
- Should the existing entkt Gradle plugin apply this workflow when Flyway
  is on the classpath, or should users opt into a separate plugin ID?
- Should timestamp-based Flyway versions be supported in V1?
- How should Flyway placeholders be represented in the Gradle extension?
- Should generated SQL include comments identifying the entkt schema
  version or generator version?
