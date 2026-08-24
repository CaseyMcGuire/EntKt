package entkt.codegen.query

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.asClassName
import entkt.codegen.kotlinpoet.codeBlock
import entkt.codegen.kotlinpoet.function
import entkt.codegen.kotlinpoet.parameter
import entkt.codegen.kotlinpoet.property
import entkt.codegen.kotlinpoet.statement

private val READ_OPERATION = ClassName("entkt.runtime.query", "ReadOperation")
private val READ_RESULT = ClassName("entkt.runtime.result", "ReadResult")
private val READ_QUERY_EXECUTOR =
    ClassName("entkt.runtime.query.execution", "ReadQueryExecutor")
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
 * Root privacy completes before selected edges are loaded; a denied
 * selected target then fails with a `SelectedEdgePath` origin from graph loading.
 * Interceptor rejection is
 * `Failed(EntQueryRejectedException)`; any other exception is stored
 * directly.
 */
internal fun buildAll(entityClass: ClassName): FunSpec {
    val listType = List::class.asClassName().parameterizedBy(entityClass)
    val resultType = READ_RESULT.parameterizedBy(listType)
    return function("all", returnType = resultType) {
        statement("return readRootQuery(%T.ALL, maximumRows = null)", READ_OPERATION)
    }
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
    return function("firstOrNull", returnType = resultType) {
        addCode(
            "return when (val result = readRootQuery(%T.FIRST, maximumRows = 1)) {\n" +
                "  is %T.Success -> %T.Success(result.value.firstOrNull())\n" +
                "  is %T.Failed -> result\n" +
                "}\n",
            READ_OPERATION,
            READ_RESULT,
            READ_RESULT,
            READ_RESULT,
        )
    }
}

/** Capture the recursive query and delegate its terminal intent to the read executor. */
internal fun buildReadRootQuery(entityClass: ClassName): FunSpec {
    val entityList = List::class.asClassName().parameterizedBy(entityClass)
    return function(
        "readRootQuery",
        returnType = READ_RESULT.parameterizedBy(entityList),
    ) {
        addAnnotation(ClassName("entkt.query", "EntktInternal"))
        addModifiers(KModifier.INTERNAL)
        parameter("operation", READ_OPERATION)
        parameter("maximumRows", Int::class.asClassName().copy(nullable = true))
        parameter(
            "structuralPredicates",
            List::class.asClassName().parameterizedBy(PREDICATE.parameterizedBy(entityClass)),
        ) {
            defaultValue("emptyList()")
        }
        addCode(
            "return _readQueryExecutor.readRootQuery(\n" +
                "  captureQuery = { captureEntityQuery(structuralPredicates) },\n" +
                "  operation = operation,\n" +
                "  maximumRows = maximumRows,\n" +
                ")\n",
        )
    }
}

/** Compile a captured query for a framework operation that consumes its storage shape. */
internal fun buildCompileEntityQuery(entityClass: ClassName): FunSpec =
    function(
        "compileEntityQuery",
        returnType = STORAGE_QUERY_SPEC.parameterizedBy(entityClass),
    ) {
        addAnnotation(ClassName("entkt.query", "EntktInternal"))
        addModifiers(KModifier.INTERNAL)
        parameter("operation", READ_OPERATION)
        parameter("privacyContext", PRIVACY_CONTEXT)
        statement(
            "return _readQueryExecutor.compileEntityQuery(captureEntityQuery(), operation, privacyContext)",
        )
    }

/** Single runtime executor used by every generated read path. */
internal fun buildReadQueryExecutorProperty(
    entityClass: ClassName,
): PropertySpec =
    property(
        "_readQueryExecutor",
        READ_QUERY_EXECUTOR.parameterizedBy(entityClass),
    ) {
        addModifiers(KModifier.PRIVATE)
        // Edge-predicate DSLs construct target queries without a client because
        // they only capture relational structure. Defer the client-dependent
        // executor until a terminal or framework compilation actually runs.
        delegate(
            codeBlock {
                add("lazy(%T.NONE) {\n", ClassName("kotlin", "LazyThreadSafetyMode"))
                indent()
                add("%T(\n", READ_QUERY_EXECUTOR)
                indent()
                add("driver = driver,\n")
                add("privacyContextProvider = requireClient(),\n")
                add("registeredInterceptorsProvider = { requireClient().entityInterceptors },\n")
                add("loadPrivacyEvaluatorProvider = { requireClient() },\n")
                unindent()
                add(")\n")
                unindent()
                add("}")
            },
        )
    }
