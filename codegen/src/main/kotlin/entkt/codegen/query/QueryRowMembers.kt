package entkt.codegen.query

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.asClassName
import entkt.codegen.pluralize

private val PRIVACY_DENIED = ClassName("entkt.runtime.privacy", "PrivacyDeniedException")
private val ENT_ERROR = ClassName("entkt.runtime.result", "EntError")
private val ENT_OPERATION = ClassName("entkt.runtime.result", "EntOperation")
private val ENT_RESULT = ClassName("entkt.runtime.result", "EntResult")
private val ENT_QUERY_REJECTED_EXCEPTION = ClassName("entkt.runtime.result", "EntQueryRejectedException")
private val READ_OPERATION = ClassName("entkt.runtime.query", "ReadOperation")
private val MEMBER_GET_OR_THROW = com.squareup.kotlinpoet.MemberName("entkt.runtime.result", "getOrThrow")
private val MEMBER_CLASSIFY = com.squareup.kotlinpoet.MemberName("entkt.runtime.driver", "classifyDriverError")

// ------------------------------------------------------------------
// Row-shaped terminals (the all / visibleAll / first / firstVisible
// families) and their explain mirrors. Each explain builder sits next
// to the terminal whose driver call it models; the query-shape
// expressions both sides send live in QueryShapeSupport.kt — so the
// plan a caller inspects cannot drift from the call the terminal
// makes. QueryGenerator.generate() assembles the members.
// ------------------------------------------------------------------

/**
 * `allOrThrow(): List<T>` — strict bulk read. Returns every
 * matching entity; if **any** matched row is denied by LOAD
 * privacy the operation fails with [EntPrivacyDeniedException]
 * (structured EntException family). Driver failures throw
 * [EntDriverException] / [EntConstraintViolationException] per
 * the classifier.
 *
 * Implemented as a `allOrError().getOrThrow()` wrapper so the
 * privacy / driver mapping stays in one place and guarantees the
 * structured-exception contract
 * across the *OrThrow family.
 */
internal fun buildAllOrThrow(schemaName: String, entityClass: ClassName, hasEdges: Boolean): FunSpec {
    return FunSpec.builder("allOrThrow")
        .returns(List::class.asClassName().parameterizedBy(entityClass))
        .addStatement(
            "return allOrError().%M()",
            MEMBER_GET_OR_THROW,
        )
        .build()
}

/**
 * `allOrError(): EntResult<List<T>>` — structured-result bulk
 * read. The canonical entry point for the throw/result pair:
 * maps every failure surface into the matching [EntError] variant,
 * and `allOrThrow` delegates here.
 *
 * Failure mapping:
 *  - any matched row denied by LOAD privacy →
 *    `Err(PrivacyDenied)` (the first denial wins via the
 *    underlying `evaluateLoadPrivacy` raise)
 *  - other uncaught Exception → routed through
 *    [classifyDriverError] so SQLSTATE 23xxx surfaces as
 *    `Err(ConstraintViolation)` and the fallback is
 *    `Err(DriverFailure)`
 */
internal fun buildAllOrError(schemaName: String, entityClass: ClassName, hasEdges: Boolean): FunSpec {
    val repoPropName = pluralize(schemaName.replaceFirstChar { it.lowercase() })
    val listType = List::class.asClassName().parameterizedBy(entityClass)
    val resultType = ENT_RESULT.parameterizedBy(listType)
    return FunSpec.builder("allOrError")
        .returns(resultType)
        .addCode(
            CodeBlock.builder()
                .add("return try {\n")
                .add("  val c = requireClient()\n")
                .add("  val privacy = c.currentPrivacyContext()\n")
                .add(
                    "  val spec = runReadInterceptors(%T.ALL, %T.QUERY)\n",
                    READ_OPERATION, ENT_OPERATION,
                )
                .add(
                    "  val rows = driver.query(%T.TABLE, spec.predicates, spec.orderBy, spec.limit, spec.offset)\n",
                    entityClass,
                )
                .add("  val results = rows.map { %T.fromRow(it) }\n", entityClass)
                .add("  if (c.%L.hasLoadPrivacy()) {\n", repoPropName)
                .add("    for (entity in results) c.%L.evaluateLoadPrivacy(privacy, entity)\n", repoPropName)
                .add("  }\n")
                .add(
                    "  %T.Ok(%L)\n",
                    ENT_RESULT,
                    if (hasEdges) "loadEdges(results, privacy)" else "results",
                )
                .add("} catch (e: %T) {\n", ENT_QUERY_REJECTED_EXCEPTION)
                .add("  %T.Err(e.queryRejected)\n", ENT_RESULT)
                .add("} catch (e: %T) {\n", PRIVACY_DENIED)
                .add(
                    "  %T.Err(%T.PrivacyDenied(e.entity, %T.valueOf(e.operation.name), e.reason))\n",
                    ENT_RESULT, ENT_ERROR, ENT_OPERATION,
                )
                .add("} catch (e: %T) {\n", Exception::class.asClassName())
                .add(
                    "  %T.Err(%M(driver, e, %S, %T.QUERY))\n",
                    ENT_RESULT, MEMBER_CLASSIFY, schemaName, ENT_OPERATION,
                )
                .add("}\n")
                .build(),
        )
        .build()
}

internal fun buildRowShapedExplain(
    queryPlan: ClassName,
    name: String,
    terminalName: String,
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
        .addCode(explainBody("ALL", CodeBlock.of("buildQueryPlan(spec, true)")))
        .build()
}

/**
 * `visibleAll(): List<T>` — filter-only bulk read. Returns the
 * subset of matching **root** rows the current viewer can LOAD.
 * Root rows that fail LOAD privacy are dropped from the result
 * instead of triggering an exception. Driver failures still
 * propagate as raw exceptions; use [visibleAllOrError] for the
 * structured form.
 *
 * **Visible filtering is root-only.** Eager-loaded edge targets
 * via `with...()` still enforce target LOAD privacy strictly —
 * a denied target throws `PrivacyDeniedException` from this
 * call, the same way it does for `allOrThrow`. The rationale
 * (and the workaround — chain visible queries on the target
 * side) is to chain visible queries on the target side.
 *
 * **When the repo has LOAD privacy rules**, the storage scan is
 * bounded by `EntClientConfig.visibleOverfetchLimit` (default
 * 100): the driver fetches at most `min(queryLimit ?: cap, cap)`
 * rows so a few visible rows hidden behind many denied ones
 * don't pull an unbounded result set into memory. Cap exhaustion
 * is silent on this path — the caller gets whatever was visible
 * within the budget; `visibleAllOrError` is the variant that
 * surfaces cap-exhausted as an explicit Err.
 *
 * **When the repo has no LOAD privacy rules**, the cap is
 * skipped entirely — there's nothing to filter in-process, so
 * "visible all" is just "all," and the caller's `queryLimit`
 * (if any) is the only bound. Otherwise a no-privacy entity
 * would silently truncate at the cap which is surprising.
 */
internal fun buildVisibleAll(schemaName: String, entityClass: ClassName, hasEdges: Boolean): FunSpec {
    val repoPropName = pluralize(schemaName.replaceFirstChar { it.lowercase() })
    val listType = List::class.asClassName().parameterizedBy(entityClass)
    val builder = FunSpec.builder("visibleAll")
        .returns(listType)
        .addStatement("val c = requireClient()")
        .addStatement("val privacy = c.currentPrivacyContext()")
        .addStatement(
            "val spec = runReadInterceptors(%T.ALL, %T.QUERY)",
            READ_OPERATION, ENT_OPERATION,
        )
    builder.addCode(
        CodeBlock.builder()
            // No-privacy fast path: skip the overfetch cap entirely
            // and pass the spec's (post-interceptor) limit through.
            // "visible all" == "all" when there's nothing to filter.
            .beginControlFlow("if (!c.%L.hasLoadPrivacy())", repoPropName)
            .addStatement(
                "val rows = driver.query(%T.TABLE, spec.predicates, spec.orderBy, spec.limit, spec.offset)",
                entityClass,
            )
            .addStatement("val results = rows.map { %T.fromRow(it) }", entityClass)
            .also {
                if (hasEdges) {
                    it.addStatement("return loadEdges(results, privacy)")
                } else {
                    it.addStatement("return results")
                }
            }
            .endControlFlow()
            // With LOAD privacy: apply the overfetch cap. The
            // post-interceptor effective limit (spec.limit) is the
            // input to the cap math, so an interceptor's
            // requireLimitAtMost can tighten the scan further.
            .addStatement("val scanLimit = %L", overfetchScanLimitExpr("c.visibleOverfetchLimit"))
            .addStatement(
                "val rows = driver.query(%T.TABLE, spec.predicates, spec.orderBy, scanLimit, spec.offset)",
                entityClass,
            )
            .addStatement("val results = rows.map { %T.fromRow(it) }", entityClass)
            .addStatement(
                "val visible = results.filter { e -> try { c.%L.evaluateLoadPrivacy(privacy, e); true } catch (_: %T) { false } }",
                repoPropName, PRIVACY_DENIED,
            )
            .build()
    )
    if (hasEdges) {
        builder.addStatement("return loadEdges(visible, privacy)")
    } else {
        builder.addStatement("return visible")
    }
    return builder.build()
}

/**
 * `visibleAllOrError(): EntResult<List<T>>` — structured-result
 * filter-only bulk read.
 *
 * **Visible filtering is root-only.** Eager-loaded edge targets
 * via `with...()` still enforce target LOAD privacy strictly —
 * a denied target surfaces as `Err(PrivacyDenied)` from this
 * call, not as a silent root-row drop. Chain visible queries on the
 * target side when target filtering should be non-throwing.
 *
 * **When the repo has LOAD privacy rules**, returns
 * `Err(OverfetchCapExceeded)` when the storage scan hit
 * `EntClientConfig.visibleOverfetchLimit` (meaning more matching
 * rows may exist beyond the cap that we didn't filter). The
 * detection is conservative: "rows returned >= cap" triggers
 * the Err even if the actual row count is exactly `cap`, and
 * `limit(cap)` is not "strictly smaller than cap" so it still
 * triggers — callers either re-query with a larger cap, paginate
 * via `queryOffset`, or accept the partial result.
 *
 * **When the repo has no LOAD privacy rules**, the cap is
 * skipped entirely — there's nothing to filter in-process, so
 * cap-exhaustion is meaningless. The user's `queryLimit` (if
 * any) is the only bound, and `Err(OverfetchCapExceeded)` is
 * never returned. This avoids the surprise of a no-privacy
 * entity being told its 100-row read "exceeded the cap".
 */
internal fun buildVisibleAllOrError(schemaName: String, entityClass: ClassName, hasEdges: Boolean): FunSpec {
    val repoPropName = pluralize(schemaName.replaceFirstChar { it.lowercase() })
    val listType = List::class.asClassName().parameterizedBy(entityClass)
    val resultType = ENT_RESULT.parameterizedBy(listType)
    val builder = FunSpec.builder("visibleAllOrError")
        .returns(resultType)
        .addCode(
            CodeBlock.builder()
                .add("return try {\n")
                .add("  val c = requireClient()\n")
                .add("  val privacy = c.currentPrivacyContext()\n")
                .add(
                    "  val spec = runReadInterceptors(%T.ALL, %T.QUERY)\n",
                    READ_OPERATION, ENT_OPERATION,
                )
                // No-privacy fast path: skip the overfetch cap
                // entirely. With no in-process filter, "visible
                // all" reduces to "all" and the spec's (post-
                // interceptor) limit is the only bound. Cap-
                // exhaustion has no semantic meaning here.
                .add("  if (!c.%L.hasLoadPrivacy()) {\n", repoPropName)
                .add(
                    "    val rows = driver.query(%T.TABLE, spec.predicates, spec.orderBy, spec.limit, spec.offset)\n",
                    entityClass,
                )
                .add("    val results = rows.map { %T.fromRow(it) }\n", entityClass)
                .add(
                    "    %T.Ok(%L)\n",
                    ENT_RESULT,
                    if (hasEdges) "loadEdges(results, privacy)" else "results",
                )
                .add("  } else {\n")
                .add("    val cap = c.visibleOverfetchLimit\n")
                .add("    val scanLimit = %L\n", overfetchScanLimitExpr("cap"))
                .add(
                    "    val rows = driver.query(%T.TABLE, spec.predicates, spec.orderBy, scanLimit, spec.offset)\n",
                    entityClass,
                )
                .add(
                    "    val results = rows.map { %T.fromRow(it) }\n",
                    entityClass,
                )
                .add(
                    "    val visible = results.filter { e -> try { c.%L.evaluateLoadPrivacy(privacy, e); true } catch (_: %T) { false } }\n",
                    repoPropName, PRIVACY_DENIED,
                )
                // Eager-load BEFORE checking the cap, so eager
                // target privacy denial (which raises
                // PrivacyDeniedException) wins over cap
                // exhaustion. Privacy denial is a hard signal
                // (caller should know about the mismatch); cap
                // exhaustion is a heuristic ("you should
                // paginate"). If both apply, the hard signal
                // surfaces through the dedicated
                // catch (PrivacyDeniedException) arm below.
                .add(
                    "    val finalRows = %L\n",
                    if (hasEdges) "loadEdges(visible, privacy)" else "visible",
                )
                // Cap exhaustion (privacy path only): we asked
                // the driver for `cap` rows and got at least that
                // many. Suppress the Err only when the caller
                // bounded the scan strictly smaller than the cap.
                // Uses spec.limit (post-interceptor) so a
                // requireLimitAtMost(N<cap) clamp still
                // suppresses the Err.
                .add("    val capturedLimit = spec.limit\n")
                .add("    if (rows.size >= cap && (capturedLimit == null || capturedLimit >= cap)) {\n")
                .add(
                    "      %T.Err(%T.OverfetchCapExceeded(%S, %T.QUERY, cap))\n",
                    ENT_RESULT, ENT_ERROR, schemaName, ENT_OPERATION,
                )
                .add("    } else {\n")
                .add("      %T.Ok(finalRows)\n", ENT_RESULT)
                .add("    }\n")
                .add("  }\n")
                // Eager-edge LOAD denial via loadEdges(...) can
                // re-raise PrivacyDeniedException. Without this
                // explicit catch arm it would fall through to the
                // generic Exception arm and be misclassified as
                // Err(DriverFailure). Same shape as allOrError /
                // firstOrError.
                .add("} catch (e: %T) {\n", ENT_QUERY_REJECTED_EXCEPTION)
                .add("  %T.Err(e.queryRejected)\n", ENT_RESULT)
                .add("} catch (e: %T) {\n", PRIVACY_DENIED)
                .add(
                    "  %T.Err(%T.PrivacyDenied(e.entity, %T.valueOf(e.operation.name), e.reason))\n",
                    ENT_RESULT, ENT_ERROR, ENT_OPERATION,
                )
                .add("} catch (e: %T) {\n", Exception::class.asClassName())
                .add(
                    "  %T.Err(%M(driver, e, %S, %T.QUERY))\n",
                    ENT_RESULT, MEMBER_CLASSIFY, schemaName, ENT_OPERATION,
                )
                .add("}\n")
                .build(),
        )
    return builder.build()
}

/**
 * Visible row-shaped explain — branches on `hasLoadPrivacy()`
 * so the plan matches the runtime driver call. On the privacy
 * path the runtime overfetches up to `visibleOverfetchLimit`,
 * filtering denied rows in Kotlin; on the no-privacy path the
 * caller's `spec.limit` passes through unchanged.
 */
internal fun buildVisibleRowShapedExplain(
    queryPlan: ClassName,
    name: String,
    terminalName: String,
    repoPropName: String,
): FunSpec {
    val body = CodeBlock.builder()
        .addStatement("val c = requireClient()")
        .beginControlFlow("if (!c.%L.hasLoadPrivacy())", repoPropName)
        .addStatement("buildQueryPlan(spec, true)")
        .nextControlFlow("else")
        .addStatement("val cap = c.visibleOverfetchLimit")
        .addStatement("val scanLimit = %L", overfetchScanLimitExpr("cap"))
        .addStatement("buildQueryPlan(spec.copy(limit = scanLimit), true)")
        .endControlFlow()
        .build()
    return FunSpec.builder(name)
        .addKdoc(
            "Return a [QueryPlan] describing the query shape [$terminalName] would execute.\n" +
            "Interceptors run with operation = ALL. On the no-privacy fast path the\n" +
            "plan uses `spec.limit` directly; on the privacy path the plan uses\n" +
            "`minOf(spec.limit ?: cap, cap)` where `cap` is\n" +
            "`EntClientConfig.visibleOverfetchLimit`, matching the runtime overfetch\n" +
            "behavior. On interceptor rejection, returns a plan with `rejected = true`."
        )
        .returns(queryPlan)
        .addCode(explainBody("ALL", body))
        .build()
}

/**
 * Terminal op: ask the driver for one row and stop, enforcing LOAD
 * privacy on the result.
 *
 * The fetch is `min(queryLimit ?: 1, 1)`, so a caller-set
 * `limit(0)` means "no rows" here just as it does on `allOrThrow` /
 * `rawExists` / `visibleCount`. `limit(n > 0)` is already satisfied
 * by the single-row fetch.
 */
internal fun buildFirstOrNull(schemaName: String, entityClass: ClassName, hasEdges: Boolean): FunSpec {
    val repoPropName = pluralize(schemaName.replaceFirstChar { it.lowercase() })
    val builder = FunSpec.builder("firstOrNull")
        .returns(entityClass.copy(nullable = true))
        .addStatement("val c = requireClient()")
        .addStatement("val privacy = c.currentPrivacyContext()")
        .addStatement(
            "val spec = runReadInterceptors(%T.FIRST, %T.QUERY)",
            READ_OPERATION, ENT_OPERATION,
        )
        // `first` semantics: at most one row — but `minOf(1, ...)`
        // rather than a hardwired 1, so an explicit
        // `query { limit(0) }` still means "no rows" here exactly
        // as it does for the exists / all / count families. Note
        // what `spec.limit` can hold at this point: interceptor
        // limit mutators are silent no-ops on FIRST (see
        // InterceptorEngine.limitOpsApply), so the only value that
        // ever reaches here is the caller's own bound — discarding
        // it would discard the one input it can't be wrong about.
        .addStatement("val limit = %L", SINGLE_ROW_LIMIT_EXPR)
        .addStatement(
            "val row = driver.query(%T.TABLE, spec.predicates, spec.orderBy, limit, spec.offset).firstOrNull()",
            entityClass,
        )
    if (hasEdges) {
        // No `?: return null` before loadEdges: the EAGER_LOAD
        // interceptor pass fires on every configured eager subquery
        // even when no row matched — interceptor firing must not
        // depend on what the database returned, and
        // `explainFirstOrNull` models the eager shapes
        // unconditionally. An empty batch loads nothing. (A matched
        // row denied by root LOAD privacy still throws before this
        // call — strict reads abort before edge loading.)
        builder.addStatement("val entity = row?.let { %T.fromRow(it) }", entityClass)
        builder.addStatement("if (entity != null && c.%L.hasLoadPrivacy()) c.%L.evaluateLoadPrivacy(privacy, entity)", repoPropName, repoPropName)
        builder.addStatement("return loadEdges(listOfNotNull(entity), privacy).firstOrNull()")
    } else {
        builder.addStatement("val entity = row?.let { %T.fromRow(it) } ?: return null", entityClass)
        builder.addStatement("if (c.%L.hasLoadPrivacy()) c.%L.evaluateLoadPrivacy(privacy, entity)", repoPropName, repoPropName)
        builder.addStatement("return entity")
    }
    return builder.build()
}

/**
 * `firstOrThrow(): T` — strict first-row read. Throws structured
 * EntException subclasses for every failure surface (NotFound on
 * empty match, PrivacyDenied on denial, DriverFailure on
 * uncategorized exceptions). Wraps [firstOrError].
 */
internal fun buildFirstOrThrow(schemaName: String, entityClass: ClassName): FunSpec {
    return FunSpec.builder("firstOrThrow")
        .returns(entityClass)
        .addStatement("return firstOrError().%M()", MEMBER_GET_OR_THROW)
        .build()
}

/**
 * `firstOrError(): EntResult<T>` — structured-result first-row
 * read. Empty match → Err(NotFound); privacy denial →
 * Err(PrivacyDenied); other uncaught Exception routed through
 * [classifyDriverError].
 *
 * NotFound carries `id = null` because a query-level "first row"
 * has no identifying id to attribute the miss to.
 */
internal fun buildFirstOrError(schemaName: String, entityClass: ClassName): FunSpec {
    val resultType = ENT_RESULT.parameterizedBy(entityClass)
    return FunSpec.builder("firstOrError")
        .returns(resultType)
        .addCode(
            CodeBlock.builder()
                .add("return try {\n")
                .add(
                    "  firstOrNull()?.let { %T.Ok(it) } ?: %T.Err(%T.NotFound(%S, %T.QUERY))\n",
                    ENT_RESULT, ENT_RESULT, ENT_ERROR, schemaName, ENT_OPERATION,
                )
                .add("} catch (e: %T) {\n", ENT_QUERY_REJECTED_EXCEPTION)
                .add("  %T.Err(e.queryRejected)\n", ENT_RESULT)
                .add("} catch (e: %T) {\n", PRIVACY_DENIED)
                .add(
                    "  %T.Err(%T.PrivacyDenied(e.entity, %T.valueOf(e.operation.name), e.reason))\n",
                    ENT_RESULT, ENT_ERROR, ENT_OPERATION,
                )
                .add("} catch (e: %T) {\n", Exception::class.asClassName())
                .add(
                    "  %T.Err(%M(driver, e, %S, %T.QUERY))\n",
                    ENT_RESULT, MEMBER_CLASSIFY, schemaName, ENT_OPERATION,
                )
                .add("}\n")
                .build(),
        )
        .build()
}

internal fun buildFirstShapedExplain(
    queryPlan: ClassName,
    name: String,
    terminalName: String,
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
            explainBody("FIRST", CodeBlock.of("buildQueryPlan(spec.copy(limit = %L), true)", SINGLE_ROW_LIMIT_EXPR)),
        )
        .build()
}

/**
 * `firstVisibleOrNull(): T?` — scans matched **root** rows in
 * storage order and returns the first row LOAD privacy allows.
 * Returns `null` if no matched root row is visible OR if the cap
 * was exhausted before finding one. Driver failures still
 * propagate as raw exceptions.
 *
 * **Visible filtering is root-only.** Eager-loaded edge targets
 * via `with...()` still enforce target LOAD privacy strictly —
 * a denied target throws `PrivacyDeniedException` from this
 * call. The "visible" name guarantees the *root entity*
 * survives privacy filtering; it does not recursively apply to
 * the eager subgraph.
 *
 * **When the repo has LOAD privacy rules**, scanning is bounded
 * by `EntClientConfig.visibleOverfetchLimit` (default 100): at
 * most `min(queryLimit ?: cap, cap)` rows are pulled from
 * storage. Cap-exhaustion is silent — the "no
 * visible row found within the work budget" outcome is
 * indistinguishable from genuine absence, which is fine for the
 * optimistic-read shape this method advertises.
 *
 * **When the repo has no LOAD privacy rules**, at most one row is
 * fetched (`min(queryLimit ?: 1, 1)`) — there's no in-process
 * filter that might skip rows, so the first row from storage is
 * the answer. Skipping the cap avoids pulling 100 rows just to
 * return one.
 *
 * Both branches bound the fetch by the caller's `limit(n)`, so
 * `query { limit(0) }.firstVisibleOrNull()` is null either way —
 * whether the repo has LOAD privacy rules is not observable in the
 * result.
 */
internal fun buildFirstVisibleOrNull(schemaName: String, entityClass: ClassName, hasEdges: Boolean): FunSpec {
    val repoPropName = pluralize(schemaName.replaceFirstChar { it.lowercase() })
    val builder = FunSpec.builder("firstVisibleOrNull")
        .returns(entityClass.copy(nullable = true))
        .addStatement("val c = requireClient()")
        .addStatement("val privacy = c.currentPrivacyContext()")
        .addStatement(
            "val spec = runReadInterceptors(%T.FIRST, %T.QUERY)",
            READ_OPERATION, ENT_OPERATION,
        )
    builder.addCode(
        CodeBlock.builder()
            // No-privacy fast path: only fetch 1 row. Nothing to
            // skip on the filter side, so the first storage row is
            // the answer. Skipping the cap-sized scan avoids
            // pulling up to 100 rows just to return one.
            //
            // `minOf(1, spec.limit ?: 1)`, not a bare 1: the privacy
            // branch below derives its scan from `spec.limit` and so
            // returns null on `limit(0)`. A hardwired 1 here would
            // make the same call on the same rows answer differently
            // depending on whether the repo happens to have LOAD
            // privacy rules — the branch must not be observable.
            .beginControlFlow("if (!c.%L.hasLoadPrivacy())", repoPropName)
            .addStatement("val limit = %L", SINGLE_ROW_LIMIT_EXPR)
            .also {
                if (hasEdges) {
                    // No `?: return null` before loadEdges — same
                    // contract as firstOrNull: the EAGER_LOAD
                    // interceptor pass fires even when no row
                    // matched. An empty batch loads nothing.
                    it.addStatement(
                        "val row = driver.query(%T.TABLE, spec.predicates, spec.orderBy, limit, spec.offset).firstOrNull()",
                        entityClass,
                    )
                    it.addStatement("val entity = row?.let { %T.fromRow(it) }", entityClass)
                    it.addStatement("return loadEdges(listOfNotNull(entity), privacy).firstOrNull()")
                } else {
                    it.addStatement(
                        "val row = driver.query(%T.TABLE, spec.predicates, spec.orderBy, limit, spec.offset).firstOrNull() ?: return null",
                        entityClass,
                    )
                    it.addStatement("val entity = %T.fromRow(row)", entityClass)
                    it.addStatement("return entity")
                }
            }
            .endControlFlow()
            // With LOAD privacy: cap the scan so the in-process
            // filter has bounded work even when many storage rows
            // are denied. Uses spec.limit so a
            // requireLimitAtMost(N<cap) clamp tightens further.
            .addStatement("val scanLimit = %L", overfetchScanLimitExpr("c.visibleOverfetchLimit"))
            .addStatement(
                "val rows = driver.query(%T.TABLE, spec.predicates, spec.orderBy, scanLimit, spec.offset)",
                entityClass,
            )
            // IMPORTANT: scope the PrivacyDeniedException catch
            // around evaluateLoadPrivacy ONLY — not around
            // loadEdges. The "visible" contract is root-only:
            // a denied root row silently skips to the next
            // candidate, but a denied EAGER TARGET must throw
            // PrivacyDeniedException out of this call (same
            // shape visibleAll / visibleAllOrError use). Earlier
            // codegen wrapped both calls in one try, which
            // swallowed eager-target denial as if the root were
            // invisible.
            .beginControlFlow("for (row in rows)")
            .addStatement("val entity = %T.fromRow(row)", entityClass)
            .beginControlFlow("val rootVisible = try")
            .addStatement("c.%L.evaluateLoadPrivacy(privacy, entity)", repoPropName)
            .addStatement("true")
            .nextControlFlow("catch (_: %T)", PRIVACY_DENIED)
            .addStatement("false")
            .endControlFlow()
            .addStatement("if (!rootVisible) continue")
            .also {
                if (hasEdges) it.addStatement("return loadEdges(listOf(entity), privacy).first()")
                else it.addStatement("return entity")
            }
            .endControlFlow()
            .also {
                // Loop exhausted without a visible root: with eager
                // edges configured, run the EAGER_LOAD pass once on
                // an empty batch — interceptor firing must not
                // depend on which rows were visible.
                if (hasEdges) it.addStatement("return loadEdges(emptyList(), privacy).firstOrNull()")
                else it.addStatement("return null")
            }
            .build()
    )
    return builder.build()
}

/**
 * `explainFirstVisibleOrNull` — on the no-privacy fast path
 * fetches a single row; on the privacy path scans up to
 * `visibleOverfetchLimit` to give the in-process filter
 * something to work with. Mirror that branching in explain.
 */
internal fun buildFirstVisibleExplain(
    queryPlan: ClassName,
    name: String,
    terminalName: String,
    repoPropName: String,
): FunSpec {
    val body = CodeBlock.builder()
        .addStatement("val c = requireClient()")
        .beginControlFlow("if (!c.%L.hasLoadPrivacy())", repoPropName)
        .addStatement("buildQueryPlan(spec.copy(limit = %L), true)", SINGLE_ROW_LIMIT_EXPR)
        .nextControlFlow("else")
        .addStatement("val cap = c.visibleOverfetchLimit")
        .addStatement("val scanLimit = %L", overfetchScanLimitExpr("cap"))
        .addStatement("buildQueryPlan(spec.copy(limit = scanLimit), true)")
        .endControlFlow()
        .build()
    return FunSpec.builder(name)
        .addKdoc(
            "Return a [QueryPlan] describing the query shape [$terminalName] would execute.\n" +
            "Interceptors run with operation = FIRST. On the no-privacy fast path the\n" +
            "plan uses `limit = minOf(1, spec.limit ?: 1)`; on the privacy path it uses\n" +
            "`minOf(spec.limit ?: cap, cap)` where `cap` is\n" +
            "`EntClientConfig.visibleOverfetchLimit`, matching the runtime cap-bounded\n" +
            "scan. On interceptor rejection, returns a plan with `rejected = true`."
        )
        .returns(queryPlan)
        .addCode(explainBody("FIRST", body))
        .build()
}
