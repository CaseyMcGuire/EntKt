package entkt.explorer

import entkt.query.Op
import entkt.query.Predicate
import entkt.schema.FieldType
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Translates a validated [EntExplorerFilter] into a typed predicate for the
 * generated query API. Generated adapters delegate here so the op-per-type
 * and value-parsing rules live (and are tested) once, in the core, instead
 * of being re-emitted into every adapter.
 *
 * Everything unsupported throws [EntExplorerBadRequestException] — before any
 * query executes — even though [EntExplorer] pre-validates most of it; the
 * adapter surface is callable by any host, so this layer trusts nothing.
 *
 * The produced [Predicate.Leaf] nodes are the same representation the
 * generated column helpers build, fed through the generated query builder's
 * `where(...)` — so read privacy, interceptors, and soft-delete filters all
 * apply exactly as they would for an application query.
 */
object EntExplorerFilters {

    private val COMPARISON_OPS = setOf(
        EntExplorerFilterOp.EQ, EntExplorerFilterOp.NEQ,
        EntExplorerFilterOp.GT, EntExplorerFilterOp.GTE,
        EntExplorerFilterOp.LT, EntExplorerFilterOp.LTE,
    )
    private val EQUALITY_OPS = setOf(EntExplorerFilterOp.EQ, EntExplorerFilterOp.NEQ)
    private val STRING_OPS = COMPARISON_OPS +
        setOf(EntExplorerFilterOp.CONTAINS, EntExplorerFilterOp.PREFIX, EntExplorerFilterOp.SUFFIX)

    fun <E : Any> predicate(
        column: EntExplorerColumn,
        filter: EntExplorerFilter,
        /** Enum constant names for ENUM columns; used to validate the value. */
        enumNames: Set<String>? = null,
    ): Predicate<E> {
        if (!column.filterable) {
            throw EntExplorerBadRequestException("Column '${column.name}' is not filterable.")
        }
        when (filter.op) {
            EntExplorerFilterOp.IS_NULL, EntExplorerFilterOp.NOT_NULL -> {
                if (!column.nullable) {
                    throw EntExplorerBadRequestException("Column '${column.name}' is not nullable.")
                }
                val op = if (filter.op == EntExplorerFilterOp.IS_NULL) Op.IS_NULL else Op.IS_NOT_NULL
                return Predicate.Leaf(column.name, op, null)
            }
            else -> {}
        }
        val raw = filter.value
            ?: throw EntExplorerBadRequestException("Filter op '${filter.op.token}' needs a value.")

        val supported = supportedOps(column.type)
        if (filter.op !in supported) {
            throw EntExplorerBadRequestException(
                "Op '${filter.op.token}' is not supported on ${column.type.name.lowercase()} column '${column.name}'.",
            )
        }
        val value: Any = when (column.type) {
            FieldType.STRING -> raw
            FieldType.BOOL -> when (raw) {
                "true" -> true
                "false" -> false
                else -> bad(column, raw, "true|false")
            }
            FieldType.INT -> raw.toIntOrNull() ?: bad(column, raw, "an integer")
            FieldType.LONG -> raw.toLongOrNull() ?: bad(column, raw, "an integer")
            FieldType.FLOAT -> raw.toFloatOrNull() ?: bad(column, raw, "a number")
            FieldType.DOUBLE -> raw.toDoubleOrNull() ?: bad(column, raw, "a number")
            FieldType.INSTANT -> try {
                Instant.parse(raw)
            } catch (_: Exception) {
                bad(column, raw, "an ISO-8601 instant")
            }
            FieldType.DATE -> try {
                LocalDate.parse(raw)
            } catch (_: java.time.format.DateTimeParseException) {
                bad(column, raw, "an ISO-8601 date")
            }
            FieldType.UUID -> try {
                UUID.fromString(raw)
            } catch (_: Exception) {
                bad(column, raw, "a UUID")
            }
            FieldType.ENUM -> {
                if (enumNames != null && raw !in enumNames) {
                    throw EntExplorerBadRequestException(
                        "'$raw' is not a ${column.name} constant (expected one of ${enumNames.sorted().joinToString(", ")}).",
                    )
                }
                raw // enums persist and bind as their constant name
            }
            FieldType.BYTES, FieldType.JSON, FieldType.PGVECTOR ->
                throw EntExplorerBadRequestException("Column '${column.name}' is display-only.")
        }
        return Predicate.Leaf(column.name, filterOpToQueryOp(filter.op), value)
    }

    /** Ops the explorer supports per field type; display-only types support none. */
    fun supportedOps(type: FieldType): Set<EntExplorerFilterOp> = when (type) {
        FieldType.STRING -> STRING_OPS
        FieldType.INT, FieldType.LONG, FieldType.FLOAT, FieldType.DOUBLE,
        FieldType.INSTANT, FieldType.DATE -> COMPARISON_OPS
        FieldType.BOOL, FieldType.UUID, FieldType.ENUM -> EQUALITY_OPS
        FieldType.BYTES, FieldType.JSON, FieldType.PGVECTOR -> emptySet()
    }

    private fun filterOpToQueryOp(op: EntExplorerFilterOp): Op = when (op) {
        EntExplorerFilterOp.EQ -> Op.EQ
        EntExplorerFilterOp.NEQ -> Op.NEQ
        EntExplorerFilterOp.GT -> Op.GT
        EntExplorerFilterOp.GTE -> Op.GTE
        EntExplorerFilterOp.LT -> Op.LT
        EntExplorerFilterOp.LTE -> Op.LTE
        EntExplorerFilterOp.CONTAINS -> Op.CONTAINS
        EntExplorerFilterOp.PREFIX -> Op.HAS_PREFIX
        EntExplorerFilterOp.SUFFIX -> Op.HAS_SUFFIX
        EntExplorerFilterOp.IS_NULL -> Op.IS_NULL
        EntExplorerFilterOp.NOT_NULL -> Op.IS_NOT_NULL
    }

    private fun bad(column: EntExplorerColumn, raw: String, expected: String): Nothing =
        throw EntExplorerBadRequestException("'$raw' is not $expected (column '${column.name}').")
}
