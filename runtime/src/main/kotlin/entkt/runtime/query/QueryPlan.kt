package entkt.runtime.query

import entkt.query.OrderField
import entkt.runtime.result.EntQueryRejectedException

/**
 * How an eager edge's per-parent `limit` / `offset` window is
 * executed. Phase 1 of the set-based eager graph loader always
 * emulates the window in memory: the driver fetches every matching
 * target row and the runtime applies each parent's window in Kotlin,
 * so a finite window can overfetch. A native per-parent window
 * strategy is owned by the phase-2 driver capability.
 */
enum class EagerWindowStrategy {
    /**
     * The driver query carries no LIMIT/OFFSET; the per-parent
     * window is applied in memory after grouping. Correct results,
     * but a finite window does not reduce rows fetched.
     */
    IN_MEMORY_EMULATED,
}

/**
 * Framework-owned execution-strategy metadata for one eager edge
 * step. Carried on the eager subplan's [QueryPlan.eagerExecution]
 * field — deliberately NOT the caller-controlled
 * [QueryPlan.annotations] map, whose keys are last-writer-wins: an
 * application or interceptor annotation cannot override what the
 * framework reports about its own execution strategy.
 *
 * This is structural metadata, not a runtime measurement: it does
 * not predict query multiplicity, chunk counts, or row counts (those
 * depend on runtime parent sets and belong to the separate
 * query-observability feature).
 */
data class EagerExecutionPlan(
    /**
     * True: the edge's nested eager loads execute once for the
     * ordered distinct union of retained targets, not once per
     * populated parent group.
     */
    val setBatchedNestedExecution: Boolean,
    /**
     * The canonical effective target order storage executes — the
     * caller's terms plus the framework's primary-key ascending
     * tie-breaker when the caller didn't order by the primary key.
     * This order drives storage reads, in-memory per-parent windows,
     * privacy-batch order, and association order.
     */
    val effectiveOrder: List<OrderField<*>>,
    /** How the per-parent window is executed. See [EagerWindowStrategy]. */
    val windowStrategy: EagerWindowStrategy,
    /** The configured per-parent limit, or null when unbounded. */
    val perParentLimit: Int?,
    /** The configured per-parent offset, or null when none was set. */
    val perParentOffset: Int?,
    /**
     * True when the emulated window can make this step fetch rows
     * the caller will never see — a positive finite per-parent limit
     * or a positive offset discards fetched rows in memory rather
     * than in storage. False when the step provably fetches nothing
     * to discard: a `limit(0)` window skips the driver fetch
     * entirely, and a to-one edge's fetch is gated on a window that
     * admits its at-most-one candidate, so to-one edges never
     * overfetch.
     */
    val windowOverfetchRisk: Boolean,
)

/**
 * A tree of [QueryExplanation]s describing the **shapes** of the
 * queries a generated read terminal would execute — the root query
 * plus any eager-loaded edge subqueries (which may themselves have
 * nested eager loads).
 *
 * Each entry in [eagerQueries] represents one query shape per edge.
 * The plan is a structural walk, not a simulation of runtime
 * fail-fast execution: it may describe later sibling steps a real
 * read would stop before, and it does not predict physical query
 * multiplicity or row counts (an eager edge step is one logical
 * query, but junction reads and driver strategies can require
 * several physical queries).
 *
 * Returned by every generated per-terminal explain method — one
 * mirror per canonical terminal: `explainAll`, `explainFirstOrNull`,
 * `explainRawCount`, and `explainRawExists` on query builders, plus
 * the repo-level `explainFindById`.
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
     * The typed [EntQueryRejectedException] when an interceptor in
     * the chain called `scope.reject(...)` during this explain step.
     * Null on the happy path. Explain does NOT throw on rejection —
     * the plan carries this payload so callers can branch without
     * try/catch. Callers that want exception-style explain chain
     * [requireNotRejected], which throws this stored exception
     * directly so its `entityType` / `interceptor` properties are
     * accurate (not synthetic).
     */
    val rejection: EntQueryRejectedException? = null,
    /**
     * Framework-owned eager execution-strategy metadata. Non-null
     * only on the eager subplans under [eagerQueries] (never on a
     * root or rejected node). See [EagerExecutionPlan] for why this
     * is a typed field rather than an [annotations] entry.
     */
    val eagerExecution: EagerExecutionPlan? = null,
    /**
     * Annotations contributed by the JUNCTION entity's interceptors
     * during an eager many-to-many step's `EAGER_JUNCTION` discovery
     * pass. Kept apart from [annotations] (the target spec's map) so
     * junction- and target-step contributions stay attributable.
     * Empty for non-M2M subplans and when no junction interceptor
     * annotated.
     */
    val junctionAnnotations: Map<String, String> = emptyMap(),
) {
    /** Convenience: true iff [rejection] is non-null. */
    val rejected: Boolean get() = rejection != null

    /** Convenience accessor for [rejection].reason. */
    val rejectedReason: String? get() = rejection?.reason

    /** Convenience accessor for [rejection].code. */
    val rejectedCode: String? get() = rejection?.code

    /** Convenience accessor for [rejection].interceptor. */
    val rejectedInterceptor: String? get() = rejection?.interceptor
    /**
     * Render the full query tree as a human-readable string. On
     * rejection the output describes the rejection metadata
     * instead of a driver subplan.
     *
     * Happy path (each eager subplan carries its framework-owned
     * `[eager execution: ...]` strategy line):
     * ```
     * Root: SELECT * FROM "posts" WHERE "published" = ?  args: [true]  [annotations: tenant=acme]
     *   Edge "author": SELECT * FROM "users" WHERE "id" IN (?) ORDER BY "id" ASC  args: [<parent IDs>]
     *     [eager execution: set-batched nested loads; effective order: id ASC; per-parent window: in-memory emulation (limit=null, offset=null)]
     *   Edge "tags":
     *     Junction: SELECT * FROM "post_tags" WHERE "post_id" IN (?)  args: [<parent IDs>]
     *     SELECT * FROM "tags" WHERE "id" IN (?) ORDER BY "id" ASC  args: [<parent IDs>]
     *     [eager execution: set-batched nested loads; effective order: id ASC; per-parent window: in-memory emulation (limit=null, offset=null)]
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
            val junctionSuffix = if (junctionAnnotations.isEmpty()) "" else
                "  [annotations: " +
                    junctionAnnotations.entries.joinToString(", ") { "${it.key}=${it.value}" } + "]"
            sb.appendLine("${pad}  Junction: ${junctionQuery.describe()}$junctionSuffix")
            sb.appendLine("${pad}  ${root?.describe()}")
        } else {
            sb.appendLine("$pad$label: ${root?.describe()}$annotationSuffix")
        }
        eagerExecution?.let { exec ->
            val order = exec.effectiveOrder.joinToString(", ") { it.describeOrderTerm() }
            val window = when (exec.windowStrategy) {
                EagerWindowStrategy.IN_MEMORY_EMULATED ->
                    "in-memory emulation (limit=${exec.perParentLimit}, offset=${exec.perParentOffset})" +
                        if (exec.windowOverfetchRisk) " — may overfetch" else ""
            }
            sb.appendLine(
                "$pad  [eager execution: set-batched nested loads; " +
                    "effective order: $order; per-parent window: $window]",
            )
        }
        for ((name, plan) in eagerQueries) {
            plan.renderTo(sb, indent + 1, "Edge \"$name\"")
        }
    }

    /**
     * Throws [EntQueryRejectedException] if this plan or any eager
     * subplan (recursively) is rejected; returns this plan
     * otherwise. For callers that want exception-style explain
     * semantics ("treat rejection as fatal, I just want the plan or
     * the throw").
     *
     * The walk covers the whole tree because executing the
     * represented query runs the eager interceptor pass too — a
     * rejection recorded under [eagerQueries] means the runtime
     * terminal would throw, so a root-only check would declare a
     * plan clean that cannot execute. Same tree walk as [render].
     *
     * The thrown exception is the first rejection in depth-first
     * order (this plan's own before its edges', edges in
     * declaration order) — the rejected node's original stored
     * [EntQueryRejectedException], not synthesized.
     */
    fun requireNotRejected(): QueryPlan {
        val rej = firstRejection() ?: return this
        throw rej
    }

    private fun firstRejection(): EntQueryRejectedException? {
        rejection?.let { return it }
        for (plan in eagerQueries.values) {
            plan.firstRejection()?.let { return it }
        }
        return null
    }

    public companion object {
        /**
         * Build a rejected plan from an [EntQueryRejectedException]
         * caught inside an explain method. The framework conversion
         * path: `scope.reject(...)` throws `AbortQueryRejected`
         * (caught inside `runReadInterceptors`), which the helper
         * re-throws as the public `EntQueryRejectedException` for
         * terminal callers. The explain wrapper catches that
         * exception instead and calls this factory to build the plan
         * rather than letting the exception escape.
         */
        public fun rejected(rejection: EntQueryRejectedException): QueryPlan = QueryPlan(
            root = null,
            rejection = rejection,
        )
    }
}

/**
 * Render one ordering term for [QueryPlan.render]'s eager-execution
 * line: `"title DESC"`, `"id ASC"`, or `"embedding <-> ? ASC"` for a
 * distance term (the operand is a bind value, never inlined; the
 * direction is kept because ascending and descending distance orders
 * select different rows into a finite window).
 */
private fun OrderField<*>.describeOrderTerm(): String {
    val d = distance
    return if (d != null) "$field ${d.operator.sql} ? $direction" else "$field $direction"
}
