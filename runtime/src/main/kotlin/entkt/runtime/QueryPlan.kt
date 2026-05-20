package entkt.runtime

/**
 * A tree of [QueryExplanation]s describing the **shapes** of the
 * queries a generated read terminal would execute — the root query
 * plus any eager-loaded edge subqueries (which may themselves have
 * nested eager loads).
 *
 * Each entry in [eagerQueries] represents one query shape per edge.
 * At runtime, nested eager loads may execute that shape multiple
 * times (once per parent group), but the plan shows the structure
 * rather than the multiplicity since the actual count depends on
 * data returned by the root query.
 *
 * Returned by every generated per-terminal explain method on query
 * builders and on repos (one explain per source-side variant —
 * `explainAllOrThrow`, `explainAllOrError`, `explainVisibleAll`,
 * `explainVisibleAllOrError`, `explainFirstOrThrow`,
 * `explainFirstOrNull`, `explainFirstOrError`,
 * `explainFirstVisibleOrNull`, `explainRawCount`,
 * `explainVisibleCount`, `explainRawExists`, `explainVisibleExists`,
 * plus repo-level `explainByIdOrThrow` / `explainByIdOrNull` /
 * `explainVisibleByIdOrNull` / `explainByIdOrError`). Different
 * variants of the same operation produce the same underlying
 * `QueryPlan` — the result-shape suffix in the name is for call-
 * site discovery, not plan content.
 *
 * On interceptor rejection, the returned plan has [rejected] = true,
 * carries the rejection metadata, and has [root] = null (no driver
 * subplan, since explain stops before reaching the driver).
 */
data class QueryPlan(
    /**
     * Driver explanation of the root query, or null when an
     * interceptor rejected the chain before the driver was
     * consulted (`rejected == true`).
     */
    val root: QueryExplanation?,
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
     * the driver received (or empty when [rejected] is true).
     */
    val annotations: Map<String, String> = emptyMap(),
    /**
     * True iff an interceptor in the chain called `scope.reject(...)`
     * during this explain step. Explain does NOT throw on rejection
     * — the plan carries [rejectedReason] / [rejectedCode] /
     * [rejectedInterceptor] so callers can branch without try/catch.
     * Callers that want exception-style explain chain
     * [requireNotRejected].
     */
    val rejected: Boolean = false,
    /** Human-readable rejection reason from `scope.reject(reason, code)`. */
    val rejectedReason: String? = null,
    /**
     * Optional machine-readable code from `scope.reject(reason, code)`.
     * Framework-set on `rejectIfLimitGreaterThan` rejections to
     * `"max_limit_exceeded"`.
     */
    val rejectedCode: String? = null,
    /**
     * Name of the rejecting interceptor (mandatory registration
     * name for application interceptors, or framework-assigned name
     * like `"framework:soft-delete"` for framework-owned).
     */
    val rejectedInterceptor: String? = null,
) {
    /**
     * Render the full query tree as a human-readable string. On
     * rejection the output describes the rejection metadata
     * instead of a driver subplan.
     *
     * Happy path:
     * ```
     * Root: SELECT * FROM "posts" WHERE "published" = ?  args: [true]  [annotations: tenant=acme]
     *   Edge "author":
     *     SELECT * FROM "users" WHERE "id" IN (?)  args: [<parent IDs>]
     *   Edge "tags":
     *     Junction: SELECT * FROM "post_tags" WHERE "post_id" IN (?)  args: [<parent IDs>]
     *     SELECT * FROM "tags" WHERE "id" IN (?)  args: [<parent IDs>]
     * ```
     *
     * Rejected:
     * ```
     * Root: REJECTED by 'tenant-scope' (code=missing_tenant_scope): "no tenant on viewer"
     * ```
     */
    fun render(): String = buildString { renderTo(this, indent = 0, label = "Root") }

    private fun renderTo(sb: StringBuilder, indent: Int, label: String) {
        val pad = "  ".repeat(indent)
        if (rejected) {
            val codePart = if (rejectedCode != null) " (code=$rejectedCode)" else ""
            sb.appendLine("$pad$label: REJECTED by '$rejectedInterceptor'$codePart: \"$rejectedReason\"")
            return
        }
        val annotationSuffix = if (annotations.isEmpty()) "" else
            "  [annotations: " + annotations.entries.joinToString(", ") { "${it.key}=${it.value}" } + "]"
        if (junctionQuery != null) {
            sb.appendLine("$pad$label:$annotationSuffix")
            sb.appendLine("${pad}  Junction: ${junctionQuery.describe()}")
            sb.appendLine("${pad}  ${root?.describe()}")
        } else {
            sb.appendLine("$pad$label: ${root?.describe()}$annotationSuffix")
        }
        for ((name, plan) in eagerQueries) {
            plan.renderTo(sb, indent + 1, "Edge \"$name\"")
        }
    }

    /**
     * Throws [EntQueryRejectedException] if [rejected] is true;
     * returns this plan otherwise. For callers that want
     * exception-style explain semantics ("treat rejection as fatal,
     * I just want the plan or the throw").
     */
    fun requireNotRejected(): QueryPlan {
        if (rejected) {
            throw EntQueryRejectedException(
                EntError.QueryRejected(
                    entity = "<explain>",
                    operation = EntOperation.QUERY,
                    reason = rejectedReason ?: "query rejected by interceptor",
                    code = rejectedCode,
                    interceptor = rejectedInterceptor ?: "<unknown>",
                )
            )
        }
        return this
    }

    public companion object {
        /**
         * Build a rejected plan from an [AbortQueryRejected] caught
         * inside an explain method. The framework conversion path:
         * `scope.reject(...)` throws `AbortQueryRejected` (caught
         * inside `runReadInterceptors`), which the helper re-throws
         * as `EntQueryRejectedException` for terminal callers. The
         * explain wrapper catches that exception instead and calls
         * this factory to build the plan rather than letting the
         * exception escape.
         */
        public fun rejected(rejection: EntError.QueryRejected): QueryPlan = QueryPlan(
            root = null,
            rejected = true,
            rejectedReason = rejection.reason,
            rejectedCode = rejection.code,
            rejectedInterceptor = rejection.interceptor,
        )
    }
}
