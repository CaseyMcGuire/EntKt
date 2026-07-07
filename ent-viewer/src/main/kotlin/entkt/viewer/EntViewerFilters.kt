package entkt.viewer

import entkt.query.Op
import entkt.query.Predicate
import entkt.schema.FieldType
import java.time.Instant
import java.util.UUID

/**
 * Translates a validated [EntViewerFilter] into a typed predicate for the
 * generated query API. Generated adapters delegate here so the op-per-type
 * and value-parsing rules live (and are tested) once, in the core, instead
 * of being re-emitted into every adapter.
 *
 * Everything unsupported throws [EntViewerBadRequestException] — before any
 * query executes — even though [EntViewer] pre-validates most of it; the
 * adapter surface is callable by any host, so this layer trusts nothing.
 *
 * The produced [Predicate.Leaf] nodes are the same representation the
 * generated column helpers build, fed through the generated query builder's
 * `where(...)` — so read privacy, interceptors, and soft-delete filters all
 * apply exactly as they would for an application query.
 */
object EntViewerFilters {

    private val COMPARISON_OPS = setOf(
        EntViewerFilterOp.EQ, EntViewerFilterOp.NEQ,
        EntViewerFilterOp.GT, EntViewerFilterOp.GTE,
        EntViewerFilterOp.LT, EntViewerFilterOp.LTE,
    )
    private val EQUALITY_OPS = setOf(EntViewerFilterOp.EQ, EntViewerFilterOp.NEQ)
    private val STRING_OPS = COMPARISON_OPS +
        setOf(EntViewerFilterOp.CONTAINS, EntViewerFilterOp.PREFIX, EntViewerFilterOp.SUFFIX)

    fun <E : Any> predicate(
        column: EntViewerColumn,
        filter: EntViewerFilter,
        /** Enum constant names for ENUM columns; used to validate the value. */
        enumNames: Set<String>? = null,
    ): Predicate<E> {
        if (!column.filterable) {
            throw EntViewerBadRequestException("Column '${column.name}' is not filterable.")
        }
        when (filter.op) {
            EntViewerFilterOp.IS_NULL, EntViewerFilterOp.NOT_NULL -> {
                if (!column.nullable) {
                    throw EntViewerBadRequestException("Column '${column.name}' is not nullable.")
                }
                val op = if (filter.op == EntViewerFilterOp.IS_NULL) Op.IS_NULL else Op.IS_NOT_NULL
                return Predicate.Leaf(column.name, op, null)
            }
            else -> {}
        }
        val raw = filter.value
            ?: throw EntViewerBadRequestException("Filter op '${filter.op.token}' needs a value.")

        val supported = supportedOps(column.type)
        if (filter.op !in supported) {
            throw EntViewerBadRequestException(
                "Op '${filter.op.token}' is not supported on ${column.type.name.lowercase()} column '${column.name}'.",
            )
        }
        val value: Any = when (column.type) {
            FieldType.STRING, FieldType.TEXT -> raw
            FieldType.BOOL -> when (raw) {
                "true" -> true
                "false" -> false
                else -> bad(column, raw, "true|false")
            }
            FieldType.INT -> raw.toIntOrNull() ?: bad(column, raw, "an integer")
            FieldType.LONG -> raw.toLongOrNull() ?: bad(column, raw, "an integer")
            FieldType.FLOAT -> raw.toFloatOrNull() ?: bad(column, raw, "a number")
            FieldType.DOUBLE -> raw.toDoubleOrNull() ?: bad(column, raw, "a number")
            FieldType.TIME -> try {
                Instant.parse(raw)
            } catch (_: Exception) {
                bad(column, raw, "an ISO-8601 instant")
            }
            FieldType.UUID -> try {
                UUID.fromString(raw)
            } catch (_: Exception) {
                bad(column, raw, "a UUID")
            }
            FieldType.ENUM -> {
                if (enumNames != null && raw !in enumNames) {
                    throw EntViewerBadRequestException(
                        "'$raw' is not a ${column.name} constant (expected one of ${enumNames.sorted().joinToString(", ")}).",
                    )
                }
                raw // enums persist and bind as their constant name
            }
            FieldType.BYTES, FieldType.JSON, FieldType.PGVECTOR ->
                throw EntViewerBadRequestException("Column '${column.name}' is display-only.")
        }
        return Predicate.Leaf(column.name, filterOpToQueryOp(filter.op), value)
    }

    /** Ops the viewer supports per field type; display-only types support none. */
    fun supportedOps(type: FieldType): Set<EntViewerFilterOp> = when (type) {
        FieldType.STRING, FieldType.TEXT -> STRING_OPS
        FieldType.INT, FieldType.LONG, FieldType.FLOAT, FieldType.DOUBLE, FieldType.TIME -> COMPARISON_OPS
        FieldType.BOOL, FieldType.UUID, FieldType.ENUM -> EQUALITY_OPS
        FieldType.BYTES, FieldType.JSON, FieldType.PGVECTOR -> emptySet()
    }

    private fun filterOpToQueryOp(op: EntViewerFilterOp): Op = when (op) {
        EntViewerFilterOp.EQ -> Op.EQ
        EntViewerFilterOp.NEQ -> Op.NEQ
        EntViewerFilterOp.GT -> Op.GT
        EntViewerFilterOp.GTE -> Op.GTE
        EntViewerFilterOp.LT -> Op.LT
        EntViewerFilterOp.LTE -> Op.LTE
        EntViewerFilterOp.CONTAINS -> Op.CONTAINS
        EntViewerFilterOp.PREFIX -> Op.HAS_PREFIX
        EntViewerFilterOp.SUFFIX -> Op.HAS_SUFFIX
        EntViewerFilterOp.IS_NULL -> Op.IS_NULL
        EntViewerFilterOp.NOT_NULL -> Op.IS_NOT_NULL
    }

    private fun bad(column: EntViewerColumn, raw: String, expected: String): Nothing =
        throw EntViewerBadRequestException("'$raw' is not $expected (column '${column.name}').")
}
