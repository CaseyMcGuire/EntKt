# entkt Documentation

entkt is a Kotlin port of [Ent](https://entgo.io/), Go's entity framework. Declare
your entities in a Kotlin DSL, run code generation, and get typed data classes,
query builders, and repositories that talk to a pluggable `Driver`.

## Guides

- [Getting Started](getting-started.md) -- setup, first schema, running codegen
- [Schema](schema.md) -- fields, edges, indexes, mixins, ID strategies
- [Queries](queries.md) -- predicates, ordering, pagination, edge traversal, eager loading
- [Hooks](hooks.md) -- lifecycle hooks for create, update, and delete
- [Migrations](migrations.md) -- dev-mode auto-apply and prod-mode SQL file generation
- [Drivers](drivers.md) -- InMemoryDriver, PostgresDriver, writing your own
