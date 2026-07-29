package entkt.codegen.query

import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.BOOLEAN
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
import entkt.codegen.pluralize
import entkt.schema.EntSchema

private val ENTKT_DSL = ClassName("entkt.schema", "EntktDsl")
private val EDGE_QUERY = ClassName("entkt.query", "EdgeQuery")
private val DRIVER = ClassName("entkt.runtime.driver", "Driver")
private val ENT_CLIENT_NAME = "EntClient"
private val QUERY_EXPLANATION = ClassName("entkt.runtime.query", "QueryExplanation")
private val FROZEN_QUERY_SPEC = ClassName("entkt.runtime.query", "FrozenQuerySpec")

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

        // Eager loading: with{Edge}() methods and properties
        val eagerEdgeSpecs = resolved.edges
            .filter { it.join != null }
            .map { buildEagerEdgeSpec(it, resolved, packageName) }

        val hasEdges = eagerEdgeSpecs.isNotEmpty()

        val clientClass = ClassName(packageName, ENT_CLIENT_NAME)

        val typeSpec = TypeSpec.classBuilder(className)
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
            // annotations to seed the target's spec with so
            // observability consumers see source annotations on the
            // final QueryPlan.
            .addProperty(
                PropertySpec.builder(
                    "deferredSourceStep",
                    LambdaTypeName.get(
                        receiver = null,
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
            .addFunction(buildWhere(queryClass, predicateForEntity))
            .addFunction(buildOrderBy(queryClass, orderFieldForEntity))
            .addFunction(buildLimit(queryClass))
            .addFunction(buildOffset(queryClass))
            .addFunction(buildCombinedPredicate(predicateForEntity))
            .addFunctions(eagerEdgeSpecs.map { it.withMethod })
            // Always emit `loadEdges` — the M2M eager-load codegen
            // emitted on a *source* query calls `subQuery.loadEdges(...)`
            // unconditionally on the target's query, so a target schema
            // with zero outgoing edges of its own (like a simple `Tag`)
            // still needs the no-op method to satisfy that call site.
            // The body's loop over `schema.edges()` produces zero
            // iterations for edge-less schemas, so the method just
            // returns its `results` parameter unchanged.
            .addFunction(buildLoadEdges(resolved))
            .addFunction(buildRequireClient(schemaName))
            .addFunction(buildRunReadInterceptors(schemaName, entityClass))
            .addFunction(buildRunEdgePredicateInterceptors(resolved))
            .addFunction(buildSnapshotForTraversal(queryClass, clientClass))
            .addFunction(buildSeedEdgeTraversal())
            .addFunction(buildSetDeferredSourceStep(entityClass))
            .addFunction(buildAllOrThrow(schemaName, entityClass, hasEdges))
            .addFunction(buildAllOrError(schemaName, entityClass, hasEdges))
            .addFunction(buildVisibleAll(schemaName, entityClass, hasEdges))
            .addFunction(buildVisibleAllOrError(schemaName, entityClass, hasEdges))
            .addFunction(buildFirstOrNull(schemaName, entityClass, hasEdges))
            .addFunction(buildFirstOrThrow(schemaName, entityClass))
            .addFunction(buildFirstOrError(schemaName, entityClass))
            .addFunction(buildFirstVisibleOrNull(schemaName, entityClass, hasEdges))
            .addFunction(buildVisibleCount(schemaName, entityClass))
            .addFunction(buildVisibleCountOrError(schemaName, entityClass))
            .addFunction(buildRawCount(schemaName, entityClass))
            .addFunction(buildRawCountOrError(schemaName, entityClass))
            .addFunctions(buildAggregateTerminals(schemaName, entityClass))
            .addFunction(buildRawExists(entityClass))
            .addFunction(buildRawExistsOrError(schemaName, entityClass))
            .addFunction(buildVisibleExists(schemaName, entityClass))
            .addFunction(buildVisibleExistsOrError(schemaName, entityClass))
            .addFunctions(buildExplainMethods(resolved))
            .addFunctions(traversalMethods)
            .build()

        // Every generated query file constructs `Predicate.HasEdge` /
        // `Predicate.HasEdgeWith` / `Predicate.HasM2MEdgeFrom` in the
        // edge-predicate walker and traversal lambdas. Those types
        // carry `@EntktInternal` constructors; the file-level OptIn
        // lets the construction sites compile without per-call annotation.
        return FileSpec.builder(packageName, className)
            .addAnnotation(
                AnnotationSpec.builder(ClassName("kotlin", "OptIn"))
                    .useSiteTarget(AnnotationSpec.UseSiteTarget.FILE)
                    .addMember("%T::class", ClassName("entkt.query", "EntktInternal"))
                    .build()
            )
            .addType(typeSpec)
            .build()
    }

    /**
     * Assemble the per-terminal explain methods plus the internal
     * `buildQueryPlan` helper that builds the [QueryPlan] tree. The
     * per-terminal builders live next to their terminals — row-shaped
     * ones in QueryRowMembers.kt, count/exists ones in
     * QueryAggregateMembers.kt, the eager explain block in
     * QueryEagerMembers.kt — and this assembler stitches them
     * together. Each explain method mirrors the execution shape of
     * its terminal:
     *
     * - `explainAllOrThrow` / `explainAllOrError` → configured limit/offset + eager edges
     * - `explainVisibleAll` / `explainVisibleAllOrError` → overfetch cap on the privacy path + eager edges
     * - `explainFirstOrThrow` / `explainFirstOrNull` / `explainFirstOrError` → `minOf(1, spec.limit ?: 1)` + eager edges
     * - `explainFirstVisibleOrNull` → single row or capped scan, branching on LOAD privacy
     * - `explainVisibleCount` → configured limit/offset, no eager edges
     * - `explainRawExists` / `explainVisibleExists` → existence probe shapes, no eager edges
     * - `explainRawCount` → COUNT query, no eager edges
     */
    private fun buildExplainMethods(resolved: ResolvedQuerySchema): List<FunSpec> {
        val entityClass = resolved.entityClass
        val queryPlan = ClassName("entkt.runtime.query", "QueryPlan")
        // Only edges with a resolved join can be explained eagerly —
        // the same capability that gates the eager-load surface.
        val hasEager = resolved.edges.any { it.join != null }

        val methods = mutableListOf<FunSpec>()

        val repoPropName = pluralize(entityClass.simpleName.replaceFirstChar { it.lowercase() })

        // Row-shaped reads (ALL operation, eager edges included).
        // The non-visible variants always fetch spec.limit; the
        // visible variants apply the overfetch cap on the
        // privacy path. *OrThrow / *OrError pairs share the same
        // driver-call shape — the result-wrap differs (throw vs
        // Err) but the explain plan content is identical.
        methods += buildRowShapedExplain(queryPlan, "explainAllOrThrow", "allOrThrow")
        methods += buildRowShapedExplain(queryPlan, "explainAllOrError", "allOrError")
        methods += buildVisibleRowShapedExplain(queryPlan, "explainVisibleAll", "visibleAll", repoPropName)
        methods += buildVisibleRowShapedExplain(queryPlan, "explainVisibleAllOrError", "visibleAllOrError", repoPropName)

        // First-row reads. Non-visible variants fetch with limit 1.
        // firstVisibleOrNull on the privacy path scans up to the
        // overfetch cap rather than 1 — so its explain branches.
        methods += buildFirstShapedExplain(queryPlan, "explainFirstOrThrow", "firstOrThrow")
        methods += buildFirstShapedExplain(queryPlan, "explainFirstOrNull", "firstOrNull")
        methods += buildFirstShapedExplain(queryPlan, "explainFirstOrError", "firstOrError")
        methods += buildFirstVisibleExplain(queryPlan, "explainFirstVisibleOrNull", "firstVisibleOrNull", repoPropName)

        // Aggregate reads.
        methods += buildVisibleCountExplain(queryPlan, "explainVisibleCount")
        methods += buildExistsShapedExplain(queryPlan, "explainRawExists", "rawExists", "RAW_EXISTS")
        methods += buildVisibleExistsExplain(queryPlan, "explainVisibleExists", "visibleExists", repoPropName)
        methods += buildRawCountExplain(queryPlan, entityClass)

        // Internal buildQueryPlan helper. Takes the post-interceptor
        // FrozenQuerySpec rather than raw limit/offset so the explain
        // output reflects every predicate, limit, offset, and
        // annotation contribution from the chain. `internal` (not
        // private) so a parent query's eager-explain block can call
        // it on a sibling *Query — that's how eager-load plans get
        // built with the right EAGER_LOAD-step spec instead of
        // re-running the sub-query through its own root explain().
        // [junctionExplain] is non-null only on the M2M eager path
        // where the parent block has computed the junction table's
        // explain (junction is internal-only, not subject to
        // interceptors).
        val helper = FunSpec.builder("buildQueryPlan")
            .addModifiers(KModifier.INTERNAL)
            // Spec is typed in this query's entity scope; every layer above the driver
            // call carries E through.
            .addParameter("spec", FROZEN_QUERY_SPEC.parameterizedBy(entityClass))
            .addParameter("includeEager", BOOLEAN)
            .addParameter(
                ParameterSpec.builder("junctionExplain", QUERY_EXPLANATION.copy(nullable = true))
                    .defaultValue("null")
                    .build()
            )
            .returns(queryPlan)
            .addStatement(
                "val root = driver.explainQuery(%T.TABLE, spec.predicates, spec.orderBy, spec.limit, spec.offset)",
                entityClass,
            )

        if (!hasEager) {
            helper.addStatement(
                "return %T(root, junctionQuery = junctionExplain, annotations = spec.annotations)",
                queryPlan,
            )
        } else {
            helper.addStatement(
                "if (!includeEager) return %T(root, junctionQuery = junctionExplain, annotations = spec.annotations)",
                queryPlan,
            )
            helper.addStatement("val edges = mutableMapOf<String, %T>()", queryPlan)
            for (info in resolved.edges) {
                val join = info.join ?: continue
                helper.addCode(buildEagerExplainBlock(info, join, entityClass))
            }
            helper.addStatement(
                "return %T(root, junctionQuery = junctionExplain, eagerQueries = edges, annotations = spec.annotations)",
                queryPlan,
            )
        }
        methods += helper.build()

        return methods
    }

    private fun buildRequireClient(schemaName: String): FunSpec {
        val clientClass = ClassName(packageName, ENT_CLIENT_NAME)
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
    private fun buildSeedEdgeTraversal(): FunSpec {
        return FunSpec.builder("seedEdgeTraversal")
            .addAnnotation(ClassName("entkt.query", "EntktInternal"))
            .addModifiers(KModifier.INTERNAL)
            .addParameter(
                "sourceEntity",
                ClassName("kotlin.reflect", "KClass")
                    .parameterizedBy(com.squareup.kotlinpoet.STAR),
            )
            .addParameter("edgeName", String::class.asClassName())
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
