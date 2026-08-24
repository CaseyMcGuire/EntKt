package entkt.runtime.mutation

import entkt.query.ColumnReference
import entkt.query.EntktInternal

/** Tracks which generated draft fields were explicitly assigned by a caller or hook. */
@EntktInternal
class AssignedFields<Entity : Any> {
    private val columnNames = mutableSetOf<String>()

    /** Record an explicit assignment, including an assignment of null. */
    fun mark(column: ColumnReference<Entity>) {
        columnNames += column.name
    }

    /** Forget an assignment so an update leaves the column unchanged. */
    fun unmark(column: ColumnReference<Entity>) {
        columnNames -= column.name
    }

    /** Return whether the column has been explicitly assigned. */
    operator fun contains(column: ColumnReference<Entity>): Boolean = column.name in columnNames
}
