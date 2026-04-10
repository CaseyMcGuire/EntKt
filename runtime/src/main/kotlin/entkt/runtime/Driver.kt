package entkt.runtime

import entkt.query.OrderField
import entkt.query.Predicate

/**
 * The runtime an `EntClient` talks to. Generated repos forward every
 * I/O operation through this interface, so production code, tests, and
 * demos all swap drivers without changing call sites.
 *
 * Drivers are schema-aware but type-agnostic: rows are passed in and
 * out as `Map<String, Any?>` keyed by snake_case column name. Typed
 * conversion happens in the generated `fromRow` / builder code, not in
 * the driver — that keeps drivers from depending on generated entity
 * classes.
 *
 * Implementations register each entity's [EntitySchema] up front (the
 * generated repo's `init` block does this), so by the time `query` or
 * `insert` is called, the driver already knows the table layout, the
 * id strategy, and how to walk edges.
 */
interface Driver {
    /**
     * Tell the driver about an entity's table layout. Idempotent —
     * registering the same schema twice should be a no-op (generated
     * repos don't coordinate with each other).
     */
    fun register(schema: EntitySchema)

    /**
     * Insert a row. The map's keys are snake_case column names; the id
     * column may be absent (driver mints one) or present (driver
     * stores as-is). Returns the persisted row, including the assigned
     * id, so the caller can hand it to `fromRow`.
     */
    fun insert(table: String, values: Map<String, Any?>): Map<String, Any?>

    /**
     * Update a row by id. Returns the new row state on success, or
     * `null` if no row exists with that id. Generated `save()` returns
     * `null` to its caller in that case; `saveOrThrow()` throws.
     */
    fun update(table: String, id: Any, values: Map<String, Any?>): Map<String, Any?>?

    /** Look up one row by primary key. */
    fun byId(table: String, id: Any): Map<String, Any?>?

    /**
     * Run a query. Predicates are AND-ed together (the generated query
     * accumulates them as a list and the driver folds them). The
     * driver applies `orderBy` then `offset`/`limit` after filtering.
     */
    fun query(
        table: String,
        predicates: List<Predicate>,
        orderBy: List<OrderField>,
        limit: Int?,
        offset: Int?,
    ): List<Map<String, Any?>>

    /** Returns true if a row was actually removed. */
    fun delete(table: String, id: Any): Boolean
}