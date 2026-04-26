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
     * val author = belongsTo<User>("author").required()
     * val byAuthor = index(author.fk)
     * ```
     */
    val fk: IndexableColumn = FkColumn(this)

    private var required: Boolean = false
    private var unique: Boolean = false
    @PublishedApi internal var explicitFieldHandle: FieldHandle<*>? = null
    private var onDelete: OnDelete? = null
    private var inverseRef: KProperty1<Target, *>? = null
    private var comment: String? = null

    // Resolved during finalize
    private var resolvedTarget: EntSchema? = null
    private var resolvedRef: String? = null

    fun required(): BelongsToBuilder<Target> = apply { checkNotFrozen(); required = true }
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

class ManyToManyBuilder<Target : EntSchema> internal constructor(
    override val edgeName: String,
    @PublishedApi internal val targetClass: KClass<Target>,
) : ManyToManyHandle<Target>, EdgeBuilderBase() {
    private var junctionClass: KClass<out EntSchema>? = null
    private var junctionSourceProp: KProperty1<out EntSchema, *>? = null
    private var junctionTargetProp: KProperty1<out EntSchema, *>? = null
    private var comment: String? = null

    // Resolved during finalize
    private var resolvedTarget: EntSchema? = null
    private var resolvedThrough: Through? = null

    inline fun <reified Junction : EntSchema> through(
        sourceEdge: KProperty1<Junction, BelongsToHandle<*>>,
        targetEdge: KProperty1<Junction, BelongsToHandle<*>>,
    ): ManyToManyBuilder<Target> = through(Junction::class, sourceEdge, targetEdge)

    @PublishedApi
    internal fun <Junction : EntSchema> through(
        junctionClass: KClass<Junction>,
        sourceEdge: KProperty1<Junction, *>,
        targetEdge: KProperty1<Junction, *>,
    ): ManyToManyBuilder<Target> = apply {
        checkNotFrozen()
        this.junctionClass = junctionClass
        this.junctionSourceProp = sourceEdge
        this.junctionTargetProp = targetEdge
    }

    fun comment(text: String): ManyToManyBuilder<Target> = apply { checkNotFrozen(); comment = text }

    override fun resolve(registry: Map<KClass<out EntSchema>, EntSchema>, owner: KClass<out EntSchema>) {
        resolvedTarget = registry[targetClass]
            ?: error("Edge '$edgeName': target schema ${targetClass.simpleName} not found in registry")

        val jc = junctionClass
            ?: error("manyToMany edge '$edgeName' must have a .through() junction schema")
        val junctionInstance = registry[jc]
            ?: error("Edge '$edgeName': junction schema ${jc.simpleName} not found in registry")

        @Suppress("UNCHECKED_CAST")
        val srcProp = junctionSourceProp!! as KProperty1<EntSchema, *>
        @Suppress("UNCHECKED_CAST")
        val tgtProp = junctionTargetProp!! as KProperty1<EntSchema, *>

        val srcHandle = resolvePropertySafely(srcProp, junctionInstance, edgeName, "through() sourceEdge")
        val tgtHandle = resolvePropertySafely(tgtProp, junctionInstance, edgeName, "through() targetEdge")

        val srcEdgeName = (srcHandle as? EdgeBuilderBase)?.edgeName
            ?: error("Edge '$edgeName': through() sourceEdge does not resolve to an edge declaration")
        val tgtEdgeName = (tgtHandle as? EdgeBuilderBase)?.edgeName
            ?: error("Edge '$edgeName': through() targetEdge does not resolve to an edge declaration")

        resolvedThrough = Through(junctionInstance, srcEdgeName, tgtEdgeName)
    }

    override fun build(): Edge {
        val target = resolvedTarget
            ?: error("Edge '$edgeName' has not been finalized — call schema.finalize() first")
        val t = resolvedThrough
            ?: error("manyToMany edge '$edgeName' must have a .through() junction schema")
        return Edge(
            name = edgeName,
            target = target,
            kind = EdgeKind.ManyToMany(t),
            comment = comment,
        )
    }
}
