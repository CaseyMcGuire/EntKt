package entkt.query

/**
 * Marker interface for nullable columns. Only columns implementing this
 * interface get the [isNull] / [isNotNull] extensions — non-nullable
 * columns reject those checks at compile time.
 */
interface Nullable

/**
 * Marker for a column usable as an aggregate group key (the `raw…By` terminals).
 * Carries the column [name] and a [decodeKey] that turns the driver's raw group
 * value into the typed key [K] — identity for most columns, enum-name → enum for
 * [EnumColumn]. Implemented by string/text, numeric, time, bool, UUID, and enum
 * columns; NOT by `Column<E, ByteArray>` (BYTES) or the pgvector column, so
 * grouping by those is rejected at compile time.
 */
interface GroupableColumn<E : Any, K> {
    val name: String
    fun decodeKey(raw: Any?): K?
}

/**
 * A nullable group column. Extends [GroupableColumn] so that, by overload
 * specificity, the grouped terminals' `NullableGroupableColumn` overload (which
 * yields `AggregateBucket<K?, …>`) is chosen for a nullable column — its NULLs
 * fold into the single `key == null` bucket.
 */
interface NullableGroupableColumn<E : Any, K> : GroupableColumn<E, K>

/**
 * A typed reference to a column in a generated entity. Exposes the
 * predicates that are valid for *every* column kind (equality and set
 * membership).
 *
 * Type parameters:
 *  - [E]: the entity scope the column belongs to — a `Column<User, T>`
 *    cannot be used to build a predicate for a `PostQuery`.
 *  - [T]: the column's value type.
 *
 * Column refs are emitted on the generated entity's companion object,
 * e.g. `User.active`, `User.age`, `User.email`.
 *
 * Ordering helpers (`asc()` / `desc()`) deliberately do NOT live on the
 * base `Column`. They live on [ComparableColumn] / [EnumColumn] so that
 * non-comparable columns (notably a `Column<E, ByteArray>` for
 * `FieldType.BYTES` — `ByteArray` does not implement `Comparable`)
 * reject `orderBy(col.asc())` at compile time instead of crashing
 * inside the in-memory comparator at runtime.
 */
open class Column<E : Any, T>(val name: String) {
    open infix fun eq(value: T): Predicate<E> = Predicate.Leaf(name, Op.EQ, value)
    open infix fun neq(value: T): Predicate<E> = Predicate.Leaf(name, Op.NEQ, value)

    // Deliberately NO eager copy of the collection: operands are
    // semantically snapshotted at terminal entry, AFTER the driver's
    // bind-capacity check has consulted the collection's O(1) size —
    // so an over-budget operand is rejected without ever being
    // materialized, and the DSL behaves exactly like raw
    // `Predicate.Leaf` construction (mutations between construction
    // and the terminal are observed at chain entry, as everywhere
    // else).
    open infix fun `in`(values: Collection<T>): Predicate<E> =
        Predicate.Leaf(name, Op.IN, values)
    open infix fun notIn(values: Collection<T>): Predicate<E> =
        Predicate.Leaf(name, Op.NOT_IN, values)
}

/**
 * A column whose type admits ordering. Adds the range predicates and
 * the `asc()` / `desc()` order builders on top of the base equality
 * ops. See the [Column] KDoc for why ordering lives here and not on
 * the base.
 */
open class ComparableColumn<E : Any, T : Comparable<T>>(name: String) :
    Column<E, T>(name), GroupableColumn<E, T> {
    // Group keys for comparable columns are already decoded to their Kotlin type
    // by the driver (e.g. TIME → Instant, INT → Int), so decoding is identity.
    @Suppress("UNCHECKED_CAST")
    override fun decodeKey(raw: Any?): T? = raw as T?

    infix fun gt(value: T): Predicate<E> = Predicate.Leaf(name, Op.GT, value)
    infix fun gte(value: T): Predicate<E> = Predicate.Leaf(name, Op.GTE, value)
    infix fun lt(value: T): Predicate<E> = Predicate.Leaf(name, Op.LT, value)
    infix fun lte(value: T): Predicate<E> = Predicate.Leaf(name, Op.LTE, value)

    fun asc(): OrderField<E> = OrderField(name, OrderDirection.ASC)
    fun desc(): OrderField<E> = OrderField(name, OrderDirection.DESC)

    /**
     * Order by a [direction] chosen at runtime — lets dynamic-sort callers
     * write `column.order(dir)` instead of branching on [asc]/[desc].
     */
    fun order(direction: OrderDirection): OrderField<E> = OrderField(name, direction)
}

/**
 * A string column. Adds substring/prefix/suffix search on top of the
 * comparable ops.
 */
open class StringColumn<E : Any>(name: String) : ComparableColumn<E, String>(name) {
    infix fun contains(value: String): Predicate<E> =
        Predicate.Leaf(name, Op.CONTAINS, value)
    infix fun hasPrefix(value: String): Predicate<E> =
        Predicate.Leaf(name, Op.HAS_PREFIX, value)
    infix fun hasSuffix(value: String): Predicate<E> =
        Predicate.Leaf(name, Op.HAS_SUFFIX, value)
}

/**
 * A numeric column (INT / LONG / FLOAT / DOUBLE). Gates `sum` / `avg`; split into
 * [IntegralColumn] and [FloatingColumn] so `sum` resolves to `Long?` vs `Double?`.
 */
open class NumericColumn<E : Any, T : Comparable<T>>(name: String) : ComparableColumn<E, T>(name)

/** An integer-typed numeric column (INT, LONG). `sum` over it returns `Long?`. */
open class IntegralColumn<E : Any, T : Comparable<T>>(name: String) : NumericColumn<E, T>(name)

/** A floating-typed numeric column (FLOAT, DOUBLE). `sum` over it returns `Double?`. */
open class FloatingColumn<E : Any, T : Comparable<T>>(name: String) : NumericColumn<E, T>(name)

/**
 * A groupable but non-comparable scalar column (BOOL, UUID). Usable as an
 * aggregate group key, but not for min/max/sum/avg — it is not a
 * [ComparableColumn].
 */
open class GroupableScalarColumn<E : Any, T>(name: String) : Column<E, T>(name), GroupableColumn<E, T> {
    @Suppress("UNCHECKED_CAST")
    override fun decodeKey(raw: Any?): T? = raw as T?
}

// ---------- Nullable variants ----------
//
// Each kind has a parallel Nullable* class that mixes in the [Nullable]
// marker. The codegen picks which variant to instantiate based on the
// field's nullability, so a non-null field's column ref simply does not
// expose isNull/isNotNull.

/**
 * A column whose type is a Kotlin enum class. Converts enum values to
 * their [Enum.name] string when creating predicates, so the driver layer
 * continues to work with plain strings.
 *
 * Two type parameters: [E] is the entity scope (parallel to other
 * `Column` subclasses); [T] is the enum value type. The on-the-wire
 * representation is `T.name: String`, set by the overrides below.
 */
open class EnumColumn<E : Any, T : Enum<T>>(
    name: String,
    /**
     * Maps a stored enum-name `String` back to the enum constant — used to decode
     * an enum **group key** ([decodeKey]). codegen always supplies
     * `{ MyEnum.valueOf(it) }`; the throwing default only guards a hand-built
     * column that was never wired for aggregate grouping.
     */
    private val fromName: (String) -> T = {
        error("EnumColumn '$name' has no enum-decode metadata; build it via codegen to group by it")
    },
) : Column<E, T>(name), GroupableColumn<E, T> {
    /** Decode an enum group key — the driver returns the stored `.name` String. */
    override fun decodeKey(raw: Any?): T? = (raw as String?)?.let(fromName)

    override infix fun eq(value: T): Predicate<E> =
        Predicate.Leaf(name, Op.EQ, value.name)
    override infix fun neq(value: T): Predicate<E> =
        Predicate.Leaf(name, Op.NEQ, value.name)

    // The `.name` mapping is applied through a lazy view, not an
    // eager `map`: the collection LENGTH is caller-controlled (a
    // huge list of repeated constants is legal), so materializing
    // the mapped list here would bypass the driver's bind-capacity
    // check exactly like the eager `toList()` the base column
    // dropped. The view's `size` is O(1); elements map only when
    // iterated — at the chain-entry snapshot for client reads, or
    // at render for direct driver calls, both after that check.
    override infix fun `in`(values: Collection<T>): Predicate<E> =
        Predicate.Leaf(name, Op.IN, LazilyMappedOperands(values) { it.name })
    override infix fun notIn(values: Collection<T>): Predicate<E> =
        Predicate.Leaf(name, Op.NOT_IN, LazilyMappedOperands(values) { it.name })

    // Enums serialize to their .name string; ordering is alphabetical
    // on the name (not by ordinal). Declared here so EnumColumn can
    // be used in orderBy(...) — the asc/desc helpers don't move down
    // from ComparableColumn because the value type is `T : Enum<T>`,
    // not `T : Comparable<T>`, and we store the .name.
    fun asc(): OrderField<E> = OrderField(name, OrderDirection.ASC)
    fun desc(): OrderField<E> = OrderField(name, OrderDirection.DESC)

    /**
     * Order by a [direction] chosen at runtime — lets dynamic-sort callers
     * write `column.order(dir)` instead of branching on [asc]/[desc].
     */
    fun order(direction: OrderDirection): OrderField<E> = OrderField(name, direction)
}

class NullableColumn<E : Any, T>(name: String) : Column<E, T>(name), Nullable
class NullableComparableColumn<E : Any, T : Comparable<T>>(name: String) :
    ComparableColumn<E, T>(name), Nullable, NullableGroupableColumn<E, T>
class NullableStringColumn<E : Any>(name: String) :
    StringColumn<E>(name), Nullable, NullableGroupableColumn<E, String>
class NullableIntegralColumn<E : Any, T : Comparable<T>>(name: String) :
    IntegralColumn<E, T>(name), Nullable, NullableGroupableColumn<E, T>
class NullableFloatingColumn<E : Any, T : Comparable<T>>(name: String) :
    FloatingColumn<E, T>(name), Nullable, NullableGroupableColumn<E, T>
class NullableGroupableScalarColumn<E : Any, T>(name: String) :
    GroupableScalarColumn<E, T>(name), Nullable, NullableGroupableColumn<E, T>
class NullableEnumColumn<E : Any, T : Enum<T>>(
    name: String,
    fromName: (String) -> T = {
        error("EnumColumn '$name' has no enum-decode metadata; build it via codegen to group by it")
    },
) : EnumColumn<E, T>(name, fromName), Nullable, NullableGroupableColumn<E, T>

/**
 * isNull predicate. Only visible when the receiver column is both a
 * [Column] and [Nullable], giving compile-time rejection of isNull
 * checks on non-nullable fields. The phantom entity scope [E] flows
 * from the receiver into the resulting predicate.
 */
fun <E : Any, C> C.isNull(): Predicate<E> where C : Column<E, *>, C : Nullable =
    Predicate.Leaf(name, Op.IS_NULL, null)

fun <E : Any, C> C.isNotNull(): Predicate<E> where C : Column<E, *>, C : Nullable =
    Predicate.Leaf(name, Op.IS_NOT_NULL, null)

/**
 * Lazily-mapped view of an `IN` / `NOT_IN` operand collection whose
 * elements need a storage transform (an enum's stored `.name`).
 * `size` delegates to the source in O(1) — so a driver bind-capacity
 * check can reject an over-budget operand without materializing it —
 * and elements are transformed only when the view is iterated, which
 * happens strictly after that check (at the runtime's chain-entry
 * snapshot, or at a driver's render). Deliberately not
 * equality-bearing: predicate equality is meaningful on the runtime's
 * snapshots (shape views, frozen specs), which materialize this view
 * into a plain list at chain entry.
 */
private class LazilyMappedOperands<T, R>(
    private val source: Collection<T>,
    private val transform: (T) -> R,
) : AbstractCollection<R>() {
    override val size: Int get() = source.size
    override fun iterator(): Iterator<R> = source.asSequence().map(transform).iterator()
}
