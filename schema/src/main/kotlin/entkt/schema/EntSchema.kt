package entkt.schema

import kotlin.reflect.KClass
import kotlin.reflect.KMutableProperty1
import kotlin.reflect.KVisibility
import kotlin.reflect.full.declaredMemberProperties
import kotlin.reflect.jvm.javaField

/**
 * Base class for all entkt schema declarations. Each schema corresponds
 * to a database table and declares its fields, edges, and indexes as
 * plain Kotlin properties that self-register with the owning schema.
 *
 * ```kotlin
 * class User : EntSchema("users") {
 *     override fun id() = EntId.long()
 *     val name = string("name").minLength(1).maxLength(64)
 *     val posts = hasMany<Post>("posts")
 * }
 * ```
 *
 * @param tableName the physical SQL table name
 */
/**
 * One field whose backing `FieldBuilder` is referenced from more
 * than one direct public `val` property on the concrete schema
 * class. Recorded by [EntSchema.captureDeclarationNames] during
 * finalize; surfaced as a `validateEntSchemas` diagnostic via the
 * codegen-side alias-rejection helper. Per declaration-name capture,
 * `docs/possible-features/edge-mutation/06-field-backed-fk-declaration-names.md`.
 *
 * Public so codegen modules can consume the accessor; treat as
 * read-only diagnostic metadata, not as part of the schema's
 * write-time DSL.
 */
data class DeclarationAlias(
    /** Column name of the shared backing field. */
    val fieldColumn: String,
    /**
     * Every direct public `val` property on the schema class that
     * references the same builder instance, in declaration order.
     * Always length ≥ 2 (no entry is recorded when only one
     * property references a given builder).
     */
    val properties: List<String>,
)

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

    /**
     * declaration-name capture alias tracking. Populated by [captureDeclarationNames]
     * during [finalize]. Each entry names a field whose backing
     * `FieldBuilder` is referenced from more than one direct
     * public `val` on the concrete schema class
     * (`val a = uuid("x"); val b = a`). Codegen-side validation
     * rejects schemas with any entries here.
     */
    @PublishedApi
    internal val _declarationAliases: MutableList<DeclarationAlias> = mutableListOf()

    /**
     * Read-only view of [_declarationAliases], for codegen-side
     * diagnostics that need to surface duplicate-alias errors.
     * Empty list before finalize.
     */
    fun declarationAliases(): List<DeclarationAlias> = _declarationAliases.toList()

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

        // Kotlin "hard" keywords — names the parser cannot accept as
        // identifiers without backtick-escaping. Codegen uses raw
        // identifier emission (`%L`) for fields/properties/params,
        // so a schema field named `class` would generate
        // `val class = this.class` and fail to compile. Rejecting
        // here at schema-validation time is louder than discovering
        // the failure several layers downstream in user-visible
        // generated code. List sourced from Kotlin's grammar
        // (KotlinSpec section "Hard keywords").
        private val KOTLIN_HARD_KEYWORDS: Set<String> = setOf(
            "as", "break", "class", "continue", "do", "else", "false",
            "for", "fun", "if", "in", "interface", "is", "null",
            "object", "package", "return", "super", "this", "throw",
            "true", "try", "typealias", "typeof", "val", "var", "when",
            "while",
        )

        @PublishedApi internal fun validateName(name: String, kind: String) {
            require(VALID_NAME.matches(name)) {
                "$kind name '$name' is not valid — names must be lowercase snake_case " +
                    "(letters, digits, single underscores; no leading/trailing/consecutive underscores)"
            }
            require(name !in KOTLIN_HARD_KEYWORDS) {
                "$kind name '$name' is a Kotlin reserved keyword — codegen emits this " +
                    "identifier without backtick-escaping, so the generated code would fail to " +
                    "compile. Rename the $kind."
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

    /**
     * Declare an enum-typed field backed by Kotlin enum class [E].
     *
     * Values are persisted as the constant's [Enum.name] string in a plain
     * `text` column (no native DB enum, no `CHECK` constraint) and read back
     * with `valueOf(name)`.
     *
     * **Renaming a constant is a data-affecting change, not a free
     * refactor.** Renaming the enum *class* is safe (the persisted value is
     * the constant name, unchanged), but renaming a *constant* (e.g.
     * `MEDIUM` → `NORMAL`):
     * - changes the persisted contract — existing rows still hold the old
     *   string, and `valueOf("MEDIUM")` throws when those rows are read;
     * - if the constant is used as a `.default(...)`, the migration differ
     *   emits only a metadata-only `ALTER COLUMN … SET DEFAULT 'NORMAL'` — it
     *   does **not** rewrite existing rows, and nothing in the DB or tooling
     *   flags the stale values.
     *
     * Treat a constant rename like any value migration: add the new constant,
     * backfill existing rows (`UPDATE … SET col = 'NORMAL' WHERE col =
     * 'MEDIUM'`) in a hand-written migration, then retire the old name.
     */
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

    /**
     * Registration hook for the `entkt.postgres.vector.postgresVector(name,
     * dimensions)` extension (RFC "Native Database Column Types", §2). Mirrors
     * [enum]: validates the dimension and name, attaches the native storage,
     * and registers the field — so the public, import-gated `postgresVector`
     * extension can build a native field without reaching the private
     * `registerField` or the internal `FieldBuilder` ctor.
     */
    @PublishedApi
    internal fun registerPostgresVector(name: String, dimensions: Int): PgVectorFieldBuilder {
        // pgvector's `vector` type allows up to 16000 dimensions. (HNSW/IVFFlat
        // indexes are further limited to 2000, enforced where the index is
        // declared, not on the column.)
        require(dimensions in 1..16000) {
            "postgresVector('$name') dimensions must be 1..16000, got $dimensions"
        }
        return PgVectorFieldBuilder(name).also {
            validateName(name, "Field")
            checkNotFinalized()
            it.setNativeStorage(
                ColumnStorage.Native(
                    dialect = "postgres",
                    typeName = "vector",
                    sqlType = "vector($dimensions)",
                    codec = "postgres.vector",
                    requiredExtension = "vector",
                    dimensions = dimensions,
                ),
            )
            it.declarationOwner = this
            _fields.add(it)
        }
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

    /**
     * Registration hook for the `entkt.postgres.vector.postgresVectorIndex(name,
     * field)` extension (RFC "Native Database Column Types", §6). Mirrors [index]
     * — same column-ownership validation — so the import-gated extension can
     * register a native vector index without reaching `IndexBuilder`'s internal
     * ctor or the protected `index()`.
     */
    @PublishedApi
    internal fun registerPostgresVectorIndex(name: String, field: IndexableColumn): IndexBuilder {
        checkNotFinalized()
        validateName(name, "Index")
        val owner = when (field) {
            is FieldBuilder<*, *> -> field.declarationOwner
            is FkColumn -> field.declarationOwner
            else -> null
        }
        require(owner == null || owner === this) {
            "postgresVectorIndex() references '${field.fieldName}' which belongs to schema " +
                "'${owner!!::class.simpleName}', not '${this::class.simpleName}'"
        }
        // A pgvector index is only valid over a pgvector column — reject anything
        // else here rather than letting it fail at CREATE INDEX.
        val native = (field as? FieldBuilder<*, *>)?.nativeStorage as? ColumnStorage.Native
        require(native != null && native.codec == "postgres.vector") {
            "postgresVectorIndex('$name') requires a pgvector column, but '${field.fieldName}' is not one"
        }
        // HNSW and IVFFlat both cap at 2000 dimensions (the column itself allows
        // up to 16000), so an index over a wider vector can never be built.
        require(native.dimensions <= 2000) {
            "postgresVectorIndex('$name') on '${field.fieldName}': pgvector HNSW/IVFFlat indexes " +
                "support at most 2000 dimensions, but the column is ${native.dimensions}-dimensional"
        }
        return IndexBuilder(name, listOf(field), isVectorIndex = true).also { _indexes.add(it) }
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
        // declaration-name capture: capture the Kotlin `val` name for each FieldBuilder.
        // Runs BEFORE freezing so we can write to FieldBuilder.declarationName.
        captureDeclarationNames()
        // Freeze all builders so mutations after finalization are rejected
        for (field in _fields) { field.frozen = true }
        for (edge in _edges) { edge.frozen = true }
        for (index in _indexes) { index.frozen = true }
        _finalized = true
    }

    /**
     * Walk the concrete schema class's direct public `val`
     * properties and set [FieldBuilder.declarationName] on each
     * one whose backing-field value is identity-equal to a
     * `FieldBuilder` already in [_fields]. Per declaration-name capture
     * (`docs/possible-features/edge-mutation/06-field-backed-fk-declaration-names.md`).
     *
     * **Reads the Java backing field, not the getter.** Calling
     * `KProperty.getter.call(...)` on a computed-getter property
     * like `val x get() = string("...")` would invoke `string(...)`
     * again, creating a throw-away `FieldBuilder` *and* registering
     * a fresh `Field` on this schema as a side effect via the
     * protected DSL helpers. Reading `javaField.get(this)` directly
     * is side-effect-free: it returns the value stored in the
     * property's backing JVM field, or — for computed getters and
     * delegated properties, which have no backing field —
     * `KProperty.javaField` is null and the property is skipped
     * entirely without ever invoking its getter.
     *
     * Properties this pass deliberately does NOT capture:
     *
     *  - non-public visibility (`private`, `protected`, `internal`)
     *  - properties inherited from a superclass — capture uses
     *    [declaredMemberProperties], which returns only the
     *    concrete schema class's own properties (matches the
     *    "direct public val property on the concrete schema class"
     *    scope rule)
     *  - `var` properties — capture filters out
     *    [KMutableProperty1] instances so `var x = string("x")`
     *    isn't treated as a stable handle declaration
     *  - computed getters (`javaField == null`)
     *  - delegated (`by lazy`, `by SomeDelegate`) — also
     *    `javaField == null` for the property itself
     *  - mixin-backed re-exports — the host schema's property holds
     *    an `EntMixin` instance (not a `FieldBuilder`), so the
     *    identity match against [_fields] never succeeds
     *
     * Aliased properties (`val a = uuid("x"); val b = a`) are
     * flagged as duplicates by [findDuplicateDeclarationAliases]
     * (called from the codegen-side validator). The capture pass
     * still records the *first* property name on the builder so
     * downstream codegen has a deterministic value to fall back
     * on if the diagnostic is suppressed — but the schema is
     * rejected before codegen ever runs.
     */
    private fun captureDeclarationNames() {
        // Build an identity-keyed map of registered builders so the
        // capture pass is O(properties), not O(properties × fields).
        // Identity-keyed because two `string("x")` calls produce
        // distinct builder instances even though they share `fieldName`.
        val byIdentity: MutableMap<FieldBuilder<*, *>, FieldBuilder<*, *>> =
            java.util.IdentityHashMap<FieldBuilder<*, *>, FieldBuilder<*, *>>().also { map ->
                for (b in _fields) map[b] = b
            }
        // Track every (builder → list of property names) match so
        // duplicates can surface as a single coherent diagnostic.
        // Identity-keyed for the same reason as `byIdentity`.
        val propsByBuilder: MutableMap<FieldBuilder<*, *>, MutableList<String>> =
            java.util.IdentityHashMap()

        val schemaClass: KClass<out EntSchema> = this::class
        // `declaredMemberProperties` returns only properties
        // declared in this class, NOT inherited ones. The
        // V1 scope is "direct public val property on the concrete
        // schema class" — inherited properties (e.g. from an
        // abstract intermediate base) are explicitly out of scope.
        for (prop in schemaClass.declaredMemberProperties) {
            // Only public.
            if (prop.visibility != KVisibility.PUBLIC) continue

            // Drop `var` properties. KProperty1 is the read-only
            // base; KMutableProperty1 represents `var`. V1
            // captures only stable `val` handles.
            if (prop is KMutableProperty1<*, *>) continue

            // `javaField` is null for computed getters and
            // delegated properties; both are excluded from V1.
            val javaField = prop.javaField ?: continue

            // The Java field is in the concrete class; reading it
            // requires bypassing Java visibility (KProperty's
            // `javaField` honors Java access modifiers, and Kotlin
            // backing fields for public `val`s are usually private
            // at the JVM level).
            javaField.isAccessible = true
            val value: Any? = javaField.get(this)
            if (value !is FieldBuilder<*, *>) continue

            // Identity match: only annotate builders that are
            // actually registered with this schema. A FieldBuilder
            // constructed but never registered (or one owned by
            // another schema) is silently skipped.
            val registered = byIdentity[value] ?: continue

            // Capture the first property name we see (declaration
            // order) so downstream codegen has a deterministic
            // value; aliased properties produce an explicit
            // diagnostic via [_declarationAliases] below.
            if (registered.declarationName == null) {
                registered.declarationName = prop.name
            }
            // Record EVERY direct val pointing at this builder.
            // Aliases (`val a = uuid("x"); val b = a`) end up here
            // with both names; the codegen-side validator surfaces
            // the duplicate.
            propsByBuilder.getOrPut(registered) { mutableListOf() }.add(prop.name)
        }

        // After the walk, every builder referenced by 2+ direct
        // vals becomes a DeclarationAlias entry. Single-property
        // builders are the normal case and don't get recorded.
        for ((builder, props) in propsByBuilder) {
            if (props.size > 1) {
                _declarationAliases.add(
                    DeclarationAlias(fieldColumn = builder.fieldName, properties = props.toList()),
                )
            }
        }
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
        val seenShapes = mutableSetOf<List<Any?>>()

        // The semantic identity of an index — mirrors the migration differ's
        // IndexKey (columns, uniqueness, where, access method, operator classes,
        // storage params) so two genuinely-distinct indexes are not falsely
        // rejected here. Two pgvector indexes on the same column that differ
        // only by access method or operator class (e.g. hnsw vector_cosine_ops
        // vs hnsw vector_l2_ops) are distinct and both must be allowed.
        fun shapeOf(
            fields: List<String>,
            unique: Boolean,
            where: String?,
            using: String?,
            opclasses: List<String>?,
            with: Map<String, String>?,
        ): List<Any?> = listOf(fields, unique, where, using, opclasses, with)

        // Pre-populate shapes from synthesized unique indexes so that
        // explicit indexes that duplicate a field.unique() or
        // belongsTo().unique() constraint are caught here rather than
        // producing duplicate indexes at migration time.
        for (field in _fields) {
            val f = field.build()
            if (f.unique) {
                seenShapes.add(shapeOf(listOf(f.name), true, null, null, null, null))
            }
        }
        for (edge in _edges) {
            if (edge is BelongsToBuilder<*>) {
                val e = edge.build()
                val bt = e.kind as EdgeKind.BelongsTo
                if (bt.unique) {
                    val fkCol = bt.field ?: "${e.name}_id"
                    seenShapes.add(shapeOf(listOf(fkCol), true, null, null, null, null))
                }
            }
        }

        for (index in built) {
            require(seenNames.add(index.name)) {
                "Duplicate index name '${index.name}' — index names must be unique per schema"
            }
            val shape = shapeOf(index.fields, index.unique, index.where, index.using, index.opclasses, index.with)
            require(seenShapes.add(shape)) {
                "Index '${index.name}' has the same columns, uniqueness, where clause, access method, " +
                    "and operator classes as another index — duplicate semantic indexes are not allowed"
            }
        }
        return built
    }
}
