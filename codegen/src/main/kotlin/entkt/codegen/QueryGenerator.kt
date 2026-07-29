package entkt.codegen

import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.BOOLEAN
import com.squareup.kotlinpoet.INT
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.LambdaTypeName
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.UNIT
import com.squareup.kotlinpoet.asClassName
import entkt.schema.EntSchema

private val ENTKT_DSL = ClassName("entkt.schema", "EntktDsl")
private val EDGE_QUERY = ClassName("entkt.query", "EdgeQuery")
private val DRIVER = ClassName("entkt.runtime.driver", "Driver")
private val ENT_CLIENT_NAME = "EntClient"
private val QUERY_EXPLANATION = ClassName("entkt.runtime.query", "QueryExplanation")
private val ENT_OPERATION = ClassName("entkt.runtime.result", "EntOperation")
private val ENT_QUERY_REJECTED_EXCEPTION = ClassName("entkt.runtime.result", "EntQueryRejectedException")
private val ABORT_QUERY_REJECTED = ClassName("entkt.runtime.query", "AbortQueryRejected")
private val FROZEN_QUERY_SPEC = ClassName("entkt.runtime.query", "FrozenQuerySpec")
private val QUERY_SPEC_BUILDER = ClassName("entkt.runtime.query", "QuerySpecBuilder")
private val QUERY_CONTEXT = ClassName("entkt.runtime.query", "QueryContext")
private val READ_OPERATION = ClassName("entkt.runtime.query", "ReadOperation")
private val INTERCEPTOR_ENGINE = ClassName("entkt.runtime.query", "InterceptorEngine")


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
                buildM2MTraversal(re, resolved)
            } else {
                buildTraversal(re, resolved)
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
     * Generate per-terminal explain methods plus a private helper
     * that builds the [QueryPlan] tree. Each public method mirrors
     * the execution shape of its corresponding terminal:
     *
     * - `explain()` → models `all()`: configured limit/offset + eager edges
     * - `explainFirst()` → models `firstOrNull()`: `min(limit ?: 1, 1)` + eager edges
     * - `explainExists()` → models `exists()`: `min(limit ?: 1, 1)`, no eager edges
     * - `explainVisibleCount()` → models `visibleCount()`: configured limit/offset, no eager edges
     * - `explainRawCount()` → models `rawCount()`: COUNT query, no eager edges
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

    /**
     * Helper: builds a [QuerySpecBuilder] seeded with the caller's
     * authored state on this query (predicates / orderFields /
     * queryLimit / queryOffset) plus any structural predicates the
     * terminal contributes, then runs the per-entity + global
     * interceptor chain via [InterceptorEngine.apply].
     *
     * Returns the [FrozenQuerySpec] terminals should hand to the
     * driver. Translates the internal [AbortQueryRejected] marker into
     * the user-facing [EntQueryRejectedException] at the boundary so
     * downstream terminal code only ever sees the public type.
     */
    private fun buildRunReadInterceptors(schemaName: String, entityClass: ClassName): FunSpec {
        val repoPropName = pluralize(schemaName.replaceFirstChar { it.lowercase() })
        val readOp = READ_OPERATION
        // Structural predicates pass in typed to this query's entity
        // scope. The deferred-source-step bridge (target-scoped) and
        // the eager-load `id IN (...)` leaf both produce
        // Predicate<EntityClass>, so the combined list is uniformly
        // typed. QuerySpecBuilder's erased-storage signatures accept
        // it via standard List<out T> variance.
        val predicateForEntity = predicateClass.parameterizedBy(entityClass)
        val structuralListType = List::class.asClassName().parameterizedBy(predicateForEntity)
        return FunSpec.builder("runReadInterceptors")
            .addModifiers(KModifier.INTERNAL)
            .addParameter("operation", readOp)
            .addParameter("entOperation", ENT_OPERATION)
            .addParameter(
                ParameterSpec.builder("extraStructural", structuralListType)
                    .defaultValue("emptyList()")
                    .build()
            )
            // Return type is typed in this query's entity scope; the
            // FrozenQuerySpec produced by `runReadInterceptors` carries
            // `List<Predicate<EntityClass>>` so the walker rewrite and
            // explain plan builders don't need unchecked casts.
            .returns(FROZEN_QUERY_SPEC.parameterizedBy(entityClass))
            .addCode(
                CodeBlock.builder()
                    .addStatement("val c = requireClient()")
                    .addStatement("val privacy = c.currentPrivacyContext()")
                    // Resolve the deferred source step (if any) at
                    // terminal time. The lambda runs the source
                    // entity's interceptor chain — a source-step
                    // rejection throws EntQueryRejectedException
                    // here, which the terminal's own try/catch
                    // (allOrError / firstOrError / byIdOrError /
                    // *OrError aggregate variants) converts to
                    // `Err(QueryRejected)`. Eager invocation at
                    // queryX() time would have raised the throw
                    // before the *OrError terminal could catch it.
                    .addStatement("val sourceResult = deferredSourceStep?.invoke()")
                    // Source annotations seed the builder so they
                    // surface on the final terminal's
                    // QueryPlan.annotations — interceptors at this
                    // step can overwrite via scope.addAnnotation
                    // (last-writer-wins).
                    .addStatement(
                        "val initialAnnotations = sourceResult?.annotations ?: emptyMap<%T, %T>()",
                        String::class.asClassName(),
                        String::class.asClassName(),
                    )
                    // Bridging predicate from the source step
                    // (HasEdgeWith / HasM2MEdgeFrom / HasEdge) goes
                    // in as STRUCTURAL alongside caller-passed
                    // extras (byId's id leaf, eager-load's IN clause).
                    .addStatement(
                        "val structural = listOfNotNull(sourceResult?.bridge) + extraStructural",
                    )
                    // rootEntity walks back along the traversal
                    // path; if no traversal context, this query IS
                    // the root. Otherwise the first EdgeStep's
                    // source is the chain's origin.
                    .addStatement(
                        "val root = traversalPath.firstOrNull()?.source ?: %T::class",
                        entityClass,
                    )
                    // QuerySpecBuilder<EntityClass> typed at construction
                    // — the typed `predicates` / `orderFields` from this
                    // query class feed in without casts.
                    .add("val builder = %T<%T>(\n", QUERY_SPEC_BUILDER, entityClass)
                    .add("  table = %T.TABLE,\n", entityClass)
                    .add("  entity = %T::class,\n", entityClass)
                    .add("  callerPredicates = predicates,\n")
                    .add("  structuralPredicates = structural,\n")
                    .add("  orderBy = orderFields,\n")
                    .add("  callerLimit = queryLimit,\n")
                    .add("  offset = queryOffset,\n")
                    .add("  flags = emptySet(),\n")
                    .add("  initialAnnotations = initialAnnotations,\n")
                    .add(")\n")
                    .add("val context = %T(\n", QUERY_CONTEXT)
                    .add("  privacy = privacy,\n")
                    .add("  operation = operation,\n")
                    .add("  rootEntity = root,\n")
                    .add("  currentEntity = %T::class,\n", entityClass)
                    .add("  sourceEntity = traversalSourceEntity,\n")
                    .add("  edgeName = traversalEdgeName,\n")
                    .add("  path = traversalPath,\n")
                    .add("  flags = emptySet(),\n")
                    .add(")\n")
                    .add("val frozen = try {\n")
                    .add("  %T.apply(\n", INTERCEPTOR_ENGINE)
                    .add("    builder = builder,\n")
                    .add("    context = context,\n")
                    .add("    entity = %S,\n", schemaName)
                    .add("    entOperation = entOperation,\n")
                    .add("    entityInterceptors = c.entityInterceptors.entityInterceptorsFor(%S),\n", repoPropName)
                    .add("    globalInterceptors = c.entityInterceptors.globals(),\n")
                    .add("  )\n")
                    .add("} catch (e: %T) {\n", ABORT_QUERY_REJECTED)
                    .add("  throw %T(e.rejected)\n", ENT_QUERY_REJECTED_EXCEPTION)
                    .add("}\n")
                    // Walk the post-interceptor predicate tree and
                    // run EDGE_PREDICATE interceptors on any
                    // HasEdgeWith / HasEdge subpredicates. The
                    // target entity's interceptors get a chance to
                    // narrow the EXISTS subquery (so e.g. soft-
                    // deleted target rows don't contribute to the
                    // existence check). Structural predicates we
                    // injected (traversalStructural from a queryX
                    // step, or extraStructural for eager-load's IN
                    // predicate / byId's id = X) are NOT re-walked
                    // — they were either already processed by the
                    // prior step's interceptors (traversal source
                    // step) or are framework-synthetic plumbing.
                    // Skip-list uses identity (`===`), not equality
                    // (`==`). A caller-authored predicate that happens
                    // to be structurally equal to a framework-injected
                    // structural (e.g. an application HasEdgeWith that
                    // matches a traversal-bridging HasEdgeWith by
                    // value) must still be walked through the
                    // edge-predicate processor.
                    // skipWalk is typed in this query's entity scope so
                    // the runEdgePredicateInterceptors call below can
                    // forward the typed `frozen.predicates.map { ... }`
                    // entries through the typed walker without casting.
                    .addStatement(
                        "val skipWalk: List<%T<%T>> = listOfNotNull(sourceResult?.bridge) + extraStructural",
                        predicateClass, entityClass,
                    )
                    // Walker accumulator: each edge-predicate target
                    // step's annotations bubble up into this map and
                    // merge into the outer FrozenQuerySpec below.
                    // Outer-step annotations (already on
                    // `frozen.annotations`) win on key conflicts —
                    // closer-to-caller wins, same direction as
                    // traversal-source-vs-terminal merge.
                    .addStatement("val edgeAnnotations: %T<%T, %T> = mutableMapOf()",
                        ClassName("kotlin.collections", "MutableMap"),
                        String::class.asClassName(),
                        String::class.asClassName(),
                    )
                    // frozen.predicates is typed `List<Predicate<EntityClass>>`
                    // because FrozenQuerySpec<E> carries E through.
                    // No cast needed at the walker
                    // call.
                    .addStatement(
                        "val walked = frozen.predicates.map { p -> if (skipWalk.any { it === p }) p else runEdgePredicateInterceptors(p, traversalPath, edgeAnnotations) }",
                    )
                    .addStatement(
                        "return frozen.copy(predicates = walked, annotations = edgeAnnotations + frozen.annotations)",
                    )
                    .build()
            )
            .build()
    }

    /**
     * Walk a [Predicate] tree and rewrite [Predicate.HasEdgeWith] /
     * [Predicate.HasEdge] sub-nodes by firing the target entity's
     * interceptors against the inner predicate with
     * [ReadOperation.EDGE_PREDICATE]. Boolean combinators (And / Or)
     * recurse; leaves return unchanged.
     *
     * HasEdge with no inner upgrades to HasEdgeWith when target
     * interceptors add predicates; stays HasEdge otherwise.
     * Important for the soft-delete shape — `User.articles.has()`
     * must filter out soft-deleted articles to give correct
     * existence semantics.
     *
     * [HasM2MEdgeFrom] is NOT rewritten in V1 — the dispatch keyed
     * by source-table (rather than this query's outgoing edge name)
     * needs a separate global registry. Workaround: use the
     * traversal form (`queryX()`) for M2M, which fires source
     * interceptors via.
     *
     * The dispatch `when` on the edge name is generated per-source-
     * entity from the schema's outgoing edges. Edges whose targets
     * aren't visible to codegen fall through unchanged.
     */
    private fun buildRunEdgePredicateInterceptors(resolved: ResolvedQuerySchema): FunSpec {
        val entityClass = resolved.entityClass
        val edgeStepClass = ClassName("entkt.runtime.query", "EdgeStep")
        val mutableMap = ClassName("kotlin.collections", "MutableMap")
        val body = CodeBlock.builder()
        body.addStatement("val c = requireClient()")
        // Recursion guard: cap the edge-predicate walker at
        // EDGE_PREDICATE_MAX_DEPTH steps so an interceptor cycle
        // (e.g. Post adds Post.author.has() AND User adds
        // User.posts.has(), or any longer cycle) surfaces as a
        // clear error rather than a StackOverflowError. Generous
        // limit so legitimate deep traversal trees (rare in
        // practice) still work; pathological cycles trip it
        // immediately because each level appends an EdgeStep to
        // parentPath.
        body.add(
            "check(parentPath.size <= %T.EDGE_PREDICATE_MAX_DEPTH) {\n",
            INTERCEPTOR_ENGINE,
        )
        body.add("  %P\n",
            "edge-predicate interceptor recursion exceeded depth " +
                "\${entkt.runtime.query.InterceptorEngine.EDGE_PREDICATE_MAX_DEPTH} on path " +
                "\${parentPath.joinToString(\" → \") { \"\${it.source.simpleName}.\${it.edgeName}\" }}. " +
                "Likely cause: interceptors on two entities each add a HasEdge[With] predicate that " +
                "references back to the other (e.g. Post adds Post.author.has(), User adds " +
                "User.posts.has()). Fix the interceptor cycle or bump InterceptorEngine.EDGE_PREDICATE_MAX_DEPTH.",
        )
        body.add("}\n")
        body.add("return when (predicate) {\n")
        body.add(
            "  is %T.And<%T> -> %T.And(runEdgePredicateInterceptors(predicate.left, parentPath, edgeAnnotations), runEdgePredicateInterceptors(predicate.right, parentPath, edgeAnnotations))\n",
            predicateClass, entityClass, predicateClass,
        )
        body.add(
            "  is %T.Or<%T> -> %T.Or(runEdgePredicateInterceptors(predicate.left, parentPath, edgeAnnotations), runEdgePredicateInterceptors(predicate.right, parentPath, edgeAnnotations))\n",
            predicateClass, entityClass, predicateClass,
        )
        // HasEdgeWith dispatch. Predicate.HasEdgeWith<E, Target>.inner
        // is typed Predicate<Target>, but the smart-cast lands at
        // HasEdgeWith<E, *> so .inner is Predicate<*>. The edge-name
        // serves as the runtime witness for recovering Target. Each
        // branch knows its target statically (from the schema), does
        // an unchecked cast inside
        // the branch, and rebuilds a typed HasEdgeWith for the
        // candidate before returning.
        body.add("  is %T.HasEdgeWith<%T, *> -> when (predicate.edge) {\n", predicateClass, entityClass)
        for (re in resolved.edges) {
            val targetClass = re.targetClass
            val targetQueryClass = re.targetQueryClass
            // The edge name in HasEdgeWith corresponds to an edge
            // on THIS query's entity (the source). Dispatch by
            // this entity's outgoing edge names.
            body.add("    %S -> {\n", re.name)
            // Reuse this source query's driver — the target
            // query never touches it on this code path (we only
            // call runReadInterceptors, which is pure transform),
            // but its primary constructor requires one.
            body.add("      val targetQ = %T(driver, c)\n", targetQueryClass)
            // Edge-name-validated unchecked cast: the schema declares
            // this edge with this exact target, so predicate.inner
            // (typed Predicate<*>) is in fact Predicate<TargetEntity>
            // — the runtime witness is the edge-name match.
            body.add("      @Suppress(\"UNCHECKED_CAST\")\n")
            body.add(
                "      val typedInner = predicate.inner as %T<%T>\n",
                predicateClass, targetClass,
            )
            // Use the public DSL (target.where) rather than writing the
            // backing list directly, preserving encapsulation.
            body.add("      targetQ.where(typedInner)\n")
            // Cross-class write through the @EntktInternal seeder.
            body.add(
                "      targetQ.seedEdgeTraversal(%T::class, predicate.edge, parentPath + %T(%T::class, predicate.edge, %T::class))\n",
                entityClass, edgeStepClass, entityClass, targetClass,
            )
            body.add(
                "      val spec = targetQ.runReadInterceptors(%T.EDGE_PREDICATE, %T.QUERY)\n",
                READ_OPERATION, ENT_OPERATION,
            )
            // Bubble up target-step annotations into the outer
            // accumulator so observability sees them on the outer
            // QueryPlan. Source-of-truth merge rule (outer wins on
            // collision) is applied at the outer's
            // runReadInterceptors via `edgeAnnotations + frozen.annotations`.
            body.add("      edgeAnnotations.putAll(spec.annotations)\n")
            // spec.predicates is typed `List<Predicate<Target>>` (the
            // target's runReadInterceptors returns FrozenQuerySpec<Target>),
            // so the reduce produces a typed Predicate<Target> directly.
            body.add(
                "      val combinedInner = spec.predicates.reduceOrNull { acc, p -> %T.And(acc, p) } ?: typedInner\n",
                predicateClass,
            )
            body.add(
                "      %T.HasEdgeWith<%T, %T>(predicate.edge, combinedInner)\n",
                predicateClass, entityClass, targetClass,
            )
            body.add("    }\n")
        }
        body.add("    else -> predicate\n")
        body.add("  }\n")
        // HasEdge (no inner): if any target interceptor adds
        // predicates, upgrade to HasEdgeWith with the interceptor-
        // contributed predicates as the inner. If interceptors add
        // nothing, keep as HasEdge. Important for soft-delete on the
        // target — `User.articles.has()` must still filter out
        // soft-deleted articles, otherwise the existence check is
        // wrong.
        body.add("  is %T.HasEdge<%T> -> when (predicate.edge) {\n", predicateClass, entityClass)
        for (re in resolved.edges) {
            val targetClass = re.targetClass
            val targetQueryClass = re.targetQueryClass
            body.add("    %S -> {\n", re.name)
            body.add("      val targetQ = %T(driver, c)\n", targetQueryClass)
            // Cross-class write through the @EntktInternal seeder.
            body.add(
                "      targetQ.seedEdgeTraversal(%T::class, predicate.edge, parentPath + %T(%T::class, predicate.edge, %T::class))\n",
                entityClass, edgeStepClass, entityClass, targetClass,
            )
            body.add(
                "      val spec = targetQ.runReadInterceptors(%T.EDGE_PREDICATE, %T.QUERY)\n",
                READ_OPERATION, ENT_OPERATION,
            )
            // Bubble up target-step annotations even when the
            // walker upgrades HasEdge → HasEdgeWith (or keeps as
            // HasEdge if interceptors added nothing).
            body.add("      edgeAnnotations.putAll(spec.annotations)\n")
            // spec.predicates is already typed in Target.
            body.add(
                "      val combined = spec.predicates.reduceOrNull { acc, p -> %T.And(acc, p) }\n",
                predicateClass,
            )
            body.add(
                "      if (combined != null) %T.HasEdgeWith<%T, %T>(predicate.edge, combined) else predicate\n",
                predicateClass, entityClass, targetClass,
            )
            body.add("    }\n")
        }
        body.add("    else -> predicate\n")
        body.add("  }\n")
        body.add("  else -> predicate\n")
        body.add("}\n")

        // Walker input/output are typed in this query's entity scope.
        // The walker recurses through a Predicate<EntityClass> tree,
        // rewriting edge-predicate sub-nodes (which carry their own
        // Target scope) via per-edge branches that use the
        // edge-name-validated unchecked-cast pattern above.
        val predicateForThis = predicateClass.parameterizedBy(entityClass)
        return FunSpec.builder("runEdgePredicateInterceptors")
            .addModifiers(KModifier.INTERNAL)
            .addParameter("predicate", predicateForThis)
            .addParameter(
                "parentPath",
                List::class.asClassName().parameterizedBy(edgeStepClass),
            )
            .addParameter(
                "edgeAnnotations",
                mutableMap.parameterizedBy(
                    String::class.asClassName(),
                    String::class.asClassName(),
                ),
            )
            .returns(predicateForThis)
            .addCode(body.build())
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
     * Generate `snapshotForTraversal(driver, client): ThisQuery` on the
     * query class.
     *
     * Generated traversal methods (`queryX()` / `queryX()` M2M variants)
     * need to seed a fresh source-query instance with the current
     * query's state so subsequent mutations on `this` don't leak into
     * the bridge. Since `predicates` / `orderFields` / traversal
     * context / `deferredSourceStep` are all `private`, the only place
     * they can be cross-instance-copied is from inside the class
     * itself (same-class private access is unaffected by instance
     * boundaries in Kotlin).
     *
     * `@EntktInternal` on the method blocks application callers from
     * invoking it without explicit opt-in — generated traversal code
     * lives in `@file:OptIn(EntktInternal::class)` files, so the call
     * compiles there.
     */
    private fun buildSnapshotForTraversal(queryClass: ClassName, clientClass: ClassName): FunSpec {
        return FunSpec.builder("snapshotForTraversal")
            .addAnnotation(ClassName("entkt.query", "EntktInternal"))
            .addModifiers(KModifier.INTERNAL)
            .addParameter("driver", DRIVER)
            .addParameter(
                ParameterSpec.builder("client", clientClass.copy(nullable = true))
                    .build(),
            )
            .returns(queryClass)
            .addCode(
                CodeBlock.builder()
                    .add("return %T(driver, client).also {\n", queryClass)
                    .add("  it.predicates = this.predicates\n")
                    .add("  it.orderFields = this.orderFields\n")
                    .add("  it.queryLimit = this.queryLimit\n")
                    .add("  it.queryOffset = this.queryOffset\n")
                    .add("  it.traversalSourceEntity = this.traversalSourceEntity\n")
                    .add("  it.traversalEdgeName = this.traversalEdgeName\n")
                    .add("  it.traversalPath = this.traversalPath\n")
                    .add("  it.deferredSourceStep = this.deferredSourceStep\n")
                    .add("}\n")
                    .build(),
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

    /**
     * Generate `setDeferredSourceStep(step)` seeder on the query
     * class. The `deferredSourceStep` field itself is `private` —
     * clearing it would remove the structural traversal-bridge
     * constraint and let queries leak across the boundary. Cross-
     * class write needs a method; the method is
     * `@EntktInternal internal` so application code can't call it
     * without explicit `@OptIn`. Generated traversal code (in
     * `@file:OptIn(EntktInternal::class)` files) calls it freely.
     */
    private fun buildSetDeferredSourceStep(entityClass: ClassName): FunSpec {
        val lambdaType = LambdaTypeName.get(
            receiver = null,
            returnType = ClassName("entkt.runtime.query", "TraversalSourceResult")
                .parameterizedBy(entityClass),
        ).copy(nullable = true)
        return FunSpec.builder("setDeferredSourceStep")
            .addAnnotation(ClassName("entkt.query", "EntktInternal"))
            .addModifiers(KModifier.INTERNAL)
            .addParameter("step", lambdaType)
            .addStatement("this.deferredSourceStep = step")
            .build()
    }

    /**
     * Generate a `queryX(): TargetQuery` traversal for a many-to-many
     * edge [re]. Lowered to a `Predicate.HasM2MEdgeFrom` against the
     * candidate target row, naming the *source* schema's table and the
     * forward edge — the runtime walks the junction backwards using the
     * source schema's own edge metadata, with no dependency on a
     * synthesized reverse edge on the target.
     */
    private fun buildM2MTraversal(
        re: ResolvedQueryEdge,
        resolved: ResolvedQuerySchema,
    ): FunSpec? {
        val sourceName = resolved.sourceName ?: return null
        val sourceEntityClass = ClassName(packageName, sourceName)
        val targetEntityClass = re.targetClass
        val targetQueryClass = re.targetQueryClass
        val methodName = "query${toPascalCase(re.name)}"
        val sourceTable = resolved.schema.tableName
        val edgeStepClass = ClassName("entkt.runtime.query", "EdgeStep")
        val traversalSourceResult = ClassName("entkt.runtime.query", "TraversalSourceResult")

        return FunSpec.builder(methodName)
            // Defaulted receiver block matching the repository / index
            // `query { ... }` helpers: it configures the *target* query
            // and runs after all traversal seeding, so it is exactly
            // equivalent to chaining `.where(...)` etc. on the returned
            // query. The source snapshot below is taken before the
            // block runs, so the block cannot leak state into the
            // bridge predicate.
            .addParameter(
                ParameterSpec.builder(
                    "block",
                    LambdaTypeName.get(receiver = targetQueryClass, returnType = UNIT),
                ).defaultValue("{}").build(),
            )
            .returns(targetQueryClass)
            // Construct the target query and stash a deferred
            // source-step lambda — the source's interceptor chain
            // does NOT fire here; it fires at the terminal's call
            // site inside the terminal's try/catch (see KDoc on
            // `deferredSourceStep`). This is what lets
            // `.queryX().allOrError()` catch source-step rejections
            // as `Err(QueryRejected)` instead of having queryX()
            // throw before allOrError() can run.
            .addStatement("val target = %T(driver, client)", targetQueryClass)
            // Cross-class write through the @EntktInternal seeder.
            .addStatement(
                "target.seedEdgeTraversal(%T::class, %S, this.traversalPath + %T(%T::class, %S, %T::class))",
                sourceEntityClass, re.name, edgeStepClass, sourceEntityClass, re.name, targetEntityClass,
            )
            // Snapshot source state at queryX() time into a fresh
            // source-Query instance so the deferred lambda is
            // immune to later mutations on `this`. Without the
            // snapshot, `users.queryPosts(); users.where(...);
            // posts.allOrThrow()` would let the post-queryX where
            // leak into posts' bridge predicate, which contradicts
            // the pre-deferral snapshot-at-construction semantics.
            // List / nullable fields are immutable values, so copying
            // the references is sufficient: source mutators reassign
            // the reference on `this`, not on the snapshot. The copy
            // is delegated to the generated `snapshotForTraversal`
            // method, which lives inside the source query class and
            // can access the private backing fields via same-class
            // private access.
            .addStatement("val sourceQ = this.snapshotForTraversal(driver, client)")
            .addCode(
                CodeBlock.builder()
                    // Cross-class write goes through the @EntktInternal
                    // seeder so application code can't clear / overwrite
                    // deferredSourceStep without an explicit opt-in (it
                    // is `private` on the target class).
                    .add("target.setDeferredSourceStep {\n")
                    .add(
                        "  val sourceSpec = sourceQ.runReadInterceptors(%T.EDGE_TRAVERSAL, %T.QUERY)\n",
                        READ_OPERATION, ENT_OPERATION,
                    )
                    // sourceSpec is FrozenQuerySpec<SourceEntity>; its
                    // predicates are typed `List<Predicate<SourceEntity>>`
                    // — no cast needed.
                    .add(
                        "  val parent: %T<%T>? = sourceSpec.predicates.reduceOrNull { acc, p -> %T.And(acc, p) }\n",
                        predicateClass, sourceEntityClass, predicateClass,
                    )
                    // M2M bridge: HasM2MEdgeFrom<Target, Source>(...).
                    // The candidate is the M2M target; the sourceFilter
                    // constrains rows in the source table.
                    .add("  %T<%T>(\n", traversalSourceResult, targetEntityClass)
                    .add(
                        "    bridge = %T.HasM2MEdgeFrom<%T, %T>(%S, %S, parent),\n",
                        predicateClass,
                        targetEntityClass, sourceEntityClass,
                        sourceTable,
                        re.name,
                    )
                    .add("    annotations = sourceSpec.annotations,\n")
                    .add("  )\n")
                    .add("}\n")
                    .build()
            )
            .addStatement("return target.apply(block)")
            .build()
    }

    /**
     * Generate a `queryX(): TargetQuery` method for edge [re]. This is
     * the traversal entry point — given a query on the source schema,
     * walk across the edge and return a query on the target.
     *
     * Lowering: the parent's combined predicate becomes a HasEdgeWith
     * predicate on the target query, naming the *inverse* edge (i.e.
     * the edge on the target that points back at the source). When the
     * parent has no wheres we still emit HasEdge so optional inverse
     * edges still filter out unrelated rows.
     *
     * Returns null when the inverse edge can't be resolved — codegen
     * just skips emitting a traversal method in that case.
     */
    private fun buildTraversal(
        re: ResolvedQueryEdge,
        resolved: ResolvedQuerySchema,
    ): FunSpec? {
        val sourceName = resolved.sourceName ?: return null
        val inverse = re.inverse ?: return null
        val sourceEntityClass = ClassName(packageName, sourceName)
        val targetEntityClass = re.targetClass
        val targetQueryClass = re.targetQueryClass
        val methodName = "query${toPascalCase(re.name)}"
        val edgeStepClass = ClassName("entkt.runtime.query", "EdgeStep")
        val traversalSourceResult = ClassName("entkt.runtime.query", "TraversalSourceResult")

        return FunSpec.builder(methodName)
            // Defaulted receiver block matching the repository / index
            // `query { ... }` helpers: it configures the *target* query
            // and runs after all traversal seeding, so it is exactly
            // equivalent to chaining `.where(...)` etc. on the returned
            // query. The source snapshot below is taken before the
            // block runs, so the block cannot leak state into the
            // bridge predicate.
            .addParameter(
                ParameterSpec.builder(
                    "block",
                    LambdaTypeName.get(receiver = targetQueryClass, returnType = UNIT),
                ).defaultValue("{}").build(),
            )
            .returns(targetQueryClass)
            // Construct the target query and stash a deferred
            // source-step lambda — the source's interceptor chain
            // does NOT fire here; it fires at the terminal's call
            // site inside the terminal's try/catch (see KDoc on
            // `deferredSourceStep`). This lets
            // `.queryX().allOrError()` catch source-step rejections
            // as `Err(QueryRejected)` instead of having queryX()
            // throw before allOrError() can run.
            .addStatement("val target = %T(driver, client)", targetQueryClass)
            // Cross-class write through the @EntktInternal seeder.
            .addStatement(
                "target.seedEdgeTraversal(%T::class, %S, this.traversalPath + %T(%T::class, %S, %T::class))",
                sourceEntityClass, re.name, edgeStepClass, sourceEntityClass, re.name, targetEntityClass,
            )
            // Snapshot source state at queryX() time into a fresh
            // source-Query instance so the deferred lambda is
            // immune to later mutations on `this`. Without the
            // snapshot, `users.queryPosts(); users.where(...);
            // posts.allOrThrow()` would let the post-queryX where
            // leak into posts' bridge predicate, which contradicts
            // the pre-deferral snapshot-at-construction semantics.
            // List / nullable fields are immutable values, so copying
            // the references is sufficient: source mutators reassign
            // the reference on `this`, not on the snapshot. The copy
            // is delegated to the generated `snapshotForTraversal`
            // method, which lives inside the source query class and
            // can access the private backing fields via same-class
            // private access.
            .addStatement("val sourceQ = this.snapshotForTraversal(driver, client)")
            .addCode(
                CodeBlock.builder()
                    // Cross-class write goes through the @EntktInternal
                    // seeder so application code can't clear / overwrite
                    // deferredSourceStep without an explicit opt-in (it
                    // is `private` on the target class).
                    .add("target.setDeferredSourceStep {\n")
                    .add(
                        "  val sourceSpec = sourceQ.runReadInterceptors(%T.EDGE_TRAVERSAL, %T.QUERY)\n",
                        READ_OPERATION, ENT_OPERATION,
                    )
                    // sourceSpec is FrozenQuerySpec<SourceEntity>; its
                    // predicates are typed `List<Predicate<SourceEntity>>`
                    // — no cast needed.
                    .add(
                        "  val parent: %T<%T>? = sourceSpec.predicates.reduceOrNull { acc, p -> %T.And(acc, p) }\n",
                        predicateClass, sourceEntityClass, predicateClass,
                    )
                    // Bridge is target-scoped: HasEdgeWith<Target, Source>
                    // (the inner predicate is on Source). The candidate
                    // entity is the target. The walker's edge-name witness +
                    // unchecked cast soundness applies symmetrically
                    // to the construction site here — codegen knows
                    // both Source and Target by schema.
                    .add(
                        "  val bridge: %T<%T> = if (parent != null) %T.HasEdgeWith<%T, %T>(%S, parent) else %T.HasEdge<%T>(%S)\n",
                        predicateClass, targetEntityClass,
                        predicateClass,
                        targetEntityClass, sourceEntityClass,
                        inverse.name,
                        predicateClass,
                        targetEntityClass,
                        inverse.name,
                    )
                    .add(
                        "  %T<%T>(bridge = bridge, annotations = sourceSpec.annotations)\n",
                        traversalSourceResult, targetEntityClass,
                    )
                    .add("}\n")
                    .build()
            )
            .addStatement("return target.apply(block)")
            .build()
    }
}

internal fun toPascalCase(snakeCase: String): String =
    toCamelCase(snakeCase).replaceFirstChar { it.uppercase() }
