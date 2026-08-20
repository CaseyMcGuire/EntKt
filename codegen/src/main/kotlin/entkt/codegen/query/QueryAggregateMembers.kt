package entkt.codegen.query

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

private val READ_OPERATION = ClassName("entkt.runtime.query", "ReadOperation")
private val READ_RESULT = ClassName("entkt.runtime.result", "ReadResult")

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
// Canonical count, exists, and raw-aggregate terminals with their
// explain mirrors. Same pairing rule as QueryRowMembers.kt: each
// explain builder sits next to the terminal whose driver call it
// models, and the shared query-shape expressions, canonicalReadBody
// capture boundary, and explainBody wrapper come from
// QueryShapeSupport.kt. QueryGenerator.generate() assembles the
// members.
// ------------------------------------------------------------------

/**
 * Emit the selected-edge guard line opening every non-entity
 * terminal's happy path. Inside the canonical capture boundary the
 * thrown `EntQueryConfigurationException` becomes `ReadResult.Failed`
 * — and because it precedes `requireClient()`, failure happens before
 * any interceptor or driver work. Emitted only when the query has a
 * load-capable edge; without one no graph can be selected and the
 * generated `requireNoSelectedEdges` helper does not exist.
 */
private fun CodeBlock.Builder.addNonEntityTerminalGuard(
    hasEdges: Boolean,
    terminal: String,
): CodeBlock.Builder {
    if (hasEdges) {
        add("  requireNoSelectedEdges(%S, %S)\n", terminal, NON_ENTITY_TERMINAL_EDGE_REASON)
    }
    return this
}

/**
 * `rawCount(): ReadResult<Long>` — count matching rows without
 * materializing them. This is a raw aggregate: LOAD privacy is not
 * evaluated, so there is no privacy-denial surface here. The `raw`
 * name makes that storage-level posture explicit, including inside
 * privacy rules.
 */
internal fun buildRawCount(schemaName: String, entityClass: ClassName, hasEdges: Boolean): FunSpec {
    return FunSpec.builder("rawCount")
        .addKdoc(
            "Count matching rows. This is a raw aggregate that does not evaluate LOAD\n" +
            "privacy or materialize entities. This storage-level behavior is the same\n" +
            "on application, validation, and privacy-rule clients.\n" +
            "Fails with `EntQueryConfigurationException` before any interceptor or\n" +
            "driver work when the query has selected edge loads.",
        )
        .returns(READ_RESULT.parameterizedBy(LONG))
        .addCode(
            canonicalReadBody(
                CodeBlock.builder()
                    .addNonEntityTerminalGuard(hasEdges, "rawCount()")
                    .add("  val c = requireClient()\n")
                    .add("  val privacy = c.currentPrivacyContext()\n")
                    .add("  val spec = runReadInterceptors(%T.RAW_COUNT, privacy)\n", READ_OPERATION)
                    .add(
                        "  %T.Success(driver.count(%T.TABLE, spec.predicates))\n",
                        READ_RESULT, entityClass,
                    )
                    .build(),
            ),
        )
        .build()
}

internal fun buildRawCountExplain(queryPlan: ClassName, entityClass: ClassName, hasEdges: Boolean): FunSpec {
    // explainRawCount uses driver.explainCount (a COUNT(*) plan)
    // rather than the row-fetch buildQueryPlan path.
    val body = CodeBlock.of(
        "%T(driver.explainCount(%T.TABLE, spec.predicates), annotations = spec.annotations)",
        queryPlan, entityClass,
    )
    val builder = FunSpec.builder("explainRawCount")
        .addKdoc(
            "Return a [QueryPlan] describing the COUNT query [rawCount] would execute.\n" +
            "Interceptors run with operation = RAW_COUNT; predicate contributions show\n" +
            "up in the plan, limit operations are silent no-ops by contract.\n" +
            "On interceptor rejection, returns a plan with `rejected = true`.\n" +
            "Throws `EntQueryConfigurationException` before driver explain work when\n" +
            "the query has selected edge loads — like [rawCount] itself, this explain\n" +
            "cannot describe a selected graph."
        )
        .returns(queryPlan)
    if (hasEdges) {
        // Unlike interceptor rejection (recorded on the plan), the
        // configuration exception is thrown: an incompatible explain
        // has no meaningful plan to return.
        builder.addStatement(
            "requireNoSelectedEdges(%S, %S)",
            "explainRawCount()", NON_ENTITY_TERMINAL_EDGE_REASON,
        )
    }
    return builder.addCode(explainBody("RAW_COUNT", body)).build()
}

/**
 * `rawExists(): ReadResult<Boolean>` — fast existence check; skips
 * LOAD privacy. `Success(true)` iff at least one storage row matches
 * the predicate. This storage-level behavior is available in every
 * read posture.
 */
internal fun buildRawExists(schemaName: String, entityClass: ClassName, hasEdges: Boolean): FunSpec {
    return FunSpec.builder("rawExists")
        .addKdoc(
            "Fast existence check; skips LOAD privacy. `Success(true)` iff at least one\n" +
            "storage row matches the predicate. No entities are materialized. This\n" +
            "storage-level behavior is the same in every read posture.\n" +
            "Fails with `EntQueryConfigurationException` before any interceptor or\n" +
            "driver work when the query has selected edge loads.",
        )
        .returns(READ_RESULT.parameterizedBy(BOOLEAN))
        .addCode(
            canonicalReadBody(
                CodeBlock.builder()
                    .addNonEntityTerminalGuard(hasEdges, "rawExists()")
                    .add("  val c = requireClient()\n")
                    .add("  val privacy = c.currentPrivacyContext()\n")
                    .add("  val spec = runReadInterceptors(%T.RAW_EXISTS, privacy)\n", READ_OPERATION)
                    // exists is fixed at limit-1 — interceptor clamps
                    // can only further restrict (to 0) so honor
                    // spec.limit if it's been set lower than 1.
                    .add("  val limit = %L\n", SINGLE_ROW_LIMIT_EXPR)
                    .add(
                        "  %T.Success(driver.query(%T.TABLE, spec.predicates, emptyList(), limit, spec.offset).isNotEmpty())\n",
                        READ_RESULT, entityClass,
                    )
                    .build(),
            ),
        )
        .build()
}

internal fun buildExistsShapedExplain(
    queryPlan: ClassName,
    name: String,
    terminalName: String,
    operationName: String,
    hasEdges: Boolean,
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
    val builder = FunSpec.builder(name)
        .addKdoc(
            "Return a [QueryPlan] describing the query shape [$terminalName] would execute.\n" +
            "Interceptors run with operation = $operationName; limit operations are\n" +
            "silent no-ops by contract. Plan mirrors the runtime exactly:\n" +
            "`orderBy = emptyList()` (existence probe doesn't order),\n" +
            "`limit = minOf(1, spec.limit ?: 1)` (usually 1, 0 when the caller\n" +
            "passed `query { limit(0) }`), and `offset = spec.offset` (caller's\n" +
            "offset is preserved). On interceptor rejection, returns a plan with\n" +
            "`rejected = true`.\n" +
            "Throws `EntQueryConfigurationException` before driver explain work when\n" +
            "the query has selected edge loads — like [$terminalName] itself, this\n" +
            "explain cannot describe a selected graph."
        )
        .returns(queryPlan)
    if (hasEdges) {
        // Thrown, not recorded on the plan: an incompatible explain
        // has no meaningful plan to return.
        builder.addStatement(
            "requireNoSelectedEdges(%S, %S)",
            "$name()", NON_ENTITY_TERMINAL_EDGE_REASON,
        )
    }
    return builder
        .addCode(
            explainBody(
                operationName,
                CodeBlock.of("buildQueryPlan(spec.copy(orderBy = emptyList(), limit = %L), false, privacy)", SINGLE_ROW_LIMIT_EXPR),
            ),
        )
        .build()
}

/**
 * The single-metric raw aggregate terminals: ungrouped
 * `rawMin/rawMax/rawSum/rawAvg` returning `ReadResult<V?>` (null is
 * the aggregate's documented SQL-null result, not entity absence),
 * and grouped `raw…By` returning
 * `ReadResult<List<AggregateBucket<K, V>>>`. All route through the
 * private `aggregateRows` helper, which runs the read interceptors as
 * RAW_AGGREGATE and calls `driver.aggregate`.
 *
 * Every public aggregate terminal opens with the selected-edge guard
 * (naming itself, not the shared helper) so a configured graph fails
 * as `ReadResult.Failed(EntQueryConfigurationException)` before any
 * interceptor or driver work.
 */
internal fun buildAggregateTerminals(schemaName: String, entityClass: ClassName, hasEdges: Boolean): List<FunSpec> {
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
        .addStatement("val c = requireClient()")
        .addStatement("val privacy = c.currentPrivacyContext()")
        .addStatement("val spec = runReadInterceptors(%T.RAW_AGGREGATE, privacy)", READ_OPERATION)
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

    // Canonical body wrapping a happy-path expression in the shared
    // capture boundary, opened by the terminal's selected-edge guard.
    fun resultBody(terminal: String, happy: CodeBlock): CodeBlock = canonicalReadBody(
        CodeBlock.builder()
            .addNonEntityTerminalGuard(hasEdges, "$terminal()")
            .add("  %T.Success(%L)\n", READ_RESULT, happy)
            .build(),
    )

    // Ungrouped scalar terminal. An ungrouped aggregate is exactly one
    // row, so take `.single().value` and cast to the metric type.
    fun scalar(name: String, fn: String, columnParam: ParameterSpec, returnType: TypeName, typeVars: List<TypeVariableName>) {
        val happy = CodeBlock.of(
            "aggregateRows(%T.%L, column.name, null).single().value as %T",
            AGG_FUNCTION, fn, returnType,
        )
        specs += FunSpec.builder(name).addAnnotation(suppress).addTypeVariables(typeVars)
            .addParameter(columnParam).returns(READ_RESULT.parameterizedBy(returnType))
            .addCode(resultBody(name, happy)).build()
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
                .addParameters(params).returns(READ_RESULT.parameterizedBy(listType))
                .addCode(resultBody(name, happy)).build()
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
