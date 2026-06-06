# Migrations

entkt generates versioned SQL migration files. Applying those files is
intentionally outside entkt's scope -- use Flyway, Liquibase, or your
deployment system.

The Flyway shadow workflow diffs a disposable Docker-backed Postgres
database against your schemas to generate the next migration file.
Your committed Flyway migration directory is the authoritative baseline.

## Safe vs Manual Operations

entkt auto-generates only safe additive operations:

| Operation | Auto-generated? | Notes |
|-----------|:---:|-------|
| `CreateExtension` | Yes | `CREATE EXTENSION IF NOT EXISTS` for a native column type (e.g. pgvector's `vector`); ordered before the table that needs it |
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

### FAIL Mode (default)

If manual ops are detected, generation throws
`ManualMigrationRequiredException`. No migration file is written.

### ACKNOWLEDGE_AND_ADVANCE Mode

Generates the migration file with auto ops and includes a checklist of
manual steps. In the Flyway workflow, a `RAISE EXCEPTION` guard
prevents Flyway from applying the file until the manual steps are
completed.

## Migration File Format

Plain SQL, up-only. Named `V{N}__{description}.sql`.

- **No `IF NOT EXISTS`** in generated files -- versioned migrations should
  fail loudly on drift
- Description is slugified (non-alphanumeric characters replaced with `_`)
- Versions are sequential (`V1`, `V2`, `V3`, ...), derived from the
  highest migration version already present in the output directory

## Flyway Shadow Migration Workflow

The `:flyway` module generates Flyway migrations by starting a
disposable Docker-backed Postgres container, replaying your existing
Flyway migrations into it, introspecting the result, and diffing that
against your entkt schemas.

Your committed Flyway migration directory is the authoritative baseline
-- no `.schema.json` snapshots needed.

### Prerequisites

- Docker must be running (the workflow starts a temporary Postgres
  container for each invocation)

### Setup

Apply the `entkt.flyway` plugin (it auto-applies the base `entkt`
plugin). It registers `generateFlywayMigration` and
`validateFlywayMigrations` tasks:

```kotlin
plugins {
    id("entkt.flyway")
}

entkt {
    packageName.set("com.example.ent")
}

entktFlyway {
    migrationsDirectory.set(layout.projectDirectory.dir("db/migrations"))
}

dependencies {
    schemas(project(":schema"))

    entktCodegen("io.entkt:codegen:0.1.0-SNAPSHOT")
    entktCodegen("io.entkt:postgres:0.1.0-SNAPSHOT")
    entktCodegen("io.entkt:flyway:0.1.0-SNAPSHOT")

    implementation("io.entkt:runtime:0.1.0-SNAPSHOT")
    implementation("io.entkt:postgres:0.1.0-SNAPSHOT")
    implementation("io.entkt:migrations:0.1.0-SNAPSHOT")
}
```

The plugin forwards Gradle properties `-Pdescription` and `-PmanualMode`
to the CLI automatically.

### Generating a Migration

```bash
./gradlew generateFlywayMigration -Pdescription="add_posts_table"
```

This will:

1. Start a temporary Postgres container
2. Apply all existing Flyway migrations from `db/migrations/`
3. Introspect the resulting database schema
4. Diff it against your entkt schema definitions
5. Write the next versioned migration file (e.g. `V3__add_posts_table.sql`)
6. Destroy the container

If your schemas already match the migrated database, no file is written.

### Validating for Drift

```bash
./gradlew validateFlywayMigrations
```

Reports whether your entkt schemas have drifted from what your committed
Flyway migrations produce. Useful in CI to ensure migrations stay in
sync with schema changes. Exits with a non-zero status if drift is
detected.

### Manual Operations

By default (`ManualMode.FAIL`), generation fails when manual ops are
detected:

```bash
# Fails with ManualMigrationRequiredException
./gradlew generateFlywayMigration -Pdescription="drop_legacy_field"
```

To generate a migration file anyway, pass `ACKNOWLEDGE_AND_ADVANCE`:

```bash
./gradlew generateFlywayMigration \
  -Pdescription="drop_legacy_field" \
  -PmanualMode=ACKNOWLEDGE_AND_ADVANCE
```

The generated file will include a checklist of manual steps and a
`RAISE EXCEPTION` guard that prevents Flyway from applying it until you
replace the guard with your manual migration SQL:

```sql
-- entkt migration V4
--
-- !! MANUAL STEPS REQUIRED !!
-- The following operations require manual SQL. Replace the failing
-- statement below with your manual migration SQL before applying.
--
-- [ ] DropColumn: posts.legacy_field
--

-- Remove this statement once you have added your manual migration SQL above.
DO $$BEGIN RAISE EXCEPTION 'entkt: manual migration steps have not been completed'; END$$;
```

### CLI Options

```
Usage: FlywayMain <validate|generate|verify> <migrationsDir> [options]

Options:
  --image=<image>         Postgres Docker image (default: postgres:16-alpine)
  --db=<name>             Database name (default: entkt_shadow)
  --user=<user>           Database user (default: postgres)
  --password=<pass>       Database password (default: postgres)
  --exclude-tables=<t1,t2,...>  Tables to exclude from management
  --description=<desc>    Migration description (default: "migration")
  --manual-mode=<mode>    FAIL or ACKNOWLEDGE_AND_ADVANCE (default: FAIL)
```

Docker settings can also be set via environment variables:
`ENTKT_FLYWAY_POSTGRES_IMAGE`, `ENTKT_FLYWAY_POSTGRES_DB`,
`ENTKT_FLYWAY_POSTGRES_USER`, `ENTKT_FLYWAY_POSTGRES_PASSWORD`.

### Excluding Tables

If your database has tables not managed by entkt (e.g. created by other
systems), exclude them so they don't appear as drift. Pass the
`--exclude-tables` flag via environment variables or by customizing the
task args in your build script.

The Flyway history table (`flyway_schema_history`) is always excluded
automatically.

### Typical Flyway Workflow

1. Modify your `EntSchema` definitions
2. Run `./gradlew generateFlywayMigration -Pdescription="describe your change"`
3. Review the generated SQL
4. If manual ops are flagged, re-run with `-PmanualMode=ACKNOWLEDGE_AND_ADVANCE`,
   then replace the `RAISE EXCEPTION` guard with your manual migration SQL
5. Commit the migration file
6. Apply with Flyway (`flyway migrate`)
