# entkt Documentation

entkt is a Kotlin entity framework. Declare your entities in a Kotlin DSL, run
code generation, and get typed data classes, query builders, and repositories
that talk to a pluggable `DatabaseDriver`.

## Guides

- [Getting Started](01-getting-started.md) -- setup, first schema, running codegen
- [Operation Lifecycle](operation-lifecycle.md) -- the order and guarantees for reads, hooks, validation, privacy, and CRUD writes
- [Schema](02-schema.md) -- fields, edges, indexes, ID strategies, native column types (pgvector, typed JSON)
- [Edges](03-edges.md) -- how edge types map to tables, columns, and generated code
- [Queries](04-queries.md) -- predicates, indexed query helpers, ordering, pagination, edge traversal, eager loading, read-path interceptors
- [Hooks](05-hooks.md) -- lifecycle hooks for create, update, and delete
- [Privacy](06-privacy.md) -- per-entity privacy rules for read and write operations
- [Validation](07-validation.md) -- entity-level validation rules for data model invariants
- [Privacy Limitations](08-privacy-limitations.md) -- V1 aggregate, filtering, and pagination caveats
- [Migrations](09-migrations.md) -- migration planning, snapshots, and SQL file generation
- [Drivers](10-drivers.md) -- PostgresDriver, writing your own
- [Ent Viewer](11-ent-viewer.md) -- read-only browser inspection of generated ents
- [Schema Inspection](01-getting-started.md#schema-inspection) -- validate and explain resolved schema shapes

## Design Records and Change History

- [Possible Features Index](possible-features/index.md) -- technical proposals and design notes for future work
- [Implemented Features Index](implemented-features/index.md) -- historical implementation records for shipped work
- [Breaking Changes](breaking-changes/index.md) -- running log of breaking changes to the public surface

The numbered guides above are the API documentation. The feature indexes are
design records for contributors and may discuss code generation, runtime
plumbing, SQL lowering, and other implementation details.
