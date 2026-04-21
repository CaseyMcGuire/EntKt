# Migrations

entkt includes a hybrid migration system: **dev mode** auto-applies
schema changes against a live database, while **prod mode** generates
reviewable SQL files from a committed snapshot.

## Overview

The migration system diffs two `NormalizedSchema` values -- a canonical
representation of your database schema. These can be derived from:

1. **Entity schemas** -- the desired state from your code
2. **Live DB introspection** -- what the database actually has (dev mode)
3. **A JSON snapshot** -- committed to version control (prod mode)

This gives one diff algorithm for both modes.

## Dev Mode

Dev mode introspects your live database, computes the diff, and applies
safe additive changes in a single transaction.

```kotlin
val migrator = PostgresMigrator.create(dataSource)

val result = migrator.migrate(
    schemas = listOf(User.SCHEMA, Post.SCHEMA),
)
// result.applied = [CreateTable("users"), AddIndex("users", ["email"]), ...]
```

Dev mode is all-or-nothing: if any manual (destructive) operations are
detected, it fails before applying anything.

## Prod Mode

Prod mode diffs your schemas against a committed JSON snapshot and
generates a versioned SQL file. No live database connection is needed.

```kotlin
val migrator = PostgresMigrator.planner()

val plan = migrator.plan(
    schemas = listOf(User.SCHEMA, Post.SCHEMA),
    snapshotPath = Paths.get("db/schema_snapshot.json"),
    outputDir = Paths.get("db/migrations"),
    description = "add_posts_table",
)
// plan.filePath = db/migrations/V20260411120000000__add_posts_table.sql
```

After generating a migration, the snapshot is advanced to reflect the new
desired state.

### Bootstrapping an Existing Database

If no snapshot exists but a live database is available, `plan()` diffs
against the live schema. This lets you adopt migrations on a database
that already has tables without generating redundant `CREATE TABLE`
statements:

```kotlin
val migrator = PostgresMigrator.create(dataSource)  // has introspector

val plan = migrator.plan(
    schemas = listOf(User.SCHEMA, Post.SCHEMA),
    snapshotPath = Paths.get("db/schema_snapshot.json"),  // does not exist yet
    outputDir = Paths.get("db/migrations"),
    description = "initial",
)
// If DB already matches schemas: no migration file, just creates the snapshot
```

## Safe vs Manual Operations

V1 auto-applies only safe additive operations:

| Operation | Auto-applied? | Notes |
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

Manual operations are detected and reported but never auto-applied.

## Handling Manual Operations

### FAIL Mode (default)

If manual ops are detected, `plan()` throws
`ManualMigrationRequiredException` with a list of what needs to be
resolved. No migration file is emitted and the snapshot is not advanced.

```kotlin
try {
    migrator.plan(schemas, snapshotPath, outputDir, "changes")
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
    schemas, snapshotPath, outputDir, "mixed_changes",
    manualMode = ManualMode.ACKNOWLEDGE_AND_ADVANCE,
)
```

The generated file looks like:

```sql
-- entkt migration V20260411130000000
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

The `MigrationRunner` refuses to apply files that still contain the
`!! MANUAL STEPS REQUIRED !!` marker. You must complete the manual steps
and remove the marker before deploying.

## Applying Migrations in Production

The `MigrationRunner` applies versioned SQL files and tracks them in a
`schema_migrations` table:

```kotlin
val runner = PostgresMigrator.runner(dataSource)
val result = runner.applyPending(Paths.get("db/migrations"))
// result.applied = ["V20260411120000000", "V20260411130000000"]
```

Key behaviors:

- Files are sorted by version (timestamp-based, with numeric suffix
  awareness for collision avoidance)
- Already-applied migrations are skipped
- **Checksum verification**: if a previously applied migration file has
  been modified, the runner throws `ChecksumMismatchException`
- **CRLF tolerance**: checksums are computed on LF-normalized content,
  with backwards compatibility for pre-normalization checksums
- **Manual step guard**: files containing the `!! MANUAL STEPS REQUIRED !!`
  marker are rejected with `UnresolvedManualStepsException`

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

Plain SQL, up-only. Named `V{YYYYMMDDHHmmssSSS}__{description}.sql`.

- **No `IF NOT EXISTS`** in generated files -- versioned migrations should
  fail loudly on drift
- Dev mode uses `IF NOT EXISTS` for idempotent application
- Description is slugified (non-alphanumeric characters replaced with `_`)
- Version collision protection: if the timestamp already exists in the
  output directory, a `_001`, `_002`, etc. suffix is appended

## Gradle Plugin: `generateMigrationFile` Task

If you're using the entkt Gradle plugin, the `generateMigrationFile` task wraps
the prod-mode planner so you don't need to write any Kotlin to generate
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
6. In production: `runner.applyPending(migrationDir)`
