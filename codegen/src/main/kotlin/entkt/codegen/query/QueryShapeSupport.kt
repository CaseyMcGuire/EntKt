package entkt.codegen.query

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock

private val READ_RESULT = ClassName("entkt.runtime.result", "ReadResult")
private val CANCELLATION_EXCEPTION = ClassName("java.util.concurrent", "CancellationException")

// ------------------------------------------------------------------
// Query-shape definitions shared by the canonical data terminals.
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
 * Reason spliced into the generated `requireNoSelectedEdges` guard by
 * every non-entity terminal (raw count / existence / aggregates).
 * Selected edge loads must be rejected, never
 * silently ignored, on terminals that cannot expose loaded edges.
 */
internal const val NON_ENTITY_TERMINAL_EDGE_REASON =
    "this terminal does not return entities and cannot expose loaded edges; " +
        "use an entity terminal such as all() or firstOrNull(), or remove the load calls"

/**
 * Reason spliced into the generated `requireNoSelectedEdges` guard by
 * `query{Name}` traversal methods: a traversal changes the result
 * root, so carrying the source query's selected graph would have no
 * coherent meaning and discarding it silently would be surprising.
 */
internal const val TRAVERSAL_EDGE_REASON =
    "traversal changes the result root and cannot carry the source query's selected " +
        "graph; traverse first and select edge loads on the target query, or " +
        "materialize the source graph with an entity terminal"

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
 *
 * [guardEdgeTopology] marks the terminals that consume the selected
 * edge-load topology (`all` / `firstOrNull` on queries with
 * load-capable edges): they hold the recursive `acquireEdgeTopology`
 * guard — root and every selected target query — for the duration of
 * the body, so `load{Name}` / `filterVisible()` calls made by an
 * interceptor or privacy rule against any query in the in-flight
 * graph are rejected instead of mutating the running execution.
 */
internal fun canonicalReadBody(
    happyPath: CodeBlock,
    guardEdgeTopology: Boolean = false,
): CodeBlock {
    val body = CodeBlock.builder()
    if (guardEdgeTopology) {
        body.add("acquireEdgeTopology()\n")
    }
    body.add("return try {\n")
        .add(happyPath)
        .add("} catch (e: %T) {\n", CANCELLATION_EXCEPTION)
        .add("  throw e\n")
        .add("} catch (e: %T) {\n", ClassName("kotlin", "Exception"))
        .add("  %T.failedForInternalUse(e)\n", READ_RESULT)
    if (guardEdgeTopology) {
        body.add("} finally {\n")
            .add("  releaseEdgeTopology()\n")
    }
    body.add("}\n")
    return body.build()
}
