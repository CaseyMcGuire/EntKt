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
- [Privacy Limitations](privacy-limitations.md) -- V1 aggregate, filtering, pagination, and upsert caveats
- [Migrations](migrations.md) -- dev-mode auto-apply and prod-mode SQL file generation
- [Drivers](drivers.md) -- InMemoryDriver, PostgresDriver, writing your own

## Possible Features

- [Edge-Derived LOAD Privacy](possible-features/edge-derived-load-privacy.md) -- RFC for allowing child reads through specific readable parent edges
- [GraphQL Kotlin Type Generation](possible-features/graphql-kotlin-generation.md) -- RFC for generating GraphQL DTOs and resolver scaffolding
- [Schema Printer](possible-features/schema-printer.md) -- RFC for printing the relational tables generated from schemas
