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
 * The schema registry is populated by [register]. By default this is
 * metadata-only: it caches the [EntitySchema] needed for query lowering
 * and persistence, but does not mutate the database. When [autoDdl] is
 * true, [register] also issues `CREATE TABLE IF NOT EXISTS` and index
 * DDL derived from the schema (rendered by [PostgresDdl]).
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

    override fun register(schema: EntitySchema) {
        if (schemas.containsKey(schema.table)) return

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

        if (autoDdl) {
            // Required extensions (e.g. pgvector) must exist before a column
            // using their type is created.
            val extensions = schema.columns
                .mapNotNull { (it.storage as? entkt.schema.ColumnStorage.Native)?.requiredExtension }
                .distinct()
            val tableDdl = ddl.createTableSql(schema)
            val indexDdl = ddl.createIndexesSql(schema)
            dataSource.connection.use { conn ->
                conn.createStatement().use { stmt ->
                    for (ext in extensions) stmt.execute("CREATE EXTENSION IF NOT EXISTS ${quote(ext)}")
                    stmt.execute(tableDdl)
                    for (sql in indexDdl) stmt.execute(sql)
                }
            }
        }

        // Cache only after optional DDL succeeds so a failed register()
        // can be retried.
        schemas.putIfAbsent(schema.table, schema)
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

    override fun <T> withTransaction(block: (Driver) -> T): T {
        val conn = dataSource.connection
        try {
            conn.autoCommit = false
            val txDriver = PostgresTransactionalDriver(conn, this, ops)
            try {
                val result = block(txDriver)
                conn.commit()
                return result
            } catch (e: Throwable) {
                conn.rollback()
                throw e
            } finally {
                txDriver.closed = true
            }
        } finally {
            conn.autoCommit = true
            conn.close()
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
}
