package entkt.runtime.driver
import entkt.runtime.result.EntMutationException
import entkt.runtime.result.EntOperation
import entkt.runtime.mutation.RelationshipLockKey
import entkt.runtime.query.QueryExplanation
import entkt.runtime.query.UnsupportedQueryExplanation
import entkt.runtime.mutation.UnsupportedDriverCapabilityException
import entkt.runtime.query.AggregateResultRow
import entkt.runtime.query.AggregateFunction

import entkt.query.OrderField
import entkt.query.Op
import entkt.query.Predicate

/**
 * The runtime an `EntClient` talks to. Generated repos forward every
 * I/O operation through this interface, so production code, tests, and
 * demos all swap drivers without changing call sites.
 *
 * Drivers are schema-aware but generated-entity-agnostic: rows are passed in
 * and out as `Map<String, Any?>` keyed by snake_case column name. Generated
 * `fromRow` / builder code owns the entity facade, while drivers apply
 * registered storage codecs such as typed JSON without depending on generated
 * entity classes.
 *
 * Implementations register every entity's [EntitySchema] up front (the
 * generated `EntClient` calls [registerAll] with the complete set while
 * initializing its driver property), so by the time `query` or `insert`
 * is called, the driver already knows the table layout, the id
 * strategy, and how to walk edges.
 */
interface Driver {
    /**
     * Tell the driver about the complete set of entities it will serve.
     *
     * The generated `EntClient` calls this once with `SCHEMAS` before
     * constructing any repo, so a driver that materializes storage sees
     * the whole set at once rather than one schema at a time.
     *
     * Implementations that emit DDL **must** create every table before
     * adding any constraint that spans tables. Foreign keys between
     * mutually-referencing entities have no valid one-at-a-time
     * ordering — whichever table is created first would reference one
     * that doesn't exist yet — so the batch is what makes such a schema
     * expressible at all.
     *
     * **Must be free of I/O when there is nothing new.** Generated
     * clients are constructed constantly — `withTransaction` builds one
     * *inside* the open transaction, `withPrivacyContext` builds one per
     * call — and each construction re-registers. When every incoming
     * schema is already registered and unchanged, this must return
     * without opening a connection or issuing a statement.
     *
     * Deliberately abstract rather than defaulted to
     * `schemas.forEach(::register)`: a decorating driver that forwards
     * `register` but inherits a sequential default would silently
     * dissolve the batch back into one-at-a-time calls, taking the
     * cross-table ordering guarantee with it. Implementations have to
     * make that choice explicitly.
     */
    fun registerAll(schemas: List<EntitySchema>)

    /**
     * Tell the driver about one entity's table layout. Idempotent —
     * registering the same schema twice is a no-op; registering a
     * *different* schema for a table already claimed should fail.
     *
     * Prefer [registerAll]: a lone registration can't resolve a
     * foreign key whose target hasn't been registered yet.
     */
    fun register(schema: EntitySchema)

    /**
     * Return the registered primary-key column for [table].
     *
     * Implementations must reject an unregistered table. This small metadata
     * lookup lets default driver operations validate an ID-scoped request
     * without trusting a raw column name supplied by a low-level caller.
     */
    fun registeredIdColumn(table: String): String

    /**
     * Return a detached copy of one typed-JSON [value] using the same mapper
     * configuration this driver uses for [table].[column]. Generated privacy
     * and validation item snapshotting calls this so mutable JSON graphs cannot
     * alter the pending database value or a later rule's snapshot.
     *
     * `null` must round-trip as `null`; a non-null result must retain the
     * column's generated Kotlin type. Drivers that support typed JSON must
     * override this method. The default fails explicitly so a driver cannot
     * silently claim snapshot isolation while returning an aliased object.
     */
    fun <T> copyJsonValue(table: String, column: String, value: T): T =
        if (value == null) {
            value
        } else {
            throw UnsupportedDriverCapabilityException(
                "driver ${this::class.simpleName} cannot copy typed JSON values for lifecycle snapshots",
            )
        }

    /**
     * Whether this driver can bind/decode/render the native storage [codec]
     * (e.g. `"postgres.vector"`). Default `false`: a driver supports a native
     * codec only by overriding this. Generated repos check it in `register`
     * for every `ColumnStorage.Native` column and fail construction with
     * [UnsupportedDriverCapabilityException] when a needed codec is
     * unsupported, so an incompatible schema is rejected up front rather than
     * at first read/write.
     */
    fun supportsNativeStorage(codec: String): Boolean = false

    /**
     * Reject a schema whose native-storage columns use a codec this driver
     * doesn't [supportsNativeStorage]. Driver `register` implementations call
     * this so an incompatible schema fails at `EntClient` construction (the
     * generated repo registers in its `init`) rather than at first read/write.
     */
    fun checkNativeStorageSupported(schema: EntitySchema) {
        for (col in schema.columns) {
            val storage = col.storage
            if (storage is entkt.schema.ColumnStorage.Native && !supportsNativeStorage(storage.codec)) {
                throw UnsupportedDriverCapabilityException(
                    "${schema.table}.${col.name} uses ${storage.dialect} ${storage.sqlType}, but " +
                        "${this::class.simpleName} does not support codec '${storage.codec}'",
                )
            }
        }
    }

    /**
     * Whether this driver can store typed JSON columns (`FieldType.JSON`).
     * Defaults to false; Postgres overrides to true.
     */
    fun supportsTypedJson(): Boolean = false

    /**
     * Validate a schema's typed JSON columns at `register` time so problems
     * fail at `EntClient` construction rather than at first read/write:
     *  - a JSON column on a driver that doesn't [supportsTypedJson] is rejected;
     *  - a JSON column must carry its [ColumnMetadata.json] serializer metadata
     *    (the generated schema attaches it; a hand-built one must too);
     *  - a non-JSON column must NOT carry JSON metadata.
     */
    fun checkTypedJsonSupported(schema: EntitySchema) {
        for (col in schema.columns) {
            val isJson = col.type == entkt.schema.FieldType.JSON
            if (isJson && !supportsTypedJson()) {
                throw UnsupportedDriverCapabilityException(
                    "${schema.table}.${col.name} is a typed JSON column, but " +
                        "${this::class.simpleName} does not support typed JSON storage",
                )
            }
            if (isJson && col.json == null) {
                throw IllegalStateException(
                    "${schema.table}.${col.name} is a JSON column but has no JsonColumnMetadata; " +
                        "register the generated schema (codegen attaches it) or supply JsonColumnMetadata",
                )
            }
            if (!isJson && col.json != null) {
                throw IllegalStateException(
                    "${schema.table}.${col.name} carries JsonColumnMetadata but is not a JSON column",
                )
            }
        }
    }

    /**
     * Insert a row. The map's keys are snake_case column names; the id
     * column may be absent (driver mints one) or present (driver
     * stores as-is). Returns the persisted row, including the assigned
     * id, so the caller can hand it to `fromRow`.
     */
    fun insert(table: String, values: Map<String, Any?>): Map<String, Any?>

    /**
     * Whether [insertIgnore] is supported. Drivers that can express a
     * targeted upsert-skip (e.g. Postgres `ON CONFLICT ... DO NOTHING`)
     * override this to `true`. Generated symmetric link-table writes
     * preflight this flag before issuing junction inserts.
     */
    val supportsInsertIgnore: Boolean
        get() = false

    /**
     * Idempotent insert: insert a row, but if it would violate the
     * unique/primary-key constraint over exactly [conflictColumns], do
     * nothing instead of throwing. Keys in [values] are snake_case column
     * names, same as [insert]. Returns the persisted row when a row was
     * inserted, or `null` when the insert was skipped because a matching
     * row already existed.
     *
     * The conflict target is *scoped* to [conflictColumns] — a violation
     * of any *other* constraint still throws, so this never silently
     * swallows unrelated integrity errors. Used for junction-row inserts
     * in symmetric link-table M2M writes, where re-adding an
     * existing pair must be a no-op rather than an error.
     *
     * A driver that does not support this leaves [supportsInsertIgnore]
     * false; the default implementation throws.
     */
    fun insertIgnore(
        table: String,
        values: Map<String, Any?>,
        conflictColumns: List<String>,
    ): Map<String, Any?>? =
        throw UnsupportedOperationException(
            "Driver ${this::class.simpleName} does not support insertIgnore; " +
                "check supportsInsertIgnore before calling.",
        )

    /**
     * Update a row by id. Returns the new row state on success, or
     * `null` if no row exists with that id. Generated update saves
     * report that as `MutationResult.Failed(EntTargetAbsentException)`.
     */
    fun update(table: String, id: Any, values: Map<String, Any?>): Map<String, Any?>?

    /** Look up one row by primary key. */
    fun byId(table: String, id: Any): Map<String, Any?>?

    /**
     * Assert this driver can bind at least [minimumParameters]
     * parameters in one statement for a read of [table]. A driver
     * with no declared statement limit implements this as an
     * explicit no-op (and may still enforce its own limit at render
     * time).
     *
     * Generated read paths wire this into the query-spec builder,
     * which invokes it with a running conservative lower bound (the
     * summed O(1) sizes of `IN`-list operands, via
     * [minimumBindParameters]) immediately BEFORE every defensive
     * operand snapshot — once for the caller and structural
     * predicates at terminal entry, and again for each
     * interceptor-added predicate. A query that can never execute
     * therefore fails fast with the driver's own actionable error,
     * and an over-budget operand is never iterated or copied,
     * whoever contributed it. The entry check runs before the
     * interceptor chain; an interceptor contribution fails at its
     * own `addPredicate` call.
     *
     * Deliberately abstract, like [registerAll]: a metrics/tracing
     * decorator that forwards each operation by hand would silently
     * disable the pre-snapshot guard if a default no-op existed —
     * huge operands would materialize again on the way to the
     * backend's eventual rejection. Kotlin `by`-delegating wrappers
     * forward it automatically; manual and Java decorators are
     * forced to.
     */
    fun requireBindCapacity(minimumParameters: Long, table: String)

    /**
     * Run a query. Predicates are AND-ed together (the generated query
     * accumulates them as a list and the driver folds them). The
     * driver applies `orderBy` then `offset`/`limit` after filtering.
     */
    fun query(
        table: String,
        predicates: List<Predicate<*>>,
        orderBy: List<OrderField<*>>,
        limit: Int?,
        offset: Int?,
    ): List<Map<String, Any?>>

    /**
     * Whether this driver can push a direct to-many eager edge's
     * per-parent ordering, offset, and limit into storage. The
     * runtime consults this before every direct to-many eager fetch
     * and routes NATIVE drivers through [queryDirectToMany]; EMULATED
     * drivers retain the phase-1 lowering (one ordinary [query] with
     * the window applied in memory), so no eager query a driver
     * accepted before this capability existed is ever rejected.
     *
     * Deliberately abstract, like [registerAll] and
     * [requireBindCapacity]: a decorator that forwards each operation
     * by hand but inherited an EMULATED default would silently
     * downgrade a native driver back to full-result overfetch — and
     * one that inherited a NATIVE default over an emulated driver
     * would route reads to a [queryDirectToMany] that cannot execute.
     * Kotlin `by`-delegating wrappers forward it automatically;
     * manual and Java decorators are forced to choose.
     */
    fun directToManyWindowCapability(): DirectToManyWindowCapability

    /**
     * Execute one logical direct to-many relationship read with the
     * per-parent window applied in storage. Called by the runtime
     * only when [directToManyWindowCapability] is NATIVE; drivers
     * without the capability implement this as a throwing stub.
     *
     * Contract, per the set-based eager graph loader RFC:
     *  - The relationship constraint is lowered by the driver itself
     *    from [DirectToManyQuery.sourceKeys] and
     *    [DirectToManyQuery.targetForeignKey], without spending one
     *    scalar bind per parent key (PostgreSQL: one typed-array
     *    parameter). [DirectToManyQuery.targetPredicates] are AND-ed
     *    with it before any ranking, so a predicate can never make a
     *    window return fewer than `limit` rows while later matching
     *    rows exist.
     *  - Rows return in the one global canonical
     *    [DirectToManyQuery.effectiveOrder], each carrying its source
     *    key ([RelatedRow.sourceKey], the decoded FK value).
     *  - Rows outside a parent's window are not returned; window
     *    bound arithmetic must not overflow `Int`.
     *  - Synthetic driver columns (ranking aliases) never appear in
     *    the returned row maps, and must not collide with registered
     *    storage columns.
     *  - All physical reads for the one logical query observe one
     *    database snapshot (a single statement satisfies this).
     *  - Deterministic pre-I/O guards (bind-parameter budgets) apply
     *    to the statement's complete bind list before any I/O.
     *
     * Deliberately abstract, paired with [directToManyWindowCapability]:
     * the two members are one forwarding unit for decorators.
     *
     * `@EntktInternal` propagates from the relationship-plan types:
     * implementing or calling this member is framework wiring, and a
     * custom driver opts in explicitly.
     */
    @entkt.query.EntktInternal
    fun queryDirectToMany(query: DirectToManyQuery): RelatedRows

    /**
     * Count rows matching [predicates]. Predicates are AND-ed together,
     * same as [query]. Returns zero for an empty or unmatched table.
     */
    fun count(table: String, predicates: List<Predicate<*>>): Long

    /**
     * Return true if at least one row matches [predicates]. Semantically
     * equivalent to `count(...) > 0` but drivers can short-circuit.
     */
    fun exists(table: String, predicates: List<Predicate<*>>): Boolean

    /**
     * Whether this driver can execute aggregate queries (a single min / max /
     * sum / avg / count metric, optionally grouped by one column). Defaults to
     * false; Postgres overrides to true.
     */
    fun supportsAggregates(): Boolean = false

    /**
     * Compute a single aggregate [function] over rows matching [predicates],
     * optionally grouped by [groupBy]. [column] is the metric column name (null
     * only for `COUNT(*)`); [groupBy] is a single group-column name or null.
     *
     * Implementations MUST validate [column] and [groupBy] against the
     * registered schema before rendering SQL, rejecting an unknown identifier
     * with a clear, field-named error rather than letting it reach the database.
     * Returns exactly one [AggregateResultRow] when ungrouped (`key == null`),
     * or one per distinct key when grouped. The default throws
     * [UnsupportedDriverCapabilityException]; only drivers reporting
     * [supportsAggregates] `= true` override it.
     */
    fun aggregate(
        table: String,
        function: AggregateFunction,
        column: String?,
        predicates: List<Predicate<*>>,
        groupBy: String? = null,
    ): List<AggregateResultRow> =
        throw UnsupportedDriverCapabilityException(
            "driver ${this::class.simpleName} does not support aggregate queries",
        )

    /** Returns true if a row was actually removed. */
    fun delete(table: String, id: Any): Boolean

    /**
     * Insert multiple rows as one logical batch. Empty input returns an empty
     * list. Otherwise, returns exactly one persisted row per input in the same
     * order as [values], each with its assigned id. Drivers should use an
     * efficient batch strategy (e.g. multi-row `INSERT`).
     *
     * The input-order guarantee is part of the contract: implementations
     * must pair returned rows with inputs unambiguously — by a
     * correlation key (e.g. the row id), or by statements that can only
     * return one row each — never by relying on an engine's unspecified
     * result ordering. SQL engines generally do not document the row
     * order of insert-returning output.
     *
     * This is a low-level driver method that does not fire lifecycle
     * callbacks itself. Generated `createMany` evaluates hooks, privacy,
     * and validation over the complete logical batch before calling this
     * method once with the prepared rows, then hydrates the correlated
     * results and runs post-write callbacks. Implementations may use several
     * physical statements, but a transaction-scoped driver must keep every
     * statement in its surrounding transaction and must not commit internally.
     * An ordinary exception from a later statement can therefore leave
     * earlier statements staged; generated callers mark that transaction
     * rollback-only rather than treating the complete logical batch as
     * definitely unpersisted. Cancellation and JVM errors must propagate to
     * the transaction boundary so it can roll the transaction back.
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
    fun updateMany(table: String, values: Map<String, Any?>, predicates: List<Predicate<*>>): Int

    /**
     * Delete all rows matching [predicates]. Predicates are AND-ed
     * together, same as [query]. Returns the number of rows deleted.
     *
     * This is a low-level driver method that does not fire lifecycle
     * callbacks. Generated repositories use the ID-returning
     * [deleteManyByIds] operation after evaluating their lifecycle pipeline;
     * its correctness-preserving default delegates here once per distinct ID.
     */
    fun deleteMany(table: String, predicates: List<Predicate<*>>): Int

    /**
     * Delete the distinct rows identified by [ids] that still match every
     * supplied [predicates], returning the IDs that were actually removed.
     * Returned order is unspecified. Every returned value must have appeared
     * in [ids], and no value may be returned more than once.
     *
     * [idColumn] must equal [registeredIdColumn] for [table]. An empty [ids]
     * list performs no write and returns an empty list. The default preserves
     * correctness by issuing one predicate-based [deleteMany] call per
     * distinct ID; drivers can override it with a set-based implementation.
     * All physical statements must remain in the surrounding transaction and
     * must not commit internally. Generated callers always provide that
     * transaction; a direct low-level caller that needs all-or-nothing behavior
     * must likewise invoke this method on a transaction-scoped driver.
     */
    fun deleteManyByIds(
        table: String,
        idColumn: String,
        ids: List<Any>,
        predicates: List<Predicate<*>>,
    ): List<Any> {
        if (ids.isEmpty()) return emptyList()
        val registeredIdColumn = registeredIdColumn(table)
        require(idColumn == registeredIdColumn) {
            "deleteManyByIds for '$table' requires its registered ID column " +
                "'$registeredIdColumn', got '$idColumn'"
        }

        val deleted = mutableListOf<Any>()
        for (id in ids.distinct()) {
            val idPredicate = Predicate.Leaf<Any>(idColumn, Op.EQ, id)
            val count = deleteMany(table, predicates + idPredicate)
            check(count in 0..1) {
                "ID-scoped deleteMany for '$table.$idColumn' affected $count rows for ID '$id'"
            }
            if (count == 1) deleted += id
        }
        return deleted
    }

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
        predicates: List<Predicate<*>>,
        orderBy: List<OrderField<*>>,
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
        predicates: List<Predicate<*>>,
    ): QueryExplanation = UnsupportedQueryExplanation(this::class.simpleName ?: "Driver")

    /**
     * Run [block] inside a transaction and report the outcome
     * structurally. The block receives a transaction-scoped [Driver]
     * that shares a single underlying connection / snapshot.
     *
     * Write-certainty contract:
     * - [DriverTransactionResult.Success] is returned only after
     *   commit is confirmed.
     * - If the block throws an ordinary [Exception] and rollback is
     *   confirmed, return `Failed(exception, NotCommitted)`; if the
     *   rollback itself fails, return `Failed(exception, OutcomeUnknown)`
     *   with the rollback failure suppressed on the exception.
     * - If commit fails, return `Failed(commitException, OutcomeUnknown)`
     *   even when a later rollback call appears to succeed — the
     *   failed commit may already have reached the database.
     * - Cleanup failures after a confirmed commit must never turn a
     *   successful commit into a failed or unknown outcome.
     * - A `CancellationException` before commit is rethrown only after
     *   rollback is confirmed. Commit-time cancellation, or cancellation
     *   followed by an unconfirmed rollback, returns
     *   `Failed(cancellation, OutcomeUnknown)`.
     * - JVM `Error`s are rolled back and rethrown because `Failed`
     *   stores only [Exception] values.
     *
     * Calling [withTransaction] on an already-transactional driver
     * throws [entkt.runtime.result.NestedTransactionUnsupportedException]
     * before entering the block or performing any transaction I/O.
     * Savepoint and reuse options belong to a separate
     * transaction-client design.
     *
     * The driver passed to [block] is only valid for the duration of
     * the block — using it after the block returns will throw.
     */
    fun <T> withTransaction(block: (Driver) -> T): DriverTransactionResult<T>

    /**
     * True when this [Driver] is the transaction-scoped driver passed
     * inside [withTransaction]. False on a normal client-level driver.
     * Generated saves use this at save-start to enforce a configured
     * [entkt.runtime.mutation.TransactionRequirement] (transaction locking) without having
     * to thread a separate flag through every layer.
     */
    val inTransaction: Boolean
        get() = false

    // ---------- Owner-row locking capabilities (transaction locking) ----------
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
     *
     * **Scope.** This lock keys on a single owner row `(table, id)`, so it
     * serializes saves that share an owner but does NOT coordinate writes
     * coming from the *opposite orientation* of the same link table (those
     * are anchored on a different owner row). Cross-orientation
     * coordination is the job of the canonical relationship lock —
     * [serializeRelationship] — which both sides opt into.
     */
    fun serializeOwnerEdgeAndRead(table: String, id: Any): Map<String, Any?>? =
        throw UnsupportedOperationException(
            "Driver ${this::class.simpleName} does not support serializeOwnerEdgeAndRead; " +
                "check supportsOwnerEdgeSerialization before calling.",
        )

    /**
     * Whether [serializeRelationship] is supported. Drivers with a
     * transaction-scoped advisory-lock primitive (e.g. Postgres
     * `pg_advisory_xact_lock`) override this to `true`. Generated saves
     * preflight this flag when `relationshipLocking = Canonical` is
     * selected for a symmetric link-table write.
     */
    val supportsRelationshipSerialization: Boolean
        get() = false

    /**
     * Serialize access to a whole link-table *relationship*, keyed by the
     * canonical [RelationshipLockKey] (junction table + sorted FK pair),
     * against other callers using the same discipline. Unlike
     * [serializeOwnerEdgeAndRead] — which keys on a single owner row and
     * therefore does NOT coordinate writes coming from the opposite
     * orientation of the same link table — this lock is symmetric: both
     * orientations compute the same key and so serialize against each
     * other. It reads no row; it only takes the lock.
     *
     * The serialization must hold from the call through the rest of the
     * save's transaction, in practice until the enclosing transaction
     * commits or rolls back. This lock is *cooperative*: it only protects
     * against other writers that also opt in via `RelationshipLocking.Canonical`.
     *
     * **Implementation contract.** Same as [serializeOwnerEdgeAndRead]:
     * implementations MUST reject calls when [inTransaction] is false (use
     * [requireTransactionForLocking]); the token's duration is the
     * surrounding transaction. A driver that does not support this leaves
     * [supportsRelationshipSerialization] false; the default throws.
     */
    fun serializeRelationship(key: RelationshipLockKey): Unit =
        throw UnsupportedOperationException(
            "Driver ${this::class.simpleName} does not support serializeRelationship; " +
                "check supportsRelationshipSerialization before calling.",
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
     * Classify an [exception] thrown by one of this driver's
     * low-level *mutation* operations into a state-bearing
     * [entkt.runtime.result.EntMutationException]. Returning `null`
     * signals "no more precise classification" — the generated
     * terminal then stores the original exception with its
     * phase-derived state (using `PersistenceUnknown` for a write
     * exception with no precise classification, never optimistically
     * `NotPersisted`). There is no parallel state field: the returned
     * exception's own `writeState` is the classification.
     *
     * Implementations should:
     *  - return [entkt.runtime.result.EntConstraintViolationException]
     *    (whose type hardcodes `NotPersisted`) for a recognized
     *    UNIQUE/FK/CHECK violation whose rollback is confirmed,
     *    preserving the original driver exception as the cause and
     *    populating `constraint`/`field`/`driverCode` from whatever
     *    metadata the underlying exception carries;
     *  - return [entkt.runtime.result.EntConflictException] for a
     *    recognized statement-level concurrency conflict (e.g. a
     *    serialization failure) whose effect did not persist;
     *  - return [entkt.runtime.result.EntUnexpectedMutationException]
     *    with the known state for an unclassified operational failure
     *    whose persistence outcome the driver *does* know;
     *  - return `null` for anything unrecognized.
     *
     * Read execution never calls this method — canonical reads store
     * the original exception directly.
     *
     * The default returns `null` so third-party drivers inherit the
     * documented fallback behavior without having to opt in.
     */
    fun classifyMutationException(
        exception: Exception,
        entity: String,
        operation: EntOperation,
    ): EntMutationException? = null
}

/**
 * Conservative lower bound on the bind parameters a predicate list will
 * need: the summed sizes of `IN` / `NOT_IN` collection operands, the
 * one caller-unbounded amplification point. Everything else (scalar
 * leaves, ordering operands) is deliberately counted as zero, so the
 * bound never over-rejects — a query this reports as over a driver's
 * budget provably cannot execute. `Collection.size` is O(1), so the
 * walk allocates nothing and never iterates an operand.
 *
 * Used with [Driver.requireBindCapacity] at generated terminal entry,
 * before any defensive operand snapshot. Public for the generated
 * cross-module call sites; not application API.
 */
@entkt.query.EntktInternal
public fun minimumBindParameters(predicates: List<Predicate<*>>): Long =
    predicates.sumOf { minimumBindParameters(it) }

// The shaped-traversal branches read @EntktInternal predicate members;
// this walk is itself framework plumbing for the generated call sites.
@OptIn(entkt.query.EntktInternal::class)
private fun minimumBindParameters(predicate: Predicate<*>): Long = when (predicate) {
    is Predicate.Leaf ->
        if ((predicate.op == Op.IN || predicate.op == Op.NOT_IN) && predicate.value is Collection<*>) {
            (predicate.value as Collection<*>).size.toLong()
        } else {
            0L
        }
    is Predicate.And -> minimumBindParameters(predicate.left) + minimumBindParameters(predicate.right)
    is Predicate.Or -> minimumBindParameters(predicate.left) + minimumBindParameters(predicate.right)
    is Predicate.HasEdge -> 0L
    is Predicate.HasEdgeWith<*, *> -> minimumBindParameters(predicate.inner)
    is Predicate.HasM2MEdgeFrom<*, *> -> predicate.sourceFilter?.let { minimumBindParameters(it) } ?: 0L
    is Predicate.HasEdgeFromShape<*, *> -> minimumBindParameters(predicate.source.predicates)
    is Predicate.HasM2MEdgeFromShape<*, *> -> minimumBindParameters(predicate.source.predicates)
}
