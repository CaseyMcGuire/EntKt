@file:OptIn(entkt.query.EntktInternal::class)

package entkt.runtime.query

import entkt.query.OrderField
import entkt.query.Predicate
import entkt.query.TraversalSourceShape
import java.lang.reflect.Array as ReflectArray
import java.math.BigDecimal
import java.math.BigInteger

/** Detach a predicate tree and every mutable value it carries. */
@Suppress("UNCHECKED_CAST")
internal fun <E : Any> Predicate<E>.semanticSnapshot(): Predicate<E> = when (this) {
    is Predicate.Leaf -> copy(value = value.semanticSnapshot())
    is Predicate.And -> Predicate.And(left.semanticSnapshot(), right.semanticSnapshot())
    is Predicate.Or -> Predicate.Or(left.semanticSnapshot(), right.semanticSnapshot())
    is Predicate.HasEdge -> this
    is Predicate.HasEdgeWith<*, *> -> {
        val typed = this as Predicate.HasEdgeWith<E, Any>
        Predicate.HasEdgeWith(typed.edge, typed.inner.semanticSnapshot())
    }
    is Predicate.HasM2MEdgeFrom<*, *> -> {
        val typed = this as Predicate.HasM2MEdgeFrom<E, Any>
        Predicate.HasM2MEdgeFrom(
            typed.sourceTable,
            typed.edgeName,
            typed.sourceFilter?.semanticSnapshot(),
        )
    }
    is Predicate.HasEdgeFromShape<*, *> -> {
        val typed = this as Predicate.HasEdgeFromShape<E, Any>
        Predicate.HasEdgeFromShape(typed.edge, typed.source.semanticSnapshot())
    }
    is Predicate.HasM2MEdgeFromShape<*, *> -> {
        val typed = this as Predicate.HasM2MEdgeFromShape<E, Any>
        Predicate.HasM2MEdgeFromShape(typed.edgeName, typed.source.semanticSnapshot())
    }
}

/** Detach a shaped traversal source and its typed query values. */
internal fun <E : Any> TraversalSourceShape<E>.semanticSnapshot(): TraversalSourceShape<E> = copy(
    predicates = predicates.map { it.semanticSnapshot() },
    orderBy = orderBy.map { it.semanticSnapshot() },
    flags = flags.toSet(),
)

/** Detach mutable operands carried by an ordering expression. */
internal fun <E : Any> OrderField<E>.semanticSnapshot(): OrderField<E> {
    val currentDistance = distance
    return copy(
        distance = currentDistance?.copy(
            operand = currentDistance.operand.semanticSnapshot() ?: currentDistance.operand,
        ),
    )
}

/** Copy the mutable value carriers accepted by the query DSL. */
private fun Any?.semanticSnapshot(): Any? = when (this) {
    null -> null
    is ByteArray -> copyOf()
    is ShortArray -> copyOf()
    is IntArray -> copyOf()
    is LongArray -> copyOf()
    is FloatArray -> copyOf()
    is DoubleArray -> copyOf()
    is CharArray -> copyOf()
    is BooleanArray -> copyOf()
    is Array<*> -> {
        val snapshot = ReflectArray.newInstance(javaClass.componentType, size)
        for (index in indices) ReflectArray.set(snapshot, index, this[index].semanticSnapshot())
        snapshot
    }
    is List<*> -> map { it.semanticSnapshot() }
    is Set<*> -> mapTo(linkedSetOf()) { it.semanticSnapshot() }
    is Collection<*> -> map { it.semanticSnapshot() }
    is Map<*, *> -> entries.associateTo(linkedMapOf()) {
        it.key.semanticSnapshot() to it.value.semanticSnapshot()
    }
    is Pair<*, *> -> first.semanticSnapshot() to second.semanticSnapshot()
    is Triple<*, *, *> -> Triple(
        first.semanticSnapshot(),
        second.semanticSnapshot(),
        third.semanticSnapshot(),
    )
    // The primitive wrappers plus BigInteger/BigDecimal are immutable. Raw
    // low-level predicates may also carry mutable Number implementations.
    is Byte, is Short, is Int, is Long, is Float, is Double, is BigInteger, is BigDecimal -> this
    is Number -> try {
        BigDecimal(toString())
    } catch (e: NumberFormatException) {
        throw IllegalArgumentException(
            "Cannot freeze mutable predicate Number ${this::class.qualifiedName}: '$this' is not a stable numeric value",
            e,
        )
    }
    else -> this
}
