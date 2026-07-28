package entkt.postgres

import entkt.query.OrderField
import entkt.query.Predicate
import entkt.runtime.query.AggregateFunction
import entkt.runtime.query.AggregateResultRow
import entkt.runtime.driver.Driver
import entkt.runtime.driver.EdgeMetadata
import entkt.runtime.driver.EntitySchema
import entkt.runtime.driver.JsonColumnCodec
import entkt.runtime.driver.KotlinxJsonCodec
import entkt.runtime.query.QueryExplanation
import java.util.concurrent.ConcurrentHashMap
import javax.sql.DataSource

/**
 * A [Driver] backed by a JDBC [DataSource] talking to PostgreSQL.
 *
 * Each call borrows one connection from the pool and runs a single
 * statement. The driver does no caching beyond the per-table
 * [EntitySchema] registry — every [insert]/[update]/[query] hits the
 * database.
 *
 * The schema registry is populated by [registerAll]. By default this is
 * metadata-only: it caches the [EntitySchema]s needed for query lowering
 * and persistence, but does not mutate the database. When [autoDdl] is
 * true, it also issues `CREATE TABLE IF NOT EXISTS`, index DDL, and
 * foreign key DDL derived from the schemas (rendered by [PostgresDdl]).
 * Foreign keys go in as separate `ALTER TABLE ... ADD CONSTRAINT`
 * statements after every table in the batch exists, so ordering — and
 * therefore FK cycles — can't break auto-DDL.
 *
 * Predicate lowering ([PredicateSqlBuilder]) produces parameterized SQL:
 * leaves become `"col" op ?`, edge predicates become `EXISTS (... )`
 * subqueries walking the registered [EdgeMetadata]. No string
 * concatenation of user values ever happens — only of column and table
 * identifiers (which originate in generated code, never user input).
 *
 * This class is a facade: every operation is implemented once in
 * [PostgresOperations] as a function of an explicit connection, with
 * value conversion in [PostgresValueCodec]. [withTransaction] hands the
 * same operation core to a [PostgresTransactionalDriver] pinned to one
 * connection.
 */
class PostgresDriver(
    private val dataSource: DataSource,
    private val autoDdl: Boolean = false,
    /**
     * Codec used for all typed JSON encode/decode. Defaults to
     * kotlinx.serialization with `Json.Default`; pass e.g.
     * `KotlinxJsonCodec(Json { ignoreUnknownKeys = true })` to configure
     * kotlinx behavior, or a different codec (e.g. `io.entkt:jackson`'s
     * `JacksonJsonCodec`) to switch mappers — the codec's id must match the
     * `jsonMapper` the schema code was generated with, checked at [register].
     */
    private val jsonCodec: JsonColumnCodec = KotlinxJsonCodec(),
) : Driver {

    private val schemas: MutableMap<String, EntitySchema> = ConcurrentHashMap()
    private val ddl = PostgresDdl()
    private val ops = PostgresOperations(schemas, PostgresValueCodec(jsonCodec))

    /**
     * Serializes the mutating part of [registerAll] — validation, DDL,
     * and caching — so concurrent batches can't both decide the same
     * tables are missing and race to create them. The already-registered
     * fast path runs outside it.
     */
    private val registrationLock = Any()

    /**
     * Register one schema. Delegates to [registerAll] so both paths
     * share one implementation.
     *
     * A lone registration can only resolve foreign keys whose target
     * table already exists; for anything cyclic, pass the whole set to
     * [registerAll] instead. The error raised on an unresolved target
     * says so.
     */
    override fun register(schema: EntitySchema) = registerAll(listOf(schema))

    /**
     * Register a batch of schemas, materializing them under [autoDdl] in
     * two passes: every table first, then every foreign key.
     *
     * The two passes are the whole point. `CREATE TABLE` with an inline
     * `REFERENCES` requires its target to already exist, which is
     * unsatisfiable for mutually-referencing entities — codegen's
     * topological sort has to break such a cycle somewhere, and whichever
     * table lands first would reference one that doesn't exist yet. Once
     * every table is created before any constraint is added, registration
     * order stops mattering entirely and no constraint has to be deferred
     * or remembered.
     *
     * The sequence is:
     *
     *  1. compare each incoming schema against the in-memory registry
     *  2. return immediately if none are new — **no connection, no
     *     catalog lookup, no DDL**
     *  3. validate every new schema before mutating anything
     *  4. create all tables (and extensions, and indexes)
     *  5. add all foreign keys
     *  6. cache the new schemas only once all of that succeeded
     *
     * Step 2 is load-bearing, not an optimization. `withTransaction`
     * constructs a generated client *inside* the open transaction and
     * `withPrivacyContext` constructs one per call, so this method runs
     * constantly on a fully-registered driver; touching the database
     * there would put DDL inside every user transaction. It is also the
     * only part that runs unsynchronized — steps 3 through 6 hold
     * [registrationLock], since the DDL precedes the cache write and
     * concurrent batches would otherwise both find the same tables
     * missing.
     *
     * Step 6 leaves a failed batch retryable: nothing is cached, and the
     * DDL is idempotent, so the next attempt re-runs cleanly.
     */
    override fun registerAll(schemas: List<EntitySchema>) {
        // (1, 2) Hot path, deliberately outside the lock: when every
        // incoming schema is already registered and matches, there is
        // nothing to serialize. Registration re-runs on every generated
        // client construction, so this must stay free of both I/O and
        // lock contention.
        if (schemas.all { alreadyRegistered(it) }) return

        // Everything past here mutates the database, so it runs one
        // batch at a time. A CAS on the schema cache can't stand in for
        // this: the cache write happens *after* the DDL, so two threads
        // would both classify the same tables as new and both execute
        // DDL before either reached the cache. Concurrent identical
        // `CREATE TABLE IF NOT EXISTS` can fail outright in Postgres
        // (duplicate key on pg_type_typname_nsp_index), and a batch that
        // loses a metadata conflict would already have written to the
        // database by the time it found out.
        //
        // Scope: one driver instance. Two drivers over the same database
        // — or two processes — still race, which is inherent to
        // convenience DDL and one more reason it isn't the migration path.
        synchronized(registrationLock) {
            // Re-derive inside the lock: a batch that ran while this one
            // waited may have registered some or all of these.
            val fresh = LinkedHashMap<String, EntitySchema>()
            for (schema in schemas) {
                val existing = this.schemas[schema.table] ?: fresh[schema.table]
                if (existing != null) checkSchemaMatches(existing, schema) else fresh[schema.table] = schema
            }
            if (fresh.isEmpty()) return

            // (3) Validate everything before creating anything, so a bad
            // schema late in the batch doesn't leave half a set materialized.
            for (schema in fresh.values) validateSchema(schema)

            if (autoDdl) {
                dataSource.connection.use { conn ->
                    conn.createStatement().use { stmt ->
                        // (4) Every table, before any cross-table constraint.
                        for (schema in fresh.values) {
                            // Required extensions (e.g. pgvector) must exist before a
                            // column using their type is created.
                            val extensions = schema.columns
                                .mapNotNull { (it.storage as? entkt.schema.ColumnStorage.Native)?.requiredExtension }
                                .distinct()
                            for (ext in extensions) stmt.execute("CREATE EXTENSION IF NOT EXISTS ${quote(ext)}")
                            stmt.execute(ddl.createTableSql(schema))
                            for (sql in ddl.createIndexesSql(schema)) stmt.execute(sql)
                        }
                        // (5) Now that every table in the batch exists, the
                        // constraints between them all resolve.
                        for (schema in fresh.values) {
                            for (fk in ddl.foreignKeysFor(schema)) ensureForeignKey(conn, stmt, fk)
                        }
                    }
                }
            }

            // (6) Cache only after the DDL succeeded, so a failed batch
            // stays retryable.
            for (schema in fresh.values) this.schemas[schema.table] = schema
        }
    }

    /**
     * True when [schema] is already registered as-is; throws when the
     * table is registered with different metadata.
     */
    private fun alreadyRegistered(schema: EntitySchema): Boolean {
        val existing = schemas[schema.table] ?: return false
        checkSchemaMatches(existing, schema)
        return true
    }

    /**
     * Reject anything this driver can't actually serve, before it
     * materializes or caches the schema.
     */
    private fun validateSchema(schema: EntitySchema) {
        // Reject native-storage columns whose codec this driver can't handle
        // (Postgres supports postgres.vector; everything else fails here).
        checkNativeStorageSupported(schema)
        // Reject typed JSON only if unsupported (Postgres supports it).
        checkTypedJsonSupported(schema)
        // Cross-check every JSON column against the configured codec: the
        // metadata records which mapper the code was GENERATED for; a
        // mismatch (regenerated with one mapper, driver configured with
        // another) must fail at startup, not at first read. Then let the
        // codec preflight anything it can't round-trip.
        for (col in schema.columns) {
            val meta = col.json ?: continue
            check(meta.mapper == jsonCodec.id) {
                "${schema.table}.${col.name} was generated for JSON mapper '${meta.mapper}', but this " +
                    "driver is configured with codec '${jsonCodec.id}' — regenerate with " +
                    "jsonMapper = \"${jsonCodec.id}\" or configure the matching codec"
            }
            jsonCodec.validate(schema.table, col)
        }
    }

    /**
     * Bring one foreign key into existence, reconciling against whatever
     * already holds its name.
     *
     * `ADD CONSTRAINT` has no `IF NOT EXISTS`, so an idempotent apply has
     * to consult `pg_constraint` first — and the name alone can't answer
     * the question. Constraint names here are fully derived
     * (`fk_<table>_<column>`), so editing an edge's target or its
     * `onDelete` produces a *different* constraint under the *same* name.
     * Skipping on a name match would then leave the old constraint in
     * place while registration reported success, which is how a schema
     * ends up quietly enforcing rules nobody declared. Same for an
     * unrelated constraint that happens to collide.
     *
     * So: absent → create; present and equivalent → nothing to do;
     * present and different → fail, saying what was wanted and what is
     * actually there. Reconciling the difference is a migration's job,
     * not auto-DDL's.
     */
    private fun ensureForeignKey(conn: java.sql.Connection, stmt: java.sql.Statement, fk: ForeignKeyDdl) {
        when (val existing = findConstraint(conn, fk)) {
            null -> createForeignKey(stmt, fk)
            else -> check(existing.matches(fk)) {
                "Table '${fk.table}' already has a constraint named '${fk.constraintName}', but it is not " +
                    "the one this schema describes. Auto-DDL will not alter an existing constraint — drop it, " +
                    "or move this schema onto the migration path.\n" +
                    "  schema wants: FOREIGN KEY (${fk.column}) REFERENCES ${fk.targetTable}(${fk.targetColumn}) " +
                    "ON DELETE ${fk.onDelete}\n" +
                    "  database has: ${existing.describe()}"
            }
        }
    }

    /** Execute the `ADD CONSTRAINT`, sharpening a missing-target error. */
    private fun createForeignKey(stmt: java.sql.Statement, fk: ForeignKeyDdl) {
        try {
            stmt.execute(fk.sql)
        } catch (e: java.sql.SQLException) {
            if (e.sqlState != UNDEFINED_TABLE) throw e
            // The batch didn't include the target entity — the caller
            // registered a subset. Postgres reports that as a bare
            // 42P01, which doesn't hint at the fix.
            throw IllegalStateException(
                "Cannot create foreign key '${fk.constraintName}': ${fk.table}.${fk.column} references " +
                    "table '${fk.targetTable}', which is neither registered nor present in the database. " +
                    "Register the complete schema set in one call — driver.registerAll(EntClient.SCHEMAS) — " +
                    "so every table exists before its constraints are added.",
                e,
            )
        }
    }

    /**
     * Look up whatever constraint currently holds [fk]'s name on its
     * table, or null if the name is free.
     *
     * Compares the target by OID (`confrelid = to_regclass(?)`) rather
     * than by rendered name, so schema qualification and search_path
     * can't produce a spurious mismatch.
     */
    private fun findConstraint(conn: java.sql.Connection, fk: ForeignKeyDdl): ExistingConstraint? =
        conn.prepareStatement(
            """
            SELECT c.contype = 'f'                       AS is_foreign_key,
                   c.confrelid = to_regclass(?)          AS target_matches,
                   c.confdeltype                         AS delete_code,
                   c.confupdtype                         AS update_code,
                   c.confmatchtype                       AS match_code,
                   c.convalidated                        AS validated,
                   c.condeferrable                       AS deferrable,
                   c.condeferred                         AS deferred,
                   COALESCE(c.confrelid::regclass::text, '(none)') AS target_table,
                   COALESCE(array_length(c.conkey, 1), 0)          AS column_count,
                   COALESCE((SELECT a.attname FROM pg_attribute a
                             WHERE a.attrelid = c.conrelid AND a.attnum = c.conkey[1]), '(none)') AS column_name,
                   COALESCE((SELECT a.attname FROM pg_attribute a
                             WHERE a.attrelid = c.confrelid AND a.attnum = c.confkey[1]), '(none)') AS target_column
            FROM pg_constraint c
            WHERE c.conname = ? AND c.conrelid = to_regclass(?)
            """.trimIndent(),
        ).use { stmt ->
            stmt.setString(1, fk.targetTable)
            stmt.setString(2, fk.constraintName)
            stmt.setString(3, fk.table)
            stmt.executeQuery().use { rs ->
                if (!rs.next()) return null
                ExistingConstraint(
                    isForeignKey = rs.getBoolean("is_foreign_key"),
                    targetMatches = rs.getBoolean("target_matches"),
                    deleteCode = rs.getString("delete_code")?.firstOrNull() ?: ' ',
                    updateCode = rs.getString("update_code")?.firstOrNull() ?: ' ',
                    matchCode = rs.getString("match_code")?.firstOrNull() ?: ' ',
                    validated = rs.getBoolean("validated"),
                    deferrable = rs.getBoolean("deferrable"),
                    deferred = rs.getBoolean("deferred"),
                    targetTable = rs.getString("target_table"),
                    columnCount = rs.getInt("column_count"),
                    columnName = rs.getString("column_name"),
                    targetColumn = rs.getString("target_column"),
                )
            }
        }

    /** A constraint already present in the catalog under a derived name. */
    private data class ExistingConstraint(
        val isForeignKey: Boolean,
        val targetMatches: Boolean,
        val deleteCode: Char,
        val updateCode: Char,
        val matchCode: Char,
        val validated: Boolean,
        val deferrable: Boolean,
        val deferred: Boolean,
        val targetTable: String,
        val columnCount: Int,
        val columnName: String,
        val targetColumn: String,
    ) {
        /**
         * Whether this is exactly the constraint [fk] describes. Compares
         * catalog columns rather than `pg_get_constraintdef` text, which
         * varies in formatting across server versions.
         *
         * `ON DELETE` is the only clause [ForeignKeyDdl] varies, so every
         * other attribute is checked against the server default that the
         * rendered `ADD CONSTRAINT` would produce. Those aren't cosmetic:
         * a `NOT VALID` twin never checked the rows already in the table,
         * a `DEFERRABLE` one moves enforcement to commit time, and an
         * `ON UPDATE` action fires on parent-key updates the schema never
         * asked to cascade. Accepting any of them would report success
         * for a constraint enforcing rules nobody declared.
         */
        fun matches(fk: ForeignKeyDdl): Boolean =
            isForeignKey &&
                targetMatches &&
                deleteCode == fk.onDeleteCode &&
                // Every FK this driver renders is single-column; a
                // composite one under the same name is a mismatch.
                columnCount == 1 &&
                columnName == fk.column &&
                targetColumn == fk.targetColumn &&
                updateCode == NO_ACTION &&
                validated &&
                !deferrable &&
                !deferred &&
                // Semantically inert while every FK here is
                // single-column — MATCH FULL and MATCH SIMPLE differ
                // only in how they treat a partially-null key — but
                // compared anyway so the rule stays "exactly what this
                // schema would create" rather than "close enough".
                matchCode == MATCH_SIMPLE

        /**
         * Human-readable rendering for the mismatch error. Non-default
         * attributes are appended so the difference is visible even when
         * the columns and tables line up.
         */
        fun describe(): String {
            if (!isForeignKey) return "a non-foreign-key constraint"
            return buildString {
                append("FOREIGN KEY ($columnName) REFERENCES $targetTable($targetColumn)")
                if (matchCode != MATCH_SIMPLE) append(" MATCH ${matchTypeName()}")
                append(" ON DELETE ${actionName(deleteCode)}")
                if (updateCode != NO_ACTION) append(" ON UPDATE ${actionName(updateCode)}")
                if (deferrable) append(if (deferred) " DEFERRABLE INITIALLY DEFERRED" else " DEFERRABLE")
                if (!validated) append(" NOT VALID")
            }
        }

        private fun actionName(code: Char): String = when (code) {
            'c' -> "CASCADE"
            'n' -> "SET NULL"
            'r' -> "RESTRICT"
            'd' -> "SET DEFAULT"
            NO_ACTION -> "NO ACTION"
            else -> "unknown ($code)"
        }

        private fun matchTypeName(): String = when (matchCode) {
            'f' -> "FULL"
            'p' -> "PARTIAL"
            MATCH_SIMPLE -> "SIMPLE"
            else -> "unknown ($matchCode)"
        }

        private companion object {
            /** `confupdtype` / `confdeltype` code for `NO ACTION`. */
            const val NO_ACTION = 'a'

            /** `confmatchtype` code for `MATCH SIMPLE`. */
            const val MATCH_SIMPLE = 's'
        }
    }

    /**
     * Fail when [incoming] claims a table name already held by a
     * different [EntitySchema]. [EntitySchema] is a data class, so this
     * is a structural comparison — re-registering an equal-but-distinct
     * instance (two generated clients built from the same schema set)
     * passes.
     */
    private fun checkSchemaMatches(existing: EntitySchema, incoming: EntitySchema) {
        check(existing == incoming) {
            "Table '${incoming.table}' is already registered with different metadata — two entities " +
                "cannot share a table name on one driver. Register each schema set with its own " +
                "PostgresDriver, or rename the conflicting table.\n" +
                "  registered: $existing\n" +
                "  incoming:   $incoming"
        }
    }

    override fun insert(table: String, values: Map<String, Any?>): Map<String, Any?> =
        dataSource.connection.use { ops.insert(it, table, values) }

    override fun insertIgnore(
        table: String,
        values: Map<String, Any?>,
        conflictColumns: List<String>,
    ): Map<String, Any?>? =
        dataSource.connection.use { ops.insertIgnore(it, table, values, conflictColumns) }

    override fun update(table: String, id: Any, values: Map<String, Any?>): Map<String, Any?>? =
        dataSource.connection.use { ops.update(it, table, id, values) }

    override fun byId(table: String, id: Any): Map<String, Any?>? =
        dataSource.connection.use { ops.byId(it, table, id) }

    override fun query(
        table: String,
        predicates: List<Predicate<*>>,
        orderBy: List<OrderField<*>>,
        limit: Int?,
        offset: Int?,
    ): List<Map<String, Any?>> =
        dataSource.connection.use { ops.query(it, table, predicates, orderBy, limit, offset) }

    override fun explainQuery(
        table: String,
        predicates: List<Predicate<*>>,
        orderBy: List<OrderField<*>>,
        limit: Int?,
        offset: Int?,
    ): QueryExplanation {
        val prepared = ops.buildSelectSql(table, predicates, orderBy, limit, offset)
        return PostgresQueryExplanation(prepared.sql, prepared.params.map { it.value })
    }

    override fun explainCount(
        table: String,
        predicates: List<Predicate<*>>,
    ): QueryExplanation {
        val prepared = ops.buildCountSql(table, predicates)
        return PostgresQueryExplanation(prepared.sql, prepared.params.map { it.value })
    }

    override fun count(table: String, predicates: List<Predicate<*>>): Long =
        dataSource.connection.use { ops.count(it, table, predicates) }

    override fun exists(table: String, predicates: List<Predicate<*>>): Boolean =
        dataSource.connection.use { ops.exists(it, table, predicates) }

    override fun aggregate(
        table: String,
        function: AggregateFunction,
        column: String?,
        predicates: List<Predicate<*>>,
        groupBy: String?,
    ): List<AggregateResultRow> =
        dataSource.connection.use { ops.aggregate(it, table, function, column, predicates, groupBy) }

    override fun delete(table: String, id: Any): Boolean =
        dataSource.connection.use { ops.delete(it, table, id) }

    override fun insertMany(table: String, values: List<Map<String, Any?>>): List<Map<String, Any?>> =
        dataSource.connection.use { ops.insertMany(it, table, values) }

    override fun updateMany(table: String, values: Map<String, Any?>, predicates: List<Predicate<*>>): Int =
        dataSource.connection.use { ops.updateMany(it, table, values, predicates) }

    override fun deleteMany(table: String, predicates: List<Predicate<*>>): Int =
        dataSource.connection.use { ops.deleteMany(it, table, predicates) }

    // ---------- Transactions ----------

    /**
     * Run [block] against a connection-pinned driver inside one
     * transaction, committing on normal return and rolling back on any
     * throw.
     *
     * **Cleanup never changes the observed outcome.** Three separate
     * failures can happen while unwinding — `rollback()`, restoring
     * `autoCommit`, and `close()` — and none of them may replace the
     * result the caller would otherwise see:
     *
     *  - A `rollback()` failure is attached to the original exception
     *    via `addSuppressed` rather than thrown in its place. The
     *    reason the transaction is being rolled back is strictly more
     *    useful than the fact that the rollback also failed (and a
     *    broken connection makes both happen together).
     *  - Connection-release failures after a **successful commit** are
     *    swallowed. Letting them propagate would report failure for
     *    durably committed work, and any retry-on-exception wrapper
     *    would then re-apply an already-applied transaction. Losing a
     *    cleanup error is strictly safer than manufacturing a false
     *    negative on a commit.
     *  - Connection-release failures while an exception is already
     *    propagating are attached to it as suppressed.
     *
     * `close()` runs even when restoring `autoCommit` throws — the two
     * are released independently so a failed restore can't leak the
     * pooled connection.
     */
    override fun <T> withTransaction(block: (Driver) -> T): T {
        val conn = dataSource.connection
        // The exception on its way out, if any — the release path
        // attaches its own failures to this rather than supplanting it.
        // A `finally` block can't see the in-flight exception, so the
        // outer catch records it before `finally` runs.
        var propagating: Throwable? = null
        // Whether the transaction reached a decided end (committed or
        // rolled back). Gates the autocommit restore — see
        // [releaseConnection].
        var resolved = false
        try {
            conn.autoCommit = false
            val txDriver = PostgresTransactionalDriver(conn, this, ops)
            try {
                val result = block(txDriver)
                conn.commit()
                resolved = true
                return result
            } catch (e: Throwable) {
                try {
                    conn.rollback()
                    resolved = true
                } catch (rollbackFailure: Throwable) {
                    e.addSuppressed(rollbackFailure)
                }
                throw e
            } finally {
                txDriver.closed = true
            }
        } catch (e: Throwable) {
            propagating = e
            throw e
        } finally {
            releaseConnection(conn, propagating, resolved)
        }
    }

    /**
     * Release [conn], routing any failure into [propagating] as a
     * suppressed exception instead of throwing. See [withTransaction] for
     * why cleanup must never be the thing the caller observes.
     *
     * **Autocommit is restored only when [transactionResolved].** Setting
     * autocommit on a connection with a live transaction *commits* it
     * (JDBC 4.3 §10.1.1) — so doing it unconditionally would take the one
     * path where the transaction's fate is undecided, a `rollback()` that
     * itself failed, and turn it into a commit. The caller would receive
     * the business exception while the work it describes was durably
     * persisted: the exact false-negative this method exists to prevent,
     * inverted.
     *
     * When the fate is undecided, [conn] is closed without touching
     * autocommit. The Postgres driver rolls back an open transaction on
     * close, which is the outcome the failed `rollback()` was after. The
     * connection then returns to the pool still in manual-commit mode;
     * pools reset that on release (HikariCP does by default), and a
     * connection whose rollback just failed is one a pool is likely to
     * discard anyway. Leaving that state behind is worth avoiding a
     * commit nobody asked for.
     *
     * When [propagating] is null the transaction committed and there is
     * nothing to attach to, so a release failure is dropped. The project
     * has no logging facility to report it through; surfacing it would
     * mean failing a committed transaction, which is the worse outcome.
     */
    private fun releaseConnection(
        conn: java.sql.Connection,
        propagating: Throwable?,
        transactionResolved: Boolean,
    ) {
        if (transactionResolved) {
            try {
                conn.autoCommit = true
            } catch (restoreFailure: Throwable) {
                propagating?.addSuppressed(restoreFailure)
            }
        }
        // Deliberately outside the block above: a failed autoCommit
        // restore must not skip close() and leak the connection.
        try {
            conn.close()
        } catch (closeFailure: Throwable) {
            propagating?.addSuppressed(closeFailure)
        }
    }

    // ---------- Locking capabilities (transaction locking) ----------
    //
    // The root (non-transactional) driver advertises both row-lock
    // capabilities so the generated capability-preflight at save-start
    // accepts saves that need them — but the methods themselves only
    // do useful work inside a transaction (a `SELECT ... FOR UPDATE`
    // in auto-commit immediately releases the lock when the statement
    // completes, defeating the purpose). Callers must reach the
    // locking methods through the [PostgresTransactionalDriver]
    // returned inside [withTransaction]; the root driver enforces the
    // contract via the shared `requireTransactionForLocking` helper.

    override val supportsReadRowForUpdate: Boolean
        get() = true

    override fun readRowForUpdate(table: String, id: Any): Map<String, Any?>? {
        // Uniform contract enforcement (transaction locking): the
        // `Driver.readRowForUpdate` interface contract requires
        // implementations to reject non-transactional callers. The
        // helper throws IllegalStateException when `inTransaction`
        // is false, which on the root driver is always.
        requireTransactionForLocking("readRowForUpdate")
        // Unreachable — the helper above always throws on the root
        // because root.inTransaction == false. The explicit error
        // here is defensive: if a future subclass overrides
        // `inTransaction` to return true on a root-class instance,
        // this still surfaces the impossible call site.
        error(
            "PostgresDriver.readRowForUpdate reached the root-class body despite passing the " +
                "transaction check — root must not advertise inTransaction = true.",
        )
    }

    override val supportsOwnerEdgeSerialization: Boolean
        get() = true

    override fun serializeOwnerEdgeAndRead(table: String, id: Any): Map<String, Any?>? {
        requireTransactionForLocking("serializeOwnerEdgeAndRead")
        error(
            "PostgresDriver.serializeOwnerEdgeAndRead reached the root-class body despite passing " +
                "the transaction check — root must not advertise inTransaction = true.",
        )
    }

    // Postgres expresses targeted upsert-skip via `ON CONFLICT DO NOTHING`,
    // so insertIgnore is supported on the root (auto-commit) driver too —
    // it's a single idempotent statement, not a multi-step lock.
    override val supportsInsertIgnore: Boolean
        get() = true

    // Phase 1 supports the Postgres pgvector codec; other native codecs are
    // not understood (a schema using one is rejected at register).
    override fun supportsNativeStorage(codec: String): Boolean = codec == "postgres.vector"

    override fun supportsTypedJson(): Boolean = true

    override fun supportsAggregates(): Boolean = true

    // Like the other lock primitives, the relationship lock only does
    // useful work inside a transaction (an advisory lock taken in
    // auto-commit releases immediately), so the root driver advertises the
    // capability but rejects the call; callers reach it via the
    // transaction-scoped driver.
    override val supportsRelationshipSerialization: Boolean
        get() = true

    override fun serializeRelationship(key: entkt.runtime.mutation.RelationshipLockKey) {
        requireTransactionForLocking("serializeRelationship")
        error(
            "PostgresDriver.serializeRelationship reached the root-class body despite passing " +
                "the transaction check — root must not advertise inTransaction = true.",
        )
    }

    // ---------- Driver exception classification (result variants) ----------

    /**
     * Map a [PSQLException] thrown by this driver to a structured
     * [EntError]. SQLSTATE classes covered in V1:
     *
     *  - `23xxx` (integrity constraint violation): UNIQUE (`23505`),
     *    FOREIGN KEY (`23503`), CHECK (`23514`), NOT NULL (`23502`),
     *    EXCLUSION (`23P01`) — all map to
     *    [EntError.ConstraintViolation] with the SQLSTATE preserved
     *    as `code`. When the server attached `ServerErrorMessage`
     *    metadata (typical for constraint errors), `constraint` and
     *    `field` are populated from it; otherwise they're `null`.
     *
     * Serialization-failure SQLSTATEs (`40001`, `40P01`) deliberately
     * return `null` in V1 — they're the natural fit for
     * [EntError.Conflict] but that variant has no generated path
     * surfacing it yet (the optimistic-locking support will land that).
     * Returning null falls through to `EntError.DriverFailure`, which
     * is the right shape until Conflict has a real consumer.
     *
     * Returns `null` for anything that isn't a PSQLException —
     * `classifyDriverError` will wrap those as `DriverFailure`.
     */
    override fun classifyException(
        throwable: Throwable,
        entity: String,
        operation: entkt.runtime.result.EntOperation,
    ): entkt.runtime.result.EntError? {
        if (throwable !is org.postgresql.util.PSQLException) return null
        val state = throwable.sqlState ?: return null
        if (!state.startsWith("23")) return null
        val server = throwable.serverErrorMessage
        return entkt.runtime.result.EntError.ConstraintViolation(
            entity = entity,
            operation = operation,
            constraint = server?.constraint,
            field = server?.column,
            code = state,
            message = throwable.message ?: "constraint violation",
        )
    }

    private companion object {
        /** SQLSTATE for `relation "..." does not exist`. */
        const val UNDEFINED_TABLE = "42P01"
    }
}
