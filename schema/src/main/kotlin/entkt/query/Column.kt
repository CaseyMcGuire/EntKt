package entkt.query

/**
 * Marker interface for nullable columns. Only columns implementing this
 * interface get the [isNull] / [isNotNull] extensions — non-nullable
 * columns reject those checks at compile time.
 */
interface Nullable

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
    open infix fun `in`(values: Collection<T>): Predicate<E> =
        Predicate.Leaf(name, Op.IN, values.toList())
    open infix fun notIn(values: Collection<T>): Predicate<E> =
        Predicate.Leaf(name, Op.NOT_IN, values.toList())
}

/**
 * A column whose type admits ordering. Adds the range predicates and
 * the `asc()` / `desc()` order builders on top of the base equality
 * ops. See the [Column] KDoc for why ordering lives here and not on
 * the base.
 */
open class ComparableColumn<E : Any, T : Comparable<T>>(name: String) : Column<E, T>(name) {
    infix fun gt(value: T): Predicate<E> = Predicate.Leaf(name, Op.GT, value)
    infix fun gte(value: T): Predicate<E> = Predicate.Leaf(name, Op.GTE, value)
    infix fun lt(value: T): Predicate<E> = Predicate.Leaf(name, Op.LT, value)
    infix fun lte(value: T): Predicate<E> = Predicate.Leaf(name, Op.LTE, value)

    fun asc(): OrderField<E> = OrderField(name, OrderDirection.ASC)
    fun desc(): OrderField<E> = OrderField(name, OrderDirection.DESC)
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
open class EnumColumn<E : Any, T : Enum<T>>(name: String) : Column<E, T>(name) {
    override infix fun eq(value: T): Predicate<E> =
        Predicate.Leaf(name, Op.EQ, value.name)
    override infix fun neq(value: T): Predicate<E> =
        Predicate.Leaf(name, Op.NEQ, value.name)
    override infix fun `in`(values: Collection<T>): Predicate<E> =
        Predicate.Leaf(name, Op.IN, values.map { it.name })
    override infix fun notIn(values: Collection<T>): Predicate<E> =
        Predicate.Leaf(name, Op.NOT_IN, values.map { it.name })

    // Enums serialize to their .name string; ordering is alphabetical
    // on the name (not by ordinal). Declared here so EnumColumn can
    // be used in orderBy(...) — the asc/desc helpers don't move down
    // from ComparableColumn because the value type is `T : Enum<T>`,
    // not `T : Comparable<T>`, and we store the .name.
    fun asc(): OrderField<E> = OrderField(name, OrderDirection.ASC)
    fun desc(): OrderField<E> = OrderField(name, OrderDirection.DESC)
}

class NullableColumn<E : Any, T>(name: String) : Column<E, T>(name), Nullable
class NullableComparableColumn<E : Any, T : Comparable<T>>(name: String) :
    ComparableColumn<E, T>(name), Nullable
class NullableStringColumn<E : Any>(name: String) : StringColumn<E>(name), Nullable
class NullableEnumColumn<E : Any, T : Enum<T>>(name: String) : EnumColumn<E, T>(name), Nullable

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
