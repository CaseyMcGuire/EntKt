# RFC: Migration Diagnostics

## Status

Possible future feature. This is not implemented.

## Summary

Improve migration planning output so schema drift, manual operations, and
generated SQL are easier to understand and act on.

This builds on the implemented Flyway shadow workflow. It does not change the
core migration model; it makes the workflow's output clearer.

## Motivation

Migration generation is one of the places where surprising behavior is most
expensive. When entkt detects drift, the user should know:

- which schema declaration caused the diff
- which database object is affected
- whether the operation is safe or manual
- why an operation cannot be generated automatically
- what file would be written
- what command should be run next

Current migration docs already distinguish safe and manual operations. The next
step is making the CLI and Gradle output as structured and explanatory as the
docs.

## Non-Goals

- Do not auto-generate destructive migrations.
- Do not apply migrations to the application database.
- Do not replace Flyway, Liquibase, or another migration runner.
- Do not infer data backfill strategies automatically.
- Do not add interactive prompts to CI paths.

## Proposed Diagnostics

Migration planning should produce a structured report before writing files:

```kotlin
data class MigrationPlanReport(
    val currentSchemaSource: SchemaSource,
    val desiredSchemaSource: SchemaSource,
    val operations: List<MigrationOperationDiagnostic>,
    val outputFile: String?,
    val warnings: List<MigrationWarning>,
)
```

Human-readable output can then group operations:

```text
Migration plan: add_posts_table

Planned operations:
  + CreateTable posts
      from schema Post
      risks: AdditiveSafe
  + AddIndex idx_posts_slug on posts(slug)
      from Post.bySlug
      risks: DataDependent, Blocking

Manual operations:
  ! DropColumn users.legacy_name
      from removed field User.legacyName
      reason: dropping data requires manual SQL
```

## Source Attribution

Every operation should carry best-effort attribution back to the schema
declaration:

```text
Post.title -> posts.title
User.email -> users.email
Post.author -> fk_posts_author_id_users
Post.byAuthor -> idx_posts_author_id
```

If attribution is unavailable, the diagnostic should say so instead of leaving
the user guessing.

## Manual Operation Advice

Manual operations should include an explicit reason and a suggested shape, not
a generated destructive statement:

```text
DropColumn users.legacy_name
reason: data loss risk
suggested action: write manual SQL after confirming data is no longer needed
```

For not-null additions:

```text
AddColumn posts.slug text NOT NULL
reason: existing rows need values before the constraint can be added
suggested action: add nullable column, backfill, then set NOT NULL manually
```

## Output Modes

Support a stable machine-readable mode for CI and tooling:

```bash
./gradlew generateFlywayMigration -Pdescription=add_posts -PentktMigrationReport=json
```

Possible output modes:

- `text`: default human-readable report
- `json`: machine-readable report
- `quiet`: only summary and errors

The JSON format should be versioned so external tools can consume it safely.

## Drift Validation

`validateFlywayMigrations` should report drift with the same operation
diagnostics as generation:

```text
Flyway migrations are out of sync with entkt schemas.

Missing from database:
  + AddIndex idx_posts_author_id on posts(author_id)

Run:
  ./gradlew generateFlywayMigration -Pdescription=add_posts_author_index
```

When no drift exists, output should be terse:

```text
entkt schemas match committed Flyway migrations.
```

## Relationship To Gradle DX

[Gradle Developer Experience](gradle-dx.md) covers the broader task surface.
This RFC is specifically about migration report content and machine-readable
diagnostics.

[Migration Risk And Online DDL](migration-risk-and-online-ddl.md) defines the
risk codes and explicit rendering strategies these diagnostics should expose.

## Test Requirements

Before implementation, add tests for:

- every planned operation reports structured risk codes
- additive, data-dependent, blocking, and destructive labels stay distinct
- manual operations include reasons
- operation diagnostics include schema declaration attribution when available
- no migration file is written in fail mode when manual operations exist
- JSON output is stable and versioned
- validation drift uses the same diagnostic model as generation
- suggested commands include the correct task and description format
