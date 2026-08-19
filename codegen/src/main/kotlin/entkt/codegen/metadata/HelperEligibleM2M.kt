package entkt.codegen.metadata

import entkt.codegen.apiName
import entkt.codegen.generatedStem
import com.squareup.kotlinpoet.TypeName
import entkt.schema.Edge
import entkt.schema.EdgeKind
import entkt.schema.EntSchema
import entkt.schema.FieldType
import entkt.schema.ManyToManyThrough

/**
 * One link-table M2M edge that is eligible for direct mutator codegen
 * (link-table M2M helpers). Every value is resolved at codegen time so downstream
 * generators can splice junction reads and writes into the save
 * pipeline without re-walking the schema.
 *
 * A writable `throughLink(...)` declaration that survives
 * [validateThroughLinkJunctions] is helper-eligible by definition —
 * that validation enforces the junction shape constraints from M2M schema modeling
 * (id + 2 FK columns only, OnDelete.CASCADE, no payload, no write-time
 * modifiers, an unordered unique pair index, and a non-partial index
 * leading with this side's source FK, etc.).
 * [helperEligibleM2MEdges] filters M2M edges to the
 * `ManyToManyThrough.LinkTable` variant and resolves the FK column names
 * through [resolveM2MEdgeJoin]. With symmetric link tables both pair-swapped
 * endpoints are writable, so a junction can surface here from both sides.
 *
 * The mutator is named after the source edge, not its target type, so
 * two M2M edges to the same target on the same source schema do not
 * collide. Both names come from the edge's Kotlin declaration: the
 * property is the declaration itself and the class is its stem, so
 * `val tags by manyToMany<Tag>("primary_tags")` generates
 * `val tags: TagsEdgeMutator`. The storage string `primary_tags` never
 * reaches the generated Kotlin API.
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
     * junction-insert codegen branches on this: AUTO_* lets
     * the driver mint the id, CLIENT_UUID mints client-side via
     * `UUID.randomUUID()`.
     */
    val junctionIdStrategy: String,
)

/**
 * Return all M2M edges on [schema] that are eligible for direct
 * link-table mutator codegen (link-table M2M helpers). Empty list if the schema has
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
        val through = m2m.through as? ManyToManyThrough.LinkTable ?: return@mapNotNull null
        val join = resolveM2MEdgeJoin(edge, schema, schemaNames) ?: return@mapNotNull null
        val junctionTable = join.junctionTable
            ?: error("resolveM2MEdgeJoin returned null junctionTable for M2M edge '${edge.name}'")
        val junctionSourceColumn = join.junctionSourceColumn
            ?: error("resolveM2MEdgeJoin returned null junctionSourceColumn for M2M edge '${edge.name}'")
        val junctionTargetColumn = join.junctionTargetColumn
            ?: error("resolveM2MEdgeJoin returned null junctionTargetColumn for M2M edge '${edge.name}'")
        val targetId = edge.target.id()
        val junction = through.junction
        HelperEligibleM2M(
            edge = edge,
            edgeName = edge.name,
            mutatorPropertyName = edge.apiName,
            mutatorClassSimpleName = edge.apiName.generatedStem() + "EdgeMutator",
            junctionTable = junctionTable,
            junctionSourceColumn = junctionSourceColumn,
            junctionTargetColumn = junctionTargetColumn,
            targetIdType = targetId.type,
            targetIdTypeName = targetId.type.toTypeName(),
            junctionIdStrategy = idStrategyName(junction),
        )
    }
}
