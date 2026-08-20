package entkt.codegen.query

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.asClassName

private val READ_OPERATION = ClassName("entkt.runtime.query", "ReadOperation")
private val READ_RESULT = ClassName("entkt.runtime.result", "ReadResult")
private val ENT_PRIVACY_DENIED = ClassName("entkt.runtime.result", "EntPrivacyDeniedException")
private val LOAD_DENIAL_ORIGIN = ClassName("entkt.runtime.result", "LoadDenialOrigin")

// ------------------------------------------------------------------
// Canonical row-shaped terminals (all / firstOrNull) and their explain
// mirrors. Each explain builder sits next to the terminal whose driver
// call it models; the query-shape expressions both sides send live in
// QueryShapeSupport.kt — so the plan a caller inspects cannot drift
// from the call the terminal makes. QueryGenerator.generate()
// assembles the members.
// ------------------------------------------------------------------

/**
 * `all(): ReadResult<List<T>>` — the canonical strict collection
 * read. Evaluates the exact selected storage window (`limit` /
 * `offset` keep their ordinary storage-query meaning) and is
 * all-or-nothing under LOAD privacy: if any selected root row is
 * denied, the result is
 * `Failed(EntPrivacyDeniedException(Root, denials))` carrying one
 * keyed [PrivacyDenial] for **every** denied root row in encountered
 * query order — never a partial list. An ordinary exception thrown
 * while evaluating any row's rules becomes `Failed(exception)`
 * instead of an incomplete denial aggregate (the evaluation loop
 * aborts at the throw and the capture boundary stores it).
 *
 * Root privacy completes before eager loading begins; a denied
 * eager target then fails with the `EagerEdge` origin from
 * `loadEdges`. Interceptor rejection is
 * `Failed(EntQueryRejectedException)`; any other exception is stored
 * directly.
 */
internal fun buildAll(schemaName: String, clientName: String, entityClass: ClassName, hasEdges: Boolean): FunSpec {
    val repoPropName = clientName
    val listType = List::class.asClassName().parameterizedBy(entityClass)
    val resultType = READ_RESULT.parameterizedBy(listType)
    return FunSpec.builder("all")
        .returns(resultType)
        .addCode(
            canonicalReadBody(
                CodeBlock.builder()
                    .add("  val c = requireClient()\n")
                    .add("  val privacy = c.currentPrivacyContext()\n")
                    .add("  val spec = runReadInterceptors(%T.ALL, privacy)\n", READ_OPERATION)
                    .add(
                        "  val rows = driver.query(%T.TABLE, spec.predicates, spec.orderBy, spec.limit, spec.offset)\n",
                        entityClass,
                    )
                    .add("  val results = rows.map { %T.fromRow(it) }\n", entityClass)
                    .add("  if (c.%L.hasLoadPrivacy()) {\n", repoPropName)
                    .add("    val denials = c.%L.loadDenials(privacy, results).filterNotNull()\n", repoPropName)
                    .add("    if (denials.isNotEmpty()) {\n")
                    .add(
                        "      return %T.failedForInternalUse(%T(%T.Root, denials))\n",
                        READ_RESULT, ENT_PRIVACY_DENIED, LOAD_DENIAL_ORIGIN,
                    )
                    .add("    }\n")
                    .add("  }\n")
                    .add(
                        "  %T.Success(%L)\n",
                        READ_RESULT,
                        if (hasEdges) "loadEdges(results, privacy)" else "results",
                    )
                    .build(),
                // Hold the activeTerminals guard while the terminal
                // consumes the selected edge-load topology.
                guardEdgeTopology = hasEdges,
            ),
        )
        .build()
}

internal fun buildRowShapedExplain(
    queryPlan: ClassName,
    name: String,
    terminalName: String,
    hasEdges: Boolean,
): FunSpec {
    return FunSpec.builder(name)
        .addKdoc(
            "Return a [QueryPlan] describing the query shapes [$terminalName] would execute.\n" +
            "Interceptors run with operation = ALL. Eager edge subplans show structure,\n" +
            "not multiplicity — nested eager loads may execute once per parent group at\n" +
            "runtime. On interceptor rejection, returns a plan with `rejected = true`\n" +
            "carrying the rejection metadata; explain does NOT throw."
        )
        .returns(queryPlan)
        .addCode(
            explainBody(
                "ALL",
                CodeBlock.of("buildQueryPlan(spec, true, privacy)"),
                guardEdgeTopology = hasEdges,
            ),
        )
        .build()
}

/**
 * `firstOrNull(): ReadResult<T?>` — the canonical single-row read.
 * SQL-shaped and exact-window: the driver executes
 * `minOf(1, spec.limit ?: 1)` rows, so a caller-set `limit(0)` still
 * means "no rows", and choosing a result representation never turns
 * a one-row query into a scan. `Success(entity)` is presence,
 * `Success(null)` is authoritative absence, and a denied first row
 * is `Failed(EntPrivacyDeniedException(Root, listOf(denial)))` —
 * EntKt does not silently scan the second row. Privacy-as-absence is
 * the explicit `visibleOrNull()` projection on the result.
 *
 * The name follows Kotlin's `firstOrNull()` convention for the
 * nullable-success operation; the `ReadResult<T?>` signature states
 * that authoritative absence is a successful null payload while
 * denial and operational failure remain distinguishable.
 */
internal fun buildFirstOrNull(schemaName: String, clientName: String, entityClass: ClassName, hasEdges: Boolean): FunSpec {
    val repoPropName = clientName
    val resultType = READ_RESULT.parameterizedBy(entityClass.copy(nullable = true))
    val happy = CodeBlock.builder()
        .add("  val c = requireClient()\n")
        .add("  val privacy = c.currentPrivacyContext()\n")
        .add("  val spec = runReadInterceptors(%T.FIRST, privacy)\n", READ_OPERATION)
        // `first` semantics: at most one row — but `minOf(1, ...)`
        // rather than a hardwired 1, so an explicit
        // `query { limit(0) }` still means "no rows" here exactly
        // as it does for the exists / all / count families.
        // Interceptor limit mutators are silent no-ops on FIRST, so
        // the only value `spec.limit` can hold is the caller's own
        // bound.
        .add("  val limit = %L\n", SINGLE_ROW_LIMIT_EXPR)
        .add(
            "  val row = driver.query(%T.TABLE, spec.predicates, spec.orderBy, limit, spec.offset).firstOrNull()\n",
            entityClass,
        )
        .add("  val entity = row?.let { %T.fromRow(it) }\n", entityClass)
        .add("  if (entity != null && c.%L.hasLoadPrivacy()) {\n", repoPropName)
        .add("    val denial = c.%L.loadDenialOrNull(privacy, entity)\n", repoPropName)
        .add("    if (denial != null) {\n")
        .add(
            "      return %T.failedForInternalUse(%T(%T.Root, listOf(denial)))\n",
            READ_RESULT, ENT_PRIVACY_DENIED, LOAD_DENIAL_ORIGIN,
        )
        .add("    }\n")
        .add("  }\n")
    if (hasEdges) {
        // No early return before loadEdges: the EAGER_LOAD
        // interceptor pass fires on every configured eager subquery
        // even when no row matched — interceptor firing must not
        // depend on what the database returned, and
        // `explainFirstOrNull` models the eager shapes
        // unconditionally. An empty batch loads nothing. (A root row
        // denied by LOAD privacy has already returned Failed above —
        // root privacy completes before eager loading begins.)
        happy.add(
            "  %T.Success(loadEdges(listOfNotNull(entity), privacy).firstOrNull())\n",
            READ_RESULT,
        )
    } else {
        happy.add("  %T.Success(entity)\n", READ_RESULT)
    }
    return FunSpec.builder("firstOrNull")
        .returns(resultType)
        // Hold the activeTerminals guard while the terminal consumes
        // the selected edge-load topology.
        .addCode(canonicalReadBody(happy.build(), guardEdgeTopology = hasEdges))
        .build()
}

internal fun buildFirstShapedExplain(
    queryPlan: ClassName,
    name: String,
    terminalName: String,
    hasEdges: Boolean,
): FunSpec {
    return FunSpec.builder(name)
        .addKdoc(
            "Return a [QueryPlan] describing the query shapes [$terminalName] would execute.\n" +
            "Interceptors run with operation = FIRST; interceptor limit operations are\n" +
            "silent no-ops for this terminal. Plan limit mirrors the runtime's\n" +
            "`minOf(1, spec.limit ?: 1)` — 1 normally, 0 when the caller passed\n" +
            "`query { limit(0) }`.\n" +
            "On interceptor rejection, returns a plan with `rejected = true`."
        )
        .returns(queryPlan)
        .addCode(
            explainBody(
                "FIRST",
                CodeBlock.of("buildQueryPlan(spec.copy(limit = %L), true, privacy)", SINGLE_ROW_LIMIT_EXPR),
                guardEdgeTopology = hasEdges,
            ),
        )
        .build()
}
