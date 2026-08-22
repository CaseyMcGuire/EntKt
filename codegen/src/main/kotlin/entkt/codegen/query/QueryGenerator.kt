package entkt.codegen.query

import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.INT
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.LambdaTypeName
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
private val PRIVACY_CONTEXT = ClassName("entkt.runtime.privacy", "PrivacyContext")

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
        val clientName = schema.clientName
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

        val hasEdges = eagerEdgeSpecs.isNotEmpty()

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
            // Mutable query state is `private`:
            // application code in the same module would have write
            // access if these were `internal`, which would let
            // app code mutate `predicates` / `orderFields` /
            // traversal context / `deferredSourceStep` directly and
            // bypass the public DSL (`where(...)`, `orderBy(...)`,
            // queryX traversal). The only cross-instance access these
            // need is from the generated `snapshotForTraversal`
            // method below, which lives inside the same class and
            // therefore enjoys same-class private access in Kotlin
            // (private is class-scoped, not instance-scoped).
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
            // Traversal context: `private var`. Cross-class writes
            // (walker, eager-load, queryX) go through the generated
            // `@EntktInternal internal fun seedEdgeTraversal(...)`
            // method below — application code in the same module
            // can't spoof traversal-source / edge / path on a sibling
            // query without an explicit `@OptIn(EntktInternal::class)`.
            // Same pattern as `setDeferredSourceStep`.
            .addProperty(
                PropertySpec.builder(
                    "traversalSourceEntity",
                    ClassName("kotlin.reflect", "KClass")
                        .parameterizedBy(com.squareup.kotlinpoet.STAR)
                        .copy(nullable = true),
                )
                    .addModifiers(KModifier.PRIVATE)
                    .mutable(true)
                    .initializer("null")
                    .build()
            )
            .addProperty(
                PropertySpec.builder("traversalEdgeName", String::class.asClassName().copy(nullable = true))
                    .addModifiers(KModifier.PRIVATE)
                    .mutable(true)
                    .initializer("null")
                    .build()
            )
            .addProperty(
                PropertySpec.builder(
                    "traversalPath",
                    List::class.asClassName().parameterizedBy(ClassName("entkt.runtime.query", "EdgeStep")),
                )
                    .addModifiers(KModifier.PRIVATE)
                    .mutable(true)
                    .initializer("emptyList()")
                    .build()
            )
            .addProperty(
                // The eager-only schema-edge path from THIS terminal's
                // root selection to this query's position, set by the
                // parent's eager block via the @EntktInternal seeder.
                // Distinct from traversalPath: traversal (queryX) hops
                // feed interceptor context but must not appear in an
                // EagerEdge denial origin, which the RFC roots at the
                // terminal's own selection. Private for the same reason
                // the traversal-context fields are: a spoofed path
                // would corrupt denial diagnostics.
                PropertySpec.builder(
                    "eagerDenialBasePath",
                    List::class.asClassName().parameterizedBy(ClassName("entkt.runtime.query", "EdgeStep")),
                )
                    .addModifiers(KModifier.PRIVATE)
                    .mutable(true)
                    .initializer("emptyList()")
                    .build()
            )
            // Deferred source-step lambda, populated by generated
            // queryX() methods when this query is the *target* of a
            // traversal. The lambda is invoked at *terminal time*
            // (inside runReadInterceptors' try/catch) so a source-step
            // rejection from `scope.reject(...)` propagates through
            // the same EntQueryRejectedException path as target-step
            // rejection — making `*OrError` terminals on the target
            // able to catch traversal-source rejection too. Returns
            // the bridging predicate to inject as STRUCTURAL into the
            // target's QuerySpecBuilder, plus the source-step
            // annotations to seed the target's spec with so the final
            // interceptor step sees source annotations.
            .addProperty(
                PropertySpec.builder(
                    "deferredSourceStep",
                    LambdaTypeName.get(
                        receiver = null,
                        parameters = listOf(ParameterSpec.unnamed(PRIVACY_CONTEXT)),
                        returnType = ClassName("entkt.runtime.query", "TraversalSourceResult")
                            .parameterizedBy(entityClass),
                    ).copy(nullable = true),
                )
                    .addModifiers(KModifier.PRIVATE)
                    .mutable(true)
                    .initializer("null")
                    .build()
            )
            .addProperties(eagerEdgeSpecs.map { it.property })
            .addProperties(eagerEdgeSpecs.map { it.filterVisibleProperty })
            .addFunction(buildWhere(queryClass, predicateForEntity))
            .addFunction(buildOrderBy(queryClass, orderFieldForEntity))
            .addFunction(buildLimit(queryClass))
            .addFunction(buildOffset(queryClass))
            .addFunction(buildCombinedPredicate(predicateForEntity))
            .addFunctions(eagerEdgeSpecs.map { it.loadMethod })
            // Always emit `loadEdges` — the M2M eager-load codegen
            // emitted on a *source* query calls `subQuery.loadEdges(...)`
            // unconditionally on the target's query, so a target schema
            // with zero outgoing edges of its own (like a simple `Tag`)
            // still needs the no-op method to satisfy that call site.
            // The body's loop over `schema.edges()` produces zero
            // iterations for edge-less schemas, so the method just
            // returns its `results` parameter unchanged.
            .addFunction(buildLoadEdges(resolved))
            // Always emitted for the same reason as loadEdges: a parent
            // query's terminal-entry guard recurses into every selected
            // target query, so even edge-less target classes need the
            // (no-op) acquire/release pair.
            .addFunction(buildAcquireEdgeTopology(resolved))
            .addFunction(buildReleaseEdgeTopology(resolved))
            .addFunction(buildRequireClient(schemaName))
            .addFunction(buildRunReadInterceptors(schemaName, clientName, entityClass))
            .addFunction(buildRunEdgePredicateInterceptors(resolved))
            .addFunction(buildSnapshotForTraversal(queryClass, clientClass))
            .addFunction(buildSeedEdgeTraversal())
            .addFunction(buildSeedEagerDenialBasePath())
            .addFunction(buildSetDeferredSourceStep(entityClass))
            .addFunction(buildAll(schemaName, clientName, entityClass, hasEdges))
            .addFunction(buildFirstOrNull(schemaName, clientName, entityClass, hasEdges))
            .addFunction(buildRawCount(schemaName, entityClass, hasEdges))
            .addFunctions(buildAggregateTerminals(schemaName, entityClass, hasEdges))
            .addFunction(buildRawExists(schemaName, entityClass, hasEdges))
            .addFunctions(traversalMethods)

        // The selected-edge guard and the terminal-entry isolation
        // counter exist only when a graph can be selected at all;
        // non-entity terminals and traversal route through the former,
        // and topology-consuming terminals hold the latter.
        if (hasEdges) {
            typeSpec.addFunction(buildRequireNoSelectedEdges(resolved))
            typeSpec.addProperty(buildActiveTerminalsProperty())
        }

        // Every generated query file constructs `Predicate.HasEdge` /
        // `Predicate.HasEdgeWith` in the edge-predicate walker and
        // `Predicate.HasEdgeFromShape` / `Predicate.HasM2MEdgeFromShape`
        // in the traversal lambdas. Those types carry `@EntktInternal`
        // constructors; the file-level OptIn lets the construction
        // sites compile without per-call annotation.
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

    /**
     * Generate `seedEdgeTraversal(sourceEntity, edgeName, path)` on
     * the query class. The three traversal-context fields are
     * `private` — spoofing source / edge / path from app code
     * would confuse the interceptor's QueryContext view. Cross-
     * class write needs a method; the method is `@EntktInternal
     * internal` so application code can't call it without explicit
     * `@OptIn`. Used by the eager-load setup, the edge-predicate
     * walker, and queryX traversal methods.
     */
    /**
     * Generate `seedEagerDenialBasePath(path)`: the eager-only-path
     * analog of [buildSeedEdgeTraversal], guarded the same way so
     * application code cannot corrupt EagerEdge denial paths.
     */
    private fun buildSeedEagerDenialBasePath(): FunSpec {
        return FunSpec.builder("seedEagerDenialBasePath")
            .addAnnotation(ClassName("entkt.query", "EntktInternal"))
            .addModifiers(KModifier.INTERNAL)
            .addParameter(
                "path",
                List::class.asClassName()
                    .parameterizedBy(ClassName("entkt.runtime.query", "EdgeStep")),
            )
            .addStatement("this.eagerDenialBasePath = path")
            .build()
    }

    private fun buildSeedEdgeTraversal(): FunSpec {
        // Parameters are nullable so an eager step can RESTORE the
        // defaults it found — `seedEdgeTraversal(null, null,
        // emptyList())` — after its work completes. A queryX-created
        // traversal query keeps its seed for life (the returned query
        // IS the traversal); an eager step's seed is scoped to the
        // step so a captured child query executed independently later
        // behaves as the fresh root query it was constructed as.
        return FunSpec.builder("seedEdgeTraversal")
            .addAnnotation(ClassName("entkt.query", "EntktInternal"))
            .addModifiers(KModifier.INTERNAL)
            .addParameter(
                "sourceEntity",
                ClassName("kotlin.reflect", "KClass")
                    .parameterizedBy(com.squareup.kotlinpoet.STAR)
                    .copy(nullable = true),
            )
            .addParameter("edgeName", String::class.asClassName().copy(nullable = true))
            .addParameter(
                "path",
                List::class.asClassName().parameterizedBy(ClassName("entkt.runtime.query", "EdgeStep")),
            )
            .addStatement("this.traversalSourceEntity = sourceEntity")
            .addStatement("this.traversalEdgeName = edgeName")
            .addStatement("this.traversalPath = path")
            .build()
    }
}
