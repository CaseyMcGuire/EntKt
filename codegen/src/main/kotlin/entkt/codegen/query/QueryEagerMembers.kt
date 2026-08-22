package entkt.codegen.query

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.LambdaTypeName
import com.squareup.kotlinpoet.MemberName
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.UNIT
import com.squareup.kotlinpoet.asClassName
import entkt.codegen.columnName
import entkt.codegen.metadata.EdgeJoin
import entkt.schema.EdgeKind

private val PREDICATE = ClassName("entkt.query", "Predicate")
private val OP = ClassName("entkt.query", "Op")
private val EDGE_STATE = ClassName("entkt.runtime.query", "EdgeState")
private val PRIVACY_CONTEXT = ClassName("entkt.runtime.privacy", "PrivacyContext")
private val ENT_PRIVACY_DENIED = ClassName("entkt.runtime.result", "EntPrivacyDeniedException")
private val LOAD_DENIAL_ORIGIN = ClassName("entkt.runtime.result", "LoadDenialOrigin")
private val EAGER_EDGE_STEP = ClassName("entkt.runtime.result", "EagerEdgeStep")
private val EDGE_LOAD_HANDLE = ClassName("entkt.runtime.query", "EdgeLoad")
private val ENT_QUERY_CONFIGURATION_EXCEPTION =
    ClassName("entkt.runtime.result", "EntQueryConfigurationException")
private val READ_OPERATION = ClassName("entkt.runtime.query", "ReadOperation")
private val EAGER_WINDOW_STRATEGY = ClassName("entkt.runtime.query", "EagerWindowStrategy")
private val DIRECT_TO_MANY_QUERY = ClassName("entkt.runtime.driver", "DirectToManyQuery")
private val PER_PARENT_WINDOW = ClassName("entkt.runtime.driver", "PerParentWindow")
private val DIRECT_TO_MANY_WINDOW_CAPABILITY =
    ClassName("entkt.runtime.driver", "DirectToManyWindowCapability")
private val EXECUTE_DIRECT_TO_MANY = MemberName("entkt.runtime.driver", "executeDirectToMany")

// ------------------------------------------------------------------
// Edge loading: the `loadX` DSL surface and the batch `loadEdges`
// member. Split out of QueryGenerator to keep the generated graph
// loading algorithm together.
// QueryGenerator.generate() assembles the members; everything here
// is driven by the resolved edge metadata in [ResolvedQuerySchema].
// ------------------------------------------------------------------

/**
 * Holds the generated property and `load{Edge}()` method for one
 * loadable edge.
 */
internal data class EagerEdgeSpec(
    val edgeName: String,
    val property: PropertySpec,
    val filterVisibleProperty: PropertySpec,
    val loadMethod: FunSpec,
)

/**
 * Build the nullable property and `load{Edge}()` method for a single
 * load-capable edge. Callers pass only edges with a resolved
 * [ResolvedQueryEdge.join] — an unresolvable join means no edge-load
 * surface at all.
 */
internal fun buildEagerEdgeSpec(
    re: ResolvedQueryEdge,
    resolved: ResolvedQuerySchema,
    packageName: String,
): EagerEdgeSpec {
    val targetQueryClass = re.targetQueryClass
    // The load-method's return type names this query class via the
    // schemaNames map entry, not the schemaName argument — see
    // [ResolvedQuerySchema.sourceName] for why the lookup is kept.
    val queryClass = ClassName(packageName, "${resolved.sourceName}Query")
    val eagerPropName = re.eagerPropName
    val loadMethodName = re.loadMethodName

    val property = PropertySpec.builder(
        eagerPropName,
        targetQueryClass.copy(nullable = true),
    )
        .addModifiers(KModifier.PRIVATE)
        .mutable(true)
        .initializer("null")
        .build()

    // Per-edge filterVisible opt-in, set only through the EdgeLoad
    // handle returned by the load-method. Selecting an edge twice is
    // rejected, so the handle always governs the edge's only
    // configuration and no reset is needed.
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
    val inFlightGuard = "a terminal on this ${resolved.schemaName}Query is " +
        "executing and the in-flight operation's edge-load topology is fixed at " +
        "terminal entry"
    val loadMethod = FunSpec.builder(loadMethodName)
        // The zero-block overload must be callable from Java without
        // Kotlin's default-argument marker, so the JVM surface gets a
        // real no-arg method.
        .addAnnotation(JvmOverloads::class)
        .addParameter(
            ParameterSpec.builder("block", blockLambda)
                .defaultValue("{}")
                .build()
        )
        .returns(EDGE_LOAD_HANDLE.parameterizedBy(queryClass))
        // Terminal-entry isolation: an interceptor or privacy rule
        // that captured this query must not change the graph the
        // in-flight terminal is materializing. Rejected loudly rather
        // than silently deferred to the next execution.
        .beginControlFlow("if (activeTerminals > 0)")
        .addStatement(
            "throw %T(\n%S,\n%S,\n)",
            ENT_QUERY_CONFIGURATION_EXCEPTION,
            resolved.schemaName,
            "$loadMethodName() cannot select an edge now: $inFlightGuard",
        )
        .endControlFlow()
        // One selection per edge: a second load call must not silently
        // replace or merge the first block. Configuration misuse is
        // thrown here, at the call, before any terminal or driver I/O.
        .beginControlFlow("if (%L != null)", eagerPropName)
        .addStatement(
            "throw %T(\n%S,\n%S,\n)",
            ENT_QUERY_CONFIGURATION_EXCEPTION,
            resolved.schemaName,
            "${resolved.schemaName}.${re.publicName} is already selected on this " +
                "${resolved.schemaName}Query: $loadMethodName() may be called at most once " +
                "per query; compose all configuration for the edge in a single " +
                "$loadMethodName block",
        )
        .endControlFlow()
        .addStatement("val configured = %T(driver, client)", targetQueryClass)
        // Reserve the slot before the block runs so a re-entrant
        // load call inside the block hits the duplicate guard instead
        // of silently last-write-winning; roll the reservation back if
        // the block fails so a caught error leaves the query as if
        // the selection never happened.
        .addStatement("%L = configured", eagerPropName)
        .beginControlFlow("try")
        .addStatement("configured.apply(block)")
        .nextControlFlow("catch (e: %T)", Throwable::class)
        .addStatement("%L = null", eagerPropName)
        .addStatement("throw e")
        .endControlFlow()
        .addCode(
            CodeBlock.builder()
                .add("return object : %T<%T> {\n", EDGE_LOAD_HANDLE, queryClass)
                .add("  override fun filterVisible(): %T {\n", queryClass)
                // Same terminal-entry isolation as the load call: a
                // retained handle cannot change the in-flight
                // operation's privacy posture.
                .add("    if (activeTerminals > 0) {\n")
                .add(
                    "      throw %T(\n%S,\n%S,\n)\n",
                    ENT_QUERY_CONFIGURATION_EXCEPTION,
                    resolved.schemaName,
                    "filterVisible() for ${resolved.schemaName}.${re.publicName} cannot be " +
                        "called now: $inFlightGuard",
                )
                .add("    }\n")
                .add("    %LFilterVisible = true\n", eagerPropName)
                .add("    return this@%L\n", queryClass.simpleName)
                .add("  }\n")
                .add("}\n")
                .build(),
        )
        .build()

    return EagerEdgeSpec(re.name, property, filterVisibleProperty, loadMethod)
}

/**
 * Build the private `activeTerminals` counter that backs terminal-entry
 * isolation for the edge-load topology. Entity terminals (`all` /
 * `firstOrNull`) acquire it via [buildAcquireEdgeTopology] on
 * entry and release in a `finally`; `load{Name}` and `filterVisible()`
 * reject while it is positive, so an interceptor or privacy rule that
 * captured any query in the selected graph cannot change the topology
 * or privacy posture the in-flight operation is materializing. A
 * counter rather than a flag so a re-entrant terminal started from an
 * interceptor cannot clear the outer terminal's guard early. A
 * captured target query's *contents* need no guard: each eager step
 * snapshots them when depth-first execution reaches it — the spec
 * builder copies predicates and ordering at interceptor-chain entry,
 * and the step's window reads the frozen spec's bounds — so
 * predicates, ordering, or bounds mutated mid-flight
 * affect later executions only. Emitted only when the query has a
 * load-capable edge.
 */
internal fun buildActiveTerminalsProperty(): PropertySpec {
    return PropertySpec.builder("activeTerminals", Int::class)
        .addModifiers(KModifier.PRIVATE)
        .mutable(true)
        .initializer("0")
        .build()
}

/**
 * Build the `acquireEdgeTopology` / `releaseEdgeTopology` pair that
 * terminal-entry isolation holds across the *entire selected graph*:
 * acquiring increments this query's guard and recurses into every
 * selected target query, so a retained nested query rejects
 * `load{Name}` / `filterVisible()` mid-flight exactly as the root
 * does. The selected topology is always a tree of
 * framework-constructed query instances (each `load{Name}` call
 * builds its own fresh target), so the recursion cannot cycle — and
 * because every node is guarded while acquired, the topology cannot
 * change between acquire and release, which therefore walk identical
 * nodes.
 *
 * Always emitted, mirroring `loadEdges`: a parent query's acquire
 * recursion calls these on the target's query class unconditionally,
 * so a target schema with no load-capable edges of its own still
 * needs the (no-op) methods to satisfy those call sites.
 */
internal fun buildAcquireEdgeTopology(resolved: ResolvedQuerySchema): FunSpec {
    return buildTopologyGuardWalk("acquireEdgeTopology", "activeTerminals++", resolved)
}

internal fun buildReleaseEdgeTopology(resolved: ResolvedQuerySchema): FunSpec {
    return buildTopologyGuardWalk("releaseEdgeTopology", "activeTerminals--", resolved)
}

private fun buildTopologyGuardWalk(
    name: String,
    counterStatement: String,
    resolved: ResolvedQuerySchema,
): FunSpec {
    val builder = FunSpec.builder(name)
        .addAnnotation(ClassName("entkt.query", "EntktInternal"))
        .addModifiers(KModifier.INTERNAL)
    val eager = resolved.edges.filter { it.join != null }
    if (eager.isNotEmpty()) {
        builder.addStatement(counterStatement)
        for (re in eager) {
            builder.addStatement("%L?.%L()", re.eagerPropName, name)
        }
    }
    return builder.build()
}

/**
 * Build the private `requireNoSelectedEdges(operation, reason)` guard
 * shared by every generated surface that cannot carry a selected
 * edge-load graph: the raw count / existence / aggregate terminals
 * (whose canonical bodies capture the throw as `ReadResult.Failed`
 * before any interceptor or driver work), and `query{Name}` traversal
 * (which throws at configuration time). A selected edge is
 * a non-null `eager{Stem}` backing property; the diagnostic names the
 * rejected operation and every selected declaration-derived edge
 * path. Emitted only when the query has at least one load-capable
 * edge.
 */
internal fun buildRequireNoSelectedEdges(resolved: ResolvedQuerySchema): FunSpec {
    val body = CodeBlock.builder()
    body.add("val selected = listOfNotNull(\n")
    for (re in resolved.edges) {
        if (re.join == null) continue
        body.add(
            "  if (%L != null) %S else null,\n",
            re.eagerPropName,
            "${resolved.schemaName}.${re.publicName}",
        )
    }
    body.add(")\n")
    body.addStatement("if (selected.isEmpty()) return")
    body.add(
        "throw %T(\n  %S,\n  operation + %S + selected.joinToString(%S) + %S + reason,\n)\n",
        ENT_QUERY_CONFIGURATION_EXCEPTION,
        resolved.schemaName,
        " on ${resolved.schemaName}Query is incompatible with the selected edge loads [",
        ", ",
        "]: ",
    )
    return FunSpec.builder("requireNoSelectedEdges")
        .addModifiers(KModifier.PRIVATE)
        .addParameter("operation", String::class)
        .addParameter("reason", String::class)
        .addCode(body.build())
        .build()
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
    // Only the driver fetches inside the per-edge blocks are data-gated.
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
 *
 * The fetch goes through the runtime-owned `executeDirectToMany`,
 * which selects the driver's native per-parent window lowering when
 * `directToManyWindowCapability()` is NATIVE and otherwise issues the
 * phase-1 emulated fetch (the complete frozen predicate list through
 * `Driver.query`, window applied in Kotlin below). The runtime helper
 * also owns the phase-1 data gates: an empty parent set or a
 * `limit(0)` window performs no driver read, while the interceptor
 * pass above it always runs.
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
    emitEagerSubquerySetup(body, re.publicName, sourceClass, targetClass)
    // Capability is sampled ONCE per eager step, before the
    // interceptor chain, because the running bind budget depends on
    // it: a NATIVE driver transports the structural relationship IN
    // as one typed-array bind, so the budget must not charge one
    // scalar bind per parent key. The same sample routes the fetch
    // below, so budgeting and routing cannot disagree even against a
    // driver whose capability answer changes between calls.
    body.addStatement(
        "val toManyWindowCapability = driver.directToManyWindowCapability()",
    )
    body.addStatement(
        "val nativeToManyWindows = toManyWindowCapability == %T.NATIVE",
        DIRECT_TO_MANY_WINDOW_CAPABILITY,
    )
    // Run target interceptors with EAGER_LOAD. The IN
    // predicate that ties target rows back to the source ids
    // goes in via extraStructural so it's tagged STRUCTURAL,
    // not CALLER — interceptors always see the complete logical
    // relationship constraint, whichever lowering executes it.
    // appendPrimaryKeyOrder installs the effective-order
    // primary-key tie-breaker before the chain runs.
    body.addStatement(
        "val subSpec = subQuery.runReadInterceptors(%T.EAGER_LOAD, eagerPrivacyContext, listOf(%T.Leaf<%T>(%S, %T.IN, sourceIds)), appendPrimaryKeyOrder = true, structuralSingleBindTransport = nativeToManyWindows)",
        READ_OPERATION, PREDICATE, targetClass, join.targetColumn, OP,
    )
    // Bounds come from the frozen spec, not live sub-query state: an
    // interceptor that captured the nested query and mutates its
    // limit()/offset() mid-flight cannot shift the window this step
    // executes (the mutation applies to later executions only).
    body.addStatement("val perGroupOffset = subSpec.offset ?: 0")
    body.addStatement("val perGroupLimit = subSpec.limit ?: %T.MAX_VALUE", Int::class.asClassName())
    // The frozen spec's predicates split at the driver boundary: the
    // native path hands the driver the non-structural remainder and
    // lets it lower the separately-attributed relationship constraint
    // through the typed parent array, while the emulated path
    // receives the complete ordered list and stays byte-identical
    // with phase 1.
    body.add("val related = %M(\n", EXECUTE_DIRECT_TO_MANY)
    body.add("  driver,\n")
    body.add("  %T(\n", DIRECT_TO_MANY_QUERY)
    body.add("    targetTable = %T.TABLE,\n", targetClass)
    body.add("    sourceKeys = sourceIds,\n")
    body.add("    targetForeignKey = %S,\n", join.targetColumn)
    body.add("    targetPredicates = subSpec.nonStructuralPredicates,\n")
    body.add("    effectiveOrder = subSpec.orderBy,\n")
    body.add("    window = %T(offset = perGroupOffset, limit = subSpec.limit),\n", PER_PARENT_WINDOW)
    body.add("  ),\n")
    body.add("  emulationPredicates = subSpec.predicates,\n")
    body.add("  capability = toManyWindowCapability,\n")
    body.add(")\n")
    // Decode once, in the returned global effective order, so the
    // strict privacy pass and the set-based nested pass both see
    // targets in that order (the grouped map iterates in
    // first-occurrence order, which is NOT result order when targets
    // belong to different parents). The association key rides the
    // envelope (`sourceKey` — the decoded FK value), never a
    // re-parsed entity field.
    body.addStatement(
        "val decodedTargets = related.rows.map { it.sourceKey to %T.fromRow(it.targetRow) }",
        targetClass,
    )
    body.addStatement(
        "val grouped = decodedTargets.groupBy { (sourceKey, _) -> sourceKey }",
    )
    // Under STORAGE_NATIVE every returned row is already inside its
    // parent's window — re-applying drop/take here would discard rows
    // the storage window selected. The emulated strategy returns the
    // complete match set and keeps the phase-1 Kotlin window.
    body.addStatement(
        "val windowInStorage = related.strategy == %T.STORAGE_NATIVE",
        EAGER_WINDOW_STRATEGY,
    )
    body.add("var loadedGroups = if (windowInStorage) {\n")
    body.add("  grouped.mapValues { (_, pairs) -> pairs.map { it.second } }\n")
    body.add("} else {\n")
    body.add("  grouped.mapValues { (_, pairs) -> pairs.drop(perGroupOffset).take(perGroupLimit).map { it.second } }\n")
    body.add("}\n")
    emitEagerPrivacyCheck(
        body, re.targetClientName, "loadedGroups", grouped = true, eagerPropName = re.eagerPropName,
        orderedTargets = "decodedTargets.map { it.second }",
    )
    emitSetBasedNestedPass(body, orderedTargetsExpr = "decodedTargets.map { it.second }")
    body.addStatement(
        "entities = entities.map { entity -> entity.copy(edges = entity.edges.copy(%L = %T.Loaded(loadedGroups[entity.id] ?: emptyList()))) }",
        re.edgePropName, EDGE_STATE,
    )
    emitEagerStepScopeEnd(body)
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
    emitEagerSubquerySetup(body, re.publicName, sourceClass, targetClass)
    body.addStatement(
        "val subSpec = subQuery.runReadInterceptors(%T.EAGER_LOAD, eagerPrivacyContext, listOf(%T.Leaf<%T>(%S, %T.IN, sourceIds)), appendPrimaryKeyOrder = true)",
        READ_OPERATION, PREDICATE, targetClass, join.targetColumn, OP,
    )
    // Bounds are resolved before the fetch so a window that admits
    // nothing skips the round trip; every row would be dropped by the
    // `drop().take()` below. The interceptor pass above still runs —
    // it fires on every eager subquery regardless of bounds. Bounds
    // come from the frozen spec, matching the sibling paths, so a
    // captured sub-query mutated mid-flight cannot shift the window.
    //
    // A positive offset also empties the window here, which it does
    // not on the to-many path: a `hasOne` edge requires its inverse
    // `belongsTo` to declare `.unique()` (enforced by codegen and by
    // the schema DSL), so the unique index guarantees at most one row
    // per source and `drop(1)` leaves nothing.
    body.addStatement("val perGroupOffset = subSpec.offset ?: 0")
    body.addStatement("val perGroupLimit = subSpec.limit ?: %T.MAX_VALUE", Int::class.asClassName())
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
        body, re.targetClientName, "loadedGroups", grouped = true, eagerPropName = re.eagerPropName,
        orderedTargets = "decodedTargets.map { it.second }",
    )
    emitSetBasedNestedPass(body, orderedTargetsExpr = "decodedTargets.map { it.second }")
    body.addStatement(
        "entities = entities.map { entity -> entity.copy(edges = entity.edges.copy(%L = %T.Loaded(loadedGroups[entity.id]?.firstOrNull()))) }",
        re.edgePropName, EDGE_STATE,
    )
    emitEagerStepScopeEnd(body)
    body.endControlFlow()
}

/**
 * Emit the set-based nested pass for the grouped eager paths
 * (hasMany / hasOne / M2M). One logical edge step owns exactly one
 * recursive nested pass: the retained (windowed, privacy-filtered)
 * targets are flattened in effective target order via
 * [orderedTargetsExpr], deduplicated by target ID at first
 * occurrence, and nested-loaded ONCE — never once per populated
 * parent group. The returned copies are the canonical targets for
 * this step; every group is rebuilt from them by ID so no
 * association retains a stale pre-recursion instance, and a target
 * shared by several parents (M2M) is nested-loaded once while
 * remaining attached to every source.
 *
 * The pass is unconditional, including when the union is empty:
 * whether a nested EAGER_LOAD interceptor fires (and can
 * `reject()`) must not depend on what level-1 data came back —
 * matching the belongsTo path, whose single unconditional
 * `loadEdges` call has these semantics already.
 */
private fun emitSetBasedNestedPass(body: CodeBlock.Builder, orderedTargetsExpr: String) {
    // `·` marks non-breaking spaces: KotlinPoet must not wrap between
    // a call and its trailing lambda — `associateBy\n{ it.id }` parses
    // as two statements.
    body.addStatement(
        "val retainedTargetIds = loadedGroups.values.flatten().mapTo(mutableSetOf())·{ it.id }",
    )
    body.addStatement(
        "val selectedTargets = $orderedTargetsExpr.filter·{ it.id in retainedTargetIds }.distinctBy·{ it.id }",
    )
    body.addStatement(
        "val nestedLoadedById = subQuery.loadEdges(selectedTargets, eagerPrivacyContext).associateBy·{ it.id }",
    )
    body.addStatement(
        "loadedGroups = loadedGroups.mapValues·{ (_, list) -> list.map·{ nestedLoadedById.getValue(it.id) } }",
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
    // Every FK surface on this schema is resolved up front, and its
    // property name is declaration-derived — there is no column-derived
    // fallback to fall back to.
    val fkPropName = fk?.propertyName
        ?: error(
            "Eager join references source column '${join.sourceColumn}', which resolves to no " +
                "FK surface on '${resolved.schemaName}'.",
        )
    // A required FK is a non-null property, so the safe-call below would be
    // redundant (and Kotlin warns). Unknown → treat as nullable (safe).
    val fkRequired = fk?.required ?: false

    body.beginControlFlow("%L?.let { subQuery ->", re.eagerPropName)
    body.addStatement("val fkValues = entities.mapNotNull { it.%L }.distinct()", fkPropName)
    emitEagerSubquerySetup(body, re.publicName, sourceClass, targetClass)
    // Fetch every matching target in one `IN (...)` pass. The
    // caller's bounds are per parent, not over this batched result,
    // so they're applied below rather than passed to the driver —
    // a `limit` here would cap the total across all parents.
    //
    // Unconditional, including when every parent's FK is null: the
    // interceptor pass fires on every eager subquery, so whether an
    // interceptor runs — and whether it can `reject()` — must not
    // depend on the relationship data that happens to be present.
    // An empty `fkValues` just means the structural IN predicate is empty.
    body.addStatement(
        "val subSpec = subQuery.runReadInterceptors(%T.EAGER_LOAD, eagerPrivacyContext, listOf(%T.Leaf<%T>(%S, %T.IN, fkValues)), appendPrimaryKeyOrder = true)",
        READ_OPERATION, PREDICATE, targetClass, join.targetColumn, OP,
    )
    // Per-parent bounds, same contract as the to-many edges. A
    // belongsTo yields at most one target per parent, so the window
    // collapses to "is index 0 inside it?" — false for `limit(0)`
    // ("no rows", as everywhere else) and for any positive offset.
    // Bounds come from the frozen spec, matching the sibling paths,
    // so a captured sub-query mutated mid-flight cannot shift the
    // window.
    //
    // Applied as a row slice below — before the privacy check, which
    // is what the to-many and hasOne paths do — so an excluded target
    // is never privacy-evaluated or nested-eager-loaded. A denial for
    // a row the caller explicitly asked not to load would otherwise
    // throw out of `loadAuthor { limit(0) }`.
    //
    // Deliberately not short-circuiting the whole branch: the
    // EAGER_LOAD interceptor pass above fires on every eager subquery
    // regardless of bounds, which is what the sibling paths do.
    body.addStatement("val perParentOffset = subSpec.offset ?: 0")
    body.addStatement("val perParentLimit = subSpec.limit ?: %T.MAX_VALUE", Int::class.asClassName())
    body.addStatement("val targetInWindow = perParentOffset == 0 && perParentLimit > 0")
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
    emitEagerPrivacyCheck(body, re.targetClientName, "loaded", grouped = false, eagerPropName = re.eagerPropName)
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
    emitEagerStepScopeEnd(body)
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
    val junctionEntityClass = checkNotNull(re.junctionEntityClass) {
        "M2M eager block for '${re.publicName}' requires a codegen-visible junction"
    }
    val junctionQueryClass = checkNotNull(re.junctionQueryClass) {
        "M2M eager block for '${re.publicName}' requires a codegen-visible junction"
    }
    body.beginControlFlow("%L?.let { subQuery ->", re.eagerPropName)
    body.addStatement("val sourceIds = entities.map { it.id }")
    emitEagerSubquerySetup(body, re.publicName, sourceClass, targetClass)
    // Junction discovery runs the JUNCTION entity's read interceptors
    // with EAGER_JUNCTION before its driver read, so predicates
    // registered on the junction (tenant scoping, ExcludeDeleted)
    // narrow relationship discovery exactly as they narrow direct
    // junction reads. The pass is unconditional — whether an
    // interceptor runs, and whether it can `reject()`, must not
    // depend on the parent data — while the driver read stays gated
    // on a non-empty parent set (an empty IN could match nothing).
    // It is issued for any non-empty parent set even under
    // `limit(0)`: its rows produce the `targetIds` the EAGER_LOAD
    // interceptor pass predicates on. Junction LOAD privacy
    // deliberately does not run here — see ReadOperation.EAGER_JUNCTION.
    body.addStatement("val junctionQuery = %T(driver, client)", junctionQueryClass)
    body.addStatement(
        "junctionQuery.seedEdgeTraversal(%T::class, %S, eagerPath)",
        sourceClass, re.publicName,
    )
    body.addStatement(
        "val junctionSpec = junctionQuery.runReadInterceptors(%T.EAGER_JUNCTION, eagerPrivacyContext, listOf(%T.Leaf<%T>(%S, %T.IN, sourceIds)))",
        READ_OPERATION, PREDICATE, junctionEntityClass, join.junctionSourceColumn, OP,
    )
    body.addStatement(
        "val junctionRows = if (sourceIds.isNotEmpty()) driver.query(%S, junctionSpec.predicates, junctionSpec.orderBy, null, null) else emptyList()",
        join.junctionTable,
    )
    body.addStatement(
        "val targetIds = junctionRows.map { it[%S] }.distinct()",
        join.junctionTargetColumn,
    )
    // Fetch all matching targets — limit/offset are applied per group below.
    //
    // The interceptor pass is unconditional, including when the
    // junction has no rows: whether an interceptor runs — and whether
    // it can `reject()` — must not depend on the relationship data
    // that happens to be present. No junction rows just means the
    // structural IN predicate is empty.
    body.addStatement(
        "val subSpec = subQuery.runReadInterceptors(%T.EAGER_LOAD, eagerPrivacyContext, listOf(%T.Leaf<%T>(%S, %T.IN, targetIds)), appendPrimaryKeyOrder = true)",
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
    // regardless of bounds. Bounds come from the frozen spec,
    // matching the direct-edge paths, so a captured sub-query
    // mutated mid-flight cannot shift the window.
    body.addStatement("val perGroupOffset = subSpec.offset ?: 0")
    body.addStatement("val perGroupLimit = subSpec.limit ?: %T.MAX_VALUE", Int::class.asClassName())
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
    // the wrong subset for `loadTags { orderBy(...); limit(...) }`.
    //
    // The membership lookup uses a `MutableSet` per target id, not
    // a `MutableList`, so a junction with duplicate
    // `(source_id, target_id)` pairs (legal for `throughEntity`
    // junctions with no unique pair index — the row carries
    // distinct payload) collapses to one membership entry. Without
    // the dedup, `loadTags()` would return the same target multiple
    // times in one source's group, while the EXISTS-based
    // `queryTags()` traversal correctly returns each target once,
    // and per-group `drop`/`take` would slice from a duplicated
    // list. `mutableSetOf()` returns a `LinkedHashSet` so insertion
    // order is preserved (not that the source iteration order
    // matters for the per-group ordering — that's driven by the
    // target-row iteration).
    body.addStatement(
        "val sourcesByTargetId = mutableMapOf<%T?, %T<%T?>>()",
        Any::class.asClassName(),
        ClassName("kotlin.collections", "MutableSet"),
        Any::class.asClassName(),
    )
    body.beginControlFlow("for (jr in junctionRows)")
    body.addStatement(
        "sourcesByTargetId.getOrPut(jr[%S]) { mutableSetOf() }.add(jr[%S])",
        join.junctionTargetColumn,
        join.junctionSourceColumn,
    )
    body.endControlFlow()
    body.addStatement(
        "val grouped = mutableMapOf<%T?, %T<%T>>()",
        Any::class.asClassName(),
        ClassName("kotlin.collections", "MutableList"),
        targetClass,
    )
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
    // Paginate, then privacy, then the set-based nested pass —
    // denied targets never trigger nested reads, and a target shared
    // by several sources is nested-loaded once for the step.
    body.addStatement(
        "var loadedGroups = grouped.mapValues { (_, list) -> list.drop(perGroupOffset).take(perGroupLimit) }",
    )
    emitEagerPrivacyCheck(
        body, re.targetClientName, "loadedGroups", grouped = true, eagerPropName = re.eagerPropName,
        orderedTargets = "orderedTargets",
    )
    emitSetBasedNestedPass(body, orderedTargetsExpr = "orderedTargets")
    body.addStatement(
        "entities = entities.map { entity -> entity.copy(edges = entity.edges.copy(%L = %T.Loaded(loadedGroups[entity.id] ?: emptyList()))) }",
        re.edgePropName, EDGE_STATE,
    )
    // No `else` arm: with no junction rows the grouping is empty, so
    // the `?: emptyList()` above already covers every parent.
    emitEagerStepScopeEnd(body)
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
    // The seed is scoped to this eager step: the matching `finally`
    // (emitted by [emitEagerStepScopeEnd]) restores the child's
    // default root state, so a captured sub-query executed
    // independently later carries no stale parent attribution.
    body.beginControlFlow("try")
}

/**
 * Close the eager-step scope opened at the end of
 * [emitEagerSubquerySetup]: restore the child query's default (root)
 * traversal and denial-path state. Runs in a `finally` so denials,
 * rejections, and driver failures restore too.
 */
private fun emitEagerStepScopeEnd(body: CodeBlock.Builder) {
    body.nextControlFlow("finally")
    body.addStatement("subQuery.seedEdgeTraversal(null, null, emptyList())")
    body.addStatement("subQuery.seedEagerDenialBasePath(emptyList())")
    body.endControlFlow()
}

/**
 * Emit the eager LOAD privacy block for one edge. [loadedVar] is the
 * name of the mutable local holding the loaded entities (or grouped
 * map; when [grouped] is true the variable is a `Map<Any?, List<T>>`).
 *
 * Every in-window target is evaluated through one positional batch in target-query
 * order (deduplicated by target ID). Strict default: the first denied target throws
 * `EntPrivacyDeniedException(EagerEdge(eagerPath), listOf(denial))` —
 * exactly one keyed denial; no later nested or sibling eager work runs solely for
 * diagnostics. The root terminal's capture boundary stores it in
 * `ReadResult.Failed`. A scalar rule's batch adapter still visits every target that
 * reaches that registered rule before the strict projection selects the first denial.
 *
 * With the edge's `filterVisible()` opt-in ([eagerPropName]'s flag),
 * a returned LOAD-deny decision instead omits the target by ID: a denied
 * to-one target becomes `EdgeState.Loaded(null)` (the filtered map
 * lookup misses), denied to-many targets are omitted from the loaded
 * list, and no replacement scanning occurs. Only a returned deny
 * decision is suppressed — an exception thrown by a privacy rule
 * escapes either way and remains a terminal failure.
 */
private fun emitEagerPrivacyCheck(
    body: CodeBlock.Builder,
    targetRepoProp: String,
    loadedVar: String,
    grouped: Boolean,
    eagerPropName: String,
    orderedTargets: String? = null,
) {
    body.addStatement("val eagerClient = client")
    body.beginControlFlow("if (eagerClient != null && eagerClient.%L.hasLoadPrivacy())", targetRepoProp)
    if (grouped) {
        body.addStatement(
            "val inWindowTargetIds = %L.values.flatten().mapTo(mutableSetOf()) { it.id }",
            loadedVar,
        )
        body.addStatement(
            "val privacyTargets = %L.filter { it.id in inWindowTargetIds }.distinctBy { it.id }",
            checkNotNull(orderedTargets) { "grouped privacy pass needs orderedTargets" },
        )
    } else {
        body.addStatement("val privacyTargets = %L.distinctBy { it.id }", loadedVar)
    }
    body.addStatement(
        "val privacyDenials = eagerClient.%L.loadDenials(eagerPrivacyContext, privacyTargets)",
        targetRepoProp,
    )
    body.beginControlFlow("if (%LFilterVisible)", eagerPropName)
    body.addStatement(
        "val visibleTargetIds = privacyTargets.zip(privacyDenials)\n" +
            "  .filter { (_, denial) -> denial == null }\n" +
            "  .mapTo(mutableSetOf()) { (entity, _) -> entity.id }",
    )
    if (grouped) {
        body.addStatement(
            "%L = %L.mapValues { (_, list) -> list.filter { it.id in visibleTargetIds } }",
            loadedVar, loadedVar,
        )
    } else {
        body.addStatement(
            "%L = %L.filter { it.id in visibleTargetIds }",
            loadedVar, loadedVar,
        )
    }
    body.nextControlFlow("else")
    body.addStatement("val denial = privacyDenials.firstOrNull { it != null }")
    body.beginControlFlow("if (denial != null)")
    body.addStatement(
        "throw %T(%T.EagerEdge(eagerDenialPath.map { %T(it.source.simpleName!!, it.edgeName, it.target.simpleName!!) }), listOf(denial))",
        ENT_PRIVACY_DENIED, LOAD_DENIAL_ORIGIN, EAGER_EDGE_STEP,
    )
    body.endControlFlow()
    body.endControlFlow()
    body.endControlFlow()
}
