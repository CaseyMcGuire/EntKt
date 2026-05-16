package entkt.codegen

import com.squareup.kotlinpoet.TypeName
import entkt.schema.Edge
import entkt.schema.EdgeKind
import entkt.schema.EntSchema
import entkt.schema.FieldType
import entkt.schema.ManyToManyThrough

/**
 * One link-table M2M edge that is eligible for direct mutator codegen
 * (RFC #5). Every value is resolved at codegen time so downstream
 * generators can splice junction reads and writes into the save
 * pipeline without re-walking the schema.
 *
 * A `throughLink(...)` declaration that survives
 * [validateThroughLinkJunctions] is helper-eligible by definition —
 * that validation enforces the junction shape constraints from RFC #3
 * (id + 2 FK columns only, OnDelete.CASCADE, no payload, no write-time
 * modifiers, source-first non-partial unique composite index, etc.).
 * [helperEligibleM2MEdges] just filters M2M edges to the
 * `ManyToManyThrough.LinkTable` variant and resolves the FK column
 * names through [resolveM2MEdgeJoin].
 *
 * The mutator is named after the source edge (`tags` →
 * `TagsEdgeMutator`, not `TagEdgeMutator`) so two M2M edges to the
 * same target type on the same source schema do not collide.
 */
internal data class HelperEligibleM2M(
    val edge: Edge,
    val edgeName: String,
    val mutatorPropertyName: String,
    val mutatorClassSimpleName: String,
    val junctionTable: String,
    val junctionSourceColumn: String,
    val junctionTargetColumn: String,
    val targetIdType: FieldType,
    val targetIdTypeName: TypeName,
    /**
     * Id-minting strategy for the junction table — one of `"AUTO_INT"`,
     * `"AUTO_LONG"`, or `"CLIENT_UUID"`. Junction-shape rule 5
     * (`validateThroughLinkJunctions`) rejects `EXPLICIT`, so the
     * helpers never have to deal with caller-supplied junction ids.
     * Phase 6's junction-insert codegen branches on this: AUTO_* lets
     * the driver mint the id, CLIENT_UUID mints client-side via
     * `UUID.randomUUID()`.
     */
    val junctionIdStrategy: String,
)

/**
 * Return all M2M edges on [schema] that are eligible for direct
 * link-table mutator codegen (RFC #5). Empty list if the schema has
 * no `throughLink(...)` M2M edges.
 *
 * Pass the same [schemaNames] map that other codegen passes use —
 * [resolveM2MEdgeJoin] needs it to find the junction schema's
 * canonical name.
 */
internal fun helperEligibleM2MEdges(
    schema: EntSchema,
    schemaNames: Map<EntSchema, String>,
): List<HelperEligibleM2M> {
    return schema.edges().mapNotNull { edge ->
        val m2m = edge.kind as? EdgeKind.ManyToMany ?: return@mapNotNull null
        if (m2m.through !is ManyToManyThrough.LinkTable) return@mapNotNull null
        val join = resolveM2MEdgeJoin(edge, schema, schemaNames) ?: return@mapNotNull null
        val junctionTable = join.junctionTable
            ?: error("resolveM2MEdgeJoin returned null junctionTable for M2M edge '${edge.name}'")
        val junctionSourceColumn = join.junctionSourceColumn
            ?: error("resolveM2MEdgeJoin returned null junctionSourceColumn for M2M edge '${edge.name}'")
        val junctionTargetColumn = join.junctionTargetColumn
            ?: error("resolveM2MEdgeJoin returned null junctionTargetColumn for M2M edge '${edge.name}'")
        val targetId = edge.target.id()
        val junction = (m2m.through as ManyToManyThrough.LinkTable).junction
        HelperEligibleM2M(
            edge = edge,
            edgeName = edge.name,
            mutatorPropertyName = edge.name,
            mutatorClassSimpleName = edge.name.replaceFirstChar { it.uppercase() } + "EdgeMutator",
            junctionTable = junctionTable,
            junctionSourceColumn = junctionSourceColumn,
            junctionTargetColumn = junctionTargetColumn,
            targetIdType = targetId.type,
            targetIdTypeName = targetId.type.toTypeName(),
            junctionIdStrategy = idStrategyName(junction),
        )
    }
}
