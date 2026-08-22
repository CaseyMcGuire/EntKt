package entkt.codegen.query

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.asClassName

private val READ_OPERATION = ClassName("entkt.runtime.query", "ReadOperation")
private val READ_RESULT = ClassName("entkt.runtime.result", "ReadResult")
private val READ_QUERY_EVALUATOR =
    ClassName("entkt.runtime.query.execution", "ReadQueryEvaluator")
private val PRIVACY_CONTEXT = ClassName("entkt.runtime.privacy", "PrivacyContext")
private val STORAGE_QUERY_SPEC = ClassName("entkt.runtime.query", "StorageQuerySpec")
private val PREDICATE = ClassName("entkt.query", "Predicate")

// ------------------------------------------------------------------
// Canonical row-shaped terminals (all / firstOrNull). QueryGenerator
// assembles these thin runtime delegates.
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
 * eager target then fails with an `EagerEdge` origin from graph loading.
 * Interceptor rejection is
 * `Failed(EntQueryRejectedException)`; any other exception is stored
 * directly.
 */
internal fun buildAll(entityClass: ClassName): FunSpec {
    val listType = List::class.asClassName().parameterizedBy(entityClass)
    val resultType = READ_RESULT.parameterizedBy(listType)
    return FunSpec.builder("all")
        .returns(resultType)
        .addStatement("return readRootQuery(%T.ALL, maximumRows = null)", READ_OPERATION)
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
internal fun buildFirstOrNull(entityClass: ClassName): FunSpec {
    val resultType = READ_RESULT.parameterizedBy(entityClass.copy(nullable = true))
    return FunSpec.builder("firstOrNull")
        .returns(resultType)
        .addCode(
            "return when (val result = readRootQuery(%T.FIRST, maximumRows = 1)) {\n" +
                "  is %T.Success -> %T.Success(result.value.firstOrNull())\n" +
                "  is %T.Failed -> result\n" +
                "}\n",
            READ_OPERATION,
            READ_RESULT,
            READ_RESULT,
            READ_RESULT,
        )
        .build()
}

/** Capture the recursive query and delegate its terminal intent to the read evaluator. */
internal fun buildReadRootQuery(entityClass: ClassName): FunSpec {
    val entityList = List::class.asClassName().parameterizedBy(entityClass)
    return FunSpec.builder("readRootQuery")
        .addAnnotation(ClassName("entkt.query", "EntktInternal"))
        .addModifiers(KModifier.INTERNAL)
        .addParameter("operation", READ_OPERATION)
        .addParameter("maximumRows", Int::class.asClassName().copy(nullable = true))
        .addParameter(
            com.squareup.kotlinpoet.ParameterSpec.builder(
                "structuralPredicates",
                List::class.asClassName().parameterizedBy(PREDICATE.parameterizedBy(entityClass)),
            )
                .defaultValue("emptyList()")
                .build(),
        )
        .returns(READ_RESULT.parameterizedBy(entityList))
        .addCode(
            "return _readQueryEvaluator.readRootQuery(\n" +
                "  captureQuery = { captureEntityQuery(structuralPredicates) },\n" +
                "  operation = operation,\n" +
                "  maximumRows = maximumRows,\n" +
                ")\n",
        )
        .build()
}

/** Compile a captured query for a framework operation that consumes its storage shape. */
internal fun buildCompileEntityQuery(entityClass: ClassName): FunSpec =
    FunSpec.builder("compileEntityQuery")
        .addAnnotation(ClassName("entkt.query", "EntktInternal"))
        .addModifiers(KModifier.INTERNAL)
        .addParameter("operation", READ_OPERATION)
        .addParameter("privacyContext", PRIVACY_CONTEXT)
        .returns(STORAGE_QUERY_SPEC.parameterizedBy(entityClass))
        .addStatement(
            "return _readQueryEvaluator.compileEntityQuery(captureEntityQuery(), operation, privacyContext)",
        )
        .build()

/** Single runtime evaluator used by every generated read path. */
internal fun buildReadQueryEvaluatorProperty(
    entityClass: ClassName,
): PropertySpec =
    PropertySpec.builder(
        "_readQueryEvaluator",
        READ_QUERY_EVALUATOR.parameterizedBy(entityClass),
    )
        .addModifiers(KModifier.PRIVATE)
        .initializer(
            CodeBlock.builder()
                .add("%T(\n", READ_QUERY_EVALUATOR)
                .indent()
                .add("driver = driver,\n")
                .add("privacyContextProvider = { requireClient().currentPrivacyContext() },\n")
                .add("registeredInterceptorsProvider = { requireClient().entityInterceptors },\n")
                .add("loadPrivacyEvaluatorProvider = { requireClient() },\n")
                .unindent()
                .add(")")
                .build(),
        )
        .build()
