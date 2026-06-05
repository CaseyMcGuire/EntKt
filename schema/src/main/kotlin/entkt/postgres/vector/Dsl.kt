package entkt.postgres.vector

import entkt.schema.EntSchema
import entkt.schema.IndexBuilder
import entkt.schema.IndexableColumn
import entkt.schema.PgVectorFieldBuilder

/**
 * Declare a Postgres `pgvector` column (RFC "Native Database Column Types").
 * Import-gated (`import entkt.postgres.vector.*`) so it does not appear on the
 * base schema DSL — a Postgres-native field looks Postgres-native at the call
 * site. `dimensions` must be 1..16000 (the `vector` column cap; HNSW/IVFFlat
 * indexes further require <= 2000, enforced at `postgresVectorIndex`).
 *
 * `inline` so the user-module call site can reach the `@PublishedApi internal`
 * [EntSchema.registerPostgresVector] hook without exposing the private
 * registration internals.
 */
inline fun EntSchema.postgresVector(name: String, dimensions: Int): PgVectorFieldBuilder =
    registerPostgresVector(name, dimensions)

/**
 * Distance metric for a `pgvector` index / nearest-neighbor ordering. Each
 * maps to a pgvector operator class (and, in Phase 6, the matching operator).
 */
enum class VectorMetric(val opclass: String) {
    Cosine("vector_cosine_ops"),
    L2("vector_l2_ops"),
    InnerProduct("vector_ip_ops"),
}

/**
 * Declare a `pgvector` index on [field]. Spell out the access method + metric
 * explicitly via `.hnsw(...)` / `.ivfflat(...)` — vector indexes are not btree.
 * Import-gated and registered through the same `@PublishedApi internal` bridge
 * as `postgresVector` (RFC §6).
 */
inline fun EntSchema.postgresVectorIndex(name: String, field: IndexableColumn): IndexBuilder =
    registerPostgresVectorIndex(name, field)

/** Configure this index as HNSW with the given [metric]. */
inline fun IndexBuilder.hnsw(metric: VectorMetric): IndexBuilder =
    apply { setVectorIndex("hnsw", listOf(metric.opclass), null) }

/** Configure this index as IVFFlat with the given [metric] and `lists` partition count (> 0). */
inline fun IndexBuilder.ivfflat(metric: VectorMetric, lists: Int): IndexBuilder =
    apply {
        require(lists > 0) { "ivfflat() lists must be > 0, got $lists" }
        setVectorIndex("ivfflat", listOf(metric.opclass), mapOf("lists" to lists.toString()))
    }
