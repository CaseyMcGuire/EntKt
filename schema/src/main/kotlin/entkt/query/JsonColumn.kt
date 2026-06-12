package entkt.query

/**
 * A reference to a typed JSON column. Deliberately
 * **narrow**: addressable for SQL identity and — when nullable — null checks
 * only. It does NOT inherit the scalar equality / membership / ordering /
 * string helpers on [Column], which have no V1 meaning for a whole `jsonb`
 * document (whole-document equality, containment, and path predicates are
 * deferred).
 *
 * [T] is the `@Serializable` Kotlin type exposed at the API boundary; it is a
 * compile-time witness here (the column ref carries no value).
 */
open class JsonColumn<E : Any, T>(val name: String)

/**
 * A nullable JSON column. Adds [isNull] / [isNotNull] as members (rather than
 * the [Nullable]-gated extensions used for [Column]) so a non-null JSON column
 * exposes no predicates at all.
 */
class NullableJsonColumn<E : Any, T>(name: String) : JsonColumn<E, T>(name) {
    fun isNull(): Predicate<E> = Predicate.Leaf(name, Op.IS_NULL, null)
    fun isNotNull(): Predicate<E> = Predicate.Leaf(name, Op.IS_NOT_NULL, null)
}
