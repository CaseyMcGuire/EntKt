package entkt.schema

import kotlin.reflect.KClass
import kotlin.reflect.KProperty1

/**
 * Call [prop].get([target]) while guarding against computed-getter
 * properties that register new declarations as a side effect.
 *
 * Two failure modes exist depending on finalization order:
 * - If [target] is already finalized, the getter's attempt to register
 *   a declaration throws [IllegalStateException] from `checkNotFinalized()`.
 *   We catch that and re-throw with a clear "computed getter" message.
 * - If [target] is not yet finalized, the getter silently registers a
 *   new declaration. We detect this via [EntSchema.declarationCount].
 */
private fun <T : EntSchema> resolvePropertySafely(
    prop: KProperty1<T, *>,
    target: T,
    edgeName: String,
    context: String,
): Any? {
    val countBefore = target.declarationCount
    val result = try {
        prop.get(target)
    } catch (e: IllegalStateException) {
        error(
            "Edge '$edgeName': $context reference '${prop.name}' on " +
                "${target::class.simpleName} is a computed getter that created new " +
                "declarations — use a field-backed val instead",
        )
    }
    if (target.declarationCount != countBefore) {
        error(
            "Edge '$edgeName': $context reference '${prop.name}' on " +
                "${target::class.simpleName} is a computed getter that created new " +
                "declarations — use a field-backed val instead",
        )
    }
    return result
}

abstract class EdgeBuilderBase {
    abstract val edgeName: String
    internal var frozen: Boolean = false
    internal var declarationOwner: EntSchema? = null

    protected fun checkNotFrozen() {
        check(!frozen) { "Edge '$edgeName' cannot be modified after schema finalization" }
    }

    abstract fun resolve(registry: Map<KClass<out EntSchema>, EntSchema>, owner: KClass<out EntSchema>)
    abstract fun build(): Edge
}

/**
 * An [IndexableColumn] for a synthesized or explicit FK column on a
 * `belongsTo` edge. Carries the declaring schema so [EntSchema.index]
 * can verify same-schema ownership at declaration time.
 */
internal class FkColumn(
    private val edgeBuilder: BelongsToBuilder<*>,
) : IndexableColumn {
    internal val declarationOwner: EntSchema? get() = edgeBuilder.declarationOwner
    override val fieldName: String get() = edgeBuilder.explicitFieldHandle?.fieldName ?: "${edgeBuilder.edgeName}_id"
}

class BelongsToBuilder<Target : EntSchema> internal constructor(
    override val edgeName: String,
    @PublishedApi internal val targetClass: KClass<Target>,
) : BelongsToHandle<Target>, EdgeBuilderBase() {

    /**
     * An [IndexableColumn] for the FK column backing this edge.
     * Use this to reference the FK in `index(...)` declarations:
     *
     * ```kotlin
     * val author = belongsTo<User>("author")
     * val byAuthor = index(author.fk)
     * ```
     */
    val fk: IndexableColumn = FkColumn(this)

    private var required: Boolean = true
    private var unique: Boolean = false
    @PublishedApi internal var explicitFieldHandle: FieldHandle<*>? = null
    private var onDelete: OnDelete? = null
    private var inverseRef: KProperty1<Target, *>? = null
    private var comment: String? = null

    // Resolved during finalize
    private var resolvedTarget: EntSchema? = null
    private var resolvedRef: String? = null

    fun nullable(): BelongsToBuilder<Target> = apply { checkNotFrozen(); required = false }
    fun unique(): BelongsToBuilder<Target> = apply { checkNotFrozen(); unique = true }
    fun field(handle: FieldHandle<*>): BelongsToBuilder<Target> = apply {
        checkNotFrozen()
        val fieldOwner = (handle as? FieldBuilder<*, *>)?.declarationOwner
        if (fieldOwner != null && declarationOwner != null && fieldOwner !== declarationOwner) {
            error(
                "belongsTo('$edgeName').field() references '${handle.fieldName}' which belongs to schema " +
                    "'${fieldOwner::class.simpleName}', not '${declarationOwner!!::class.simpleName}'"
            )
        }
        explicitFieldHandle = handle
    }
    fun onDelete(action: OnDelete): BelongsToBuilder<Target> = apply { checkNotFrozen(); onDelete = action }
    fun comment(text: String): BelongsToBuilder<Target> = apply { checkNotFrozen(); comment = text }

    fun inverse(prop: KProperty1<Target, HasManyHandle<*>>): BelongsToBuilder<Target> = apply {
        checkNotFrozen(); inverseRef = prop
    }

    @JvmName("inverseHasOne")
    fun inverse(prop: KProperty1<Target, HasOneHandle<*>>): BelongsToBuilder<Target> = apply {
        checkNotFrozen(); inverseRef = prop
    }

    override fun resolve(registry: Map<KClass<out EntSchema>, EntSchema>, owner: KClass<out EntSchema>) {
        resolvedTarget = registry[targetClass]
            ?: error("Edge '$edgeName': target schema ${targetClass.simpleName} not found in registry")

        inverseRef?.let { prop ->
            @Suppress("UNCHECKED_CAST")
            val targetInstance = resolvedTarget as Target
            val handle = resolvePropertySafely(prop, targetInstance, edgeName, "inverse()")
            val inverseBuilder = handle as? EdgeBuilderBase
                ?: error("Edge '$edgeName': inverse() reference does not resolve to an edge declaration")

            // Validate that the inverse edge targets back to the owning schema
            val inverseTargetClass = when (inverseBuilder) {
                is HasManyBuilder<*> -> inverseBuilder.targetClass
                is HasOneBuilder<*> -> inverseBuilder.targetClass
                else -> error(
                    "Edge '$edgeName': inverse() must reference a hasMany or hasOne edge, " +
                        "got ${inverseBuilder::class.simpleName}"
                )
            }
            if (inverseTargetClass != owner) {
                error(
                    "Edge '$edgeName': inverse edge '${inverseBuilder.edgeName}' targets " +
                        "${(inverseTargetClass as KClass<*>).simpleName}, not the owning schema " +
                        "${owner.simpleName}"
                )
            }

            // Validate cardinality agreement between belongsTo and its inverse
            if (inverseBuilder is HasOneBuilder<*> && !unique) {
                error(
                    "Edge '$edgeName': inverse edge '${inverseBuilder.edgeName}' is hasOne, " +
                        "so this belongsTo must have .unique()"
                )
            }
            if (inverseBuilder is HasManyBuilder<*> && unique) {
                error(
                    "Edge '$edgeName': belongsTo has .unique() but inverse edge " +
                        "'${inverseBuilder.edgeName}' is hasMany — use hasOne on the inverse " +
                        "or remove .unique() from the belongsTo"
                )
            }

            resolvedRef = inverseBuilder.edgeName
        }
    }

    override fun build(): Edge {
        val target = resolvedTarget
            ?: error("Edge '$edgeName' has not been finalized — call schema.finalize() first")
        if (onDelete == OnDelete.SET_NULL && required) {
            error(
                "onDelete SET_NULL is incompatible with required edges — " +
                    "edge '$edgeName' cannot be both required (NOT NULL) and SET_NULL on delete",
            )
        }
        return Edge(
            name = edgeName,
            target = target,
            kind = EdgeKind.BelongsTo(
                required = required,
                unique = unique,
                field = explicitFieldHandle?.fieldName,
                onDelete = onDelete,
            ),
            ref = resolvedRef,
            comment = comment,
        )
    }
}

class HasManyBuilder<Target : EntSchema> internal constructor(
    override val edgeName: String,
    @PublishedApi internal val targetClass: KClass<Target>,
) : HasManyHandle<Target>, EdgeBuilderBase() {
    private var comment: String? = null
    private var resolvedTarget: EntSchema? = null

    fun comment(text: String): HasManyBuilder<Target> = apply { checkNotFrozen(); comment = text }

    override fun resolve(registry: Map<KClass<out EntSchema>, EntSchema>, owner: KClass<out EntSchema>) {
        resolvedTarget = registry[targetClass]
            ?: error("Edge '$edgeName': target schema ${targetClass.simpleName} not found in registry")
    }

    override fun build(): Edge {
        val target = resolvedTarget
            ?: error("Edge '$edgeName' has not been finalized — call schema.finalize() first")
        return Edge(
            name = edgeName,
            target = target,
            kind = EdgeKind.HasMany,
            comment = comment,
        )
    }
}

class HasOneBuilder<Target : EntSchema> internal constructor(
    override val edgeName: String,
    @PublishedApi internal val targetClass: KClass<Target>,
) : HasOneHandle<Target>, EdgeBuilderBase() {
    private var comment: String? = null
    private var resolvedTarget: EntSchema? = null

    fun comment(text: String): HasOneBuilder<Target> = apply { checkNotFrozen(); comment = text }

    override fun resolve(registry: Map<KClass<out EntSchema>, EntSchema>, owner: KClass<out EntSchema>) {
        resolvedTarget = registry[targetClass]
            ?: error("Edge '$edgeName': target schema ${targetClass.simpleName} not found in registry")
    }

    override fun build(): Edge {
        val target = resolvedTarget
            ?: error("Edge '$edgeName' has not been finalized — call schema.finalize() first")
        return Edge(
            name = edgeName,
            target = target,
            kind = EdgeKind.HasOne,
            comment = comment,
        )
    }
}

/**
 * Write model for an M2M relationship. Picked by the schema author at
 * declaration time via `throughLink(...)` (pure relationship storage,
 * direct edge helpers) or `throughEntity(...)` (junction is a domain
 * entity, mutated through its repo). The generic `.through(...)`
 * marker has been removed — schemas must pick one explicitly.
 *
 * Marked `@PublishedApi internal` so the public inline `throughLink` /
 * `throughEntity` builders can reference the enum entries while
 * keeping the enum out of the published API surface.
 */
@PublishedApi
internal enum class ManyToManyMode { LINK, ENTITY }

class ManyToManyBuilder<Target : EntSchema> internal constructor(
    override val edgeName: String,
    @PublishedApi internal val targetClass: KClass<Target>,
) : ManyToManyHandle<Target>, EdgeBuilderBase() {
    private var junctionClass: KClass<out EntSchema>? = null
    private var junctionSourceProp: KProperty1<out EntSchema, *>? = null
    private var junctionTargetProp: KProperty1<out EntSchema, *>? = null
    private var mode: ManyToManyMode? = null
    private var comment: String? = null

    // Resolved during finalize
    private var resolvedTarget: EntSchema? = null
    private var resolvedThrough: ManyToManyThrough? = null

    /**
     * Declare the relationship as a pure link table. The junction
     * schema is relationship storage; direct M2M helpers (per the
     * Link-Table Helpers RFC) are eligible for generation when the
     * junction satisfies the helper-eligibility constraints.
     */
    inline fun <reified Junction : EntSchema> throughLink(
        sourceEdge: KProperty1<Junction, BelongsToHandle<*>>,
        targetEdge: KProperty1<Junction, BelongsToHandle<*>>,
    ): ManyToManyBuilder<Target> = throughInternal(Junction::class, sourceEdge, targetEdge, ManyToManyMode.LINK)

    /**
     * Declare the relationship as a through-entity. The junction is a
     * domain entity; callers mutate it through its generated repo so
     * payload fields, hooks, privacy, and validation apply on the
     * normal builder paths.
     */
    inline fun <reified Junction : EntSchema> throughEntity(
        sourceEdge: KProperty1<Junction, BelongsToHandle<*>>,
        targetEdge: KProperty1<Junction, BelongsToHandle<*>>,
    ): ManyToManyBuilder<Target> = throughInternal(Junction::class, sourceEdge, targetEdge, ManyToManyMode.ENTITY)

    @PublishedApi
    internal fun <Junction : EntSchema> throughInternal(
        junctionClass: KClass<Junction>,
        sourceEdge: KProperty1<Junction, *>,
        targetEdge: KProperty1<Junction, *>,
        mode: ManyToManyMode,
    ): ManyToManyBuilder<Target> = apply {
        checkNotFrozen()
        check(this.mode == null) {
            "manyToMany edge '$edgeName' has both throughLink() and throughEntity() — pick one"
        }
        this.junctionClass = junctionClass
        this.junctionSourceProp = sourceEdge
        this.junctionTargetProp = targetEdge
        this.mode = mode
    }

    fun comment(text: String): ManyToManyBuilder<Target> = apply { checkNotFrozen(); comment = text }

    override fun resolve(registry: Map<KClass<out EntSchema>, EntSchema>, owner: KClass<out EntSchema>) {
        resolvedTarget = registry[targetClass]
            ?: error("Edge '$edgeName': target schema ${targetClass.simpleName} not found in registry")

        val jc = junctionClass
            ?: error(
                "manyToMany edge '$edgeName' must declare a write model — call either " +
                    "throughLink<Junction>(sourceEdge, targetEdge) (pure relationship storage) or " +
                    "throughEntity<Junction>(sourceEdge, targetEdge) (junction is a domain entity)",
            )
        val junctionInstance = registry[jc]
            ?: error("Edge '$edgeName': junction schema ${jc.simpleName} not found in registry")
        val resolvedMode = mode
            ?: error("manyToMany edge '$edgeName' is missing a write model — internal state error")

        @Suppress("UNCHECKED_CAST")
        val srcProp = junctionSourceProp!! as KProperty1<EntSchema, *>
        @Suppress("UNCHECKED_CAST")
        val tgtProp = junctionTargetProp!! as KProperty1<EntSchema, *>

        if (srcProp == tgtProp) {
            error(
                "manyToMany edge '$edgeName': sourceEdge and targetEdge are the same junction " +
                    "property '${srcProp.name}' on ${jc.simpleName} — pass two distinct belongsTo " +
                    "refs (one pointing at the declaring schema, one at the M2M target).",
            )
        }

        val srcHandle = resolvePropertySafely(srcProp, junctionInstance, edgeName, "sourceEdge")
        val tgtHandle = resolvePropertySafely(tgtProp, junctionInstance, edgeName, "targetEdge")

        val srcBuilder = srcHandle as? BelongsToBuilder<*>
            ?: error("Edge '$edgeName': sourceEdge '${srcProp.name}' does not resolve to a belongsTo edge")
        val tgtBuilder = tgtHandle as? BelongsToBuilder<*>
            ?: error("Edge '$edgeName': targetEdge '${tgtProp.name}' does not resolve to a belongsTo edge")

        val ownerInstance = registry[owner]
            ?: error("Edge '$edgeName': declaring schema ${owner.simpleName} not found in registry")
        val targetInstance = resolvedTarget!!

        // The junction's sourceEdge must point back at the declaring schema, and
        // the targetEdge at the M2M target — otherwise the relationship is
        // miswired and would resolve to FK columns on the wrong side at codegen.
        if (registry[srcBuilder.targetClass] !== ownerInstance) {
            error(
                "manyToMany edge '$edgeName': sourceEdge '${srcBuilder.edgeName}' on " +
                    "${jc.simpleName} targets ${srcBuilder.targetClass.simpleName}, not the declaring " +
                    "schema ${owner.simpleName} — pass the junction belongsTo that points back at the " +
                    "schema declaring this manyToMany.",
            )
        }
        if (registry[tgtBuilder.targetClass] !== targetInstance) {
            error(
                "manyToMany edge '$edgeName': targetEdge '${tgtBuilder.edgeName}' on " +
                    "${jc.simpleName} targets ${tgtBuilder.targetClass.simpleName}, not the M2M target " +
                    "${targetClass.simpleName} — pass the junction belongsTo that points at the " +
                    "manyToMany<Target> type parameter.",
            )
        }

        resolvedThrough = when (resolvedMode) {
            ManyToManyMode.LINK -> ManyToManyThrough.LinkTable(junctionInstance, srcBuilder.edgeName, tgtBuilder.edgeName)
            ManyToManyMode.ENTITY -> ManyToManyThrough.ThroughEntity(junctionInstance, srcBuilder.edgeName, tgtBuilder.edgeName)
        }
    }

    override fun build(): Edge {
        val target = resolvedTarget
            ?: error("Edge '$edgeName' has not been finalized — call schema.finalize() first")
        val t = resolvedThrough
            ?: error(
                "manyToMany edge '$edgeName' must declare a write model — call either " +
                    "throughLink<Junction>(...) or throughEntity<Junction>(...)",
            )
        return Edge(
            name = edgeName,
            target = target,
            kind = EdgeKind.ManyToMany(t),
            comment = comment,
        )
    }
}
