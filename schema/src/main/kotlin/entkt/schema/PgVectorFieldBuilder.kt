package entkt.schema

/**
 * Builder for a Postgres `pgvector` column (RFC "Native Database Column
 * Types"). Declares no modifiers of its own and does not override the final
 * `build()`: `.nullable()` / `.comment()` are inherited and valid;
 * `.unique()` is inherited too but rejected by `build()`; length/default
 * modifiers live only on the scalar builders, so they are absent here.
 *
 * The dimensions and native storage are attached at registration via
 * `setNativeStorage(...)` (see `EntSchema.registerPostgresVector`), mirroring
 * how `enum` attaches its `enumClass`. The value type parameter is `FloatArray`
 * (the raw component carrier); the generated entity property is the
 * `entkt.postgres.vector.PgVector` value type, resolved by codegen.
 */
class PgVectorFieldBuilder internal constructor(name: String) :
    FieldBuilder<PgVectorFieldBuilder, FloatArray>(name, FieldType.PGVECTOR)
