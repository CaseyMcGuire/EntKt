package entkt.codegen

import entkt.schema.Edge
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
 * Resolution rules (mirrors Ent's behavior):
 *
 * 1. If [edge] declares `.ref(name)`, the inverse is the edge on the
 *    target whose own name is `name`.
 * 2. Otherwise, look at the target's edges for one that points back at
 *    [source] and whose `.ref(...)` names [edge].
 * 3. As a last-resort fallback when there's exactly one edge from the
 *    target back to [source], use that one. Multiple back-edges with no
 *    `.ref(...)` are ambiguous and yield no inverse.
 *
 * Throws if an explicit `.ref(...)` doesn't match any edge on the target.
 * Returns null if no inverse can be resolved via rules 2–3 — in which
 * case codegen skips emitting a traversal method for that edge.
 */
internal fun findInverseEdge(edge: Edge, source: EntSchema): Edge? {
    val targetEdges = edge.target.edges()

    // Rule 1: this edge has a ref, look up by name on the target.
    edge.ref?.let { refName ->
        return targetEdges.firstOrNull { it.name == refName && it.target === source }
            ?: error(
                "Edge '${edge.name}' declares .ref(\"$refName\") but no edge named " +
                    "'$refName' pointing back at the source schema was found on the target"
            )
    }

    // Rule 2: target has an edge whose ref names us.
    val refMatches = targetEdges.filter { it.target === source && it.ref == edge.name }
    if (refMatches.size > 1) {
        val names = refMatches.joinToString(", ") { "'${it.name}'" }
        error(
            "Edge '${edge.name}' has ${refMatches.size} inverse edges ($names) that " +
                "declare .ref(\"${edge.name}\") — this is ambiguous. Use distinct .ref() " +
                "values or remove duplicates"
        )
    }
    refMatches.singleOrNull()?.let { return it }

    // Rule 3: single unambiguous back-edge.
    val backEdges = targetEdges.filter { it.target === source }
    return backEdges.singleOrNull()
}