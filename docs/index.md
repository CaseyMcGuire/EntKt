# entkt Documentation

entkt is a Kotlin port of [Ent](https://entgo.io/), Go's entity framework. Declare
your entities in a Kotlin DSL, run code generation, and get typed data classes,
query builders, and repositories that talk to a pluggable `Driver`.

## Guides

- [Getting Started](getting-started.md) -- setup, first schema, running codegen
- [Schema](schema.md) -- fields, edges, indexes, mixins, ID strategies
- [Queries](queries.md) -- predicates, ordering, pagination, edge traversal, eager loading
- [Hooks](hooks.md) -- lifecycle hooks for create, update, and delete
- [Privacy](privacy.md) -- per-entity privacy rules for read and write operations
- [Validation](validation.md) -- entity-level validation rules for data model invariants
- [Privacy Limitations](privacy-limitations.md) -- V1 aggregate, filtering, pagination, and upsert caveats
- [Migrations](migrations.md) -- dev-mode auto-apply and prod-mode SQL file generation
- [Drivers](drivers.md) -- InMemoryDriver, PostgresDriver, writing your own

## Possible Features

- [Possible Features Index](possible-features/index.md) -- catalog of future feature RFCs and design notes
