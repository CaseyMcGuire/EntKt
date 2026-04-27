package entkt.schema

import kotlin.reflect.KClass

/**
 * Base class for all entkt schema declarations. Each schema corresponds
 * to a database table and declares its fields, edges, and indexes as
 * plain Kotlin properties that self-register with the owning schema.
 *
 * ```kotlin
 * class User : EntSchema("users") {
 *     override fun id() = EntId.long()
 *     val name = string("name").minLen(1).maxLen(64)
 *     val posts = hasMany<Post>("posts")
 * }
 * ```
 *
 * @param tableName the physical SQL table name
 */
abstract class EntSchema(val tableName: String) {

    init {
        validateName(tableName, "Table")
    }

    @PublishedApi
    internal val _fields: MutableList<FieldBuilder<*, *>> = mutableListOf()

    @PublishedApi
    internal val _edges: MutableList<EdgeBuilderBase> = mutableListOf()

    @PublishedApi
    internal val _indexes: MutableList<IndexBuilder> = mutableListOf()

    private var _finalized = false

    val isFinalized: Boolean get() = _finalized

    /**
     * Total number of registered field, edge, and index declarations.
     * Used by edge resolution to detect computed-getter properties that
     * create new declarations as a side effect of [KProperty1.get].
     */
    internal val declarationCount: Int get() = _fields.size + _edges.size + _indexes.size

    abstract fun id(): EntId

    @PublishedApi
    internal fun checkNotFinalized() {
        check(!_finalized) { "Schema '${this::class.simpleName}' cannot add declarations after finalization" }
    }

    companion object {
        private val VALID_NAME = Regex("^[a-z][a-z0-9]*(_[a-z0-9]+)*$")

        @PublishedApi internal fun validateName(name: String, kind: String) {
            require(VALID_NAME.matches(name)) {
                "$kind name '$name' is not valid — names must be lowercase snake_case " +
                    "(letters, digits, single underscores; no leading/trailing/consecutive underscores)"
            }
        }
    }

    // ── Field builder methods ──────────────────────────────────────

    private fun <T : FieldBuilder<*, *>> registerField(builder: T): T =
        builder.also { validateName(it.fieldName, "Field"); checkNotFinalized(); it.declarationOwner = this; _fields.add(it) }

    protected fun string(name: String): StringFieldBuilder = registerField(StringFieldBuilder(name))
    protected fun text(name: String): TextFieldBuilder = registerField(TextFieldBuilder(name))
    protected fun bool(name: String): BoolFieldBuilder = registerField(BoolFieldBuilder(name))
    protected fun int(name: String): IntFieldBuilder = registerField(IntFieldBuilder(name))
    protected fun long(name: String): LongFieldBuilder = registerField(LongFieldBuilder(name))
    protected fun float(name: String): FloatFieldBuilder = registerField(FloatFieldBuilder(name))
    protected fun double(name: String): DoubleFieldBuilder = registerField(DoubleFieldBuilder(name))
    protected fun time(name: String): TimeFieldBuilder = registerField(TimeFieldBuilder(name))
    protected fun uuid(name: String): UuidFieldBuilder = registerField(UuidFieldBuilder(name))
    protected fun bytes(name: String): BytesFieldBuilder = registerField(BytesFieldBuilder(name))

    protected inline fun <reified E : Enum<E>> enum(name: String): EnumFieldBuilder =
        enum(name, E::class)

    @PublishedApi
    internal fun enum(name: String, enumClass: KClass<out Enum<*>>): EnumFieldBuilder =
        EnumFieldBuilder(name).also {
            validateName(name, "Field")
            checkNotFinalized()
            it.setEnumClass(enumClass)
            it.declarationOwner = this
            _fields.add(it)
        }

    @PublishedApi internal fun stringForMixin(name: String): StringFieldBuilder = string(name)
    @PublishedApi internal fun textForMixin(name: String): TextFieldBuilder = text(name)
    @PublishedApi internal fun boolForMixin(name: String): BoolFieldBuilder = bool(name)
    @PublishedApi internal fun intForMixin(name: String): IntFieldBuilder = int(name)
    @PublishedApi internal fun longForMixin(name: String): LongFieldBuilder = long(name)
    @PublishedApi internal fun floatForMixin(name: String): FloatFieldBuilder = float(name)
    @PublishedApi internal fun doubleForMixin(name: String): DoubleFieldBuilder = double(name)
    @PublishedApi internal fun timeForMixin(name: String): TimeFieldBuilder = time(name)
    @PublishedApi internal fun uuidForMixin(name: String): UuidFieldBuilder = uuid(name)
    @PublishedApi internal fun bytesForMixin(name: String): BytesFieldBuilder = bytes(name)
    @PublishedApi internal inline fun <reified E : Enum<E>> enumForMixin(name: String): EnumFieldBuilder =
        enum(name, E::class)

    // ── Edge builder methods ───────────────────────────────────────

    protected inline fun <reified Target : EntSchema> belongsTo(
        name: String,
    ): BelongsToBuilder<Target> = belongsTo(name, Target::class)

    @PublishedApi
    internal fun <Target : EntSchema> belongsTo(
        name: String,
        target: KClass<Target>,
    ): BelongsToBuilder<Target> = BelongsToBuilder<Target>(name, target).also { validateName(name, "Edge"); checkNotFinalized(); it.declarationOwner = this; _edges.add(it) }

    protected inline fun <reified Target : EntSchema> hasMany(
        name: String,
    ): HasManyBuilder<Target> = hasMany(name, Target::class)

    @PublishedApi
    internal fun <Target : EntSchema> hasMany(
        name: String,
        target: KClass<Target>,
    ): HasManyBuilder<Target> = HasManyBuilder<Target>(name, target).also { validateName(name, "Edge"); checkNotFinalized(); it.declarationOwner = this; _edges.add(it) }

    protected inline fun <reified Target : EntSchema> hasOne(
        name: String,
    ): HasOneBuilder<Target> = hasOne(name, Target::class)

    @PublishedApi
    internal fun <Target : EntSchema> hasOne(
        name: String,
        target: KClass<Target>,
    ): HasOneBuilder<Target> = HasOneBuilder<Target>(name, target).also { validateName(name, "Edge"); checkNotFinalized(); it.declarationOwner = this; _edges.add(it) }

    protected inline fun <reified Target : EntSchema> manyToMany(
        name: String,
    ): ManyToManyBuilder<Target> = manyToMany(name, Target::class)

    @PublishedApi
    internal fun <Target : EntSchema> manyToMany(
        name: String,
        target: KClass<Target>,
    ): ManyToManyBuilder<Target> = ManyToManyBuilder<Target>(name, target).also { validateName(name, "Edge"); checkNotFinalized(); it.declarationOwner = this; _edges.add(it) }

    // ── Index builder methods ──────────────────────────────────────

    protected fun index(name: String, vararg fields: IndexableColumn): IndexBuilder {
        checkNotFinalized()
        validateName(name, "Index")
        for (col in fields) {
            val owner = when (col) {
                is FieldBuilder<*, *> -> col.declarationOwner
                is FkColumn -> col.declarationOwner
                else -> null
            }
            if (owner != null && owner !== this) {
                require(false) {
                    "index() references '${col.fieldName}' which belongs to schema " +
                        "'${owner::class.simpleName}', not '${this::class.simpleName}'"
                }
            }
        }
        return IndexBuilder(name, fields.toList()).also { _indexes.add(it) }
    }

    @PublishedApi internal fun indexForMixin(name: String, vararg fields: IndexableColumn): IndexBuilder =
        index(name, *fields)

    protected fun <M : EntMixin> include(factory: (EntMixin.Scope) -> M): M {
        checkNotFinalized()
        return factory(EntMixin.Scope(this))
    }

    @PublishedApi internal fun <M : EntMixin> includeForMixin(factory: (EntMixin.Scope) -> M): M =
        include(factory)

    // ── Finalization ───────────────────────────────────────────────

    /**
     * Resolve symbolic cross-schema references (KClass targets, KProperty1
     * inverse and through refs) against the canonical schema registry.
     * Must be called after all schemas are collected and before [fields],
     * [edges], or [indexes] are accessed.
     */
    fun finalize(registry: Map<KClass<out EntSchema>, EntSchema>) {
        check(!_finalized) { "Schema '${this::class.simpleName}' has already been finalized" }
        for (edge in _edges) {
            edge.resolve(registry, this::class)
        }
        // Freeze all builders so mutations after finalization are rejected
        for (field in _fields) { field.frozen = true }
        for (edge in _edges) { edge.frozen = true }
        for (index in _indexes) { index.frozen = true }
        _finalized = true
    }

    // ── Accessors (post-finalization) ──────────────────────────────

    fun fields(): List<Field> {
        val built = _fields.map { it.build() }
        val seen = mutableSetOf<String>()
        for (field in built) {
            require(seen.add(field.name)) {
                "Duplicate field name '${field.name}' — field names must be unique per schema"
            }
        }
        return built
    }

    fun edges(): List<Edge> {
        if (_edges.isEmpty()) return emptyList()
        check(_finalized) { "Schema '${this::class.simpleName}' must be finalized before accessing edges" }
        val built = _edges.map { it.build() }
        val seen = mutableSetOf<String>()
        for (edge in built) {
            require(seen.add(edge.name)) {
                "Duplicate edge name '${edge.name}' — edge names must be unique per schema"
            }
        }
        return built
    }

    fun indexes(): List<Index> {
        val built = _indexes.map { it.build() }
        val seenNames = mutableSetOf<String>()
        val seenShapes = mutableSetOf<Triple<List<String>, Boolean, String?>>()
        for (index in built) {
            require(seenNames.add(index.name)) {
                "Duplicate index name '${index.name}' — index names must be unique per schema"
            }
            val shape = Triple(index.fields, index.unique, index.where)
            require(seenShapes.add(shape)) {
                "Index '${index.name}' has the same columns, uniqueness, and where clause " +
                    "as another index — duplicate semantic indexes are not allowed"
            }
        }
        return built
    }
}
