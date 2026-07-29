package entkt.codegen

import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.BOOLEAN
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.DOUBLE
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.LIST
import com.squareup.kotlinpoet.LONG
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.STAR
import com.squareup.kotlinpoet.STRING
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.TypeVariableName
import com.squareup.kotlinpoet.asClassName

private val PRIVACY_DENIED = ClassName("entkt.runtime.privacy", "PrivacyDeniedException")
private val ENT_ERROR = ClassName("entkt.runtime.result", "EntError")
private val ENT_OPERATION = ClassName("entkt.runtime.result", "EntOperation")
private val ENT_RESULT = ClassName("entkt.runtime.result", "EntResult")
private val ENT_QUERY_REJECTED_EXCEPTION = ClassName("entkt.runtime.result", "EntQueryRejectedException")
private val READ_OPERATION = ClassName("entkt.runtime.query", "ReadOperation")
private val MEMBER_CLASSIFY = com.squareup.kotlinpoet.MemberName("entkt.runtime.driver", "classifyDriverError")

// Raw aggregate terminals.
private val AGG_FUNCTION = ClassName("entkt.runtime.query", "AggregateFunction")
private val AGG_BUCKET = ClassName("entkt.runtime.query", "AggregateBucket")
private val AGG_RESULT_ROW = ClassName("entkt.runtime.query", "AggregateResultRow")
private val COMPARABLE_COLUMN = ClassName("entkt.query", "ComparableColumn")
private val NUMERIC_COLUMN = ClassName("entkt.query", "NumericColumn")
private val INTEGRAL_COLUMN = ClassName("entkt.query", "IntegralColumn")
private val FLOATING_COLUMN = ClassName("entkt.query", "FloatingColumn")
private val GROUPABLE_COLUMN = ClassName("entkt.query", "GroupableColumn")
private val NULLABLE_GROUPABLE_COLUMN = ClassName("entkt.query", "NullableGroupableColumn")
private val KOTLIN_COMPARABLE = ClassName("kotlin", "Comparable")

// ------------------------------------------------------------------
// Count, exists, and raw-aggregate terminals with their explain
// mirrors. Same pairing rule as QueryRowMembers.kt: each explain
// builder sits next to the terminal whose driver call it models, and
// the shared query-shape expressions and explainBody wrapper come
// from QueryShapeSupport.kt. QueryGenerator.generate() assembles the
// members.
// ------------------------------------------------------------------

/**
 * Terminal op: count matching rows without materializing them.
 * This is a raw aggregate -- LOAD privacy is not evaluated.
 */
internal fun buildRawCount(schemaName: String, entityClass: ClassName): FunSpec {
    return FunSpec.builder("rawCount")
        .addKdoc("Count matching rows. This is a raw aggregate that does not evaluate LOAD privacy.\n" +
            "Use [visibleCount] for a privacy-aware count.")
        .returns(LONG)
        .addStatement(
            "val spec = runReadInterceptors(%T.RAW_COUNT, %T.QUERY)",
            READ_OPERATION, ENT_OPERATION,
        )
        .addStatement("return driver.count(%T.TABLE, spec.predicates)", entityClass)
        .build()
}

/**
 * `rawCountOrError(): EntResult<Long>` — structured-result form of
 * [rawCount]. Maps each failure mode to its [EntError] variant:
 *  - interceptor rejection → `Err(QueryRejected)`
 *  - any other uncaught Exception → routed through
 *    [classifyDriverError]
 *
 * There is no PrivacyDenied surface here — rawCount intentionally
 * bypasses LOAD privacy, so PrivacyDeniedException can't occur on
 * this path.
 */
internal fun buildRawCountOrError(schemaName: String, entityClass: ClassName): FunSpec {
    val resultType = ENT_RESULT.parameterizedBy(LONG)
    return FunSpec.builder("rawCountOrError")
        .returns(resultType)
        .addCode(
            CodeBlock.builder()
                .add("return try {\n")
                .add(
                    "  val spec = runReadInterceptors(%T.RAW_COUNT, %T.QUERY)\n",
                    READ_OPERATION, ENT_OPERATION,
                )
                .add(
                    "  %T.Ok(driver.count(%T.TABLE, spec.predicates))\n",
                    ENT_RESULT, entityClass,
                )
                .add("} catch (e: %T) {\n", ENT_QUERY_REJECTED_EXCEPTION)
                .add("  %T.Err(e.queryRejected)\n", ENT_RESULT)
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

internal fun buildRawCountExplain(queryPlan: ClassName, entityClass: ClassName): FunSpec {
    // explainRawCount uses driver.explainCount (a COUNT(*) plan)
    // rather than the row-fetch buildQueryPlan path.
    val body = CodeBlock.of(
        "%T(driver.explainCount(%T.TABLE, spec.predicates), annotations = spec.annotations)",
        queryPlan, entityClass,
    )
    return FunSpec.builder("explainRawCount")
        .addKdoc(
            "Return a [QueryPlan] describing the COUNT query [rawCount] would execute.\n" +
            "Interceptors run with operation = RAW_COUNT; predicate contributions show\n" +
            "up in the plan, limit operations are silent no-ops by contract.\n" +
            "On interceptor rejection, returns a plan with `rejected = true`."
        )
        .returns(queryPlan)
        .addCode(explainBody("RAW_COUNT", body))
        .build()
}

/**
 * Terminal op: count entities visible to the current viewer.
 * Materializes all matching rows, evaluates LOAD privacy on each,
 * and returns the count of allowed entities.
 */
internal fun buildVisibleCount(schemaName: String, entityClass: ClassName): FunSpec {
    val repoPropName = pluralize(schemaName.replaceFirstChar { it.lowercase() })
    val builder = FunSpec.builder("visibleCount")
        .addKdoc("Count entities visible to the current viewer. Materializes matching rows and\n" +
            "evaluates LOAD privacy on each, returning only the count of allowed entities.\n" +
            "Use [rawCount] for a fast aggregate that skips privacy.")
        .returns(LONG)
        .addStatement("val c = requireClient()")
        .addStatement("val privacy = c.currentPrivacyContext()")
        .addStatement(
            "val spec = runReadInterceptors(%T.VISIBLE_COUNT, %T.QUERY)",
            READ_OPERATION, ENT_OPERATION,
        )
        .addStatement(
            "val rows = driver.query(%T.TABLE, spec.predicates, spec.orderBy, spec.limit, spec.offset)",
            entityClass,
        )
        .addStatement("val results = rows.map { %T.fromRow(it) }", entityClass)
    builder.addCode(CodeBlock.builder()
        .beginControlFlow("if (!c.%L.hasLoadPrivacy())", repoPropName)
        .addStatement("return results.size.toLong()")
        .endControlFlow()
        .build()
    )
    builder.addCode(CodeBlock.builder()
        .addStatement("var count = 0L")
        .beginControlFlow("for (entity in results)")
        .beginControlFlow("try")
        .addStatement("c.%L.evaluateLoadPrivacy(privacy, entity)", repoPropName)
        .addStatement("count++")
        .nextControlFlow("catch (_: %T)", PRIVACY_DENIED)
        .endControlFlow()
        .endControlFlow()
        .addStatement("return count")
        .build()
    )
    return builder.build()
}

/**
 * `visibleCountOrError(): EntResult<Long>` — structured-result form
 * of [visibleCount]. Maps each failure mode to its [EntError] variant.
 *
 * Visible-count materializes rows to evaluate LOAD privacy, so the
 * same PrivacyDeniedException catch arm used by [allOrError] applies
 * (e.g. an eager edge's denied target re-raises). Denied root rows
 * are silently filtered (per visibleCount semantics) — those don't
 * surface as PrivacyDenied here.
 */
internal fun buildVisibleCountOrError(schemaName: String, entityClass: ClassName): FunSpec {
    val repoPropName = pluralize(schemaName.replaceFirstChar { it.lowercase() })
    val resultType = ENT_RESULT.parameterizedBy(LONG)
    return FunSpec.builder("visibleCountOrError")
        .returns(resultType)
        .addCode(
            CodeBlock.builder()
                .add("return try {\n")
                .add("  val c = requireClient()\n")
                .add("  val privacy = c.currentPrivacyContext()\n")
                .add(
                    "  val spec = runReadInterceptors(%T.VISIBLE_COUNT, %T.QUERY)\n",
                    READ_OPERATION, ENT_OPERATION,
                )
                .add(
                    "  val rows = driver.query(%T.TABLE, spec.predicates, spec.orderBy, spec.limit, spec.offset)\n",
                    entityClass,
                )
                .add("  val results = rows.map { %T.fromRow(it) }\n", entityClass)
                .add("  if (!c.%L.hasLoadPrivacy()) {\n", repoPropName)
                .add("    %T.Ok(results.size.toLong())\n", ENT_RESULT)
                .add("  } else {\n")
                .add("    var count = 0L\n")
                .add("    for (entity in results) {\n")
                .add("      try { c.%L.evaluateLoadPrivacy(privacy, entity); count++ } catch (_: %T) {}\n", repoPropName, PRIVACY_DENIED)
                .add("    }\n")
                .add("    %T.Ok(count)\n", ENT_RESULT)
                .add("  }\n")
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

internal fun buildVisibleCountExplain(queryPlan: ClassName, name: String): FunSpec {
    return FunSpec.builder(name)
        .addKdoc(
            "Return a [QueryPlan] describing the query shape [visibleCount] would execute.\n" +
            "Interceptors run with operation = VISIBLE_COUNT; limit operations are\n" +
            "silent no-ops by contract. On interceptor rejection, returns a plan with\n" +
            "`rejected = true`."
        )
        .returns(queryPlan)
        .addCode(explainBody("VISIBLE_COUNT", CodeBlock.of("buildQueryPlan(spec, false)")))
        .build()
}

/**
 * `rawExists(): Boolean` — fast existence check that skips
 * privacy entirely. Returns true iff at least one storage row
 * matches the predicate. Use this for "does this row exist at
 * all?" semantics (uniqueness checks before insert, idempotency
 * keys, etc.) where privacy of the caller is not the relevant
 * question.
 *
 * Replaces the legacy `exists()` that fetched one row and threw
 * `PrivacyDeniedException` if it was denied — neither "do any
 * rows exist?" nor "is there a row I can see?" was the answer
 * you got, which surprised callers. Use [visibleExists] for the
 * privacy-aware variant.
 */
internal fun buildRawExists(entityClass: ClassName): FunSpec {
    return FunSpec.builder("rawExists")
        .returns(BOOLEAN)
        .addKdoc(
            "Fast existence check; skips LOAD privacy. Returns true iff at least one\n" +
            "storage row matches the predicate. Pair with [visibleExists] for the\n" +
            "privacy-aware variant.",
        )
        .addStatement(
            "val spec = runReadInterceptors(%T.RAW_EXISTS, %T.QUERY)",
            READ_OPERATION, ENT_OPERATION,
        )
        // exists is fixed at limit-1 — interceptor clamps can
        // only further restrict (to 0) so honor spec.limit if
        // it's been set lower than 1.
        .addStatement("val limit = %L", SINGLE_ROW_LIMIT_EXPR)
        .addStatement(
            "return driver.query(%T.TABLE, spec.predicates, emptyList(), limit, spec.offset).isNotEmpty()",
            entityClass,
        )
        .build()
}

/**
 * `rawExistsOrError(): EntResult<Boolean>` — structured-result form
 * of [rawExists]. Same failure-mode mapping as [rawCountOrError]:
 * interceptor rejection → `Err(QueryRejected)`, driver failure →
 * `Err(DriverFailure)`. No PrivacyDenied surface (rawExists bypasses
 * LOAD privacy).
 */
internal fun buildRawExistsOrError(schemaName: String, entityClass: ClassName): FunSpec {
    val resultType = ENT_RESULT.parameterizedBy(BOOLEAN)
    return FunSpec.builder("rawExistsOrError")
        .returns(resultType)
        .addCode(
            CodeBlock.builder()
                .add("return try {\n")
                .add(
                    "  val spec = runReadInterceptors(%T.RAW_EXISTS, %T.QUERY)\n",
                    READ_OPERATION, ENT_OPERATION,
                )
                .add("  val limit = %L\n", SINGLE_ROW_LIMIT_EXPR)
                .add(
                    "  %T.Ok(driver.query(%T.TABLE, spec.predicates, emptyList(), limit, spec.offset).isNotEmpty())\n",
                    ENT_RESULT, entityClass,
                )
                .add("} catch (e: %T) {\n", ENT_QUERY_REJECTED_EXCEPTION)
                .add("  %T.Err(e.queryRejected)\n", ENT_RESULT)
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

internal fun buildExistsShapedExplain(
    queryPlan: ClassName,
    name: String,
    terminalName: String,
    operationName: String,
): FunSpec {
    // Runtime: `driver.query(TABLE, spec.predicates,
    // emptyList(), minOf(1, spec.limit ?: 1), spec.offset)`.
    // The orderBy is dropped (no point ordering for an
    // existence probe) but the caller's offset is preserved
    // (so `query { offset(5) }.rawExists()` skips the first
    // 5 rows and asks "is there a 6th?"). Mirror exactly so
    // the explain plan matches the driver call. Note the
    // limit mirrors the runtime's `minOf(1, spec.limit ?: 1)`
    // so a caller who pre-set `limit(0)` shows up as `limit
    // = 0` in the plan.
    return FunSpec.builder(name)
        .addKdoc(
            "Return a [QueryPlan] describing the query shape [$terminalName] would execute.\n" +
            "Interceptors run with operation = $operationName; limit operations are\n" +
            "silent no-ops by contract. Plan mirrors the runtime exactly:\n" +
            "`orderBy = emptyList()` (existence probe doesn't order),\n" +
            "`limit = minOf(1, spec.limit ?: 1)` (usually 1, 0 when the caller\n" +
            "passed `query { limit(0) }`), and `offset = spec.offset` (caller's\n" +
            "offset is preserved). On interceptor rejection, returns a plan with\n" +
            "`rejected = true`."
        )
        .returns(queryPlan)
        .addCode(
            explainBody(
                operationName,
                CodeBlock.of("buildQueryPlan(spec.copy(orderBy = emptyList(), limit = %L), false)", SINGLE_ROW_LIMIT_EXPR),
            ),
        )
        .build()
}

internal fun buildVisibleExists(schemaName: String, entityClass: ClassName): FunSpec {
    val repoPropName = pluralize(schemaName.replaceFirstChar { it.lowercase() })
    val builder = FunSpec.builder("visibleExists")
        .returns(BOOLEAN)
        .addKdoc(
            "Privacy-aware existence check. Returns true iff at least one storage row\n" +
            "matches AND the viewer can LOAD it. Bounded by `visibleOverfetchLimit` on\n" +
            "the privacy path; cap-exhausted-with-no-visible returns false silently.",
        )
        .addStatement("val c = requireClient()")
        .addStatement("val privacy = c.currentPrivacyContext()")
        .addStatement(
            "val spec = runReadInterceptors(%T.VISIBLE_EXISTS, %T.QUERY)",
            READ_OPERATION, ENT_OPERATION,
        )
    builder.addCode(
        CodeBlock.builder()
            // No-privacy fast path: single-row probe, no cap.
            .beginControlFlow("if (!c.%L.hasLoadPrivacy())", repoPropName)
            .addStatement("val limit = %L", SINGLE_ROW_LIMIT_EXPR)
            .addStatement(
                "return driver.query(%T.TABLE, spec.predicates, emptyList(), limit, spec.offset).isNotEmpty()",
                entityClass,
            )
            .endControlFlow()
            // Privacy path: cap the scan, return true on first
            // visible row, false if cap exhausted or no rows.
            .addStatement("val scanLimit = %L", overfetchScanLimitExpr("c.visibleOverfetchLimit"))
            .addStatement(
                "val rows = driver.query(%T.TABLE, spec.predicates, spec.orderBy, scanLimit, spec.offset)",
                entityClass,
            )
            .beginControlFlow("for (row in rows)")
            .addStatement("val entity = %T.fromRow(row)", entityClass)
            .beginControlFlow("try")
            .addStatement("c.%L.evaluateLoadPrivacy(privacy, entity)", repoPropName)
            .addStatement("return true")
            .nextControlFlow("catch (_: %T)", PRIVACY_DENIED)
            .endControlFlow()
            .endControlFlow()
            .addStatement("return false")
            .build()
    )
    return builder.build()
}

/**
 * `visibleExistsOrError(): EntResult<Boolean>` — structured-result
 * form of [visibleExists]. Same failure-mode mapping as the *OrError
 * counterparts: interceptor rejection → `Err(QueryRejected)`, driver
 * failure → `Err(DriverFailure)`. Denied rows are silently scanned
 * past per visibleExists semantics — only an eager-edge denial path
 * (which visibleExists doesn't have) could surface PrivacyDenied.
 */
internal fun buildVisibleExistsOrError(schemaName: String, entityClass: ClassName): FunSpec {
    val repoPropName = pluralize(schemaName.replaceFirstChar { it.lowercase() })
    val resultType = ENT_RESULT.parameterizedBy(BOOLEAN)
    return FunSpec.builder("visibleExistsOrError")
        .returns(resultType)
        .addCode(
            CodeBlock.builder()
                .add("return try {\n")
                .add("  val c = requireClient()\n")
                .add("  val privacy = c.currentPrivacyContext()\n")
                .add(
                    "  val spec = runReadInterceptors(%T.VISIBLE_EXISTS, %T.QUERY)\n",
                    READ_OPERATION, ENT_OPERATION,
                )
                .add("  if (!c.%L.hasLoadPrivacy()) {\n", repoPropName)
                .add("    val limit = %L\n", SINGLE_ROW_LIMIT_EXPR)
                .add(
                    "    %T.Ok(driver.query(%T.TABLE, spec.predicates, emptyList(), limit, spec.offset).isNotEmpty())\n",
                    ENT_RESULT, entityClass,
                )
                .add("  } else {\n")
                .add("    val scanLimit = %L\n", overfetchScanLimitExpr("c.visibleOverfetchLimit"))
                .add(
                    "    val rows = driver.query(%T.TABLE, spec.predicates, spec.orderBy, scanLimit, spec.offset)\n",
                    entityClass,
                )
                .add("    var found = false\n")
                .add("    for (row in rows) {\n")
                .add("      val entity = %T.fromRow(row)\n", entityClass)
                .add("      try { c.%L.evaluateLoadPrivacy(privacy, entity); found = true; break } catch (_: %T) {}\n", repoPropName, PRIVACY_DENIED)
                .add("    }\n")
                .add("    %T.Ok(found)\n", ENT_RESULT)
                .add("  }\n")
                .add("} catch (e: %T) {\n", ENT_QUERY_REJECTED_EXCEPTION)
                .add("  %T.Err(e.queryRejected)\n", ENT_RESULT)
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

/**
 * `explainVisibleExists` — same shape as the runtime: probe
 * with `limit = minOf(1, spec.limit ?: 1)` on the no-privacy
 * path; on the privacy path scan up to `visibleOverfetchLimit`
 * so the in-process filter can find a visible row.
 */
internal fun buildVisibleExistsExplain(
    queryPlan: ClassName,
    name: String,
    terminalName: String,
    repoPropName: String,
): FunSpec {
    val body = CodeBlock.builder()
        .addStatement("val c = requireClient()")
        .beginControlFlow("if (!c.%L.hasLoadPrivacy())", repoPropName)
        // No-privacy fast path mirrors rawExists shape:
        // emptyList orderBy + caller offset preserved.
        .addStatement("buildQueryPlan(spec.copy(orderBy = emptyList(), limit = %L), false)", SINGLE_ROW_LIMIT_EXPR)
        .nextControlFlow("else")
        // Privacy path needs the order: the in-process filter
        // iterates rows in storage order, so spec.orderBy
        // matters. spec.offset is also preserved by the runtime.
        .addStatement("val cap = c.visibleOverfetchLimit")
        .addStatement("val scanLimit = %L", overfetchScanLimitExpr("cap"))
        .addStatement("buildQueryPlan(spec.copy(limit = scanLimit), false)")
        .endControlFlow()
        .build()
    return FunSpec.builder(name)
        .addKdoc(
            "Return a [QueryPlan] describing the query shape [$terminalName] would execute.\n" +
            "Interceptors run with operation = VISIBLE_EXISTS. The plan mirrors the\n" +
            "runtime driver call exactly:\n" +
            " - **No-privacy fast path**: `orderBy = emptyList()`,\n" +
            "   `limit = minOf(1, spec.limit ?: 1)` (so\n" +
            "   `query { limit(0) }.visibleExists()` shows `limit = 0`), and\n" +
            "   `offset = spec.offset` (caller offset preserved).\n" +
            " - **Privacy path**: `orderBy = spec.orderBy` (the in-process filter\n" +
            "   iterates rows in storage order, so order matters), `limit =\n" +
            "   minOf(spec.limit ?: cap, cap)` with\n" +
            "   `EntClientConfig.visibleOverfetchLimit`, and `offset = spec.offset`.\n" +
            "\n" +
            "On interceptor rejection, returns a plan with `rejected = true`."
        )
        .returns(queryPlan)
        .addCode(explainBody("VISIBLE_EXISTS", body))
        .build()
}

/**
 * The single-metric raw aggregate terminals:
 * ungrouped `rawMin/rawMax/rawSum/rawAvg` returning a typed scalar, and
 * grouped `raw…By` returning `List<AggregateBucket<K, V>>`, each with an
 * `…OrError` twin. All route through the private `aggregateRows` helper, which
 * runs the read interceptors as RAW_AGGREGATE and calls `driver.aggregate`.
 */
internal fun buildAggregateTerminals(schemaName: String, entityClass: ClassName): List<FunSpec> {
    val specs = mutableListOf<FunSpec>()
    val suppress = AnnotationSpec.builder(Suppress::class)
        .addMember("%S", "UNCHECKED_CAST").build()

    // The private helper every aggregate terminal delegates to.
    specs += FunSpec.builder("aggregateRows")
        .addModifiers(KModifier.PRIVATE)
        .addParameter("function", AGG_FUNCTION)
        .addParameter("column", STRING.copy(nullable = true))
        .addParameter("groupBy", STRING.copy(nullable = true))
        .returns(LIST.parameterizedBy(AGG_RESULT_ROW))
        .addStatement(
            "val spec = runReadInterceptors(%T.RAW_AGGREGATE, %T.QUERY)",
            READ_OPERATION, ENT_OPERATION,
        )
        .addStatement(
            "return driver.aggregate(%T.TABLE, function, column, spec.predicates, groupBy)",
            entityClass,
        )
        .build()

    // A fresh `T : Comparable<T>` type variable.
    fun comparableT(): TypeVariableName {
        val t = TypeVariableName("T")
        return t.copy(bounds = listOf(KOTLIN_COMPARABLE.parameterizedBy(t)))
    }

    // OrError try/catch wrapping a happy-path expression (mirrors rawCountOrError).
    fun orErrorBody(happy: CodeBlock): CodeBlock = CodeBlock.builder()
        .add("return try {\n")
        .add("  %T.Ok(%L)\n", ENT_RESULT, happy)
        .add("} catch (e: %T) {\n", ENT_QUERY_REJECTED_EXCEPTION)
        .add("  %T.Err(e.queryRejected)\n", ENT_RESULT)
        .add("} catch (e: %T) {\n", Exception::class.asClassName())
        .add(
            "  %T.Err(%M(driver, e, %S, %T.QUERY))\n",
            ENT_RESULT, MEMBER_CLASSIFY, schemaName, ENT_OPERATION,
        )
        .add("}\n")
        .build()

    // Ungrouped scalar terminal + its OrError twin. An ungrouped aggregate is
    // exactly one row, so take `.single().value` and cast to the metric type.
    fun scalar(name: String, fn: String, columnParam: ParameterSpec, returnType: TypeName, typeVars: List<TypeVariableName>) {
        val happy = CodeBlock.of(
            "aggregateRows(%T.%L, column.name, null).single().value as %T",
            AGG_FUNCTION, fn, returnType,
        )
        specs += FunSpec.builder(name).addAnnotation(suppress).addTypeVariables(typeVars)
            .addParameter(columnParam).returns(returnType)
            .addStatement("return %L", happy).build()
        specs += FunSpec.builder("${name}OrError").addAnnotation(suppress).addTypeVariables(typeVars)
            .addParameter(columnParam).returns(ENT_RESULT.parameterizedBy(returnType))
            .addCode(orErrorBody(happy)).build()
    }

    run {
        val t = comparableT()
        scalar("rawMin", "MIN", ParameterSpec.builder("column", COMPARABLE_COLUMN.parameterizedBy(entityClass, t)).build(), t.copy(nullable = true), listOf(t))
    }
    run {
        val t = comparableT()
        scalar("rawMax", "MAX", ParameterSpec.builder("column", COMPARABLE_COLUMN.parameterizedBy(entityClass, t)).build(), t.copy(nullable = true), listOf(t))
    }
    scalar("rawSum", "SUM", ParameterSpec.builder("column", INTEGRAL_COLUMN.parameterizedBy(entityClass, STAR)).build(), LONG.copy(nullable = true), emptyList())
    scalar("rawSum", "SUM", ParameterSpec.builder("column", FLOATING_COLUMN.parameterizedBy(entityClass, STAR)).build(), DOUBLE.copy(nullable = true), emptyList())
    scalar("rawAvg", "AVG", ParameterSpec.builder("column", NUMERIC_COLUMN.parameterizedBy(entityClass, STAR)).build(), DOUBLE.copy(nullable = true), emptyList())

    // Grouped terminal: two overloads per metric (GroupableColumn → key K,
    // NullableGroupableColumn → key K?). `valueColumn` is null for countBy.
    fun grouped(name: String, fn: String, valueColumn: ParameterSpec?, bucketValueType: TypeName, valueTypeVars: List<TypeVariableName>) {
        for (nullableKey in listOf(false, true)) {
            val k = TypeVariableName("K")
            val groupColType = (if (nullableKey) NULLABLE_GROUPABLE_COLUMN else GROUPABLE_COLUMN).parameterizedBy(entityClass, k)
            val keyType = if (nullableKey) k.copy(nullable = true) else k
            val listType = LIST.parameterizedBy(AGG_BUCKET.parameterizedBy(keyType, bucketValueType))
            val keyExpr = if (nullableKey) "groupBy.decodeKey(it.key)" else "groupBy.decodeKey(it.key)!!"
            val columnArg = if (valueColumn != null) "column.name" else "null"
            val params = listOfNotNull(ParameterSpec.builder("groupBy", groupColType).build(), valueColumn)
            val happy = CodeBlock.of(
                "aggregateRows(%T.%L, %L, groupBy.name).map { %T(%L, it.value as %T) }",
                AGG_FUNCTION, fn, columnArg, AGG_BUCKET, keyExpr, bucketValueType,
            )
            val typeVars = listOf(k) + valueTypeVars
            specs += FunSpec.builder(name).addAnnotation(suppress).addTypeVariables(typeVars)
                .addParameters(params).returns(listType)
                .addStatement("return %L", happy).build()
            specs += FunSpec.builder("${name}OrError").addAnnotation(suppress).addTypeVariables(typeVars)
                .addParameters(params).returns(ENT_RESULT.parameterizedBy(listType))
                .addCode(orErrorBody(happy)).build()
        }
    }

    grouped("rawCountBy", "COUNT", null, LONG, emptyList())
    run {
        val t = comparableT()
        grouped("rawMinBy", "MIN", ParameterSpec.builder("column", COMPARABLE_COLUMN.parameterizedBy(entityClass, t)).build(), t.copy(nullable = true), listOf(t))
    }
    run {
        val t = comparableT()
        grouped("rawMaxBy", "MAX", ParameterSpec.builder("column", COMPARABLE_COLUMN.parameterizedBy(entityClass, t)).build(), t.copy(nullable = true), listOf(t))
    }
    grouped("rawSumBy", "SUM", ParameterSpec.builder("column", INTEGRAL_COLUMN.parameterizedBy(entityClass, STAR)).build(), LONG.copy(nullable = true), emptyList())
    grouped("rawSumBy", "SUM", ParameterSpec.builder("column", FLOATING_COLUMN.parameterizedBy(entityClass, STAR)).build(), DOUBLE.copy(nullable = true), emptyList())
    grouped("rawAvgBy", "AVG", ParameterSpec.builder("column", NUMERIC_COLUMN.parameterizedBy(entityClass, STAR)).build(), DOUBLE.copy(nullable = true), emptyList())

    return specs
}
