# entkt Documentation

entkt is a Kotlin port of [Ent](https://entgo.io/), Go's entity framework. Declare
your entities in a Kotlin DSL, run code generation, and get typed data classes,
query builders, and repositories that talk to a pluggable `Driver`.

## Guides

- [Getting Started](01-getting-started.md) -- setup, first schema, running codegen
- [Schema](02-schema.md) -- fields, edges, indexes, ID strategies, native column types (pgvector)
- [Edges](03-edges.md) -- how edge types map to tables, columns, and generated code
- [Queries](04-queries.md) -- predicates, ordering, pagination, edge traversal, eager loading, read-path interceptors
- [Hooks](05-hooks.md) -- lifecycle hooks for create, update, and delete
- [Privacy](06-privacy.md) -- per-entity privacy rules for read and write operations
- [Validation](07-validation.md) -- entity-level validation rules for data model invariants
- [Privacy Limitations](08-privacy-limitations.md) -- V1 aggregate, filtering, and pagination caveats
- [Migrations](09-migrations.md) -- migration planning, snapshots, and SQL file generation
- [Drivers](10-drivers.md) -- PostgresDriver, writing your own
- [Schema Inspection](01-getting-started.md#schema-inspection) -- validate and explain resolved schema shapes

## Possible Features

- [Possible Features Index](possible-features/index.md) -- catalog of future feature RFCs and design notes
- [Implemented Features Index](implemented-features/index.md) -- RFCs whose main implementation has landed
