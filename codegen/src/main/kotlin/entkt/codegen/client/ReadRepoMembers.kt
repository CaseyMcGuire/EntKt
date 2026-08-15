package entkt.codegen.client

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.LambdaTypeName
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.UNIT
import entkt.codegen.query.canonicalReadBody

private val PREDICATE = ClassName("entkt.query", "Predicate")
private val OP = ClassName("entkt.query", "Op")
private val READ_OPERATION = ClassName("entkt.runtime.query", "ReadOperation")
private val READ_RESULT = ClassName("entkt.runtime.result", "ReadResult")
private val ENT_PRIVACY_DENIED = ClassName("entkt.runtime.result", "EntPrivacyDeniedException")
private val LOAD_DENIAL_ORIGIN = ClassName("entkt.runtime.result", "LoadDenialOrigin")
private val ENT_QUERY_REJECTED_EXCEPTION = ClassName("entkt.runtime.result", "EntQueryRejectedException")

// ------------------------------------------------------------------
// Shared builders for the canonical primary-key lookup, the
// `query { }` entry, and the `indexes` accessor. `RepoGenerator`
// emits them with clientRef = "client" (the repo's EntClient); the
// validation read repos emit the identical bodies with clientRef =
// "runtime" (the EntReadRuntime host). One builder per member keeps
// the two read surfaces byte-identical modulo that reference — the
// same shared-shape discipline the query explain emitters use.
// ------------------------------------------------------------------

/**
 * `findById(id): ReadResult<Entity?>` — the canonical primary-key
 * lookup. `Success(entity)` is presence, `Success(null)` is
 * authoritative absence, and a selected-but-denied row is
 * `Failed(EntPrivacyDeniedException(Root, listOf(denial)))`.
 * Privacy-as-absence is the explicit `visibleOrNull()` projection on
 * the result; throwing behavior is `getOrThrow()`. The name follows
 * Kotlin's nullable `find()` convention: the `ReadResult<Entity?>`
 * signature states that absence is a successful null payload.
 */
internal fun buildFindById(
    schemaName: String,
    entityClass: ClassName,
    idType: TypeName,
    clientRef: String,
): FunSpec {
    val queryClass = ClassName(entityClass.packageName, "${schemaName}Query")
    val resultType = READ_RESULT.parameterizedBy(entityClass.copy(nullable = true))
    val happy = CodeBlock.builder()
        .add("  val privacy = %L.currentPrivacyContext()\n", clientRef)
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
        .add("  val q = %T(driver, %L)\n", queryClass, clientRef)
        .add("  val spec = q.runReadInterceptors(\n")
        .add("    operation = %T.BY_ID,\n", READ_OPERATION)
        .add("    privacy = privacy,\n")
        .add(
            "    extraStructural = listOf(%T.Leaf<%T>(%S, %T.EQ, id)),\n",
            PREDICATE, entityClass, "id", OP,
        )
        .add("  )\n")
        // Use driver.query (not driver.byId) because interceptors
        // may have added predicates (e.g. tenant_id = X) that
        // byId's PK lookup wouldn't honor.
        .add(
            "  val row = driver.query(%T.TABLE, spec.predicates, emptyList(), 1, null).firstOrNull()\n",
            entityClass,
        )
        .add("  val entity = row?.let { %T.fromRow(it) }\n", entityClass)
        .add("  if (entity != null) {\n")
        .add("    val denial = loadDenialOrNull(privacy, entity)\n")
        .add("    if (denial != null) {\n")
        .add(
            "      return %T.failedForInternalUse(%T(%T.Root, listOf(denial)))\n",
            READ_RESULT, ENT_PRIVACY_DENIED, LOAD_DENIAL_ORIGIN,
        )
        .add("    }\n")
        .add("  }\n")
        .add("  %T.Success(entity)\n", READ_RESULT)
        .build()
    return FunSpec.builder("findById")
        .addParameter("id", idType)
        .returns(resultType)
        .addCode(canonicalReadBody(happy))
        .build()
}

/**
 * `explainFindById(id)` — the single by-id explain. Builds a *Query
 * instance, runs its interceptor chain with operation = BY_ID, and
 * either delegates to the query's [buildQueryPlan] for a happy-path
 * plan or returns a rejected [QueryPlan] via `QueryPlan.rejected(...)`.
 */
internal fun buildFindByIdExplainMethod(
    schemaName: String,
    entityClass: ClassName,
    idType: TypeName,
    clientRef: String,
): FunSpec {
    val queryClass = ClassName(entityClass.packageName, "${schemaName}Query")
    val queryPlan = ClassName("entkt.runtime.query", "QueryPlan")
    return FunSpec.builder("explainFindById")
        .addParameter("id", idType)
        .returns(queryPlan)
        .addKdoc(
            "Return a [QueryPlan] describing the query [findById] would\n" +
                "execute. Interceptors run with operation = BY_ID; limit operations\n" +
                "are silent no-ops by contract. On interceptor rejection, returns\n" +
                "a plan with `rejected = true` carrying the rejection metadata;\n" +
                "explain does NOT throw."
        )
        .addCode(
            CodeBlock.builder()
                .add("val privacy = %L.currentPrivacyContext()\n", clientRef)
                .add("val q = %T(driver, %L)\n", queryClass, clientRef)
                .add("return try {\n")
                .add("  val spec = q.runReadInterceptors(\n")
                .add("    operation = %T.BY_ID,\n", READ_OPERATION)
                .add("    privacy = privacy,\n")
                .add(
                    "    extraStructural = listOf(%T.Leaf<%T>(%S, %T.EQ, id)),\n",
                    PREDICATE, entityClass, "id", OP,
                )
                .add("  )\n")
                // By-id is a single-row PK lookup; hardwire
                // limit = 1 / offset = null in the plan so the
                // explain output matches the runtime call.
                .add("  q.buildQueryPlan(spec.copy(limit = 1, offset = null), includeEager = false, privacy = privacy)\n")
                .add("} catch (e: %T) {\n", ENT_QUERY_REJECTED_EXCEPTION)
                .add("  %T.rejected(e)\n", queryPlan)
                .add("}\n")
                .build(),
        )
        .build()
}

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
