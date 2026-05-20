package entkt.runtime

/**
 * A tree of [QueryExplanation]s describing the **shapes** of the
 * queries a query builder would execute — the root query plus any
 * eager-loaded edge subqueries (which may themselves have nested
 * eager loads).
 *
 * Each entry in [eagerQueries] represents one query shape per edge.
 * At runtime, nested eager loads may execute that shape multiple
 * times (once per parent group), but the plan shows the structure
 * rather than the multiplicity since the actual count depends on
 * data returned by the root query.
 *
 * Returned by the generated `explain()` / `explainFirst()` /
 * `explainExists()` / `explainVisibleCount()` / `explainRawCount()`
 * methods on query builders.
 */
data class QueryPlan(
    val root: QueryExplanation,
    val junctionQuery: QueryExplanation? = null,
    val eagerQueries: Map<String, QueryPlan> = emptyMap(),
    /**
     * Annotations attached by interceptors at this step via
     * `scope.addAnnotation(key, value)`. Surfaces in [render] under
     * the node's `[annotations: ...]` suffix so observability /
     * tracing consumers can inspect why an interceptor chose a
     * particular shape (e.g. `"tenant" -> "acme"`,
     * `"query-source" -> "internal-cron"`). Last writer wins on
     * duplicate keys across interceptors in the same chain. Always
     * a snapshot of the post-interceptor [FrozenQuerySpec.annotations]
     * the driver received.
     */
    val annotations: Map<String, String> = emptyMap(),
) {
    /**
     * Render the full query tree as a human-readable string.
     *
     * ```
     * Root: SELECT * FROM "posts" WHERE "published" = ?  args: [true]  [annotations: tenant=acme]
     *   Edge "author":
     *     SELECT * FROM "users" WHERE "id" IN (?)  args: [<parent IDs>]
     *   Edge "tags":
     *     Junction: SELECT * FROM "post_tags" WHERE "post_id" IN (?)  args: [<parent IDs>]
     *     SELECT * FROM "tags" WHERE "id" IN (?)  args: [<parent IDs>]
     * ```
     */
    fun render(): String = buildString { renderTo(this, indent = 0, label = "Root") }

    private fun renderTo(sb: StringBuilder, indent: Int, label: String) {
        val pad = "  ".repeat(indent)
        val annotationSuffix = if (annotations.isEmpty()) "" else
            "  [annotations: " + annotations.entries.joinToString(", ") { "${it.key}=${it.value}" } + "]"
        if (junctionQuery != null) {
            sb.appendLine("$pad$label:$annotationSuffix")
            sb.appendLine("${pad}  Junction: ${junctionQuery.describe()}")
            sb.appendLine("${pad}  ${root.describe()}")
        } else {
            sb.appendLine("$pad$label: ${root.describe()}$annotationSuffix")
        }
        for ((name, plan) in eagerQueries) {
            plan.renderTo(sb, indent + 1, "Edge \"$name\"")
        }
    }
}
