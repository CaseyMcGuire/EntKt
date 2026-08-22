package entkt.codegen.query

import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.INT
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.asClassName
import entkt.schema.EntSchema

private val ENTKT_DSL = ClassName("entkt.schema", "EntktDsl")
private val EDGE_QUERY = ClassName("entkt.query", "EdgeQuery")
private val DRIVER = ClassName("entkt.runtime.driver", "DatabaseDriver")

// Generated queries depend on the read-runtime contract, not the full
// EntClient: every internal use (requireClient, interceptor lookup,
// LOAD-privacy delegation, sibling-query construction) stays within
// EntReadRuntime's surface, so the read-only EntReadClientImpl behind
// the posture wrappers can host queries identically.
private val ENT_READ_RUNTIME_NAME = "EntReadRuntime"

internal class QueryGenerator(
    private val packageName: String,
) {
    private val predicateClass = ClassName("entkt.query", "Predicate")
    private val orderFieldClass = ClassName("entkt.query", "OrderField")

    fun generate(
        schemaName: String,
        schema: EntSchema,
        schemaNames: Map<EntSchema, String> = emptyMap(),
    ): FileSpec {
        // Edge metadata (target names, joins, inverses, member names)
        // resolves once here; every member builder below reads it
        // instead of re-deriving its own copy from the raw schema.
        val resolved = resolveQuerySchema(packageName, schemaName, schema, schemaNames)
        val className = "${schemaName}Query"
        val queryClass = resolved.queryClass
        val entityClass = resolved.entityClass
        // The generated client property for this schema, declared on the
        // schema and emitted verbatim — never derived from schemaName.
        // Typed convenience names for this entity's predicate/order-field
        // scopes. Used everywhere the generated query stores or accepts
        // its own predicate / order field.
        val predicateForEntity = predicateClass.parameterizedBy(entityClass)
        val orderFieldForEntity = orderFieldClass.parameterizedBy(entityClass)

        val traversalMethods = resolved.edges.mapNotNull { re ->
            if (re.isManyToMany) {
                buildM2MTraversal(re, resolved, packageName)
            } else {
                buildTraversal(re, resolved, packageName)
            }
        }

        // Edge loading: load{Edge}() methods and properties
        val eagerEdgeSpecs = resolved.edges
            .filter { it.join != null }
            .map { buildEagerEdgeSpec(it, resolved, packageName) }

        val clientClass = ClassName(packageName, ENT_READ_RUNTIME_NAME)

        val typeSpec = TypeSpec.classBuilder(className)
            .addKdoc(
                "Mutable query builder for [%T]. Configure and execute this instance from one " +
                    "thread at a time; query builders are not thread-safe. Do not mutate or " +
                    "execute the same instance concurrently. Create a separate query builder " +
                    "for each concurrent operation. A fully configured instance may be " +
                    "executed repeatedly when those executions are sequential.\n",
                entityClass,
            )
            .addAnnotation(AnnotationSpec.builder(ENTKT_DSL).build())
            // Generated query class implements EdgeQuery<EntityClass>;
            // the scope flows out of combinedPredicate() typed as
            // `Predicate<EntityClass>`.
            .addSuperinterface(EDGE_QUERY.parameterizedBy(entityClass))
            // Also implements `EdgePredicateScope<EntityClass>` so it
            // can be used as the narrow receiver inside
            // `EdgeRef.has { ... }` blocks. The generated `where()`
            // method below is marked `override` to satisfy this
            // interface's `where(Predicate<E>)` member — Kotlin
            // permits the concrete query class to refine the return
            // type covariantly from `EdgePredicateScope<E>` to the
            // concrete query type so chaining outside `has` blocks
            // still returns the wider type.
            .addSuperinterface(
                ClassName("entkt.query", "EdgePredicateScope").parameterizedBy(entityClass),
            )
            .primaryConstructor(
                FunSpec.constructorBuilder()
                    .addParameter("driver", DRIVER)
                    .addParameter(
                        ParameterSpec.builder("client", clientClass.copy(nullable = true))
                            .defaultValue("null")
                            .build()
                    )
                    .build()
            )
            .addProperty(
                PropertySpec.builder("driver", DRIVER)
                    .addModifiers(KModifier.PRIVATE)
                    .initializer("driver")
                    .build()
            )
            .addProperty(
                PropertySpec.builder("client", clientClass.copy(nullable = true))
                    .addModifiers(KModifier.PRIVATE)
                    .initializer("client")
                    .build()
            )
            // Mutable query state stays private so application code cannot
            // bypass the public where/order/bounds DSL before capture.
            .addProperty(
                PropertySpec.builder(
                    "predicates",
                    List::class.asClassName().parameterizedBy(predicateForEntity),
                )
                    .addModifiers(KModifier.PRIVATE)
                    .mutable(true)
                    .initializer("emptyList()")
                    .build()
            )
            .addProperty(
                PropertySpec.builder(
                    "orderFields",
                    List::class.asClassName().parameterizedBy(orderFieldForEntity),
                )
                    .addModifiers(KModifier.PRIVATE)
                    .mutable(true)
                    .initializer("emptyList()")
                    .build()
            )
            // queryLimit / queryOffset: public getter, private setter.
            // The eager-load codegen path reads them on a sibling
            // `subQuery` (cross-class read), so the getter must be
            // visible. The DSL methods `.limit(n)` / `.offset(n)` are
            // the only legitimate write path and enforce `require(n >= 0)`
            // — direct app-code mutation bypassing the guard is closed
            // by `private set`.
            .addProperty(
                PropertySpec.builder("queryLimit", INT.copy(nullable = true))
                    .mutable(true)
                    .initializer("null")
                    .setter(FunSpec.setterBuilder().addModifiers(KModifier.PRIVATE).build())
                    .build()
            )
            .addProperty(
                PropertySpec.builder("queryOffset", INT.copy(nullable = true))
                    .mutable(true)
                    .initializer("null")
                    .setter(FunSpec.setterBuilder().addModifiers(KModifier.PRIVATE).build())
                    .build()
            )
            .addProperty(buildEntityQuerySourceProperty(entityClass))
            .addProperties(eagerEdgeSpecs.map { it.property })
            .addProperties(eagerEdgeSpecs.map { it.filterVisibleProperty })
            .addProperty(buildQueryPreparationProperty())
            .addProperty(buildGraphLoaderProperty(entityClass))
            .addProperty(buildQueryTerminalExecutorProperty(entityClass))
            .addTypes(buildEntityQueryMappings(resolved))
            .addFunction(buildWhere(queryClass, predicateForEntity))
            .addFunction(buildOrderBy(queryClass, orderFieldForEntity))
            .addFunction(buildLimit(queryClass))
            .addFunction(buildOffset(queryClass))
            .addFunction(buildCombinedPredicate(predicateForEntity))
            .addFunctions(eagerEdgeSpecs.map { it.loadMethod })
            .addFunction(buildRequireClient(schemaName))
            .addFunction(buildSetEntityQuerySource(entityClass))
            .addFunction(buildCaptureEntityQuery(resolved))
            .addFunction(buildReadRootQuery(entityClass))
            .addFunction(buildPrepareEntityQuery(entityClass))
            .addFunction(buildAll(entityClass))
            .addFunction(buildFirstOrNull(entityClass))
            .addFunction(buildRawCount())
            .addFunctions(buildAggregateTerminals(entityClass))
            .addFunction(buildRawExists())
            .addFunctions(traversalMethods)

        // Generated mappings and recursive query capture use framework-internal
        // runtime contracts; the generated file owns that opt-in.
        return FileSpec.builder(packageName, className)
            .addAnnotation(
                AnnotationSpec.builder(ClassName("kotlin", "OptIn"))
                    .useSiteTarget(AnnotationSpec.UseSiteTarget.FILE)
                    .addMember("%T::class", ClassName("entkt.query", "EntktInternal"))
                    .build()
            )
            .addType(typeSpec.build())
            .build()
    }

    private fun buildRequireClient(schemaName: String): FunSpec {
        val clientClass = ClassName(packageName, ENT_READ_RUNTIME_NAME)
        return FunSpec.builder("requireClient")
            .addModifiers(KModifier.PRIVATE)
            .returns(clientClass)
            .addStatement(
                "return client ?: error(%S)",
                "$schemaName query requires a client for privacy enforcement",
            )
            .build()
    }

    private fun buildWhere(queryClass: ClassName, predicateForEntity: TypeName): FunSpec {
        // Marked `override` because `EdgePredicateScope<E>.where(Predicate<E>)`
        // declares this signature. Covariant return type — the
        // interface declares `EdgePredicateScope<E>` and the
        // concrete query class returns its own concrete type for
        // fluent chaining outside `has { }` blocks.
        return FunSpec.builder("where")
            .addModifiers(KModifier.OVERRIDE)
            .addParameter("predicate", predicateForEntity)
            .returns(queryClass)
            .addStatement("this.predicates = this.predicates + predicate")
            .addStatement("return this")
            .build()
    }

    private fun buildOrderBy(queryClass: ClassName, orderFieldForEntity: TypeName): FunSpec {
        return FunSpec.builder("orderBy")
            .addParameter("field", orderFieldForEntity)
            .returns(queryClass)
            .addStatement("this.orderFields = this.orderFields + field")
            .addStatement("return this")
            .build()
    }

    private fun buildLimit(queryClass: ClassName): FunSpec {
        return FunSpec.builder("limit")
            .addParameter("n", INT)
            .returns(queryClass)
            // Reject negatives at the boundary so the bad input never
            // reaches the driver. Postgres rejects LIMIT -1 with a
            // syntax error one layer removed from the caller — loud-fail
            // here instead.
            .addStatement("require(n >= 0) { %S + n }", "limit must be non-negative; was ")
            .addStatement("this.queryLimit = n")
            .addStatement("return this")
            .build()
    }

    private fun buildOffset(queryClass: ClassName): FunSpec {
        return FunSpec.builder("offset")
            .addParameter("n", INT)
            .returns(queryClass)
            .addStatement("require(n >= 0) { %S + n }", "offset must be non-negative; was ")
            .addStatement("this.queryOffset = n")
            .addStatement("return this")
            .build()
    }

    /**
     * Implements the [EdgeQuery] contract: returns the AND of every
     * accumulated predicate, or null if the query has no wheres. This
     * is what `EdgeRef.has { }` and the generated traversal methods
     * call to fold a query's filters into a single Predicate.
     */
    private fun buildCombinedPredicate(predicateForEntity: TypeName): FunSpec {
        return FunSpec.builder("combinedPredicate")
            .addModifiers(KModifier.OVERRIDE)
            .returns(predicateForEntity.copy(nullable = true))
            .addStatement(
                "return predicates.reduceOrNull { acc, p -> %T.And(acc, p) }",
                predicateClass,
            )
            .build()
    }

}
