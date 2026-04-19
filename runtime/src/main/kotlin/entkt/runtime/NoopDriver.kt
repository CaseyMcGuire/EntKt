package entkt.runtime

import entkt.query.OrderField
import entkt.query.Predicate

/**
 * A driver that only exists to satisfy the `Driver` constructor
 * parameter on generated query classes when they're being used purely
 * for predicate construction (e.g. inside `EdgeRef.has { }`).
 *
 * The generated entity companion emits
 *
 * ```
 * val posts: EdgeRef<Post, PostQuery> = EdgeRef("posts") { PostQuery(NoopDriver) }
 * ```
 *
 * and `EdgeRef.has { block }` runs [block] against that query to collect
 * its `combinedPredicate()`. It never actually calls `query` / `insert`
 * / etc. — so those methods throw here. If you hit one of these errors,
 * you've almost certainly called a terminal op (`all`, `firstOrNull`,
 * `save`) inside a `has { ... }` block, which isn't meaningful.
 */
object NoopDriver : Driver {
    override fun register(schema: EntitySchema) {
        // Registering a schema is harmless — the Noop driver just
        // ignores it. This lets a query class safely be constructed
        // even if its init path ever grows to register eagerly.
    }

    override fun insert(table: String, values: Map<String, Any?>): Map<String, Any?> =
        error("NoopDriver cannot insert — was a terminal op called inside EdgeRef.has { }?")

    override fun update(
        table: String,
        id: Any,
        values: Map<String, Any?>,
    ): Map<String, Any?>? =
        error("NoopDriver cannot update — was a terminal op called inside EdgeRef.has { }?")

    override fun byId(table: String, id: Any): Map<String, Any?>? =
        error("NoopDriver cannot byId — was a terminal op called inside EdgeRef.has { }?")

    override fun query(
        table: String,
        predicates: List<Predicate>,
        orderBy: List<OrderField>,
        limit: Int?,
        offset: Int?,
    ): List<Map<String, Any?>> =
        error("NoopDriver cannot query — was a terminal op called inside EdgeRef.has { }?")

    override fun count(table: String, predicates: List<Predicate>): Long =
        error("NoopDriver cannot count — was a terminal op called inside EdgeRef.has { }?")

    override fun exists(table: String, predicates: List<Predicate>): Boolean =
        error("NoopDriver cannot exists — was a terminal op called inside EdgeRef.has { }?")

    override fun upsert(
        table: String,
        values: Map<String, Any?>,
        conflictColumns: List<String>,
        immutableColumns: List<String>,
    ): UpsertResult =
        error("NoopDriver cannot upsert — was a terminal op called inside EdgeRef.has { }?")

    override fun delete(table: String, id: Any): Boolean =
        error("NoopDriver cannot delete — was a terminal op called inside EdgeRef.has { }?")

    override fun <T> withTransaction(block: (Driver) -> T): T =
        error("NoopDriver cannot start a transaction — was withTransaction called inside EdgeRef.has { }?")
}
