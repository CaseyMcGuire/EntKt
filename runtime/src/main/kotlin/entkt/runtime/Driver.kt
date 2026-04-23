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

    /**
     * Count rows matching [predicates]. Predicates are AND-ed together,
     * same as [query]. Returns zero for an empty or unmatched table.
     */
    fun count(table: String, predicates: List<Predicate>): Long

    /**
     * Return true if at least one row matches [predicates]. Semantically
     * equivalent to `count(...) > 0` but drivers can short-circuit.
     */
    fun exists(table: String, predicates: List<Predicate>): Boolean

    /** Returns true if a row was actually removed. */
    fun delete(table: String, id: Any): Boolean

    /**
     * Insert multiple rows in a single batch. Returns the persisted rows
     * in the same order as [values], each with its assigned id. Drivers
     * should use an efficient batch strategy (e.g. multi-row `INSERT`).
     *
     * This is a low-level driver method that does not fire lifecycle
     * hooks. The generated `createMany` repo method delegates to
     * `create { }.save()` per row so hooks fire for every entity.
     */
    fun insertMany(table: String, values: List<Map<String, Any?>>): List<Map<String, Any?>>

    /**
     * Update all rows matching [predicates] with the same [values].
     * Predicates are AND-ed together, same as [query]. Returns the
     * number of rows updated.
     *
     * This is a low-level driver method that does not fire lifecycle
     * hooks. No generated repo method wraps this — callers who need
     * per-row hooks should loop over [update].
     */
    fun updateMany(table: String, values: Map<String, Any?>, predicates: List<Predicate>): Int

    /**
     * Delete all rows matching [predicates]. Predicates are AND-ed
     * together, same as [query]. Returns the number of rows deleted.
     *
     * This is a low-level driver method that does not fire lifecycle
     * hooks. The generated `deleteMany` repo method queries matching
     * entities and deletes each through `delete(entity)` so hooks fire.
     */
    fun deleteMany(table: String, predicates: List<Predicate>): Int

    /**
     * Run [block] inside a transaction. The block receives a
     * transaction-scoped [Driver] that shares a single underlying
     * connection / snapshot. If [block] completes normally the
     * transaction is committed; if it throws the transaction is rolled
     * back and the exception propagates.
     *
     * Calling [withTransaction] on an already-transactional driver
     * reuses the existing transaction (no savepoints).
     *
     * The driver passed to [block] is only valid for the duration of
     * the block — using it after the block returns will throw.
     */
    fun <T> withTransaction(block: (Driver) -> T): T
}