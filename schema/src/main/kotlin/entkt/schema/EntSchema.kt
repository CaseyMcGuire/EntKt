package entkt.schema

import kotlin.reflect.KClass
import kotlin.reflect.KType
import kotlin.reflect.KVariance
import kotlin.reflect.KVisibility
import kotlin.reflect.full.createType
import kotlin.reflect.full.declaredMemberProperties
import kotlin.reflect.full.isSubclassOf
import kotlin.reflect.full.memberProperties
import kotlin.reflect.jvm.javaField
import kotlin.reflect.typeOf

/**
 * Base class for all entkt schema declarations. Each schema corresponds
 * to a database table and declares its fields, edges, and indexes as
 * plain Kotlin properties that self-register with the owning schema.
 *
 * ```kotlin
 * class User : EntSchema("users", clientName = "users") {
 *     override fun id() = EntId.long()
 *     val name by string("name").minLength(1).maxLength(64)
 *     val posts by hasMany<Post>("posts")
 * }
 * ```
 *
 * [tableName] and [clientName] are independent, explicit metadata: the
 * table name is a storage identifier, the client name is a generated
 * Kotlin API identifier. Neither is derived from the other, and neither
 * is derived from the schema class name. Renaming one does not rename
 * the other. See
 * `docs/implemented-features/schema/schema-declaration-api-names.md`.
 *
 * @param tableName the physical SQL table name (lowercase snake_case)
 * @param clientName the exact generated client/configuration property
 *   name for this schema — `clientName = "users"` produces
 *   `client.users`, `privacy.users { }`, `validation.users { }`, and
 *   `hooks.users { }`. It must be a lower-camel Kotlin identifier and
 *   unique across the generated schema set. entkt emits it verbatim: it
 *   is never pluralized, singularized, or otherwise transformed, so an
 *   irregular or intentionally singular term (`people`, `news`, `audit`)
 *   is spelled out here rather than inferred.
 */
abstract class EntSchema(val tableName: String, val clientName: String) {

    init {
        validateName(tableName, "Table")
        validateApiName(clientName, "Client name")
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

        // Generated Kotlin API identifiers: client names, and field and
        // edge declaration names bound through `by`.
        // Deliberately a separate vocabulary from VALID_NAME: that one
        // governs *storage* strings, which are snake_case. A generated
        // API name is a lower-camel Kotlin identifier, so `user_accounts`
        // is a valid table name and an invalid client name, while
        // `userAccounts` is the reverse.
        private val VALID_API_NAME = Regex("^[a-z][A-Za-z0-9]*$")

        // Kotlin "hard" keywords — names the parser cannot accept as
        // identifiers without backtick-escaping. These constrain
        // *generated API names* only (see validateApiName): a
        // declaration named `class` would emit `val class = ...` and
        // fail to compile.
        //
        // Storage names are exempt. They reach generated source only as
        // string literals and reach SQL only as quoted identifiers, so
        // `string("class")` and `hasMany<Item>("object")` are valid —
        // the Kotlin API those produce comes from the declaration, not
        // the storage string. List sourced from Kotlin's grammar
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
        }

        /**
         * Validate a name that becomes a generated Kotlin identifier
         * verbatim, as opposed to [validateName], which validates a
         * storage string.
         *
         * Rejecting here is louder than discovering the failure in
         * user-visible generated source: entkt performs no inflection
         * or normalization on these names, so whatever is supplied is
         * exactly what codegen emits.
         */
        @PublishedApi internal fun validateApiName(name: String, kind: String) {
            require(VALID_API_NAME.matches(name)) {
                "$kind '$name' is not a valid generated API name — it must be a lower-camel " +
                    "Kotlin identifier (a lowercase letter followed by letters and digits; no " +
                    "underscores, leading uppercase, or other punctuation). entkt emits this " +
                    "name verbatim and never transforms it."
            }
            require(name !in KOTLIN_HARD_KEYWORDS) {
                "$kind '$name' is a Kotlin reserved keyword — codegen emits this identifier " +
                    "without backtick-escaping, so the generated code would fail to compile. " +
                    "Choose a different $kind."
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
     * Declare a typed JSON column backed by the Kotlin class [klass]. The
     * class is exposed at the generated API boundary; values are stored as
     * Postgres `jsonb` and encoded/decoded through the configured JSON
     * mapper. With the default kotlinx mapper the class must be
     * `@Serializable` — generated code references `klass.serializer()`, so a
     * non-serializable class fails at compile time; other mappers (e.g.
     * Jackson, selected via the codegen `jsonMapper` setting) resolve
     * (de)serialization reflectively from the captured type instead.
     *
     * [klass] must not have type parameters — a `KClass` cannot carry type
     * arguments, so `json("rects", List::class)` could only produce a raw
     * `List`. Declare generic shapes with the reified overload, which captures
     * the full type: `json<List<HighlightRect>>("rects")`.
     *
     * `.nullable()` and `.comment()` apply; `.unique()`, defaults, primary keys,
     * and JSON indexes are rejected. Equality/membership/ordering predicates are
     * out of scope in V1 — the generated column ref exposes null checks only.
     *
     * Registering a JSON field with a driver that does not support typed JSON
     * fails at `register()`.
     */
    protected fun <T : Any> json(name: String, klass: KClass<T>): JsonFieldBuilder {
        require(klass.typeParameters.isEmpty()) {
            "json(\"$name\", ${klass.simpleName}::class): ${klass.simpleName} has type parameters, " +
                "which a KClass cannot carry — declare the full type with the reified overload, " +
                "e.g. json<${klass.simpleName}<Element>>(\"$name\")"
        }
        return registerJson(name, klass.createType())
    }

    /**
     * Reified convenience for [json] — `json<PetMetadata>("pet_metadata")`.
     *
     * Unlike the `KClass` overload this captures the **full type**, so generic
     * shapes work directly: `json<List<HighlightRect>>("rects")` generates a
     * `List<HighlightRect>` property and serializes the element type (no
     * wrapper class needed). Under the default kotlinx mapper, every class in
     * the type must have a kotlinx serializer: `@Serializable` classes
     * (including `@Serializable` enums), primitives/`String`, and
     * `List`/`Set`/`Map`/`Pair`/`Triple`, and Kotlin array types; other mappers
     * resolve their own (de)serialization from the captured type. EntKt emits
     * any opt-ins required by kotlinx array serializer factories in generated
     * source, so applications do not need to enable them globally.
     */
    protected inline fun <reified T : Any> json(name: String): JsonFieldBuilder =
        registerJson(name, typeOf<T>())

    @PublishedApi
    internal fun registerJson(name: String, type: KType): JsonFieldBuilder =
        JsonFieldBuilder(name).also {
            validateName(name, "Field")
            checkNotFinalized()
            validateJsonType(name, type)
            it.setJsonType(type)
            it.declarationOwner = this
            _fields.add(it)
        }

    /**
     * Reject JSON types codegen cannot faithfully emit or serialize. Every
     * classifier in the tree must be a concrete class (a bare `T` that leaked
     * from a generic context has no serializer), projections must be concrete
     * and invariant (`List<*>` and `List<out Foo>` have no stable element
     * serializer), and top-level nullability belongs to `.nullable()`, not the
     * type. Failing here beats failing inside generated code the user has to
     * reverse-engineer.
     */
    private fun validateJsonType(name: String, type: KType) {
        require(!type.isMarkedNullable) {
            "json(\"$name\") type '$type' is nullable — declare nullability with .nullable() instead"
        }
        fun walk(t: KType) {
            require(t.classifier is KClass<*>) {
                "json(\"$name\") type '$type' contains '$t', which is not a concrete class — " +
                    "type parameters cannot be serialized; declare a fully concrete type"
            }
            for (arg in t.arguments) {
                val argType = arg.type
                    ?: throw IllegalArgumentException(
                        "json(\"$name\") type '$type' contains a star projection — " +
                            "declare a concrete type argument",
                    )
                require(arg.variance == KVariance.INVARIANT) {
                    "json(\"$name\") type '$type' uses an 'in'/'out' projection on '$argType' — " +
                        "declare invariant type arguments"
                }
                walk(argType)
            }
        }
        walk(type)
    }

    /**
     * Registration hook for the `entkt.postgres.vector.postgresVector(name,
     * dimensions)` extension. Mirrors
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
     * field)` extension. Mirrors [index]
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
        val mixin = factory(EntMixin.Scope(this))
        validateMixinBindings(mixin)
        return mixin
    }

    /**
     * Apply the same binding rules to a mixin's own declarations that
     * [validateDeclarationBindings] applies to the schema's.
     *
     * This has to run here rather than at finalization: entkt does not
     * retain mixin instances, and a `by lazy` declaration never
     * registers a builder at all, so by finalize time there is nothing
     * left to notice. Checking while the instance is in hand is what
     * makes the RFC's wrapper-delegate rejection real for mixins instead
     * of silently dropping the column.
     *
     * Scoped by [FieldBuilder.declarationMixinClass] so a nested
     * `include(...)` is validated against its own class, not its host's.
     */
    private fun validateMixinBindings(mixin: EntMixin) {
        val mixinClass: KClass<*> = mixin::class
        val mixinName = mixinClass.simpleName ?: "<anonymous mixin>"

        val declaredHere: Set<String> = mixinClass.declaredMemberProperties
            .asSequence()
            .filter { it.visibility == KVisibility.PUBLIC }
            .map { it.name }
            .toSet()

        val boundHere: Set<String> = _fields
            .asSequence()
            .filter { it.declarationMixinClass == mixinClass }
            .mapNotNull { it.declarationName }
            .toSet()

        // A builder-typed property that never bound: `by lazy { ... }`,
        // a computed getter, or an ordinary `=`.
        for (prop in mixinClass.declaredMemberProperties) {
            if (prop.visibility != KVisibility.PUBLIC) continue
            val returnType = prop.returnType.classifier as? KClass<*> ?: continue
            if (!isDeclarationType(returnType)) continue
            if (prop.name in boundHere) continue
            error(
                "Mixin '$mixinName': property '${prop.name}' holds a schema builder but never " +
                    "bound a declaration name. Declare it directly with `by` — for example " +
                    "`val ${prop.name} by time(\"...\")`. A wrapper delegate such as " +
                    "`by lazy { ... }` or a computed getter (`get() = ...`) does not register a " +
                    "declaration, and would silently drop the column.",
            )
        }

        // A declaration that bound through this mixin but is declared on
        // a superclass of it — V1 binds only direct declarations.
        for (name in boundHere) {
            if (name in declaredHere) continue
            error(
                "Mixin '$mixinName': declaration '$name' is not declared on '$mixinName' " +
                    "itself. V1 binds only declarations on the concrete mixin class — move the " +
                    "declaration onto '$mixinName'.",
            )
        }
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
        validateDeclarationBindings()
        // Freeze all builders so mutations after finalization are rejected
        for (field in _fields) { field.frozen = true }
        for (edge in _edges) { edge.frozen = true }
        for (index in _indexes) { index.frozen = true }
        _finalized = true
    }

    /**
     * Reject every declaration shape that cannot produce a stable
     * generated API name.
     *
     * Binding itself happens in each builder's `provideDelegate` during
     * construction, so by the time this runs each builder either carries
     * a declaration name or never bound. Two things still need checking:
     *
     *  1. **Registered but unbound** — `val x = string("col")` builds and
     *     registers a real field that names nothing. Walking [_fields] and
     *     [_edges] catches it, including programmatic registration with no
     *     property at all.
     *
     *  2. **Declared but never registered** — `val x by lazy { string("col") }`
     *     never runs the builder during construction, so the field is
     *     absent from [_fields] entirely and (1) cannot see it. Walking the
     *     class's *declared properties* and looking for builder-typed ones
     *     that never bound catches it. This reads declared return types
     *     only; it never invokes a getter, so a computed getter cannot
     *     register a field as a side effect of being inspected.
     *
     * The same property walk detects a declaration inherited from a
     * superclass: it bound (so (1) passes) but has no property on the
     * concrete class. Mixin-contributed fields look identical from the
     * host's perspective, which is why binding records
     * [FieldBuilder.declarationMixinClass] to tell them apart.
     *
     * This pass only sees the schema class. A mixin's own declarations
     * are checked by [validateMixinBindings] while the mixin instance is
     * still in hand — entkt does not retain mixin instances after
     * `include(...)` returns, so by finalize time a `by lazy` inside a
     * mixin would have left nothing to notice.
     *
     * See `docs/implemented-features/schema/schema-declaration-api-names.md`.
     */
    /**
     * True when a property's declared type can only have come from a
     * schema builder, so a property of that type which never bound is a
     * dropped declaration rather than an unrelated member.
     *
     * Checking the concrete builder classes alone is not enough: the
     * builders' public supertypes are the *handle* interfaces, and
     * `val x: FieldHandle<String> by lazy { string("col") }` declares a
     * field whose property type mentions no builder at all.
     *
     * [IndexableColumn] is included even though `belongsTo(...).fk`
     * legitimately yields one that is not a declaration. That case is
     * separated by value rather than by type — see [holdsFkColumnHandle].
     */
    private fun isDeclarationType(type: KClass<*>): Boolean =
        type.isSubclassOf(FieldBuilder::class) ||
            type.isSubclassOf(EdgeBuilderBase::class) ||
            type == FieldHandle::class ||
            type == IndexableColumn::class ||
            type == BelongsToHandle::class ||
            type == HasManyHandle::class ||
            type == HasOneHandle::class ||
            type == ManyToManyHandle::class

    /**
     * True when [prop] holds an FK column handle (`belongsTo(...).fk`),
     * which is an [IndexableColumn] but not a declaration — it exists to
     * be passed to `index(...)`.
     *
     * Distinguishing it by *value* rather than by type is what lets the
     * unbound-declaration check cover `val x: IndexableColumn by lazy {
     * string("col") }` — which silently drops a column — while still
     * accepting `val ownerFk = writer.fk`.
     *
     * Reads the Java backing field, never a getter, so inspecting a
     * property cannot register a declaration as a side effect. A
     * delegated or computed property has no backing field, so it returns
     * false and stays subject to the unbound check — which is exactly
     * the `by lazy` case.
     */
    private fun holdsFkColumnHandle(prop: kotlin.reflect.KProperty1<*, *>): Boolean {
        val javaField = prop.javaField ?: return false
        return try {
            javaField.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            (javaField.get(this) as? IndexableColumn) is FkColumn
        } catch (_: Exception) {
            false
        }
    }

    private fun validateDeclarationBindings() {
        val schemaName = this::class.simpleName ?: "<anonymous>"

        // (1) Registered builders that never bound to a property.
        val unbound = buildList {
            for (f in _fields) if (f.declarationName == null) add("field '${f.fieldName}'")
            for (e in _edges) if (e.declarationName == null) add("edge '${e.edgeName}'")
        }
        if (unbound.isNotEmpty()) {
            error(
                "Schema '$schemaName': ${unbound.joinToString(", ")} " +
                    (if (unbound.size == 1) "is" else "are") +
                    " registered but not bound to a Kotlin property, so no generated API name " +
                    "exists. Declare with `by` instead of `=` — for example " +
                    "`val myField by string(\"my_field\")`.",
            )
        }

        // Declaration names must be unique per schema. Kotlin already
        // guarantees this within one class, but a mixin contributes into
        // the host's namespace, so a host field and a mixin field — or
        // two included mixins — can still collide.
        val byDeclaration = mutableMapOf<String, String>()
        fun claim(name: String, what: String) {
            val existing = byDeclaration.put(name, what)
            if (existing != null) {
                error(
                    "Schema '$schemaName': declaration name '$name' is claimed by both " +
                        "$existing and $what. Generated members would collide — rename one " +
                        "declaration (a mixin contributes its fields into the including " +
                        "schema's namespace).",
                )
            }
        }
        for (f in _fields) claim(f.declarationName!!, "field '${f.fieldName}'")
        for (e in _edges) claim(e.declarationName!!, "edge '${e.edgeName}'")

        // (2) Builder-typed properties on the concrete class that never
        // bound, and bound declarations with no property on this class.
        val declaredHere: Set<String> = this::class.declaredMemberProperties
            .asSequence()
            .filter { it.visibility == KVisibility.PUBLIC }
            .map { it.name }
            .toSet()

        // Only a declaration bound *on this class* accounts for a
        // property of the same name. A mixin contributes into the host's
        // namespace, so without this filter a mixin field named `title`
        // would vouch for an unbound host `val title`, and the host's
        // column would vanish silently.
        val boundOnSchema: Set<String> = buildSet {
            for (f in _fields) if (!f.declarationFromMixin) f.declarationName?.let { add(it) }
            for (e in _edges) if (!e.declarationFromMixin) e.declarationName?.let { add(it) }
        }

        // `memberProperties`, not `declaredMemberProperties`: an unbound
        // declaration on an abstract schema base class is just as
        // invisible to the author, and is otherwise never inspected.
        for (prop in this::class.memberProperties) {
            if (prop.visibility != KVisibility.PUBLIC) continue
            val returnType = prop.returnType.classifier as? KClass<*> ?: continue
            if (!isDeclarationType(returnType)) continue
            if (prop.name in boundOnSchema) continue
            @Suppress("UNCHECKED_CAST")
            if (holdsFkColumnHandle(prop as kotlin.reflect.KProperty1<Any, *>)) continue
            error(
                "Schema '$schemaName': property '${prop.name}' holds a schema builder but never " +
                    "bound a declaration name. Declare it directly with `by` — for example " +
                    "`val ${prop.name} by string(\"...\")`. A wrapper delegate such as " +
                    "`by lazy { ... }` or a computed getter (`get() = ...`) does not register " +
                    "a declaration, and would silently drop the column.",
            )
        }

        for ((name, what) in byDeclaration) {
            val fromMixin = _fields.any { it.declarationName == name && it.declarationFromMixin } ||
                _edges.any { it.declarationName == name && it.declarationFromMixin }
            if (fromMixin || name in declaredHere) continue
            error(
                "Schema '$schemaName': $what is bound to declaration '$name', which is not " +
                    "declared on '$schemaName' itself. V1 binds only declarations on the " +
                    "concrete schema class or an included mixin — move the declaration onto " +
                    "'$schemaName', or contribute it through a mixin.",
            )
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
