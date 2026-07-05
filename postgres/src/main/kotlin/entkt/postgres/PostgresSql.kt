package entkt.postgres

import entkt.query.Predicate
import entkt.runtime.driver.EntitySchema
import entkt.schema.ColumnStorage
import entkt.schema.FieldType

/**
 * Wrap an identifier in PG's `"..."` quoting and escape embedded
 * quotes defensively. Most identifiers originate in generated
 * schema metadata, but callers can still construct raw predicates
 * and order fields by hand.
 */
internal fun quote(identifier: String): String =
    "\"${identifier.replace("\"", "\"\"")}\""

/**
 * One positional `?` binding: the column's [FieldType] (null when the
 * column is unknown to the registered schema) and the raw value.
 */
internal data class Param(val type: FieldType?, val value: Any?)

/** A rendered SQL statement plus its positional parameters, in placeholder order. */
internal data class PreparedSql(val sql: String, val params: List<Param>)

/**
 * AND together a list of erased predicates into a single erased
 * predicate, or null when the list is empty. Used by the SQL builders
 * to combine the driver-call's `List<Predicate<*>>` into a single
 * predicate tree for [PredicateSqlBuilder.lower].
 *
 * The runtime representation of `Predicate.And<E>(left, right)` is a
 * plain wrapper that doesn't inspect E, so combining two erased
 * predicates into an erased And is sound — drivers consume predicates
 * structurally (field/op/value/edge name/source table) and don't
 * introspect the phantom type. The unchecked cast localizes the
 * "phantom doesn't matter at the driver" invariant to one place.
 */
@Suppress("UNCHECKED_CAST")
internal fun List<Predicate<*>>.andTogether(): Predicate<*>? =
    reduceOrNull { left, right ->
        Predicate.And(left as Predicate<Any>, right as Predicate<Any>)
    }

/** [FieldType] of the named column, or null when it isn't on the schema. */
internal fun EntitySchema.columnType(name: String): FieldType? =
    columns.firstOrNull { it.name == name }?.type

/** Native storage of the named column (e.g. pgvector), or null. */
internal fun EntitySchema.nativeStorage(name: String): ColumnStorage.Native? =
    columns.firstOrNull { it.name == name }?.storage as? ColumnStorage.Native

/** [FieldType] of the primary-key column. */
internal val EntitySchema.idType: FieldType?
    get() = columns.firstOrNull { it.name == idColumn }?.type

/** Reject a [entkt.postgres.vector.PgVector] whose dimension doesn't match a native vector column. */
internal fun checkVectorDimensions(native: ColumnStorage.Native?, value: Any?, label: String) {
    if (native != null && native.codec == "postgres.vector" && value is entkt.postgres.vector.PgVector) {
        require(value.dimensions == native.dimensions) {
            "$label expects a ${native.dimensions}-dimensional vector, got ${value.dimensions}"
        }
    }
}
