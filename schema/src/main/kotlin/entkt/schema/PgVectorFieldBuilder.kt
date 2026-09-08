package entkt.schema

/**
 * Builder for a Postgres `pgvector` column. Inherits `.nullable()`,
 * `.immutable()`, `.sensitive()`, and `.comment()`. Uniqueness and default
 * modifiers are not exposed for vector fields.
 *
 * The dimensions and native storage are attached at registration via
 * `setNativeStorage(...)` (see `EntSchema.registerPostgresVector`), mirroring
 * how `enum` attaches its `enumClass`. The value type parameter is `FloatArray`
 * (the raw component carrier); the generated entity property is the
 * `entkt.postgres.vector.PgVector` value type, resolved by codegen.
 */
class PgVectorFieldBuilder internal constructor(name: String) :
    FieldBuilder<PgVectorFieldBuilder, FloatArray>(name, FieldType.PGVECTOR)
