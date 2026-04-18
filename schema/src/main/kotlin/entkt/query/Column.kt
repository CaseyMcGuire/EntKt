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
 * membership) plus ordering helpers.
 *
 * Column refs are emitted on the generated entity's companion object,
 * e.g. `User.active`, `User.age`, `User.email`.
 */
open class Column<T>(val name: String) {
    open infix fun eq(value: T): Predicate = Predicate.Leaf(name, Op.EQ, value)
    open infix fun neq(value: T): Predicate = Predicate.Leaf(name, Op.NEQ, value)
    open infix fun `in`(values: Collection<T>): Predicate =
        Predicate.Leaf(name, Op.IN, values.toList())
    open infix fun notIn(values: Collection<T>): Predicate =
        Predicate.Leaf(name, Op.NOT_IN, values.toList())

    fun asc(): OrderField = OrderField(name, OrderDirection.ASC)
    fun desc(): OrderField = OrderField(name, OrderDirection.DESC)
}

/**
 * A column whose type admits ordering. Adds the range predicates on top
 * of the base equality ops.
 */
open class ComparableColumn<T : Comparable<T>>(name: String) : Column<T>(name) {
    infix fun gt(value: T): Predicate = Predicate.Leaf(name, Op.GT, value)
    infix fun gte(value: T): Predicate = Predicate.Leaf(name, Op.GTE, value)
    infix fun lt(value: T): Predicate = Predicate.Leaf(name, Op.LT, value)
    infix fun lte(value: T): Predicate = Predicate.Leaf(name, Op.LTE, value)
}

/**
 * A string column. Adds substring/prefix/suffix search on top of the
 * comparable ops.
 */
open class StringColumn(name: String) : ComparableColumn<String>(name) {
    infix fun contains(value: String): Predicate =
        Predicate.Leaf(name, Op.CONTAINS, value)
    infix fun hasPrefix(value: String): Predicate =
        Predicate.Leaf(name, Op.HAS_PREFIX, value)
    infix fun hasSuffix(value: String): Predicate =
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
 */
open class EnumColumn<E : Enum<E>>(name: String) : Column<E>(name) {
    override infix fun eq(value: E): Predicate = Predicate.Leaf(name, Op.EQ, value.name)
    override infix fun neq(value: E): Predicate = Predicate.Leaf(name, Op.NEQ, value.name)
    override infix fun `in`(values: Collection<E>): Predicate =
        Predicate.Leaf(name, Op.IN, values.map { it.name })
    override infix fun notIn(values: Collection<E>): Predicate =
        Predicate.Leaf(name, Op.NOT_IN, values.map { it.name })
}

class NullableColumn<T>(name: String) : Column<T>(name), Nullable
class NullableComparableColumn<T : Comparable<T>>(name: String) :
    ComparableColumn<T>(name), Nullable
class NullableStringColumn(name: String) : StringColumn(name), Nullable
class NullableEnumColumn<E : Enum<E>>(name: String) : EnumColumn<E>(name), Nullable

/**
 * isNull predicate. Only visible when the receiver column is both a
 * [Column] and [Nullable], giving compile-time rejection of isNull
 * checks on non-nullable fields.
 */
fun <C> C.isNull(): Predicate where C : Column<*>, C : Nullable =
    Predicate.Leaf(name, Op.IS_NULL, null)

fun <C> C.isNotNull(): Predicate where C : Column<*>, C : Nullable =
    Predicate.Leaf(name, Op.IS_NOT_NULL, null)