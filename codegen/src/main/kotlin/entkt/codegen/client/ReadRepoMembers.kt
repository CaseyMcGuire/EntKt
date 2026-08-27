package entkt.codegen.client

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.LambdaTypeName
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.UNIT
import entkt.codegen.kotlinpoet.body
import entkt.codegen.kotlinpoet.function
import entkt.codegen.kotlinpoet.getter
import entkt.codegen.kotlinpoet.parameter
import entkt.codegen.kotlinpoet.property
import entkt.codegen.kotlinpoet.statement
import entkt.codegen.metadata.VIEWER_CONTEXT

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
// the two read surfaces byte-identical modulo that reference.
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
    return function("findById", returnType = resultType) {
        parameter("viewerContext", VIEWER_CONTEXT)
        parameter("id", idType)
        body {
            add("val query = %T(driver, %L)\n", queryClass, clientRef)
            add("return when (val result = query.readRootQuery(\n")
            add("  viewerContext = viewerContext,\n")
            add("  operation = %T.BY_ID,\n", READ_OPERATION)
            add("  maximumRows = 1,\n")
            add(
                "  structuralPredicates = listOf(%T.Leaf<%T>(%S, %T.EQ, id)),\n",
                PREDICATE,
                entityClass,
                "id",
                OP,
            )
            add(")) {\n")
            add("  is %T.Success -> %T.Success(result.value.firstOrNull())\n", READ_RESULT, READ_RESULT)
            add("  is %T.Failed -> result\n", READ_RESULT)
            add("}\n")
        }
    }
}

/** `query(block)` entry point: a fresh `${Entity}Query` bound to [clientRef]. */
internal fun buildQueryEntry(queryClass: ClassName, clientRef: String): FunSpec {
    val queryLambda = LambdaTypeName.get(receiver = queryClass, returnType = UNIT)
    return function("query", returnType = queryClass) {
        parameter("block", queryLambda) {
            defaultValue("{}")
        }
        statement("return %T(driver, %L).apply(block)", queryClass, clientRef)
    }
}

/**
 * The `indexes` namespace accessor. A computed getter so a lateinit
 * [clientRef] is read at access time, not construction; the namespace
 * itself is stateless (driver + read runtime only).
 */
internal fun buildIndexesProperty(indexesClass: ClassName, clientRef: String): PropertySpec =
    property("indexes", indexesClass) {
        getter {
            statement("return %T(driver, %L)", indexesClass, clientRef)
        }
    }
