package entkt.postgres.vector

import entkt.schema.EntSchema
import entkt.schema.PgVectorFieldBuilder

/**
 * Declare a Postgres `pgvector` column (RFC "Native Database Column Types").
 * Import-gated (`import entkt.postgres.vector.*`) so it does not appear on the
 * base schema DSL — a Postgres-native field looks Postgres-native at the call
 * site. `dimensions` must be 1..2000.
 *
 * `inline` so the user-module call site can reach the `@PublishedApi internal`
 * [EntSchema.registerPostgresVector] hook without exposing the private
 * registration internals.
 */
inline fun EntSchema.postgresVector(name: String, dimensions: Int): PgVectorFieldBuilder =
    registerPostgresVector(name, dimensions)
