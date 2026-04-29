# Migrations

entkt plans migrations and generates versioned SQL files. Applying
those files is intentionally outside entkt's scope.

Use entkt to:

- diff desired schemas against a committed planning baseline
- generate reviewed up-only SQL migration files
- optionally bootstrap planning from a live database when no baseline exists yet

Use your migration runner of choice to apply the SQL:

- Flyway
- Liquibase
- deployment-managed SQL execution
- another in-house migration system

## Overview

The migration system diffs two `NormalizedSchema` values -- a canonical
representation of your database schema. These can be derived from:

1. **Entity schemas** -- the desired state from your code
2. **Live DB introspection** -- an optional bootstrap baseline when no snapshot exists yet
3. **A JSON snapshot** -- committed to version control alongside migration SQL

The key point is that entkt uses these inputs to **plan** SQL. It does
not apply SQL to a database at runtime.

## Planning Mode

Planning diffs your schemas against the latest committed snapshot in the
migrations directory and generates a versioned SQL file. No live database
connection is needed in the normal case.

```kotlin
val migrator = PostgresMigrator.planner()

val plan = migrator.plan(
    schemas = listOf(User.SCHEMA, Post.SCHEMA),
    outputDir = Paths.get("db/migrations"),
    description = "add_posts_table",
)
// plan.filePath = db/migrations/V3__add_posts_table.sql
```

The planner reads the highest-numbered `V*.schema.json` file in
`outputDir` as the baseline, writes `V{N+1}__description.sql`, and
advances the paired `V{N+1}.schema.json` snapshot.

### Bootstrapping an Existing Database

If no snapshot exists but a live database is available, a bootstrap-aware
planner can introspect the live schema and use it as the initial
baseline. This lets you adopt entkt migration planning on a
database that already has tables without generating redundant
`CREATE TABLE` statements:

```kotlin
val migrator = PostgresMigrator.plannerWithIntrospection(dataSource)

val plan = migrator.plan(
    schemas = listOf(User.SCHEMA, Post.SCHEMA),
    outputDir = Paths.get("db/migrations"),
    description = "initial",
)
// If DB already matches schemas: no SQL file, just creates the first snapshot
```

## Safe vs Manual Operations

V1 auto-generates only safe additive operations:

| Operation | Auto-generated? | Notes |
|-----------|:---:|-------|
| `CreateTable` | Yes | Columns + PK only; indexes and FKs are separate ops |
| `AddColumn` (nullable) | Yes | |
| `AddColumn` (NOT NULL) | No | Requires a default or backfill strategy |
| `AddIndex` | Yes | |
| `AddForeignKey` | Yes | Can fail if existing rows violate the constraint |
| `DropTable` | No | Data loss risk |
| `DropColumn` | No | Data loss risk |
| `AlterColumnType` | No | May require data transformation |
| `SetColumnNotNull` | No | May fail on existing NULLs |
| `DropColumnNotNull` | No | Semantic change |
| `AlterPrimaryKey` | No | Requires DROP + re-CREATE |
| `DropIndex` | No | |
| `DropForeignKey` | No | |

Manual operations are detected and reported but never auto-generated.

## Handling Manual Operations

### FAIL Mode (default)

If manual ops are detected, `plan()` throws
`ManualMigrationRequiredException` with a list of what needs to be
resolved. No migration file is emitted and the snapshot is not advanced.

```kotlin
try {
    migrator.plan(schemas, outputDir, "changes")
} catch (e: ManualMigrationRequiredException) {
    for (op in e.ops) {
        println("Manual: $op")
    }
}
```

### ACKNOWLEDGE_AND_ADVANCE Mode

When you need to make progress despite manual ops, use
`ManualMode.ACKNOWLEDGE_AND_ADVANCE`. This generates the migration file
with auto ops and includes a checklist of manual steps at the top:

```kotlin
val plan = migrator.plan(
    schemas, outputDir, "mixed_changes",
    manualMode = ManualMode.ACKNOWLEDGE_AND_ADVANCE,
)
```

The generated file looks like:

```sql
-- entkt migration V4
--
-- !! MANUAL STEPS REQUIRED !!
-- The following operations were detected but NOT included in this file.
-- You must handle them separately before applying this migration.
--
-- [ ] DropColumn: posts.legacy_field
-- [ ] AlterColumnType: users.age (integer -> bigint)
--
-- Auto-applied operations follow.

ALTER TABLE "posts" ADD COLUMN "subtitle" text;
```

When this mode is used, the generated SQL file is intentionally not
ready for blind application. You must complete the manual steps and then
apply the finalized SQL with your migration runner.

## Applying Migrations

entkt does not apply migrations.

Clients should use a migration runner that matches their operational
environment. Common choices:

- Flyway
- Liquibase
- platform/deployment SQL execution

The example Spring app uses Flyway, but that is just one integration path.

## Schema Snapshots

The snapshot is a JSON serialization of `NormalizedSchema`, committed to
version control alongside your migration files. It records:

- All table definitions (columns, types, nullability, primary keys)
- Indexes with their storage-level names (from introspection)
- Foreign keys with their constraint names (from introspection)

Deterministic ordering (tables by name, columns in declaration order,
indexes and FKs sorted) prevents snapshot churn across commits.

When a snapshot is advanced, storage-level names (real index names, FK
constraint names) are preserved from the previous snapshot and/or live
introspection. This ensures that future `DropIndex` and `DropForeignKey`
operations reference the actual database-side names.

## Migration File Format

Plain SQL, up-only. Named `V{N}__{description}.sql`.

- **No `IF NOT EXISTS`** in generated files -- versioned migrations should
  fail loudly on drift
- Description is slugified (non-alphanumeric characters replaced with `_`)
- Versions are sequential (`V1`, `V2`, `V3`, ...), derived from the
  highest migration version already present in the output directory

## Gradle Plugin: `generateMigrationFile` Task

If you're using the entkt Gradle plugin, the `generateMigrationFile` task wraps
the migration planner so you don't need to write any Kotlin to generate
migrations:

```bash
./gradlew generateMigrationFile -Pdescription="add users table"
```

The task scans the `schemas` classpath for `EntSchema` objects, diffs
against the stored snapshot, and writes a versioned SQL file.

Migrations are written to `db/migrations/` by default. You can change
this in the `entkt` extension:

```kotlin
entkt {
    packageName.set("com.example.ent")
    migrationsDirectory.set(layout.projectDirectory.dir("src/main/resources/db/migrations"))
}
```

If no description is provided, it defaults to `"migration"`.

## Typical Workflow

1. Modify your `EntSchema` definitions
2. Run `./gradlew generateMigrationFile -Pdescription="describe your change"` to
   generate a migration file
3. Review the generated SQL
4. If manual ops are flagged: write a manual migration, then re-run with
   `ACKNOWLEDGE_AND_ADVANCE`
5. Commit the migration file and updated snapshot
6. Apply the SQL with Flyway, Liquibase, or your deployment system
