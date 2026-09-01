package entkt.codegen.query

import entkt.codegen.apiName
import entkt.codegen.generatedStem
import com.squareup.kotlinpoet.ClassName
import entkt.codegen.metadata.EdgeFk
import entkt.codegen.metadata.EdgeJoin
import entkt.codegen.metadata.computeEdgeFks
import entkt.codegen.metadata.findInverseEdge
import entkt.codegen.metadata.resolveEdgeJoin
import entkt.codegen.metadata.resolveM2MEdgeJoin
import entkt.schema.Edge
import entkt.schema.EdgeKind
import entkt.schema.EntSchema

/**
 * Names, joins, and inverses for one generated query class, resolved
 * once per generated entity/query pair.
 *
 * Edge loading, edge-predicate interception, and
 * `queryX()` traversal all key off the same per-edge metadata: the
 * target schema's generated name, the join columns, the inverse edge,
 * and the derived member names (`eagerX` / `loadX` / edge property).
 * Each of those emitters used to re-derive that metadata from the raw
 * schema at its own call site, which meant several copies of the same
 * resolution rules that could drift apart. Resolution happens here,
 * once; the member builders read the result.
 *
 * Edge capability is expressed through nullability:
 *  - an edge appears in [edges] at all only when its target schema is
 *    visible to codegen (present in `schemaNames`) — edges whose
 *    targets aren't being generated are skipped everywhere, exactly
 *    as the per-site `schemaNames[edge.target] ?: continue` guards
 *    used to do;
 *  - [ResolvedQueryEdge.join] non-null ⇔ the edge supports eager
 *    loading;
 *  - [ResolvedQueryEdge.inverse] non-null ⇔ a direct edge supports
 *    `queryX()` traversal (M2M traversal never needs an inverse).
 */
internal class ResolvedQuerySchema(
    /** The generated-name argument passed to [QueryGenerator.generate]. */
    val schemaName: String,
    val schema: EntSchema,
    /**
     * This schema's own entry in the `schemaNames` map, which is what
     * traversal codegen and the load-method's return-type name
     * have always been derived from — not the [schemaName] argument.
     * The two agree for every real caller ([EntGenerator] builds the
     * map from the same names it passes as arguments); carrying the
     * map lookup keeps the historical behavior for callers that pass
     * a map that omits or renames the source schema (traversals are
     * skipped when this is null, exactly as before).
     */
    val sourceName: String?,
    val entityClass: ClassName,
    val entityDescriptorClass: ClassName,
    val queryClass: ClassName,
    /** In `schema.edges()` order, restricted to codegen-visible targets. */
    val edges: List<ResolvedQueryEdge>,
    /** FK surfaces for this schema's belongsTo edges, resolved once. */
    val edgeFks: List<EdgeFk>,
)

internal class ResolvedQueryEdge(
    val edge: Edge,
    /** The target schema's generated name from `schemaNames`. */
    val targetName: String,
    /**
     * The target schema's declared client property. Eager loading
     * reaches the target's repo through `client.<targetClientName>`;
     * it is declared on the target schema, never derived from
     * [targetName].
     */
    val targetClientName: String,
    val targetClass: ClassName,
    val targetDescriptorClass: ClassName,
    val targetQueryClass: ClassName,
    /** Top-level generated descriptor for this relationship. */
    val edgeDescriptorClass: ClassName,
    /** Backing property holding the selected edge-load sub-query: `eagerX`. */
    val eagerPropName: String,
    /** Edge-load DSL entry point: `loadX`. */
    val loadMethodName: String,
    /** Edge-traversal entry point: `queryX`. */
    val queryMethodName: String,
    /** Generated `Edges` property the eager result lands on. */
    val edgePropName: String,
    /**
     * Join columns for eager loading, or null when
     * they can't be resolved (an M2M junction schema not visible to
     * codegen). Direct-edge join resolution either succeeds or throws
     * — see [resolveEdgeJoin] — so null here always means "skip the
     * eager surface", never "silently broken join".
     */
    val join: EdgeJoin?,
    /**
     * The inverse edge on the target for direct-edge traversal
     * lowering (`queryX()` filters targets by the inverse edge name).
     * Null for M2M edges and when no inverse resolves — traversal
     * codegen skips the method in that case.
     */
    val inverse: Edge?,
    /**
     * The junction entity's generated entity and query classes, for
     * the eager M2M step's `EAGER_JUNCTION` discovery interceptor
     * pass. Non-null exactly when [join] is non-null on an M2M edge:
     * eager capability requires a codegen-visible junction, and both
     * `throughLink` and `throughEntity` junctions are entity classes.
     * Null for direct edges.
     */
    val junctionEntityClass: ClassName?,
    val junctionDescriptorClass: ClassName?,
    /** FK properties available on the target entity for typed edge correlation. */
    val targetEdgeFks: List<EdgeFk>,
) {
    /**
     * The edge's **storage** identifier. This is the edge-lookup key in
     * driver metadata and the value carried by the companion `EdgeRef`,
     * so predicate dispatch (`Predicate.HasEdgeWith.edge`) matches on
     * it. Never emit it into a caller-facing path.
     */
    val name: String get() = edge.name

    /**
     * The edge's Kotlin declaration name, for caller-facing paths:
     * interceptor context (`InterceptorContext.edgeName`) and
     * selected-edge denial origins (`LoadDenialOrigin.SelectedEdgePath`). A caller who
     * wrote `queryCurator()` must not be told about `legacy_owner`.
     */
    val publicName: String get() = edge.apiName

    val isManyToMany: Boolean get() = edge.kind is EdgeKind.ManyToMany
}

internal fun resolveQuerySchema(
    packageName: String,
    schemaName: String,
    schema: EntSchema,
    schemaNames: Map<EntSchema, String>,
): ResolvedQuerySchema {
    val sourceName = schemaNames[schema]
    val edges = schema.edges().mapNotNull { edge ->
        val targetName = schemaNames[edge.target] ?: return@mapNotNull null
        // Inverse before join, and only when the source schema is
        // itself visible: findInverseEdge throws on a bad or
        // ambiguous `.inverse(...)` ref, and the traversal builder —
        // historically the first resolution site in generate() —
        // only ran it behind its own sourceName guard. Keeping that
        // order and guard keeps which error a broken edge surfaces
        // unchanged for that edge. (With several broken edges,
        // resolution is now per-edge rather than the old
        // all-inverses-then-all-joins phase order, so which edge's
        // error wins can differ.)
        val inverse = if (edge.kind is EdgeKind.ManyToMany || sourceName == null) {
            null
        } else {
            findInverseEdge(edge, schema)
        }
        val join = if (edge.kind is EdgeKind.ManyToMany) {
            resolveM2MEdgeJoin(edge, schema, schemaNames)
        } else {
            resolveEdgeJoin(edge, schema)
        }
        val junctionName = if (edge.kind is EdgeKind.ManyToMany && join != null) {
            schemaNames[(edge.kind as EdgeKind.ManyToMany).through.junction]
        } else {
            null
        }
        ResolvedQueryEdge(
            edge = edge,
            targetName = targetName,
            targetClientName = edge.target.clientName,
            targetClass = ClassName(packageName, targetName),
            targetDescriptorClass = ClassName(packageName, "${targetName}Descriptor"),
            targetQueryClass = ClassName(packageName, "${targetName}Query"),
            edgeDescriptorClass = ClassName(
                packageName,
                "${schemaName}${edge.apiName.generatedStem()}EdgeDescriptor",
            ),
            eagerPropName = "eager${edge.apiName.generatedStem()}",
            loadMethodName = "load${edge.apiName.generatedStem()}",
            queryMethodName = "query${edge.apiName.generatedStem()}",
            edgePropName = edge.apiName,
            join = join,
            inverse = inverse,
            junctionEntityClass = junctionName?.let { ClassName(packageName, it) },
            junctionDescriptorClass = junctionName?.let {
                ClassName(packageName, "${it}Descriptor")
            },
            targetEdgeFks = computeEdgeFks(edge.target, schemaNames),
        )
    }
    return ResolvedQuerySchema(
        schemaName = schemaName,
        schema = schema,
        sourceName = sourceName,
        entityClass = ClassName(packageName, schemaName),
        entityDescriptorClass = ClassName(packageName, "${schemaName}Descriptor"),
        queryClass = ClassName(packageName, "${schemaName}Query"),
        edges = edges,
        edgeFks = computeEdgeFks(schema, schemaNames),
    )
}
