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
     * Return a [QueryExplanation] describing the SELECT query the
     * driver would execute for the given parameters, without actually
     * running it. Useful for debugging and logging — the explanation
     * format is driver-specific (e.g. SQL + bind args for Postgres).
     *
     * The default implementation returns an [UnsupportedQueryExplanation].
     * Override this to provide driver-specific detail (e.g. SQL + bind args).
     */
    fun explainQuery(
        table: String,
        predicates: List<Predicate>,
        orderBy: List<OrderField>,
        limit: Int?,
        offset: Int?,
    ): QueryExplanation = UnsupportedQueryExplanation(this::class.simpleName ?: "Driver")

    /**
     * Return a [QueryExplanation] describing the COUNT query the
     * driver would execute for the given parameters, without actually
     * running it. Used by `explainRawCount()` on generated query
     * builders.
     *
     * The default implementation returns an [UnsupportedQueryExplanation].
     * Override this to provide driver-specific detail.
     */
    fun explainCount(
        table: String,
        predicates: List<Predicate>,
    ): QueryExplanation = UnsupportedQueryExplanation(this::class.simpleName ?: "Driver")

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

    /**
     * True when this [Driver] is the transaction-scoped driver passed
     * inside [withTransaction]. False on a normal client-level driver.
     * Generated saves use this at save-start to enforce a configured
     * [entkt.runtime.TransactionRequirement] (RFC #4) without having
     * to thread a separate flag through every layer.
     */
    val inTransaction: Boolean
        get() = false

    // ---------- Owner-row locking capabilities (RFC #4) ----------
    //
    // Generated saves use these capabilities for two distinct purposes
    // that ride on different lock semantics:
    //
    //  - `UpdateConsistency.Pessimistic` updates need a true owner-row
    //    lock — one that blocks ordinary `UPDATE`/`DELETE` from any
    //    transaction until ours commits, so the checked owner state
    //    can't change between rule evaluation and the write.
    //    Implemented via `readRowForUpdate(...)` (`SELECT ... FOR
    //    UPDATE`-equivalent).
    //  - Generated link-table M2M helpers need owner-edge
    //    serialization — they only need to serialize against other
    //    callers using the same discipline (so two concurrent
    //    `tags.set(...)` calls on the same owner can't interleave
    //    junction reads and writes), not against unrelated
    //    `UPDATE`/`DELETE` traffic. On drivers with true row locking,
    //    `readRowForUpdate(...)` strictly satisfies this and the M2M
    //    helpers reuse it; on advisory-only drivers the weaker
    //    `serializeOwnerEdgeAndRead(...)` is sufficient.
    //
    // Capability-rejection happens at the start of `save()` so
    // unsupported combinations surface before hooks, privacy,
    // validation, driver reads, or driver writes.

    /**
     * True when this driver can take a true row lock (`SELECT ... FOR
     * UPDATE`-equivalent) that blocks ordinary `UPDATE`/`DELETE` until
     * the surrounding transaction commits or rolls back. Required by
     * `UpdateConsistency.Pessimistic`; preferred by link-table M2M
     * saves on drivers that support both this and
     * [supportsOwnerEdgeSerialization].
     *
     * **Contract subtlety.** This flag advertises *driver-family*
     * support — "this driver's transaction-scoped sub-driver can take
     * a true row lock." It does *not* say "calling [readRowForUpdate]
     * on this instance right now will succeed." A driver may legally
     * report `true` on its non-transactional root and still throw
     * from [readRowForUpdate] there (e.g. PostgresDriver: the root
     * runs in auto-commit, where the row lock would release
     * immediately, so the root throws but the transaction-scoped
     * sub-driver executes the lock for real). Generated code
     * preflights [inTransaction] before calling, so it never hits
     * that throw; callers reaching for [readRowForUpdate] directly
     * should do the same.
     *
     * Default `false` — subclasses opt in by overriding both this and
     * [readRowForUpdate].
     */
    val supportsReadRowForUpdate: Boolean
        get() = false

    /**
     * Lock the row by id and return its current contents in one
     * logical operation, equivalent to `SELECT ... FOR UPDATE`. The
     * lock must hold until the surrounding transaction commits or
     * rolls back, so that other transactions cannot update or delete
     * the row in between. Returns `null` if no row exists with that id.
     *
     * **Implementation contract.** Implementations that override this
     * method MUST also reject calls when the driver is not in a
     * transaction (i.e. when [inTransaction] is false). The lock
     * semantics require a transaction boundary to bind to; calling
     * this method on a non-transactional driver would either release
     * the lock immediately at statement end (in auto-commit) or have
     * nothing to bind to at all. Generated saves preflight
     * [inTransaction] before calling, but the implementation must
     * defend the contract independently — a future caller that skips
     * the preflight is a programming error and should fail fast.
     * Use [requireTransactionForLocking] if a uniform `IllegalStateException`
     * shape is desired.
     *
     * Implementations that do not support true row-lock semantics must
     * leave [supportsReadRowForUpdate] false; calling this method on
     * such a driver is a programming error and should throw
     * [UnsupportedOperationException]. The default implementation
     * throws.
     */
    fun readRowForUpdate(table: String, id: Any): Map<String, Any?>? =
        throw UnsupportedOperationException(
            "Driver ${this::class.simpleName} does not support readRowForUpdate; " +
                "check supportsReadRowForUpdate before calling.",
        )

    /**
     * True when this driver can serialize owner-edge access against
     * other callers using the same discipline (cooperative locking)
     * and return the owner row in one logical operation. Sufficient
     * for link-table M2M owner-edge serialization, **not** sufficient
     * for `UpdateConsistency.Pessimistic`: a cooperative advisory
     * lock does not block ordinary `UPDATE`/`DELETE` and so cannot
     * provide the `Pessimistic` owner-row stability guarantee.
     *
     * Same contract subtlety as [supportsReadRowForUpdate]: this flag
     * advertises *driver-family* support, not "this instance can
     * call right now." A driver may report `true` on its non-
     * transactional root and still throw from
     * [serializeOwnerEdgeAndRead] there (e.g. PostgresDriver — the
     * `pg_advisory_xact_lock` is xact-scoped, so the root in
     * auto-commit has nothing to bind to). Generated code preflights
     * [inTransaction] before calling.
     *
     * Default `false` — subclasses opt in by overriding both this and
     * [serializeOwnerEdgeAndRead]. A driver that supports
     * [supportsReadRowForUpdate] does *not* automatically satisfy
     * this — link-table M2M saves prefer the true row lock when
     * available, so most drivers will support both flags
     * independently.
     */
    val supportsOwnerEdgeSerialization: Boolean
        get() = false

    /**
     * Serialize owner-edge access keyed by `(table, id)` against other
     * callers using the same discipline, then return the owner row.
     * The serialization must hold from the call through the rest of
     * the save's transaction (current junction read, privacy and
     * validation checks, junction writes) — in practice until the
     * enclosing transaction commits or rolls back. Returns `null` if
     * no row exists with that id.
     *
     * **Implementation contract.** Same as [readRowForUpdate]:
     * implementations that override this method MUST also reject
     * calls when [inTransaction] is false. The serialization token's
     * duration is the surrounding transaction; calling this method on
     * a non-transactional driver leaves nothing for the token to bind
     * to. Use [requireTransactionForLocking] for a uniform
     * `IllegalStateException` shape.
     *
     * Postgres-style implementations bind the serialization token to
     * the transaction (e.g. `pg_advisory_xact_lock`) so the duration
     * requirement is automatic; non-transactional primitives must
     * hold explicitly until transaction end. A driver that does not
     * support this leaves [supportsOwnerEdgeSerialization] false; the
     * default implementation throws.
     */
    fun serializeOwnerEdgeAndRead(table: String, id: Any): Map<String, Any?>? =
        throw UnsupportedOperationException(
            "Driver ${this::class.simpleName} does not support serializeOwnerEdgeAndRead; " +
                "check supportsOwnerEdgeSerialization before calling.",
        )

    /**
     * Helper for [readRowForUpdate] / [serializeOwnerEdgeAndRead]
     * implementations that want a uniform error shape when the driver
     * is asked to lock outside a transaction. Throws
     * [IllegalStateException] when [inTransaction] is false; otherwise
     * does nothing. The interface contract on the lock methods *requires*
     * implementations to reject non-transactional calls — this just
     * standardizes the rejection.
     *
     * Use it at the top of an overriding method:
     *
     * ```kotlin
     * override fun readRowForUpdate(table: String, id: Any): Map<String, Any?>? {
     *     requireTransactionForLocking("readRowForUpdate")
     *     // ... do the locking read
     * }
     * ```
     */
    fun requireTransactionForLocking(method: String) {
        check(inTransaction) {
            "Driver.$method requires a transaction-scoped driver — call inside withTransaction. " +
                "Calling on a non-transactional driver would release the lock immediately " +
                "(in auto-commit) or have no transaction boundary to bind to."
        }
    }

    /**
     * Map a [throwable] thrown from this driver to a structured
     * [EntError] when the driver recognizes it. Returning `null`
     * signals "I don't know what this is" — the caller (typically the
     * `classifyDriverError` helper) will fall back to wrapping it in
     * [EntError.DriverFailure].
     *
     * Generated `*OrError()` wrappers call this AFTER the more
     * specific catch arms (privacy / validation / EntException) but
     * BEFORE a generic Throwable fallback, so:
     *  - the framework's own exceptions (TransactionRequiredException,
     *    UnsupportedDriverCapabilityException, EntException subclasses)
     *    are never offered to the classifier — they always propagate
     *    as themselves, per the RFC Status carve-out;
     *  - everything else (PSQLException, IllegalStateException from
     *    driver-side validators, etc.) gets one shot at being
     *    classified, then falls through to DriverFailure.
     *
     * Implementations should:
     *  - return `EntError.ConstraintViolation` for UNIQUE/FK/CHECK
     *    violations, populating `constraint`/`field`/`code` from
     *    whatever metadata the underlying exception carries
     *    (PostgreSQL's SQLSTATE, the InMemory driver's message
     *    prefix, etc.);
     *  - leave [EntError.Conflict] for a future optimistic-locking
     *    surface — returning `null` for serialization failures is
     *    fine in V1;
     *  - return `null` for any throwable the driver doesn't
     *    recognize, including IO/network errors — the
     *    `classifyDriverError` fallback will wrap those as
     *    [EntError.DriverFailure].
     *
     * The default returns `null` so existing third-party drivers
     * inherit the "raw exception propagates as DriverFailure" V1
     * behavior without having to opt in.
     */
    fun classifyException(
        throwable: Throwable,
        entity: String,
        operation: EntOperation,
    ): EntError? = null
}

/**
 * Wrap an arbitrary [throwable] from a generated `*OrError()` catch
 * arm into an [EntError], using the [driver]'s own classifier first
 * and falling back to [EntError.DriverFailure].
 *
 * The cause is preserved on `EntError.DriverFailure.cause` so the
 * matching [EntDriverException] forwards it to the JVM exception
 * chain — `printStackTrace()` and friends still see the original
 * driver exception.
 *
 * **Re-throws deterministic programming/configuration errors.**
 * [TransactionRequiredException] and
 * [UnsupportedDriverCapabilityException] are not surfaced as
 * `EntError` — per the Result Variants RFC they're errors in how
 * the caller configured the client / drivers, not failures in the
 * operation. Generated `*OrError()` blocks call this from a `catch
 * (Exception)` arm that catches both kinds of throwable; this
 * function re-throws the configuration errors so they escape the
 * `*OrError` path the same way they escape `*OrThrow`. The result
 * type stays `EntError` because that's still the contract for the
 * happy classification path.
 */
public fun classifyDriverError(
    driver: Driver,
    throwable: Throwable,
    entity: String,
    operation: EntOperation,
): EntError {
    if (throwable is TransactionRequiredException) throw throwable
    if (throwable is UnsupportedDriverCapabilityException) throw throwable
    return driver.classifyException(throwable, entity, operation)
        ?: EntError.DriverFailure(entity = entity, operation = operation, cause = throwable)
}