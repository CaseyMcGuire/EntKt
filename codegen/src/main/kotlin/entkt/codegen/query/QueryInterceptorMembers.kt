package entkt.codegen.query

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.asClassName
import entkt.codegen.pluralize

private val PREDICATE = ClassName("entkt.query", "Predicate")
private val ENT_OPERATION = ClassName("entkt.runtime.result", "EntOperation")
private val ENT_QUERY_REJECTED_EXCEPTION = ClassName("entkt.runtime.result", "EntQueryRejectedException")
private val ABORT_QUERY_REJECTED = ClassName("entkt.runtime.query", "AbortQueryRejected")
private val FROZEN_QUERY_SPEC = ClassName("entkt.runtime.query", "FrozenQuerySpec")
private val QUERY_SPEC_BUILDER = ClassName("entkt.runtime.query", "QuerySpecBuilder")
private val QUERY_CONTEXT = ClassName("entkt.runtime.query", "QueryContext")
private val READ_OPERATION = ClassName("entkt.runtime.query", "ReadOperation")
private val INTERCEPTOR_ENGINE = ClassName("entkt.runtime.query", "InterceptorEngine")

// ------------------------------------------------------------------
// Interceptor plumbing on the generated query class: the shared
// runReadInterceptors entry every terminal calls, and the
// edge-predicate walker that fires target-entity interceptors on
// HasEdge / HasEdgeWith subpredicates. Split out of QueryGenerator;
// generate() assembles the members, and the per-edge dispatch is
// driven by the resolved edge metadata in [ResolvedQuerySchema].
// ------------------------------------------------------------------

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
internal fun buildRunReadInterceptors(schemaName: String, entityClass: ClassName): FunSpec {
    val repoPropName = pluralize(schemaName.replaceFirstChar { it.lowercase() })
    val readOp = READ_OPERATION
    // Structural predicates pass in typed to this query's entity
    // scope. The deferred-source-step bridge (target-scoped) and
    // the eager-load `id IN (...)` leaf both produce
    // Predicate<EntityClass>, so the combined list is uniformly
    // typed. QuerySpecBuilder's erased-storage signatures accept
    // it via standard List<out T> variance.
    val predicateForEntity = PREDICATE.parameterizedBy(entityClass)
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
                    PREDICATE, entityClass,
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
internal fun buildRunEdgePredicateInterceptors(resolved: ResolvedQuerySchema): FunSpec {
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
        PREDICATE, entityClass, PREDICATE,
    )
    body.add(
        "  is %T.Or<%T> -> %T.Or(runEdgePredicateInterceptors(predicate.left, parentPath, edgeAnnotations), runEdgePredicateInterceptors(predicate.right, parentPath, edgeAnnotations))\n",
        PREDICATE, entityClass, PREDICATE,
    )
    // HasEdgeWith dispatch. Predicate.HasEdgeWith<E, Target>.inner
    // is typed Predicate<Target>, but the smart-cast lands at
    // HasEdgeWith<E, *> so .inner is Predicate<*>. The edge-name
    // serves as the runtime witness for recovering Target. Each
    // branch knows its target statically (from the schema), does
    // an unchecked cast inside
    // the branch, and rebuilds a typed HasEdgeWith for the
    // candidate before returning.
    body.add("  is %T.HasEdgeWith<%T, *> -> when (predicate.edge) {\n", PREDICATE, entityClass)
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
            PREDICATE, targetClass,
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
            PREDICATE,
        )
        body.add(
            "      %T.HasEdgeWith<%T, %T>(predicate.edge, combinedInner)\n",
            PREDICATE, entityClass, targetClass,
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
    body.add("  is %T.HasEdge<%T> -> when (predicate.edge) {\n", PREDICATE, entityClass)
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
            PREDICATE,
        )
        body.add(
            "      if (combined != null) %T.HasEdgeWith<%T, %T>(predicate.edge, combined) else predicate\n",
            PREDICATE, entityClass, targetClass,
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
    val predicateForThis = PREDICATE.parameterizedBy(entityClass)
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
