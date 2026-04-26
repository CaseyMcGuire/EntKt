package entkt.codegen

import entkt.schema.Edge
import entkt.schema.EdgeKind
import entkt.schema.EntSchema

/**
 * Find the inverse of [edge] on its target schema, given that [edge] is
 * declared on [source].
 *
 * The codegen needs the inverse edge for traversal: when generating
 * `UserQuery.queryPosts()`, we want to filter posts by `Post.author`
 * matching the parent user query — and `"author"` is the *inverse*
 * edge name on the Post side.
 *
 * Resolution rules:
 *
 * 1. If [edge] has an inverse name (set via `.inverse(...)` on the
 *    belongsTo side), the inverse is the edge on the target whose own
 *    name matches and is a valid inverse kind.
 * 2. Otherwise, look at the target's edges for one that points back at
 *    [source] and whose `.inverse(...)` names [edge].
 *
 * Returns null if no inverse can be resolved. Callers decide whether
 * that is an error: `HasMany` and `HasOne` require an inverse and
 * should throw; `BelongsTo` can function without one.
 *
 * Throws if an explicit `.inverse(...)` doesn't match any edge on the
 * target, or if multiple edges claim the same inverse name.
 */
internal fun findInverseEdge(edge: Edge, source: EntSchema): Edge? {
    val targetEdges = edge.target.edges()

    // Determine which edge kinds are valid inverses:
    //   HasMany / HasOne → BelongsTo
    //   BelongsTo → HasMany or HasOne
    val isValidInverse: (Edge) -> Boolean = when (edge.kind) {
        is EdgeKind.HasMany, is EdgeKind.HasOne ->
            { e -> e.kind is EdgeKind.BelongsTo }
        is EdgeKind.BelongsTo ->
            { e -> e.kind is EdgeKind.HasMany || e.kind is EdgeKind.HasOne }
        is EdgeKind.ManyToMany ->
            error("findInverseEdge should not be called for ManyToMany edges")
    }

    // Rule 1: this edge has a ref, look up by name on the target.
    edge.ref?.let { refName ->
        return targetEdges.firstOrNull { it.name == refName && it.target === source && isValidInverse(it) }
            ?: error(
                "Edge '${edge.name}' declares .inverse(\"$refName\") but no edge named " +
                    "'$refName' pointing back at the source schema was found on the target"
            )
    }

    // Rule 2: target has an edge whose ref names us.
    val refMatches = targetEdges.filter { it.target === source && it.ref == edge.name && isValidInverse(it) }
    if (refMatches.size > 1) {
        val names = refMatches.joinToString(", ") { "'${it.name}'" }
        error(
            "Edge '${edge.name}' has ${refMatches.size} inverse edges ($names) that " +
                "declare .inverse(\"${edge.name}\") — this is ambiguous. Use distinct " +
                ".inverse() values or remove duplicates"
        )
    }
    return refMatches.singleOrNull()
}
