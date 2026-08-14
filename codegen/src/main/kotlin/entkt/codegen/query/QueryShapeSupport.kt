package entkt.codegen.query

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock

private val READ_OPERATION = ClassName("entkt.runtime.query", "ReadOperation")
private val READ_RESULT = ClassName("entkt.runtime.result", "ReadResult")
private val ENT_QUERY_REJECTED_EXCEPTION = ClassName("entkt.runtime.result", "EntQueryRejectedException")
private val CANCELLATION_EXCEPTION = ClassName("java.util.concurrent", "CancellationException")

// ------------------------------------------------------------------
// Query-shape definitions shared by every terminal/explain pair. The
// row-shaped terminals (QueryRowMembers.kt) and the count/exists/
// aggregate terminals (QueryAggregateMembers.kt) both splice these
// same expressions into their runtime and explain emitters, so a
// terminal's explain plan cannot drift from the driver call it
// models. explainBody is the wrapper every explain method shares:
// run the interceptor chain, turn rejection into a rejected
// QueryPlan, hand the spec to the shape-specific body.
// canonicalReadBody is the wrapper every canonical data terminal
// shares: the RFC's exception-capture boundary around a happy path
// that produces a ReadResult.
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
 * Shared canonical-terminal wrapper: the RFC's exception-capture
 * boundary. [happyPath] must be statements ending in a `ReadResult`
 * expression (constructed via `ReadResult.Success(...)` or
 * `ReadResult.failedForInternalUse(...)`).
 *
 * The boundary rethrows `CancellationException` so structured
 * cancellation works, catches `Exception` (never `Throwable`, so JVM
 * `Error`s propagate), and stores the original exception directly —
 * interceptor rejection arrives here as the typed
 * `EntQueryRejectedException` thrown by `runReadInterceptors`, LOAD
 * denial as the typed `EntPrivacyDeniedException` constructed at its
 * evaluation site, and driver/materialization/rule exceptions as
 * themselves. Read execution never calls driver exception
 * classification.
 */
internal fun canonicalReadBody(happyPath: CodeBlock): CodeBlock =
    CodeBlock.builder()
        .add("return try {\n")
        .add(happyPath)
        .add("} catch (e: %T) {\n", CANCELLATION_EXCEPTION)
        .add("  throw e\n")
        .add("} catch (e: %T) {\n", ClassName("kotlin", "Exception"))
        .add("  %T.failedForInternalUse(e)\n", READ_RESULT)
        .add("}\n")
        .build()

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
            "  val spec = runReadInterceptors(%T.%L)\n",
            READ_OPERATION, operationName,
        )
        .add("  ")
        .add(bodyOnSuccess)
        .add("\n")
        .add("} catch (e: %T) {\n", ENT_QUERY_REJECTED_EXCEPTION)
        .add("  %T.rejected(e)\n", queryPlan)
        .add("}\n")
        .build()
}
