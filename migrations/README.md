# :migrations

Driver-agnostic schema diffing and migration operation models.

entkt plans migrations and emits reviewed SQL. It does **not** apply
migrations at runtime; clients are expected to use Flyway, Liquibase,
their deployment system, or another SQL migration runner.

## Key components

- `NormalizedSchema` — canonical representation of database schema
- `SchemaDiffer` — diffs desired vs current schemas into migration ops
- `MigrationOp` — sealed class hierarchy of migration operations
- `ManualMode` — controls behavior when destructive ops are detected

The `:flyway` module uses these components to implement the
[Flyway shadow migration workflow](../docs/09-migrations.md#flyway-shadow-migration-workflow).
