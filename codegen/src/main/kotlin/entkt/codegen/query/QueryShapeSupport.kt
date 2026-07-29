package entkt.codegen.query

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock

private val ENT_OPERATION = ClassName("entkt.runtime.result", "EntOperation")
private val ENT_QUERY_REJECTED_EXCEPTION = ClassName("entkt.runtime.result", "EntQueryRejectedException")
private val READ_OPERATION = ClassName("entkt.runtime.query", "ReadOperation")

// ------------------------------------------------------------------
// Query-shape definitions shared by every terminal/explain pair. The
// row-shaped terminals (QueryRowMembers.kt) and the count/exists/
// aggregate terminals (QueryAggregateMembers.kt) both splice these
// same expressions into their runtime and explain emitters, so a
// terminal's explain plan cannot drift from the driver call it
// models. explainBody is the wrapper every explain method shares:
// run the interceptor chain, turn rejection into a rejected
// QueryPlan, hand the spec to the shape-specific body.
// ------------------------------------------------------------------

/**
 * Single-row probe limit for the first/exists-shaped terminals:
 * 1 normally, 0 when the caller pre-set `query { limit(0) }` — "no
 * rows" must mean no rows on every terminal family. Interceptor limit
 * mutators are silent no-ops on these operations (see
 * `InterceptorEngine.limitOpsApply`), so the only value `spec.limit`
 * can carry here is the caller's own bound.
 */
internal const val SINGLE_ROW_LIMIT_EXPR = "minOf(1, spec.limit ?: 1)"

/**
 * Bounded storage scan for the visible family's privacy path: capped
 * at `EntClientConfig.visibleOverfetchLimit` so the in-process LOAD
 * filter has bounded work, while a caller/interceptor limit below the
 * cap tightens the scan further. [capExpr] is how the emitting site
 * names the cap — `c.visibleOverfetchLimit` inline, or a `cap` local.
 */
internal fun overfetchScanLimitExpr(capExpr: String): String =
    "minOf(spec.limit ?: $capExpr, $capExpr)"

/**
 * Shared explain wrapper: runs the interceptor chain for
 * [operationName], converts an interceptor `scope.reject(...)`
 * into a rejected [QueryPlan] via `QueryPlan.rejected(...)`
 * instead of throwing, and otherwise calls [bodyOnSuccess] with
 * the `spec` local in scope to produce the QueryPlan body. The
 * caller's body is responsible for the final `buildQueryPlan(...)`
 * (or `QueryPlan(driver.explainCount(...))`) expression.
 */
internal fun explainBody(operationName: String, bodyOnSuccess: CodeBlock): CodeBlock {
    val queryPlan = ClassName("entkt.runtime.query", "QueryPlan")
    return CodeBlock.builder()
        .add("return try {\n")
        .add(
            "  val spec = runReadInterceptors(%T.%L, %T.QUERY)\n",
            READ_OPERATION, operationName, ENT_OPERATION,
        )
        .add("  ")
        .add(bodyOnSuccess)
        .add("\n")
        .add("} catch (e: %T) {\n", ENT_QUERY_REJECTED_EXCEPTION)
        .add("  %T.rejected(e.queryRejected)\n", queryPlan)
        .add("}\n")
        .build()
}
