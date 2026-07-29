package entkt.codegen.client

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.LambdaTypeName
import com.squareup.kotlinpoet.MemberName
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.UNIT
import com.squareup.kotlinpoet.asClassName

private val PREDICATE = ClassName("entkt.query", "Predicate")
private val OP = ClassName("entkt.query", "Op")
private val PRIVACY_DENIED = ClassName("entkt.runtime.privacy", "PrivacyDeniedException")
private val ENT_ERROR = ClassName("entkt.runtime.result", "EntError")
private val ENT_OPERATION = ClassName("entkt.runtime.result", "EntOperation")
private val ENT_RESULT = ClassName("entkt.runtime.result", "EntResult")
private val ENT_QUERY_REJECTED_EXCEPTION = ClassName("entkt.runtime.result", "EntQueryRejectedException")
private val READ_OPERATION = ClassName("entkt.runtime.query", "ReadOperation")

// ------------------------------------------------------------------
// Shared builders for the by-id read family, the `query { }` entry,
// and the `indexes` accessor. `RepoGenerator` emits them with
// clientRef = "client" (the repo's EntClient); the validation read
// repos emit the identical bodies with clientRef = "runtime" (the
// EntReadRuntime host). One builder per member keeps the two read
// surfaces byte-identical modulo that reference — the same
// shared-shape discipline the query explain emitters use.
// ------------------------------------------------------------------

/**
 * `byIdOrNull(id): T?` — the absence-collapses-to-null read API.
 *
 * - Row missing → `null`
 * - Row exists but LOAD privacy denies → throws `PrivacyDeniedException`
 * - Row exists and visible → returns the hydrated entity
 *
 * The privacy-denial throw is the raw [PrivacyDeniedException];
 * structured [EntException] wrapping is provided by [byIdOrError]
 * and [byIdOrThrow] (which both wrap this method). The visible-or-
 * null collapse is provided by [visibleByIdOrNull].
 */
internal fun buildByIdOrNull(
    schemaName: String,
    entityClass: ClassName,
    idType: TypeName,
    clientRef: String,
): FunSpec {
    val queryClass = ClassName(entityClass.packageName, "${schemaName}Query")
    val body = CodeBlock.builder()
    body.addStatement("val privacy = %L.currentPrivacyContext()", clientRef)
    // Route the by-id read through the generated *Query's
    // runReadInterceptors so we get the same predicate-walk
    // post-processing the query terminals get. In particular,
    // if a by-id interceptor adds `Edge.has { ... }`, the
    // walker fires the target entity's EDGE_PREDICATE
    // interceptors on the inner — without this route the
    // target soft-delete / tenant interceptors would be silently
    // bypassed on by-id reads. extraStructural carries the
    // `id = X` predicate; the walker skips structural
    // predicates so the id leaf is never re-walked.
    body.add(
        "val q = %T(driver, %L)\n",
        queryClass, clientRef,
    )
    body.add(
        "val spec = q.runReadInterceptors(\n",
    )
    body.add("  operation = %T.BY_ID,\n", READ_OPERATION)
    body.add("  entOperation = %T.LOAD,\n", ENT_OPERATION)
    body.add(
        "  extraStructural = listOf(%T.Leaf<%T>(%S, %T.EQ, id)),\n",
        PREDICATE, entityClass, "id", OP,
    )
    body.add(")\n")
    // Use driver.query (not driver.byId) because interceptors
    // may have added predicates (e.g. tenant_id = X) that
    // byId's PK lookup wouldn't honor.
    body.addStatement(
        "val row = driver.query(%T.TABLE, spec.predicates, emptyList(), 1, null).firstOrNull()",
        entityClass,
    )
    body.addStatement("if (row == null) return null")
    body.addStatement("val entity = %T.fromRow(row)", entityClass)
    body.addStatement("evaluateLoadPrivacy(privacy, entity)")
    body.addStatement("return entity")
    return FunSpec.builder("byIdOrNull")
        .addParameter("id", idType)
        .returns(entityClass.copy(nullable = true))
        .addCode(body.build())
        .build()
}

/**
 * `byIdOrThrow(id): T` — the strict read API. Throws structured
 * EntException subclasses for every failure surface; on the happy
 * path returns the hydrated entity.
 *
 * Delegates to [byIdOrError] + `getOrThrow()` so the mapping table
 * (NotFound / PrivacyDenied / DriverFailure) lives in one place.
 */
internal fun buildByIdOrThrow(
    entityClass: ClassName,
    idType: TypeName,
): FunSpec {
    return FunSpec.builder("byIdOrThrow")
        .addParameter("id", idType)
        .returns(entityClass)
        .addStatement(
            "return byIdOrError(id).%M()",
            MemberName("entkt.runtime.result", "getOrThrow"),
        )
        .build()
}

/**
 * `visibleByIdOrNull(id): T?` — the absence-OR-invisibility
 * collapse. Treats both "row missing" and "row exists but LOAD
 * privacy denies" as `null`. Driver failures still propagate.
 *
 * Implemented as `try { byIdOrNull(id) } catch
 * (PrivacyDeniedException) { null }`. The driver-failure surface
 * remains as exceptions on this path — `byIdOrError` is the
 * structured alternative.
 */
internal fun buildVisibleByIdOrNull(
    entityClass: ClassName,
    idType: TypeName,
): FunSpec {
    return FunSpec.builder("visibleByIdOrNull")
        .addParameter("id", idType)
        .returns(entityClass.copy(nullable = true))
        .addCode(
            CodeBlock.builder()
                .add("return try {\n")
                .add("  byIdOrNull(id)\n")
                .add("} catch (_: %T) {\n", PRIVACY_DENIED)
                .add("  null\n")
                .add("}\n")
                .build(),
        )
        .build()
}

/**
 * `byIdOrError(id): EntResult<T>` — the structured-result read API.
 * Maps each failure mode to its [EntError] variant:
 *
 * - row missing → `Err(NotFound)`
 * - privacy denied → `Err(PrivacyDenied)`
 * - any other uncaught Exception → routed through
 *   [classifyDriverError] → `Err(ConstraintViolation)` for
 *   recognized constraints or `Err(DriverFailure)` for the
 *   fallback.
 */
internal fun buildByIdOrError(
    schemaName: String,
    entityClass: ClassName,
    idType: TypeName,
): FunSpec {
    val resultType = ENT_RESULT.parameterizedBy(entityClass)
    return FunSpec.builder("byIdOrError")
        .addParameter("id", idType)
        .returns(resultType)
        .addCode(
            CodeBlock.builder()
                .add("return try {\n")
                .add(
                    "  byIdOrNull(id)?.let { %T.Ok(it) } ?: %T.Err(%T.NotFound(%S, %T.LOAD, id))\n",
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
                    "  %T.Err(%M(driver, e, %S, %T.LOAD))\n",
                    ENT_RESULT,
                    MemberName("entkt.runtime.driver", "classifyDriverError"),
                    schemaName,
                    ENT_OPERATION,
                )
                .add("}\n")
                .build(),
        )
        .build()
}

/**
 * Shared by-id explain wrapper. Builds a *Query instance, runs
 * its interceptor chain with operation = BY_ID, and either
 * delegates to the query's [buildQueryPlan] for a happy-path
 * plan or returns a rejected [QueryPlan] via
 * `QueryPlan.rejected(...)`. All four `explainById*` variants
 * produce the same plan — the result-shape suffix in the name
 * is for call-site discovery, not plan content (by contract).
 */
internal fun buildByIdExplainMethod(
    name: String,
    terminalName: String,
    schemaName: String,
    entityClass: ClassName,
    idType: TypeName,
    clientRef: String,
): FunSpec {
    val queryClass = ClassName(entityClass.packageName, "${schemaName}Query")
    val queryPlan = ClassName("entkt.runtime.query", "QueryPlan")
    return FunSpec.builder(name)
        .addParameter("id", idType)
        .returns(queryPlan)
        .addKdoc(
            "Return a [QueryPlan] describing the query [$terminalName] would\n" +
                "execute. Interceptors run with operation = BY_ID; limit operations\n" +
                "are silent no-ops by contract. On interceptor rejection, returns\n" +
                "a plan with `rejected = true` carrying the rejection metadata;\n" +
                "explain does NOT throw."
        )
        .addCode(
            CodeBlock.builder()
                .add("val q = %T(driver, %L)\n", queryClass, clientRef)
                .add("return try {\n")
                .add("  val spec = q.runReadInterceptors(\n")
                .add("    operation = %T.BY_ID,\n", READ_OPERATION)
                .add("    entOperation = %T.LOAD,\n", ENT_OPERATION)
                .add(
                    "    extraStructural = listOf(%T.Leaf<%T>(%S, %T.EQ, id)),\n",
                    PREDICATE, entityClass, "id", OP,
                )
                .add("  )\n")
                // By-id is a single-row PK lookup; hardwire
                // limit = 1 / offset = null in the plan so the
                // explain output matches the runtime call.
                .add("  q.buildQueryPlan(spec.copy(limit = 1, offset = null), includeEager = false)\n")
                .add("} catch (e: %T) {\n", ENT_QUERY_REJECTED_EXCEPTION)
                .add("  %T.rejected(e.queryRejected)\n", queryPlan)
                .add("}\n")
                .build(),
        )
        .build()
}

/** The four `explainById*` variants over [buildByIdExplainMethod]. */
internal fun buildByIdExplainMethods(
    schemaName: String,
    entityClass: ClassName,
    idType: TypeName,
    clientRef: String,
): List<FunSpec> = listOf(
    buildByIdExplainMethod("explainByIdOrThrow", "byIdOrThrow", schemaName, entityClass, idType, clientRef),
    buildByIdExplainMethod("explainByIdOrNull", "byIdOrNull", schemaName, entityClass, idType, clientRef),
    buildByIdExplainMethod("explainVisibleByIdOrNull", "visibleByIdOrNull", schemaName, entityClass, idType, clientRef),
    buildByIdExplainMethod("explainByIdOrError", "byIdOrError", schemaName, entityClass, idType, clientRef),
)

/** `query(block)` entry point: a fresh `${Entity}Query` bound to [clientRef]. */
internal fun buildQueryEntry(queryClass: ClassName, clientRef: String): FunSpec {
    val queryLambda = LambdaTypeName.get(receiver = queryClass, returnType = UNIT)
    return FunSpec.builder("query")
        .addParameter(
            ParameterSpec.builder("block", queryLambda)
                .defaultValue("{}")
                .build()
        )
        .returns(queryClass)
        .addStatement("return %T(driver, %L).apply(block)", queryClass, clientRef)
        .build()
}

/**
 * The `indexes` namespace accessor. A computed getter so a lateinit
 * [clientRef] is read at access time, not construction; the namespace
 * itself is stateless (driver + read runtime only).
 */
internal fun buildIndexesProperty(indexesClass: ClassName, clientRef: String): PropertySpec =
    PropertySpec.builder("indexes", indexesClass)
        .getter(
            FunSpec.getterBuilder()
                .addStatement("return %T(driver, %L)", indexesClass, clientRef)
                .build(),
        )
        .build()
