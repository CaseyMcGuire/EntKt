package entkt.codegen

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.LambdaTypeName
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.UNIT

private val PREDICATE = ClassName("entkt.query", "Predicate")
private val DRIVER = ClassName("entkt.runtime.driver", "Driver")
private val ENT_OPERATION = ClassName("entkt.runtime.result", "EntOperation")
private val READ_OPERATION = ClassName("entkt.runtime.query", "ReadOperation")

// ------------------------------------------------------------------
// queryX() traversal generation: the deferred-source-step bridge and
// the two traversal method shapes (direct-edge via the inverse edge,
// M2M via HasM2MEdgeFrom), plus the traversal-only state seeders
// (snapshotForTraversal / setDeferredSourceStep). seedEdgeTraversal
// stays in QueryGenerator — the eager blocks and the edge-predicate
// walker seed traversal context through it too, not just queryX().
// ------------------------------------------------------------------

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
internal fun buildSnapshotForTraversal(queryClass: ClassName, clientClass: ClassName): FunSpec {
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
 * Generate `setDeferredSourceStep(step)` seeder on the query
 * class. The `deferredSourceStep` field itself is `private` —
 * clearing it would remove the structural traversal-bridge
 * constraint and let queries leak across the boundary. Cross-
 * class write needs a method; the method is
 * `@EntktInternal internal` so application code can't call it
 * without explicit `@OptIn`. Generated traversal code (in
 * `@file:OptIn(EntktInternal::class)` files) calls it freely.
 */
internal fun buildSetDeferredSourceStep(entityClass: ClassName): FunSpec {
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
internal fun buildM2MTraversal(
    re: ResolvedQueryEdge,
    resolved: ResolvedQuerySchema,
    packageName: String,
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
                    PREDICATE, sourceEntityClass, PREDICATE,
                )
                // M2M bridge: HasM2MEdgeFrom<Target, Source>(...).
                // The candidate is the M2M target; the sourceFilter
                // constrains rows in the source table.
                .add("  %T<%T>(\n", traversalSourceResult, targetEntityClass)
                .add(
                    "    bridge = %T.HasM2MEdgeFrom<%T, %T>(%S, %S, parent),\n",
                    PREDICATE,
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
internal fun buildTraversal(
    re: ResolvedQueryEdge,
    resolved: ResolvedQuerySchema,
    packageName: String,
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
                    PREDICATE, sourceEntityClass, PREDICATE,
                )
                // Bridge is target-scoped: HasEdgeWith<Target, Source>
                // (the inner predicate is on Source). The candidate
                // entity is the target. The walker's edge-name witness +
                // unchecked cast soundness applies symmetrically
                // to the construction site here — codegen knows
                // both Source and Target by schema.
                .add(
                    "  val bridge: %T<%T> = if (parent != null) %T.HasEdgeWith<%T, %T>(%S, parent) else %T.HasEdge<%T>(%S)\n",
                    PREDICATE, targetEntityClass,
                    PREDICATE,
                    targetEntityClass, sourceEntityClass,
                    inverse.name,
                    PREDICATE,
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
