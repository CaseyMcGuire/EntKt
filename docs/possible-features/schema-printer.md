# RFC: Schema Printer

## Status

Possible future feature. This is not implemented.

## Summary

Add a Gradle task and programmatic API for printing the relational schema
that entkt would create from Kotlin schema definitions.

The first version should describe the desired schema without requiring a
database connection.

Example task:

```bash
./gradlew entktDescribeSchema
```

Example output:

```text
users
  id          BIGSERIAL PRIMARY KEY
  name        TEXT NOT NULL
  email       TEXT NOT NULL UNIQUE
  created_at  TIMESTAMPTZ NOT NULL
  updated_at  TIMESTAMPTZ NOT NULL

posts
  id          BIGSERIAL PRIMARY KEY
  title       TEXT NOT NULL
  body        TEXT
  author_id   BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE
  created_at  TIMESTAMPTZ NOT NULL
  updated_at  TIMESTAMPTZ NOT NULL

post_tags
  post_id     BIGINT NOT NULL REFERENCES posts(id) ON DELETE CASCADE
  tag_id      BIGINT NOT NULL REFERENCES tags(id) ON DELETE CASCADE
  PRIMARY KEY (post_id, tag_id)
```

## Motivation

entkt schemas can produce relational structure that is not obvious from
the Kotlin DSL alone:

- synthesized ID columns
- mixin fields
- edge-owned foreign key columns
- generated many-to-many join tables
- inferred and explicit storage names
- indexes and unique constraints
- partial indexes
- foreign key constraints
- `ON DELETE` actions

A schema printer would make generated table structure inspectable before
running migrations or connecting to a database. It would also make sample
projects easier to understand.

## Non-Goals

- Do not require a live database in the first version.
- Do not introspect an existing database in the first version.
- Do not apply migrations.
- Do not replace migration planning.
- Do not add new dependencies.

## Proposed Gradle API

Add a task exposed by the Gradle plugin:

```bash
./gradlew entktDescribeSchema
```

Default behavior:

- scan the configured schema inputs
- build the same normalized schema metadata used by codegen/migrations
- print a human-readable table description to stdout
- also write the output to `build/entkt/schema.txt`

Future optional flags:

```bash
./gradlew entktDescribeSchema --format=text
./gradlew entktDescribeSchema --format=json
./gradlew entktDescribeSchema --format=sql
./gradlew entktDescribeSchema --dialect=postgres
```

The initial implementation can omit flags and only support text output.

## Proposed Programmatic API

Add a small formatting API in the codegen or migrations module:

```kotlin
interface SchemaPrinter {
    fun print(schema: List<EntitySchema>): String
}
```

Potential implementation:

```kotlin
class TextSchemaPrinter : SchemaPrinter {
    override fun print(schema: List<EntitySchema>): String
}
```

The printer should consume normalized schema metadata rather than raw
schema DSL objects. That keeps it aligned with migrations and avoids
duplicating edge/FK/join-table logic.

## Output Scope

The first version should include:

- table names
- column names
- column types
- nullability
- primary keys
- default values when available
- unique constraints
- indexes
- partial index predicates
- foreign keys
- `ON DELETE` actions
- generated join tables

It should clearly identify generated structure, such as synthetic join
tables and inferred foreign key columns.

Example:

```text
posts
  id         BIGSERIAL PRIMARY KEY
  author_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE

  indexes
    posts_author_id_idx (author_id)

  generated from
    edge author -> users
```

## Dialect Handling

The first version can use the same SQL type names currently used by the
Postgres renderer, because Postgres is the primary SQL dialect in the
project today.

Longer term, the printer could support dialect-aware output:

```text
postgres:
  id BIGSERIAL

mysql:
  id BIGINT AUTO_INCREMENT

sqlite:
  id INTEGER
```

If dialect-specific output is added, unsupported features should be
shown explicitly rather than hidden.

Example:

```text
indexes
  active_email_idx (email) WHERE active = true
    unsupported in: mysql
```

## Relationship To Migrations

The schema printer should share the same schema-building path as
migration planning.

Desired flow:

```text
Kotlin schemas
  -> schema scan
  -> EntitySchema / normalized schema metadata
  -> migrations
  -> schema printer
```

This keeps printed output consistent with migration files and avoids
creating a second interpretation of the schema DSL.

## Example Sample-Project Usage

In a sample project:

```bash
./gradlew entktDescribeSchema
```

The output lets users understand what their schema creates before
starting the app:

```text
Schema: 4 tables

users
posts
tags
post_tags
```

This is especially useful when explaining edges and many-to-many
relationships.

## Open Questions

- Should the task be named `entktDescribeSchema`, `entktPrintSchema`, or
  `entktTables`?
- Should output be printed to stdout by default, written to a file, or
  both?
- Should the first version include indexes and constraints, or only
  tables and columns?
- Should the printer live in `:codegen`, `:migrations`, or a small shared
  support module?
- Should the first version show Kotlin field names alongside storage
  column names?
- Should generated join tables include source-edge annotations?

## Test Requirements

Before implementation, add tests for:

- scalar fields render with expected storage names and nullability
- mixin fields are included
- explicit storage keys are shown
- generated FK columns are shown
- explicit FK fields are shown
- many-to-many join tables are shown
- unique constraints are shown
- indexes are shown
- partial index predicates are shown
- `ON DELETE` actions are shown
- output is stable for snapshot-style assertions

