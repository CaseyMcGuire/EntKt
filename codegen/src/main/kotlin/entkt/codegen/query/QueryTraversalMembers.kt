package entkt.codegen.query

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.LambdaTypeName
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.UNIT
import entkt.codegen.toPascalCase

private val PREDICATE = ClassName("entkt.query", "Predicate")
private val TRAVERSAL_SOURCE_SHAPE = ClassName("entkt.query", "TraversalSourceShape")
private val DRIVER = ClassName("entkt.runtime.driver", "Driver")
private val ENT_OPERATION = ClassName("entkt.runtime.result", "EntOperation")
private val READ_OPERATION = ClassName("entkt.runtime.query", "ReadOperation")
private val PRIVACY_CONTEXT = ClassName("entkt.runtime.privacy", "PrivacyContext")

// ------------------------------------------------------------------
// queryX() traversal generation: the deferred-source-step bridge and
// the two traversal method shapes (direct-edge via the inverse edge,
// M2M via the source's forward edge), plus the traversal-only state
// seeders (snapshotForTraversal / setDeferredSourceStep).
// seedEdgeTraversal stays in QueryGenerator — the eager blocks and
// the edge-predicate walker seed traversal context through it too,
// not just queryX(). Both traversal shapes embed the source query's
// post-interceptor shape (predicates, order, limit, offset, flags)
// in a shaped bridge — HasEdgeFromShape / HasM2MEdgeFromShape — via
// the single [deferredShapedSourceStep] emitter so the two lowerings
// can't drift as the traversal shape evolves.
// ------------------------------------------------------------------

/**
 * Emit the deferred source-step lambda both traversal shapes stash on
 * the target query: run the source's interceptor chain (operation =
 * EDGE_TRAVERSAL) at terminal time, then embed the post-interceptor
 * source shape in a [bridgeType] bridge predicate. The source's
 * `where`, `orderBy`, `limit`, and `offset` all survive into the
 * bridge — traversal follows the source query as written.
 *
 * [selectedColumn] is the column on the source table the traversal
 * joins through, known statically to codegen: the source-side join
 * column of the edge being traversed (`id` when the target row
 * carries the FK, the FK column when traversing child-to-parent,
 * and the junction-referenced source column for M2M).
 */
private fun deferredShapedSourceStep(
    bridgeType: String,
    edgeName: String,
    selectedColumn: String,
    targetEntityClass: ClassName,
    sourceEntityClass: ClassName,
): CodeBlock {
    val traversalSourceResult = ClassName("entkt.runtime.query", "TraversalSourceResult")
    return CodeBlock.builder()
        // Cross-class write goes through the @EntktInternal
        // seeder so application code can't clear / overwrite
        // deferredSourceStep without an explicit opt-in (it
        // is `private` on the target class).
        .add("target.setDeferredSourceStep { privacy ->\n")
        // The source's interceptor chain does NOT fire at queryX()
        // time; it fires here, at the terminal's call site inside
        // the terminal's try/catch — which is what lets
        // `.queryX().all()` capture source-step rejections as
        // `Err(QueryRejected)`.
        .add(
            "  val sourceSpec = sourceQ.runReadInterceptors(%T.EDGE_TRAVERSAL, privacy)\n",
            READ_OPERATION,
        )
        // The embedded shape is the POST-interceptor source shape:
        // sourceSpec's predicates / orderBy / limit / offset already
        // reflect every source-step interceptor mutation (added
        // predicates, set-or-clamped limits), so safety limits apply
        // to which source rows are traversed. sourceSpec's lists are
        // typed in the source entity scope, so the shape and bridge
        // construction need no casts.
        .add("  %T<%T>(\n", traversalSourceResult, targetEntityClass)
        .add(
            "    bridge = %T.%L<%T, %T>(\n",
            PREDICATE, bridgeType, targetEntityClass, sourceEntityClass,
        )
        .add("      %S,\n", edgeName)
        .add("      %T(\n", TRAVERSAL_SOURCE_SHAPE)
        .add("        table = sourceSpec.table,\n")
        .add("        selectedColumn = %S,\n", selectedColumn)
        .add("        predicates = sourceSpec.predicates,\n")
        .add("        orderBy = sourceSpec.orderBy,\n")
        .add("        limit = sourceSpec.limit,\n")
        .add("        offset = sourceSpec.offset,\n")
        .add("        flags = sourceSpec.flags,\n")
        .add("      ),\n")
        .add("    ),\n")
        .add("    annotations = sourceSpec.annotations,\n")
        .add("  )\n")
        .add("}\n")
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
        parameters = listOf(ParameterSpec.unnamed(PRIVACY_CONTEXT)),
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
 * edge [re]. Lowered to a `Predicate.HasM2MEdgeFromShape` against
 * the candidate target row, embedding the source query's post-
 * interceptor shape and naming the forward edge declared on the
 * source — the runtime walks the junction backwards using the
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
    val edgeStepClass = ClassName("entkt.runtime.query", "EdgeStep")

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
        // `.queryX().all()` capture source-step rejections
        // as `Err(QueryRejected)` instead of having queryX()
        // throw before all() could capture it.
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
        // posts.all()` would let the post-queryX where
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
        // M2M bridge: HasM2MEdgeFromShape<Target, Source>. The
        // candidate is the M2M target; the shape's rows in the
        // source table feed the junction walk. The junction
        // references the source's id column — resolveM2MEdgeJoin
        // pins sourceColumn = "id" — so that is the selected
        // column even when the junction schema isn't visible to
        // codegen (re.join == null).
        .addCode(
            deferredShapedSourceStep(
                bridgeType = "HasM2MEdgeFromShape",
                edgeName = re.name,
                selectedColumn = re.join?.sourceColumn ?: "id",
                targetEntityClass = targetEntityClass,
                sourceEntityClass = sourceEntityClass,
            )
        )
        .addStatement("return target.apply(block)")
        .build()
}

/**
 * Generate a `queryX(): TargetQuery` method for edge [re]. This is
 * the traversal entry point — given a query on the source schema,
 * walk across the edge and return a query on the target.
 *
 * Lowering: the source query's post-interceptor shape (predicates,
 * order, limit, offset) embeds in a HasEdgeFromShape predicate on
 * the target query, naming the *inverse* edge (i.e. the edge on the
 * target that points back at the source). The driver lowers the
 * shape into a source-id subquery so traversal follows the source
 * query as written.
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
    // Direct-edge join resolution either succeeds or throws (see
    // resolveEdgeJoin), so join is always present when the inverse
    // is; the guard just mirrors the nullable type.
    val join = re.join ?: return null
    val sourceEntityClass = ClassName(packageName, sourceName)
    val targetEntityClass = re.targetClass
    val targetQueryClass = re.targetQueryClass
    val methodName = "query${toPascalCase(re.name)}"
    val edgeStepClass = ClassName("entkt.runtime.query", "EdgeStep")

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
        // `.queryX().all()` capture source-step rejections
        // as `Err(QueryRejected)` instead of having queryX()
        // throw before all() could capture it.
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
        // posts.all()` would let the post-queryX where
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
        // Bridge is target-scoped: HasEdgeFromShape<Target, Source>
        // (the embedded shape is on Source), naming the inverse edge
        // on the target. The selected column is the source-side join
        // column of the traversed edge: "id" when the target row
        // carries the FK (User.queryPosts selects users.id), the FK
        // column when traversing child-to-parent (Post.queryAuthor
        // selects posts.author_id).
        .addCode(
            deferredShapedSourceStep(
                bridgeType = "HasEdgeFromShape",
                edgeName = inverse.name,
                selectedColumn = join.sourceColumn,
                targetEntityClass = targetEntityClass,
                sourceEntityClass = sourceEntityClass,
            )
        )
        .addStatement("return target.apply(block)")
        .build()
}
