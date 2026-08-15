package entkt.codegen.query

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.LambdaTypeName
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.UNIT
import com.squareup.kotlinpoet.asClassName
import entkt.codegen.columnName
import entkt.codegen.metadata.EdgeJoin
import entkt.codegen.pluralize
import entkt.codegen.toCamelCase
import entkt.schema.EdgeKind

private val PREDICATE = ClassName("entkt.query", "Predicate")
private val OP = ClassName("entkt.query", "Op")
private val EDGE_STATE = ClassName("entkt.runtime.query", "EdgeState")
private val PRIVACY_CONTEXT = ClassName("entkt.runtime.privacy", "PrivacyContext")
private val QUERY_EXPLANATION = ClassName("entkt.runtime.query", "QueryExplanation")
private val ENT_QUERY_REJECTED_EXCEPTION = ClassName("entkt.runtime.result", "EntQueryRejectedException")
private val ENT_PRIVACY_DENIED = ClassName("entkt.runtime.result", "EntPrivacyDeniedException")
private val LOAD_DENIAL_ORIGIN = ClassName("entkt.runtime.result", "LoadDenialOrigin")
private val EAGER_EDGE_STEP = ClassName("entkt.runtime.result", "EagerEdgeStep")
private val EAGER_LOAD_HANDLE = ClassName("entkt.runtime.query", "EagerLoad")
private val READ_OPERATION = ClassName("entkt.runtime.query", "ReadOperation")

// ------------------------------------------------------------------
// Eager loading: the `withX` DSL surface, the batch `loadEdges`
// member, and the per-edge eager explain block. Split out of
// QueryGenerator so the eager runtime shape and its explain mirror
// live side by side — every "explain must match the driver call"
// constraint in here binds a block below to its sibling.
// QueryGenerator.generate() assembles the members; everything here
// is driven by the resolved edge metadata in [ResolvedQuerySchema].
// ------------------------------------------------------------------

/**
 * Holds the generated property and `with{Edge}()` method for one
 * eagerly-loadable edge.
 */
internal data class EagerEdgeSpec(
    val edgeName: String,
    val property: PropertySpec,
    val filterVisibleProperty: PropertySpec,
    val withMethod: FunSpec,
)

/**
 * Build the nullable property and `with{Edge}()` method for a single
 * eager-capable edge. Callers pass only edges with a resolved
 * [ResolvedQueryEdge.join] — an unresolvable join means no eager
 * surface at all.
 */
internal fun buildEagerEdgeSpec(
    re: ResolvedQueryEdge,
    resolved: ResolvedQuerySchema,
    packageName: String,
): EagerEdgeSpec {
    val targetQueryClass = re.targetQueryClass
    // The with-method's return type names this query class via the
    // schemaNames map entry, not the schemaName argument — see
    // [ResolvedQuerySchema.sourceName] for why the lookup is kept.
    val queryClass = ClassName(packageName, "${resolved.sourceName}Query")
    val eagerPropName = re.eagerPropName
    val withMethodName = re.withMethodName

    val property = PropertySpec.builder(
        eagerPropName,
        targetQueryClass.copy(nullable = true),
    )
        .addModifiers(KModifier.PRIVATE)
        .mutable(true)
        .initializer("null")
        .build()

    // Per-edge filterVisible opt-in, set only through the EagerLoad
    // handle returned by the with-method. Reset on every
    // reconfiguration of the edge so a stale opt-in can't leak into a
    // later strict configuration.
    val filterVisibleProperty = PropertySpec.builder(
        "${eagerPropName}FilterVisible",
        Boolean::class,
    )
        .addModifiers(KModifier.PRIVATE)
        .mutable(true)
        .initializer("false")
        .build()

    val blockLambda = LambdaTypeName.get(
        receiver = targetQueryClass,
        returnType = UNIT,
    )
    val withMethod = FunSpec.builder(withMethodName)
        .addParameter(
            ParameterSpec.builder("block", blockLambda)
                .defaultValue("{}")
                .build()
        )
        .returns(EAGER_LOAD_HANDLE.parameterizedBy(queryClass))
        .addStatement("val configured = %T(driver, client).apply(block)", targetQueryClass)
        .addStatement("%L = configured", eagerPropName)
        .addStatement("%LFilterVisible = false", eagerPropName)
        .addCode(
            CodeBlock.builder()
                .add("return object : %T<%T> {\n", EAGER_LOAD_HANDLE, queryClass)
                .add("  override fun filterVisible(): %T {\n", queryClass)
                // The handle governs only the configuration that
                // produced it: reconfiguring the edge resets the flag,
                // and a retained stale handle must not silently weaken
                // the newer (strict-by-default) configuration.
                .add("    check(%L === configured) {\n", eagerPropName)
                .add(
                    "      %S\n",
                    "stale EagerLoad handle: with-edge was reconfigured after this handle was created; " +
                        "call filterVisible() on the handle returned by the latest configuration",
                )
                .add("    }\n")
                .add("    %LFilterVisible = true\n", eagerPropName)
                .add("    return this@%L\n", queryClass.simpleName)
                .add("  }\n")
                .add("}\n")
                .build(),
        )
        .build()

    return EagerEdgeSpec(re.name, property, filterVisibleProperty, withMethod)
}

/**
 * Build the `loadEdges` method that batch-loads all eager edges.
 * Generated per-query, with edge-type-specific blocks for each
 * declared edge. Called by `all()` and `firstOrNull()` with their
 * single terminal-captured privacy context.
 */
internal fun buildLoadEdges(resolved: ResolvedQuerySchema): FunSpec {
    val entityClass = resolved.entityClass
    val body = CodeBlock.builder()
    // No empty-results shortcut: the EAGER_LOAD interceptor pass
    // fires on every configured eager subquery even when the root
    // matched nothing — whether an interceptor runs, and whether it
    // can `reject()`, must not depend on what the database returned.
    // `explain()` fires the pass unconditionally too. Only the
    // driver fetches inside the per-edge blocks are data-gated.
    // (Scope: a read that aborts before edge loading — a root
    // interceptor rejection, or a strict read's LOAD-privacy denial
    // thrown before its loadEdges call — never reaches this pass;
    // the invariant is over reads that reach edge loading.)
    body.addStatement("var entities = results")

    for (re in resolved.edges) {
        val join = re.join ?: continue
        when (re.edge.kind) {
            is EdgeKind.ManyToMany -> emitM2MEagerBlock(body, re, join, entityClass)
            is EdgeKind.BelongsTo -> emitToOneEagerBlock(body, re, join, entityClass, resolved)
            is EdgeKind.HasOne -> emitHasOneEagerBlock(body, re, join, entityClass)
            is EdgeKind.HasMany -> emitToManyEagerBlock(body, re, join, entityClass)
        }
    }

    body.addStatement("return entities")

    return FunSpec.builder("loadEdges")
        .addModifiers(KModifier.INTERNAL)
        .addParameter("results", List::class.asClassName().parameterizedBy(entityClass))
        .addParameter("eagerPrivacyContext", PRIVACY_CONTEXT)
        .returns(List::class.asClassName().parameterizedBy(entityClass))
        .addCode(body.build())
        .build()
}

/**
 * Emit the eager loading block for a to-many direct edge.
 * The FK lives on the target side: source.id → target.fk_column.
 */
private fun emitToManyEagerBlock(
    body: CodeBlock.Builder,
    re: ResolvedQueryEdge,
    join: EdgeJoin,
    sourceClass: ClassName,
) {
    val targetClass = re.targetClass
    body.beginControlFlow("%L?.let { subQuery ->", re.eagerPropName)
    body.addStatement("val sourceIds = entities.map { it.id }")
    emitEagerSubquerySetup(body, re.name, sourceClass, targetClass)
    // Run target interceptors with EAGER_LOAD. The IN
    // predicate that ties target rows back to the source ids
    // goes in via extraStructural so it's tagged STRUCTURAL,
    // not CALLER. Fetch all matching rows — limit/offset are
    // applied per group below.
    body.addStatement(
        "val subSpec = subQuery.runReadInterceptors(%T.EAGER_LOAD, eagerPrivacyContext, listOf(%T.Leaf<%T>(%S, %T.IN, sourceIds)))",
        READ_OPERATION, PREDICATE, targetClass, join.targetColumn, OP,
    )
    // Bounds are resolved before the fetch so a window that admits
    // nothing skips the round trip; every row would be dropped by the
    // `take(0)` below. An empty parent set skips it too — the IN
    // could match nothing. The interceptor pass above still runs —
    // it fires on every eager subquery regardless of bounds or data.
    body.addStatement("val perGroupOffset = subQuery.queryOffset ?: 0")
    body.addStatement("val perGroupLimit = subQuery.queryLimit ?: Int.MAX_VALUE")
    body.addStatement(
        "val targetRows = if (perGroupLimit > 0 && sourceIds.isNotEmpty()) driver.query(%T.TABLE, subSpec.predicates, subSpec.orderBy, null, null) else emptyList()",
        targetClass,
    )
    // Decode once, in the target query's result order, so the strict
    // privacy pass can evaluate targets in that order (the grouped map
    // iterates in first-occurrence order, which is NOT result order
    // when denied targets belong to different parents).
    body.addStatement(
        "val decodedTargets = targetRows.map { it to %T.fromRow(it) }",
        targetClass,
    )
    body.addStatement(
        "val grouped = decodedTargets.groupBy { (row, _) -> row[%S] }",
        join.targetColumn,
    )
    body.addStatement(
        "var loadedGroups = grouped.mapValues { (_, pairs) -> pairs.drop(perGroupOffset).take(perGroupLimit).map { it.second } }",
    )
    emitEagerPrivacyCheck(
        body, re.targetName, "loadedGroups", grouped = true, eagerPropName = re.eagerPropName,
        orderedIteration = "for ((_, entity) in decodedTargets)",
    )
    body.addStatement(
        "loadedGroups = loadedGroups.mapValues { (_, list) -> subQuery.loadEdges(list, eagerPrivacyContext) }",
    )
    emitEmptyGroupsNestedPass(body)
    body.addStatement(
        "entities = entities.map { entity -> entity.copy(edges = entity.edges.copy(%L = %T.Loaded(loadedGroups[entity.id] ?: emptyList()))) }",
        re.edgePropName, EDGE_STATE,
    )
    body.endControlFlow()
}

/**
 * Emit the eager loading block for a hasOne edge.
 * The FK lives on the target side (like hasMany), but the Edges
 * property is a single nullable entity (like belongsTo).
 */
private fun emitHasOneEagerBlock(
    body: CodeBlock.Builder,
    re: ResolvedQueryEdge,
    join: EdgeJoin,
    sourceClass: ClassName,
) {
    val targetClass = re.targetClass
    body.beginControlFlow("%L?.let { subQuery ->", re.eagerPropName)
    body.addStatement("val sourceIds = entities.map { it.id }")
    emitEagerSubquerySetup(body, re.name, sourceClass, targetClass)
    body.addStatement(
        "val subSpec = subQuery.runReadInterceptors(%T.EAGER_LOAD, eagerPrivacyContext, listOf(%T.Leaf<%T>(%S, %T.IN, sourceIds)))",
        READ_OPERATION, PREDICATE, targetClass, join.targetColumn, OP,
    )
    // Bounds are resolved before the fetch so a window that admits
    // nothing skips the round trip; every row would be dropped by the
    // `drop().take()` below. The interceptor pass above still runs —
    // it fires on every eager subquery regardless of bounds.
    //
    // A positive offset also empties the window here, which it does
    // not on the to-many path: a `hasOne` edge requires its inverse
    // `belongsTo` to declare `.unique()` (enforced by codegen and by
    // the schema DSL), so the unique index guarantees at most one row
    // per source and `drop(1)` leaves nothing.
    body.addStatement("val perGroupOffset = subQuery.queryOffset ?: 0")
    body.addStatement("val perGroupLimit = subQuery.queryLimit ?: Int.MAX_VALUE")
    body.addStatement("val targetInWindow = perGroupOffset == 0 && perGroupLimit > 0")
    body.addStatement(
        "val targetRows = if (targetInWindow && sourceIds.isNotEmpty()) driver.query(%T.TABLE, subSpec.predicates, subSpec.orderBy, null, null) else emptyList()",
        targetClass,
    )
    body.addStatement(
        "val decodedTargets = targetRows.map { it to %T.fromRow(it) }",
        targetClass,
    )
    body.addStatement(
        "val grouped = decodedTargets.groupBy { (row, _) -> row[%S] }",
        join.targetColumn,
    )
    // Bounds apply per parent, exactly as they do for the to-many
    // edges. A hasOne group holds at most one row, so a positive
    // limit is already satisfied — but `limit(0)` means "no rows"
    // here as everywhere else, and `offset(1)` steps past the only
    // candidate. Ignoring them because "a to-one can't have many"
    // answers a different question than the caller asked.
    body.addStatement(
        "var loadedGroups = grouped.mapValues { (_, pairs) -> pairs.drop(perGroupOffset).take(perGroupLimit).map { it.second } }",
    )
    emitEagerPrivacyCheck(
        body, re.targetName, "loadedGroups", grouped = true, eagerPropName = re.eagerPropName,
        orderedIteration = "for ((_, entity) in decodedTargets)",
    )
    body.addStatement(
        "loadedGroups = loadedGroups.mapValues { (_, list) -> subQuery.loadEdges(list, eagerPrivacyContext) }",
    )
    emitEmptyGroupsNestedPass(body)
    body.addStatement(
        "entities = entities.map { entity -> entity.copy(edges = entity.edges.copy(%L = %T.Loaded(loadedGroups[entity.id]?.firstOrNull()))) }",
        re.edgePropName, EDGE_STATE,
    )
    body.endControlFlow()
}

/**
 * Emit the zero-group nested pass for the grouped eager paths
 * (hasMany / hasOne / M2M): nested eager loads run once per parent
 * group, so with no groups the nested EAGER_LOAD interceptor pass
 * would silently not fire — making a nested interceptor's view of
 * the query (and its ability to `reject()`) depend on what level-1
 * data came back. One empty-batch pass keeps nested firing
 * data-independent, matching the belongsTo path (whose single
 * unconditional `loadEdges` call has these semantics already) and
 * `explain()`'s unconditional recursion into nested eager shapes.
 */
private fun emitEmptyGroupsNestedPass(body: CodeBlock.Builder) {
    body.addStatement(
        "if (loadedGroups.isEmpty()) subQuery.loadEdges(emptyList(), eagerPrivacyContext)",
    )
}

/**
 * Emit the eager loading block for a to-one direct edge.
 * The FK lives on the source side: source.fk_column → target.id.
 */
private fun emitToOneEagerBlock(
    body: CodeBlock.Builder,
    re: ResolvedQueryEdge,
    join: EdgeJoin,
    sourceClass: ClassName,
    resolved: ResolvedQuerySchema,
) {
    val targetClass = re.targetClass
    // Find the FK property name on the source entity
    val fk = resolved.edgeFks.find { it.columnName == join.sourceColumn }
    val fkPropName = fk?.propertyName ?: toCamelCase(join.sourceColumn)
    // A required FK is a non-null property, so the safe-call below would be
    // redundant (and Kotlin warns). Unknown → treat as nullable (safe).
    val fkRequired = fk?.required ?: false

    body.beginControlFlow("%L?.let { subQuery ->", re.eagerPropName)
    // Per-parent bounds, same contract as the to-many edges. A
    // belongsTo yields at most one target per parent, so the window
    // collapses to "is index 0 inside it?" — false for `limit(0)`
    // ("no rows", as everywhere else) and for any positive offset.
    //
    // Applied as a row slice below — before the privacy check, which
    // is what the to-many and hasOne paths do — so an excluded target
    // is never privacy-evaluated or nested-eager-loaded. A denial for
    // a row the caller explicitly asked not to load would otherwise
    // throw out of `withAuthor { limit(0) }`.
    //
    // Deliberately not short-circuiting the whole branch: the
    // EAGER_LOAD interceptor pass has to fire on every eager subquery
    // regardless of bounds, which is what the sibling paths and
    // `explain()` both do.
    body.addStatement("val perParentOffset = subQuery.queryOffset ?: 0")
    body.addStatement("val perParentLimit = subQuery.queryLimit ?: Int.MAX_VALUE")
    body.addStatement("val targetInWindow = perParentOffset == 0 && perParentLimit > 0")
    body.addStatement("val fkValues = entities.mapNotNull { it.%L }.distinct()", fkPropName)
    emitEagerSubquerySetup(body, re.name, sourceClass, targetClass)
    // Fetch every matching target in one `IN (...)` pass. The
    // caller's bounds are per parent, not over this batched result,
    // so they're applied below rather than passed to the driver —
    // a `limit` here would cap the total across all parents.
    //
    // Unconditional, including when every parent's FK is null: the
    // interceptor pass fires on every eager subquery, so whether an
    // interceptor runs — and whether it can `reject()` — must not
    // depend on the relationship data that happens to be present.
    // `explain()` fires it unconditionally too. An empty `fkValues`
    // just means the structural IN predicate is empty.
    body.addStatement(
        "val subSpec = subQuery.runReadInterceptors(%T.EAGER_LOAD, eagerPrivacyContext, listOf(%T.Leaf<%T>(%S, %T.IN, fkValues)))",
        READ_OPERATION, PREDICATE, targetClass, join.targetColumn, OP,
    )
    // Only the fetch is conditional. Skipped when nothing could
    // match — an empty IN, or a window that admits no row — because
    // every row it returned would be discarded anyway.
    body.addStatement(
        "val targetRows = if (targetInWindow && fkValues.isNotEmpty()) driver.query(%T.TABLE, subSpec.predicates, subSpec.orderBy, null, null) else emptyList()",
        targetClass,
    )
    // An empty fetch leaves nothing to privacy-check, nothing to
    // nested-load, and an empty `targetMap`, so the assignment below
    // yields null on its own with no special case.
    body.addStatement(
        "var loaded = targetRows.map { %T.fromRow(it) }",
        targetClass,
    )
    emitEagerPrivacyCheck(body, re.targetName, "loaded", grouped = false, eagerPropName = re.eagerPropName)
    body.addStatement("loaded = subQuery.loadEdges(loaded, eagerPrivacyContext)")
    body.addStatement("val targetMap = loaded.associateBy { it.id }")
    if (fkRequired) {
        // Non-null FK: map lookup directly (still nullable — the target may
        // have been filtered by eager LOAD privacy). The edge was requested,
        // so an absent target is Loaded(null), never Unloaded.
        body.addStatement(
            "entities = entities.map { entity -> entity.copy(edges = entity.edges.copy(%L = %T.Loaded(targetMap[entity.%L]))) }",
            re.edgePropName, EDGE_STATE, fkPropName,
        )
    } else {
        body.addStatement(
            "entities = entities.map { entity -> entity.copy(edges = entity.edges.copy(%L = %T.Loaded(entity.%L?.let { targetMap[it] }))) }",
            re.edgePropName, EDGE_STATE, fkPropName,
        )
    }
    // No `else` arm: an empty fetch produces an empty `targetMap`,
    // so the lookup above already yields null for every parent.
    body.endControlFlow()
}

/**
 * Emit the eager loading block for a many-to-many edge via junction table.
 */
private fun emitM2MEagerBlock(
    body: CodeBlock.Builder,
    re: ResolvedQueryEdge,
    join: EdgeJoin,
    sourceClass: ClassName,
) {
    val targetClass = re.targetClass
    body.beginControlFlow("%L?.let { subQuery ->", re.eagerPropName)
    body.addStatement("val sourceIds = entities.map { it.id }")
    // Query junction table (no interceptors — the junction
    // is internal storage, not an entity with interceptors).
    // Junction-table query has no entity scope. Skipped when there
    // are no parents at all — nothing could match — but issued for
    // any non-empty parent set even under `limit(0)`: its rows
    // produce the `targetIds` the EAGER_LOAD interceptor pass
    // predicates on.
    body.addStatement(
        "val junctionRows = if (sourceIds.isNotEmpty()) driver.query(%S, listOf(%T.Leaf<%T>(%S, %T.IN, sourceIds)), emptyList(), null, null) else emptyList()",
        join.junctionTable, PREDICATE, Any::class.asClassName(), join.junctionSourceColumn, OP,
    )
    body.addStatement(
        "val targetIds = junctionRows.map { it[%S] }.distinct()",
        join.junctionTargetColumn,
    )
    emitEagerSubquerySetup(body, re.name, sourceClass, targetClass)
    // Fetch all matching targets — limit/offset are applied per group below.
    //
    // The interceptor pass is unconditional, including when the
    // junction has no rows: whether an interceptor runs — and whether
    // it can `reject()` — must not depend on the relationship data
    // that happens to be present, and `explain()` fires it
    // unconditionally too. No junction rows just means the structural
    // IN predicate is empty.
    body.addStatement(
        "val subSpec = subQuery.runReadInterceptors(%T.EAGER_LOAD, eagerPrivacyContext, listOf(%T.Leaf<%T>(%S, %T.IN, targetIds)))",
        READ_OPERATION, PREDICATE, targetClass, "id", OP,
    )
    // Bounds resolved before the fetch, as on the direct-edge paths:
    // with `limit(0)` every row would be dropped by the `take(0)`
    // below, so the target round trip is pure waste. Offset is not
    // part of the condition — an M2M group can hold many rows, so
    // skipping some still leaves others.
    //
    // The junction query above is deliberately still issued: its rows
    // produce the `targetIds` that the EAGER_LOAD interceptor pass
    // predicates on, and interceptors fire on every eager subquery
    // regardless of bounds.
    body.addStatement("val perGroupOffset = subQuery.queryOffset ?: 0")
    body.addStatement("val perGroupLimit = subQuery.queryLimit ?: Int.MAX_VALUE")
    body.addStatement(
        "val targetRows = if (perGroupLimit > 0 && targetIds.isNotEmpty()) driver.query(%T.TABLE, subSpec.predicates, subSpec.orderBy, null, null) else emptyList()",
        targetClass,
    )
    // Build a target → sources membership lookup from the junction
    // rows. Then iterate `targetRows` in its (already
    // `subQuery.orderFields`-ordered) order, appending each target
    // to the per-source groups it belongs to. Iterating junction
    // rows directly here would drop the ordering — junction rows
    // come back in driver-default order, not target order — and the
    // subsequent `drop(offset).take(limit)` per group would slice
    // the wrong subset for `withTags { orderBy(...); limit(...) }`.
    //
    // The membership lookup uses a `MutableSet` per target id, not
    // a `MutableList`, so a junction with duplicate
    // `(source_id, target_id)` pairs (legal for `throughEntity`
    // junctions with no unique pair index — the row carries
    // distinct payload) collapses to one membership entry. Without
    // the dedup, `withTags()` would return the same target multiple
    // times in one source's group, while the EXISTS-based
    // `queryTags()` traversal correctly returns each target once,
    // and per-group `drop`/`take` would slice from a duplicated
    // list. `mutableSetOf()` returns a `LinkedHashSet` so insertion
    // order is preserved (not that the source iteration order
    // matters for the per-group ordering — that's driven by the
    // target-row iteration).
    body.addStatement(
        "val sourcesByTargetId = mutableMapOf<Any?, MutableSet<Any?>>()",
    )
    body.beginControlFlow("for (jr in junctionRows)")
    body.addStatement(
        "sourcesByTargetId.getOrPut(jr[%S]) { mutableSetOf() }.add(jr[%S])",
        join.junctionTargetColumn,
        join.junctionSourceColumn,
    )
    body.endControlFlow()
    body.addStatement("val grouped = mutableMapOf<Any?, MutableList<%T>>()", targetClass)
    // Row-ordered membership-bearing targets: the strict privacy pass
    // iterates this list so evaluation follows the target query's
    // result order rather than per-group map order.
    body.addStatement("val orderedTargets = mutableListOf<%T>()", targetClass)
    body.beginControlFlow("for (row in targetRows)")
    body.addStatement("val target = %T.fromRow(row)", targetClass)
    body.addStatement("val sources = sourcesByTargetId[target.id] ?: continue")
    body.addStatement("orderedTargets.add(target)")
    body.beginControlFlow("for (src in sources)")
    body.addStatement(
        "grouped.getOrPut(src) { mutableListOf() }.add(target)",
    )
    body.endControlFlow()
    body.endControlFlow()
    // Paginate, then privacy, then loadEdges — denied targets never trigger nested reads.
    body.addStatement(
        "var loadedGroups = grouped.mapValues { (_, list) -> list.drop(perGroupOffset).take(perGroupLimit) }",
    )
    emitEagerPrivacyCheck(
        body, re.targetName, "loadedGroups", grouped = true, eagerPropName = re.eagerPropName,
        orderedIteration = "for (entity in orderedTargets)",
    )
    body.addStatement(
        "loadedGroups = loadedGroups.mapValues { (_, list) -> subQuery.loadEdges(list, eagerPrivacyContext) }",
    )
    emitEmptyGroupsNestedPass(body)
    body.addStatement(
        "entities = entities.map { entity -> entity.copy(edges = entity.edges.copy(%L = %T.Loaded(loadedGroups[entity.id] ?: emptyList()))) }",
        re.edgePropName, EDGE_STATE,
    )
    // No `else` arm: with no junction rows the grouping is empty, so
    // the `?: emptyList()` above already covers every parent.
    body.endControlFlow()
}

/**
 * Set the eager subquery's traversal context (sourceEntity /
 * edgeName / path) so its [runReadInterceptors] call exposes
 * the right [QueryContext] for [ReadOperation.EAGER_LOAD]. Must
 * be called inside the `subQuery ->` lambda — uses `subQuery`
 * as the receiver. `entities.map { it.id }` and similar setup
 * happens before this call; this just patches in the context.
 */
private fun emitEagerSubquerySetup(
    body: CodeBlock.Builder,
    edgeName: String,
    sourceClass: ClassName,
    targetClass: ClassName,
) {
    val edgeStepClass = ClassName("entkt.runtime.query", "EdgeStep")
    // Interceptor context path: includes any traversal (queryX) hops
    // that led to this terminal, because EAGER_LOAD interceptors see
    // the full chain.
    body.addStatement(
        "val eagerPath = this.traversalPath + %T(%T::class, %S, %T::class)",
        edgeStepClass, sourceClass, edgeName, targetClass,
    )
    // Denial path: eager hops only, rooted at THIS terminal's own
    // selection — the RFC's EagerEdge origin describes the eager
    // schema-edge path from the terminal root, so traversal hops must
    // not appear in it.
    body.addStatement(
        "val eagerDenialPath = this.eagerDenialBasePath + %T(%T::class, %S, %T::class)",
        edgeStepClass, sourceClass, edgeName, targetClass,
    )
    // Cross-class write through the @EntktInternal seeder
    // (traversal context fields are `private` on the target
    // query class).
    body.addStatement(
        "subQuery.seedEdgeTraversal(%T::class, %S, eagerPath)",
        sourceClass, edgeName,
    )
    body.addStatement("subQuery.seedEagerDenialBasePath(eagerDenialPath)")
}

/**
 * Emit the eager LOAD privacy block for one edge. [loadedVar] is the
 * name of the mutable local holding the loaded entities (or grouped
 * map; when [grouped] is true the variable is a `Map<Any?, List<T>>`).
 *
 * Strict default: the first denied target throws
 * `EntPrivacyDeniedException(EagerEdge(eagerPath), listOf(denial))` —
 * exactly one keyed denial, fail-fast, no later eager work run solely
 * for diagnostics. The root terminal's capture boundary stores it in
 * `ReadResult.Failed`. Targets are evaluated in target-query result
 * order (the grouped maps preserve target-row encounter order).
 *
 * With the edge's `filterVisible()` opt-in ([eagerPropName]'s flag),
 * a returned LOAD-deny decision instead omits the target: a denied
 * to-one target becomes `EdgeState.Loaded(null)` (the filtered map
 * lookup misses), denied to-many targets are omitted from the loaded
 * list, and no replacement scanning occurs. Only a returned deny
 * decision is suppressed — an exception thrown by a privacy rule
 * escapes either way and remains a terminal failure.
 */
private fun emitEagerPrivacyCheck(
    body: CodeBlock.Builder,
    targetName: String,
    loadedVar: String,
    grouped: Boolean,
    eagerPropName: String,
    orderedIteration: String? = null,
) {
    val targetRepoProp = pluralize(targetName.replaceFirstChar { it.lowercase() })
    body.addStatement("val eagerClient = client")
    body.beginControlFlow("if (eagerClient != null && eagerClient.%L.hasLoadPrivacy())", targetRepoProp)
    body.beginControlFlow("if (%LFilterVisible)", eagerPropName)
    if (grouped) {
        // Filtered mode mirrors the strict pass's evaluation contract:
        // each in-window target's rules run exactly ONCE, in the
        // target query's result order — never once per parent group,
        // which would re-run rules for shared M2M targets and let
        // group iteration order decide which thrown rule exception
        // wins or (for a stateful rule) produce inconsistent
        // visibility across parents.
        body.addStatement(
            "val inWindow = %L.values.flatMapTo(mutableSetOf()) { it }",
            loadedVar,
        )
        body.addStatement("val visibleTargets = mutableSetOf<Any?>()")
        body.beginControlFlow(checkNotNull(orderedIteration) { "grouped filter pass needs orderedIteration" })
        body.addStatement("if (entity !in inWindow || entity in visibleTargets) continue")
        body.addStatement(
            "if (eagerClient.%L.loadDenialOrNull(eagerPrivacyContext, entity) == null) visibleTargets.add(entity)",
            targetRepoProp,
        )
        body.endControlFlow()
        body.addStatement(
            "%L = %L.mapValues { (_, list) -> list.filter { it in visibleTargets } }",
            loadedVar, loadedVar,
        )
    } else {
        body.addStatement(
            "%L = %L.filter { eagerClient.%L.loadDenialOrNull(eagerPrivacyContext, it) == null }",
            loadedVar, loadedVar, targetRepoProp,
        )
    }
    body.nextControlFlow("else")
    if (grouped) {
        // Strict evaluation follows the target query's RESULT order,
        // not per-group map order: iterate the row-ordered decoded
        // list and skip targets sliced out of every parent's window.
        body.addStatement(
            "val inWindow = %L.values.flatMapTo(mutableSetOf()) { it }",
            loadedVar,
        )
        body.beginControlFlow(checkNotNull(orderedIteration) { "grouped strict pass needs orderedIteration" })
        body.addStatement("if (entity !in inWindow) continue")
        emitEagerDenialThrow(body, targetRepoProp)
        body.endControlFlow()
    } else {
        body.beginControlFlow("for (entity in %L)", loadedVar)
        emitEagerDenialThrow(body, targetRepoProp)
        body.endControlFlow()
    }
    body.endControlFlow()
    body.endControlFlow()
}

/** The strict fail-fast denial throw shared by both eager shapes. */
private fun emitEagerDenialThrow(body: CodeBlock.Builder, targetRepoProp: String) {
    body.addStatement(
        "val denial = eagerClient.%L.loadDenialOrNull(eagerPrivacyContext, entity)",
        targetRepoProp,
    )
    body.beginControlFlow("if (denial != null)")
    body.addStatement(
        "throw %T(%T.EagerEdge(eagerDenialPath.map { %T(it.source.simpleName!!, it.edgeName, it.target.simpleName!!) }), listOf(denial))",
        ENT_PRIVACY_DENIED, LOAD_DENIAL_ORIGIN, EAGER_EDGE_STEP,
    )
    body.endControlFlow()
}

/**
 * Emit the explain block for a single eager edge. Mirrors the
 * runtime EAGER_LOAD flow in `emit*EagerBlock` so the plan
 * reflects what actually runs:
 *
 * 1. Set up the sub-query's traversal context (sourceEntity /
 *    edgeName / path) — same as runtime.
 * 2. Run `subQuery.runReadInterceptors(EAGER_LOAD, privacy, QUERY,
 *    listOf(IN-predicate))`. The target's interceptors fire with
 *    `context.operation == EAGER_LOAD` (not ALL as the previous
 *    `subQuery.explain()` route would have done), and the
 *    resulting spec contains every target-side interceptor's
 *    predicates + annotations.
 * 3. Hand the spec to the sub-query's own `buildQueryPlan` so
 *    the rendered plan shows the post-interceptor shape, plus
 *    recursively-walked nested eager edges via the same path.
 *
 * For M2M edges the junction-table explain is computed in this
 * block (junction tables aren't entities and don't have
 * interceptors), then handed to the sub-query's `buildQueryPlan`
 * as the optional junction explain.
 *
 * The IN predicate uses [QueryExplanation.EXPLAIN_PLACEHOLDER]
 * as the value so the driver renders the actual column name
 * (e.g. `"author_id" IN (?)`) rather than collapsing an empty
 * IN list to FALSE.
 */
internal fun buildEagerExplainBlock(
    info: ResolvedQueryEdge,
    join: EdgeJoin,
    sourceClass: ClassName,
): CodeBlock {
    val edgeStepClass = ClassName("entkt.runtime.query", "EdgeStep")
    val body = CodeBlock.builder()
    body.beginControlFlow("%L?.let { subQuery ->", info.eagerPropName)
    // Mirror runtime emit*EagerBlock context setup so the
    // sub-query's interceptors see the right QueryContext.
    // Cross-class write goes through the @EntktInternal seeder.
    body.addStatement(
        "subQuery.seedEdgeTraversal(%T::class, %S, this.traversalPath + %T(%T::class, %S, %T::class))",
        sourceClass, info.name, edgeStepClass, sourceClass, info.name, info.targetClass,
    )

    // A rejected eager sub-explain becomes a rejected entry
    // in `edges` rather than failing the whole parent plan —
    // the root + sibling eager subplans still appear, the
    // caller can inspect `plan.eagerQueries["X"]?.rejected` to
    // see which step rejected. Explain rejection lives on the
    // plan, not as an exception.
    val queryPlanLocal = ClassName("entkt.runtime.query", "QueryPlan")
    if (info.isManyToMany) {
        // M2M: junction table explain stands alone (no
        // interceptors on the junction), then the target-table
        // plan uses the post-EAGER_LOAD spec with an IN on "id".
        // Junction-table query has no entity scope (junctions are
        // internal storage). Predicate.Leaf<Any> renders the same
        // structural fields and erases at the driver call.
        body.addStatement(
            "val junctionExplain = driver.explainQuery(%S, listOf(%T.Leaf<%T>(%S, %T.IN, %T.EXPLAIN_PLACEHOLDER)), emptyList(), null, null)",
            join.junctionTable, PREDICATE, Any::class.asClassName(), join.junctionSourceColumn, OP, QUERY_EXPLANATION,
        )
        body.add("edges[%S] = try {\n", info.name)
        body.add(
            "  val subSpec = subQuery.runReadInterceptors(%T.EAGER_LOAD, privacy, listOf(%T.Leaf<%T>(%S, %T.IN, %T.EXPLAIN_PLACEHOLDER)))\n",
            READ_OPERATION, PREDICATE, info.targetClass, "id", OP, QUERY_EXPLANATION,
        )
        // Strip limit/offset before handing to buildQueryPlan:
        // the runtime eager fetch uses null/null limit/offset
        // and paginates per-group in Kotlin (see
        // emit*EagerBlock), so passing spec.limit/spec.offset
        // here would render LIMIT/OFFSET in the explain that
        // doesn't match what the driver actually receives at
        // runtime. Predicates / orderBy / annotations DO flow
        // through accurately.
        body.add(
            "  subQuery.buildQueryPlan(subSpec.copy(limit = null, offset = null), includeEager = true, privacy = privacy, junctionExplain = junctionExplain)\n",
        )
        body.add("} catch (e: %T) {\n", ENT_QUERY_REJECTED_EXCEPTION)
        body.add("  %T.rejected(e)\n", queryPlanLocal)
        body.add("}\n")
    } else {
        // Direct edge: single query with IN on the join column.
        // hasMany/hasOne: IN on targetColumn (FK on target side).
        // belongsTo: IN on targetColumn ("id" on target side).
        body.add("edges[%S] = try {\n", info.name)
        body.add(
            "  val subSpec = subQuery.runReadInterceptors(%T.EAGER_LOAD, privacy, listOf(%T.Leaf<%T>(%S, %T.IN, %T.EXPLAIN_PLACEHOLDER)))\n",
            READ_OPERATION, PREDICATE, info.targetClass, join.targetColumn, OP, QUERY_EXPLANATION,
        )
        // See M2M branch above for why limit/offset are
        // stripped here.
        body.add(
            "  subQuery.buildQueryPlan(subSpec.copy(limit = null, offset = null), includeEager = true, privacy = privacy)\n",
        )
        body.add("} catch (e: %T) {\n", ENT_QUERY_REJECTED_EXCEPTION)
        body.add("  %T.rejected(e)\n", queryPlanLocal)
        body.add("}\n")
    }

    body.endControlFlow()
    return body.build()
}
