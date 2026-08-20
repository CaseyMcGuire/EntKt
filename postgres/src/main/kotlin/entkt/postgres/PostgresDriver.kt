@file:OptIn(entkt.query.EntktInternal::class)

package entkt.postgres

import entkt.migrations.MigrationOp
import entkt.migrations.NormalizedSchema
import entkt.migrations.SchemaDiffer
import entkt.migrations.describeOp
import entkt.migrations.normalizeWhere
import entkt.query.OrderField
import entkt.query.Predicate
import entkt.runtime.query.AggregateFunction
import entkt.runtime.query.AggregateResultRow
import entkt.runtime.driver.Driver
import entkt.runtime.driver.DriverTransactionResult
import entkt.runtime.driver.EdgeMetadata
import entkt.runtime.driver.EntitySchema
import entkt.runtime.driver.JsonColumnCodec
import entkt.runtime.driver.KotlinxJsonCodec
import entkt.runtime.result.TransactionFailureState
import entkt.runtime.result.TransactionExecutionGuard
import entkt.runtime.query.QueryExplanation
import java.util.concurrent.ConcurrentHashMap
import javax.sql.DataSource

/**
 * Thrown — before any statement is prepared or sent — when a rendered
 * PostgreSQL statement's final bind-parameter count exceeds the
 * protocol's 65,535-parameter limit. Read terminals capture it as
 * `ReadResult.Failed` like any driver failure, so `getOrThrow()`
 * rethrows it. The count covers every parameter the statement binds —
 * relationship IDs, caller and interceptor predicates, and ordering
 * operands — because they all share the same budget. Eager
 * relationship loads with very large parent sets are the common
 * trigger: their `IN (...)` lists are not yet chunked, so the fix is
 * to reduce the root result size or split the query. Note the root
 * query of an eager load may already have executed by the time the
 * relationship statement is rejected; the over-limit statement itself
 * never reaches PostgreSQL.
 */
class PostgresBindLimitException(message: String) : RuntimeException(message)

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
 * connection. An execution-local guard rejects calls through this root
 * driver while that transaction is active on the current thread; callers
 * must use the driver supplied to the block.
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
    private val transactionExecutionGuard = TransactionExecutionGuard()

    /**
     * Serializes the mutating part of [registerAll] — validation, DDL,
     * and caching — so concurrent batches can't both decide the same
     * tables are missing and race to create them. The already-registered
     * fast path runs outside it.
     */
    private val registrationLock = Any()

    override fun registeredIdColumn(table: String): String =
        schemas[table]?.idColumn ?: error("Unregistered table: $table")

    override fun <T> copyJsonValue(table: String, column: String, value: T): T =
        ops.copyJsonValue(table, column, value)

    /**
     * Borrow a pooled connection, run [block], and release it without
     * letting the release change what the caller observes.
     *
     * Deliberately not `dataSource.connection.use { ... }`: `use` calls
     * `close()` bare on the success path, so a close failure propagates.
     * Since every operation here runs in autocommit, the write is already
     * durable by then — reporting failure would invite a retry wrapper to
     * apply it a second time. See [closeAttributingFailure], and
     * [useQuietClose] for the same rule applied to statements and result
     * sets.
     *
     * That durability premise is checked, not assumed: a pool configured
     * with `autoCommit = false` would let every statement here run in an
     * implicit transaction nobody commits — the caller sees the write
     * succeed (RETURNING works inside the open transaction) and the
     * pool's release path rolls it back. Rejecting the connection up
     * front turns that silent data loss into a configuration error.
     * `getAutoCommit()` is client-side JDBC state, so the check costs no
     * round trip. [withTransaction] borrows its own connection and is
     * exempt: it disables autocommit itself and commits explicitly, so
     * the pool's setting doesn't affect its outcome.
     */
    private inline fun <T> withConnection(block: (java.sql.Connection) -> T): T {
        transactionExecutionGuard.checkClientOperation(null)
        val conn = dataSource.connection
        // A `finally` can't see the in-flight exception, so the catch
        // records it for the release to attach to.
        var propagating: Throwable? = null
        try {
            check(conn.autoCommit) {
                "PostgresDriver requires connections with autoCommit = true: every root operation " +
                    "counts on its statement being durable when it returns, but this connection arrived " +
                    "with autoCommit = false — its writes would be silently rolled back when the " +
                    "connection is returned to the pool. Configure the pool with autoCommit = true, " +
                    "or use withTransaction { } for explicitly transactional work."
            }
            return block(conn)
        } catch (e: Throwable) {
            propagating = e
            throw e
        } finally {
            closeAttributingFailure(conn, propagating)
        }
    }

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
        transactionExecutionGuard.checkClientOperation(null)

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
                // (3.5) Guard pre-existing tables. `CREATE TABLE IF NOT
                // EXISTS` is a silent no-op on an existing table
                // whatever its shape, so body drift has to be detected
                // explicitly — otherwise registration succeeds against
                // an incompatible table and the first query or write
                // fails (or silently runs under different constraints).
                checkExistingTableBodies(fresh.values)
                withConnection { conn ->
                    conn.createStatement().useQuietClose { stmt ->
                        // (4) Every table, before any cross-table constraint.
                        for (schema in fresh.values) {
                            // Required extensions (e.g. pgvector) must exist before a
                            // column using their type is created.
                            val extensions = schema.columns
                                .mapNotNull { (it.storage as? entkt.schema.ColumnStorage.Native)?.requiredExtension }
                                .distinct()
                            for (ext in extensions) stmt.execute("CREATE EXTENSION IF NOT EXISTS ${quote(ext)}")
                            stmt.execute(ddl.createTableSql(schema))
                            for (idx in ddl.indexesFor(schema)) ensureIndex(conn, stmt, idx)
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
     * Auto-DDL guard for pre-existing tables. Applies the same policy
     * the index / FK guards use — absent → create; present and
     * equivalent → nothing; present and different → fail — to the table
     * body: columns, types, nullability, and primary key.
     *
     * The comparison runs through the migration engine's own
     * normalizer, introspector, and differ ([NormalizedSchema] /
     * [PostgresIntrospector] / [SchemaDiffer]), so what auto-DDL calls
     * drift is exactly what a generated migration would try to change.
     * Two deliberate exclusions: index and FK differences belong to
     * [ensureIndex] / [ensureForeignKey], which have the same guard
     * semantics; and column DEFAULTs are not compared at all — the
     * generated runtime `SCHEMA` literal intentionally carries no
     * default expressions, so a live default has nothing to be compared
     * against and flagging it would fail every table the migration path
     * itself created from a schema with `.default(...)` fields.
     */
    private fun checkExistingTableBodies(schemas: Collection<EntitySchema>) {
        val introspected = PostgresIntrospector(dataSource)
            .introspectTableBodies(schemas.map { it.table }.toSet())
        if (introspected.tables.isEmpty()) return
        val existing = schemas.filter { it.table in introspected.tables }
        val desired = NormalizedSchema.fromEntitySchemas(existing, PostgresTypeMapper())
        val diff = SchemaDiffer().diff(desired, introspected)
        val drift = (diff.ops + diff.manual).filter { op ->
            when (op) {
                is MigrationOp.AddColumn, is MigrationOp.DropColumn,
                is MigrationOp.AlterColumnType,
                is MigrationOp.SetColumnNotNull, is MigrationOp.DropColumnNotNull,
                is MigrationOp.AlterPrimaryKey, is MigrationOp.DropColumnExpression,
                -> true
                else -> false
            }
        }
        check(drift.isEmpty()) {
            "auto-DDL: existing table(s) differ from the requested schema:\n" +
                drift.joinToString("\n") { "  - ${describeOp(it)}" } +
                "\nAuto-DDL never alters an existing table. Reconcile with a " +
                "migration (docs/09-migrations.md), or drop the table(s) and " +
                "re-register."
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
     *
     * A free derived name is not proof of absence either: the same
     * constraint may live under another name — Postgres's own default
     * (`<table>_<column>_fkey`) on a hand-written `REFERENCES`, or a
     * DBA's choice. Creating the derived-name constraint anyway would
     * silently double-enforce the endpoints (and the migration differ
     * would then flag the twin it just created), so the endpoints are
     * checked before any create: an equivalent constraint under any
     * name satisfies the declaration; a different one fails loudly.
     */
    private fun ensureForeignKey(conn: java.sql.Connection, stmt: java.sql.Statement, fk: ForeignKeyDdl) {
        when (val existing = findConstraint(conn, fk)) {
            null -> {
                // Every live FK on the source column, whatever its name or
                // target. All of them must be the declared constraint: a
                // stale FK to a DIFFERENT target would otherwise stay
                // invisible, the blind create would double-constrain the
                // column (every write failing against a table the schema
                // never declared), and an equivalent constraint must not
                // mask a rogue twin enforcing rules nobody declared.
                val sameColumn = findSourceColumnConstraints(conn, fk)
                val foreign = sameColumn.filter { (_, c) -> !c.matches(fk) }
                check(foreign.isEmpty()) {
                    "Table '${fk.table}' already has a foreign key on (${fk.column}) under a " +
                        "different name, and it is not the constraint this schema describes. " +
                        "Auto-DDL will not alter or duplicate it — drop it, or move this schema " +
                        "onto the migration path.\n" +
                        "  schema wants: FOREIGN KEY (${fk.column}) REFERENCES " +
                        "${fk.targetTable}(${fk.targetColumn}) ON DELETE ${fk.onDelete}\n" +
                        foreign.joinToString("\n") { (name, c) ->
                            "  database has: [$name] ${c.describe()}"
                        }
                }
                if (sameColumn.isEmpty()) createForeignKey(stmt, fk)
                // else: already enforced under another name — done.
            }
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
        ).useQuietClose { stmt ->
            stmt.setString(1, fk.targetTable)
            stmt.setString(2, fk.constraintName)
            stmt.setString(3, fk.table)
            stmt.executeQuery().useQuietClose { rs ->
                if (!rs.next()) return null
                readExistingConstraint(rs)
            }
        }

    /**
     * Every live single-column FK constraint whose referencing column is
     * [fk]'s column — regardless of name or target — paired with its
     * constraint name. Used when the derived name is free: the
     * declaration may already be enforced under another name (blindly
     * creating would double-enforce it), and a stale FK to a different
     * target must surface loudly rather than silently double-constrain
     * the column. `matches` decides equivalence per row; the target
     * comparison lives there, not in the query. Partition-propagated
     * child constraints (`conparentid <> 0`) are excluded; composite
     * FKs sharing the column are out of scope, as on the derived-name
     * path.
     */
    private fun findSourceColumnConstraints(
        conn: java.sql.Connection,
        fk: ForeignKeyDdl,
    ): List<Pair<String, ExistingConstraint>> =
        conn.prepareStatement(
            """
            SELECT c.conname                             AS constraint_name,
                   c.contype = 'f'                       AS is_foreign_key,
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
            WHERE c.contype = 'f'
              AND c.conrelid = to_regclass(?)
              AND c.conparentid = 0
              AND array_length(c.conkey, 1) = 1
              AND (SELECT a.attname FROM pg_attribute a
                   WHERE a.attrelid = c.conrelid AND a.attnum = c.conkey[1]) = ?
            ORDER BY c.conname
            """.trimIndent(),
        ).useQuietClose { stmt ->
            stmt.setString(1, fk.targetTable)
            stmt.setString(2, fk.table)
            stmt.setString(3, fk.column)
            stmt.executeQuery().useQuietClose { rs ->
                val found = mutableListOf<Pair<String, ExistingConstraint>>()
                while (rs.next()) {
                    found.add(rs.getString("constraint_name") to readExistingConstraint(rs))
                }
                found
            }
        }

    private fun readExistingConstraint(rs: java.sql.ResultSet): ExistingConstraint =
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
         * for a constraint enforcing rules nobody declared. The one
         * deliberate equivalence is [restrictLikeEquivalent]: immediate
         * `RESTRICT` ≡ `NO ACTION`, matching the migration differ.
         */
        fun matches(fk: ForeignKeyDdl): Boolean =
            isForeignKey &&
                targetMatches &&
                restrictLikeEquivalent(deleteCode, fk.onDeleteCode) &&
                // Every FK this driver renders is single-column; a
                // composite one under the same name is a mismatch.
                columnCount == 1 &&
                columnName == fk.column &&
                targetColumn == fk.targetColumn &&
                restrictLikeEquivalent(updateCode, NO_ACTION) &&
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

            /** `confupdtype` / `confdeltype` code for `RESTRICT`. */
            const val RESTRICT = 'r'

            /** `confmatchtype` code for `MATCH SIMPLE`. */
            const val MATCH_SIMPLE = 's'

            /**
             * Immediate `RESTRICT` and `NO ACTION` behave identically
             * for the non-deferrable constraints this driver creates
             * (deferrability is compared strictly alongside), and
             * [entkt.migrations.SchemaDiffer] deliberately equates
             * them — so a plain hand-written `REFERENCES` (server
             * default `NO ACTION`) must not fail registration against
             * the `RESTRICT` a required edge resolves to while
             * `migrate validate` calls the same database in sync.
             */
            fun restrictLikeEquivalent(a: Char, b: Char): Boolean =
                a == b || (a in RESTRICT_LIKE_CODES && b in RESTRICT_LIKE_CODES)

            val RESTRICT_LIKE_CODES = setOf(NO_ACTION, RESTRICT)
        }
    }

    /**
     * Bring one index into existence, reconciling against whatever
     * relation already holds its name.
     *
     * `CREATE INDEX IF NOT EXISTS` skips on a *name* match — Postgres
     * never compares the definition, and the name blocks on *any*
     * relation, not just indexes. Index names here are derived
     * (`idx_<table>_<column>_unique`) or user-declared and stable, so
     * editing a declared index's uniqueness, columns, method, or
     * predicate produces a *different* index under the *same* name.
     * Executing the DDL blindly would then report success while the old
     * definition stayed in force — for a non-unique → unique flip, that
     * means duplicates stay permitted while registration claims the
     * constraint exists. Same policy as [ensureForeignKey]: absent →
     * create; present and equivalent → nothing to do; present and
     * different → fail, saying what was wanted and what is there.
     * Reconciling the difference is a migration's job, not auto-DDL's.
     */
    private fun ensureIndex(conn: java.sql.Connection, stmt: java.sql.Statement, idx: IndexDdl) {
        when (val existing = findIndex(conn, idx)) {
            null -> stmt.execute(idx.sql)
            else -> {
                check(existing.matches(idx)) {
                    "Table '${idx.table}' already has a relation named '${idx.name}', but it is not the " +
                        "index this schema describes. Auto-DDL will not alter an existing index — drop it, " +
                        "or move this schema onto the migration path.\n" +
                        "  schema wants: ${idx.sql}\n" +
                        "  database has: ${existing.describe()}"
                }
                // A definition match is not enough: an INVALID index —
                // the wreck of a failed CREATE INDEX CONCURRENTLY — is
                // ignored by the planner, and a unique one enforces
                // nothing, while its name blocks any fresh CREATE.
                // Accepting it would report success for a constraint
                // that does not exist.
                check(existing.valid) {
                    "Table '${idx.table}' has an INVALID index named '${idx.name}' (a failed " +
                        "CREATE INDEX CONCURRENTLY leaves one behind). PostgreSQL ignores it and, " +
                        "for a unique index, does not enforce uniqueness. Drop it and re-register, " +
                        "or REINDEX it: DROP INDEX ${idx.name}; / REINDEX INDEX ${idx.name};"
                }
            }
        }
    }

    /**
     * Look up whatever relation currently holds [idx]'s name, or null
     * if the name is free.
     *
     * Unlike constraint names, index names are relation names — global
     * within a schema, and `CREATE INDEX IF NOT EXISTS` skips when
     * *any* relation holds the name. So the lookup is by name within
     * the target table's namespace (not filtered to the table), and
     * "is it even an index on the right table?" is part of the
     * comparison rather than the query.
     */
    private fun findIndex(conn: java.sql.Connection, idx: IndexDdl): ExistingIndex? =
        conn.prepareStatement(
            """
            SELECT r.relkind = 'i'                              AS is_index,
                   COALESCE(ix.indrelid = to_regclass(?), false) AS table_matches,
                   COALESCE(ix.indrelid::regclass::text, '(none)') AS table_name,
                   COALESCE(ix.indisunique, false)              AS is_unique,
                   COALESCE(ix.indisvalid, false)               AS is_valid,
                   COALESCE(ix.indnkeyatts, 0)                  AS key_att_count,
                   am.amname                                    AS access_method,
                   (SELECT array_agg(a.attname ORDER BY array_position(ix.indkey, a.attnum))
                      FROM pg_attribute a
                      WHERE a.attrelid = ix.indrelid AND a.attnum = ANY(ix.indkey)
                        AND array_position(ix.indkey, a.attnum) < ix.indnkeyatts) AS columns,
                   pg_get_expr(ix.indpred, ix.indrelid)         AS predicate,
                   (SELECT array_agg(opc.opcname ORDER BY u.ord)
                      FROM unnest(string_to_array(ix.indclass::text, ' ')::oid[])
                           WITH ORDINALITY AS u(oid, ord)
                      JOIN pg_opclass opc ON opc.oid = u.oid)   AS opclasses,
                   r.reloptions                                 AS reloptions
            FROM pg_class r
            LEFT JOIN pg_index ix ON ix.indexrelid = r.oid
            LEFT JOIN pg_am am ON am.oid = r.relam
            WHERE r.relname = ?
              AND r.relnamespace = (SELECT t.relnamespace FROM pg_class t WHERE t.oid = to_regclass(?))
            """.trimIndent(),
        ).useQuietClose { stmt ->
            stmt.setString(1, idx.table)
            stmt.setString(2, idx.name)
            stmt.setString(3, idx.table)
            stmt.executeQuery().useQuietClose { rs ->
                if (!rs.next()) return null
                val keyColumns =
                    (rs.getArray("columns")?.array as? Array<*>)?.map { it.toString() } ?: emptyList()
                val keyAttCount = rs.getInt("key_att_count")
                ExistingIndex(
                    isIndex = rs.getBoolean("is_index"),
                    tableMatches = rs.getBoolean("table_matches"),
                    tableName = rs.getString("table_name"),
                    unique = rs.getBoolean("is_unique"),
                    valid = rs.getBoolean("is_valid"),
                    accessMethod = rs.getString("access_method"),
                    // Expression key slots resolve no pg_attribute row; pad
                    // them so a mixed index like UNIQUE (a, lower(c)) can
                    // never pass for the plain UNIQUE (a) this schema
                    // declares — it does not enforce uniqueness on `a`.
                    columns = keyColumns +
                        List((keyAttCount - keyColumns.size).coerceAtLeast(0)) { "<expression>" },
                    predicate = rs.getString("predicate"),
                    opclasses = (rs.getArray("opclasses")?.array as? Array<*>)?.map { it.toString() },
                    reloptions = parseReloptions(rs.getArray("reloptions")),
                )
            }
        }

    /** `pg_class.reloptions` entries (`"k=v"` strings) as a map, or null when none. */
    private fun parseReloptions(arr: java.sql.Array?): Map<String, String>? {
        val items = (arr?.array as? Array<*>)?.map { it.toString() } ?: return null
        if (items.isEmpty()) return null
        return items.mapNotNull { opt ->
            val eq = opt.indexOf('=')
            if (eq < 0) null else opt.substring(0, eq) to opt.substring(eq + 1)
        }.toMap()
    }

    /** A relation already present in the catalog under an index's name. */
    private data class ExistingIndex(
        val isIndex: Boolean,
        val tableMatches: Boolean,
        val tableName: String,
        val unique: Boolean,
        val valid: Boolean,
        val accessMethod: String?,
        val columns: List<String>,
        val predicate: String?,
        val opclasses: List<String>?,
        val reloptions: Map<String, String>?,
    ) {
        /**
         * Whether this is exactly the index [idx] describes. Uniqueness,
         * ordered columns, access method, and the partial-index
         * predicate are always compared — those are the attributes
         * whose silent drift produces wrong constraints or wrong plans.
         * Predicates go through [normalizeWhere] on both sides because
         * `pg_get_expr` deparses with casts and parens the declared
         * text doesn't have. Operator classes and storage parameters
         * are compared only when the schema declares them: for btree
         * the catalog reports implicit defaults that a null declaration
         * can't be checked against.
         */
        fun matches(idx: IndexDdl): Boolean =
            isIndex &&
                tableMatches &&
                unique == idx.unique &&
                columns == idx.columns &&
                accessMethod == (idx.using ?: "btree") &&
                normalizeWhere(predicate, idx.columnTypes) == normalizeWhere(idx.where, idx.columnTypes) &&
                (idx.opclasses == null || opclasses == idx.opclasses) &&
                (idx.with == null || reloptions == idx.with)

        /** Human-readable rendering for the mismatch error. */
        fun describe(): String {
            if (!isIndex) return "a relation of this name that is not an index"
            return buildString {
                if (!valid) append("INVALID ")
                append(if (unique) "UNIQUE INDEX" else "INDEX")
                append(" on $tableName USING ${accessMethod ?: "btree"} (${columns.joinToString(", ")})")
                if (predicate != null) append(" WHERE $predicate")
                if (reloptions != null) append(" WITH (${reloptions.entries.joinToString(", ") { "${it.key} = ${it.value}" }})")
            }
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
        withConnection { ops.insert(it, table, values) }

    override fun insertIgnore(
        table: String,
        values: Map<String, Any?>,
        conflictColumns: List<String>,
    ): Map<String, Any?>? =
        withConnection { ops.insertIgnore(it, table, values, conflictColumns) }

    override fun update(table: String, id: Any, values: Map<String, Any?>): Map<String, Any?>? =
        withConnection { ops.update(it, table, id, values) }

    override fun byId(table: String, id: Any): Map<String, Any?>? =
        withConnection { ops.byId(it, table, id) }

    override fun query(
        table: String,
        predicates: List<Predicate<*>>,
        orderBy: List<OrderField<*>>,
        limit: Int?,
        offset: Int?,
    ): List<Map<String, Any?>> =
        withConnection { ops.query(it, table, predicates, orderBy, limit, offset) }

    override fun requireBindCapacity(minimumParameters: Long, table: String) =
        requirePostgresBindCapacity(minimumParameters, table)

    override fun explainQuery(
        table: String,
        predicates: List<Predicate<*>>,
        orderBy: List<OrderField<*>>,
        limit: Int?,
        offset: Int?,
    ): QueryExplanation {
        transactionExecutionGuard.checkClientOperation(null)
        val prepared = ops.buildSelectSql(table, predicates, orderBy, limit, offset)
        return PostgresQueryExplanation(prepared.sql, prepared.params.map { it.value })
    }

    override fun explainCount(
        table: String,
        predicates: List<Predicate<*>>,
    ): QueryExplanation {
        transactionExecutionGuard.checkClientOperation(null)
        val prepared = ops.buildCountSql(table, predicates)
        return PostgresQueryExplanation(prepared.sql, prepared.params.map { it.value })
    }

    override fun count(table: String, predicates: List<Predicate<*>>): Long =
        withConnection { ops.count(it, table, predicates) }

    override fun exists(table: String, predicates: List<Predicate<*>>): Boolean =
        withConnection { ops.exists(it, table, predicates) }

    override fun aggregate(
        table: String,
        function: AggregateFunction,
        column: String?,
        predicates: List<Predicate<*>>,
        groupBy: String?,
    ): List<AggregateResultRow> =
        withConnection { ops.aggregate(it, table, function, column, predicates, groupBy) }

    override fun delete(table: String, id: Any): Boolean =
        withConnection { ops.delete(it, table, id) }

    override fun insertMany(table: String, values: List<Map<String, Any?>>): List<Map<String, Any?>> =
        withConnection { ops.insertMany(it, table, values) }

    override fun updateMany(table: String, values: Map<String, Any?>, predicates: List<Predicate<*>>): Int =
        withConnection { ops.updateMany(it, table, values, predicates) }

    override fun deleteMany(table: String, predicates: List<Predicate<*>>): Int =
        withConnection { ops.deleteMany(it, table, predicates) }

    override fun deleteManyByIds(
        table: String,
        idColumn: String,
        ids: List<Any>,
        predicates: List<Predicate<*>>,
    ): List<Any> = withConnection { ops.deleteManyByIds(it, table, idColumn, ids, predicates) }

    // ---------- Transactions ----------

    /**
     * Run [block] against a connection-pinned driver inside one
     * transaction, reporting the outcome structurally per the
     * [Driver.withTransaction] write-certainty contract: `Success`
     * only after a confirmed commit; an ordinary block failure with a
     * confirmed rollback is `Failed(exception, NotCommitted)`; a
     * failed rollback or a failed commit is
     * `Failed(exception, OutcomeUnknown)` — a failed COMMIT may
     * already have reached the server, so a later apparently
     * successful rollback never downgrades it to `NotCommitted`.
     * A `CancellationException` is rethrown only when rollback is
     * confirmed before commit; commit-time cancellation or an
     * unconfirmed rollback is `Failed(exception, OutcomeUnknown)`.
     * JVM `Error`s roll back and rethrow because the result algebra
     * stores only `Exception`s.
     *
     * **Cleanup never changes the observed outcome.** Three separate
     * failures can happen while unwinding — `rollback()`, restoring
     * `autoCommit`, and `close()` — and none of them may replace the
     * result the caller would otherwise see:
     *
     *  - A `rollback()` failure is attached to the outcome's stored
     *    (or propagating) exception via `addSuppressed` rather than
     *    reported in its place — and flips the failure state to
     *    `OutcomeUnknown`, the one sanctioned way cleanup affects the
     *    outcome.
     *  - Connection-release failures after a **successful commit** are
     *    swallowed. Surfacing them would report failure for durably
     *    committed work, and any retry wrapper would then re-apply an
     *    already-applied transaction.
     *  - Connection-release failures on a failure path are attached to
     *    the stored (or propagating) exception as suppressed.
     *
     * `close()` runs even when restoring `autoCommit` throws — the two
     * are released independently so a failed restore can't leak the
     * pooled connection.
     */
    override fun <T> withTransaction(block: (Driver) -> T): DriverTransactionResult<T> {
        val executionToken = transactionExecutionGuard.enterTransaction()
        return try {
            runTransaction(block)
        } finally {
            transactionExecutionGuard.exitTransaction(executionToken)
        }
    }

    private fun <T> runTransaction(block: (Driver) -> T): DriverTransactionResult<T> {
        val conn = dataSource.connection
        // The exception the caller will observe — thrown (cancellation
        // / JVM errors / setup failures) or stored in Failed. Cleanup
        // failures attach here rather than supplanting the outcome.
        var attachTo: Throwable? = null
        // Whether the transaction reached a decided end (committed or
        // rolled back). Gates the autocommit restore — see
        // [releaseConnection].
        var resolved = false
        try {
            conn.autoCommit = false
            val txDriver = PostgresTransactionalDriver(conn, this, ops)
            val result: T = try {
                block(txDriver)
            } catch (e: Throwable) {
                attachTo = e
                val rolledBack = rollbackAttributingFailure(conn, e)
                resolved = rolledBack
                if (e is java.util.concurrent.CancellationException && rolledBack) {
                    // Pre-commit cancellation is safe to rethrow only
                    // after rollback is confirmed.
                    throw e
                }
                if (e !is Exception) {
                    throw e
                }
                return DriverTransactionResult.Failed(
                    e,
                    if (rolledBack) TransactionFailureState.NotCommitted
                    else TransactionFailureState.OutcomeUnknown,
                )
            } finally {
                txDriver.closed = true
            }
            // A block that swallowed a failed statement's error and
            // completed anyway cannot commit: the transaction is in
            // PostgreSQL's aborted state and pgjdbc would silently
            // turn the COMMIT into a ROLLBACK — success reported,
            // nothing persisted. Roll back and report the aborted
            // state as a failure instead.
            val aborted = try {
                transactionAborted(conn)
            } catch (inspectionFailure: Throwable) {
                attachTo = inspectionFailure
                val rolledBack = rollbackAttributingFailure(conn, inspectionFailure)
                resolved = rolledBack
                if (inspectionFailure is java.util.concurrent.CancellationException && rolledBack) {
                    throw inspectionFailure
                }
                if (inspectionFailure !is Exception) {
                    throw inspectionFailure
                }
                return DriverTransactionResult.Failed(
                    inspectionFailure,
                    if (rolledBack) TransactionFailureState.NotCommitted
                    else TransactionFailureState.OutcomeUnknown,
                )
            }
            if (aborted) {
                val aborted = IllegalStateException(TRANSACTION_ABORTED_MESSAGE)
                attachTo = aborted
                val rolledBack = rollbackAttributingFailure(conn, aborted)
                resolved = rolledBack
                return DriverTransactionResult.Failed(
                    aborted,
                    if (rolledBack) TransactionFailureState.NotCommitted
                    else TransactionFailureState.OutcomeUnknown,
                )
            }
            return try {
                conn.commit()
                resolved = true
                DriverTransactionResult.Success(result)
            } catch (commitFailure: Throwable) {
                attachTo = commitFailure
                // The failed COMMIT may already have reached the
                // server. Roll back for connection hygiene, but the
                // outcome stays OutcomeUnknown regardless of whether
                // that rollback appears to succeed.
                resolved = rollbackAttributingFailure(conn, commitFailure)
                if (commitFailure !is Exception) {
                    // JVM errors cannot be stored in the result
                    // algebra. Cancellation is an Exception and stays
                    // structural here because commit may have happened.
                    throw commitFailure
                }
                DriverTransactionResult.Failed(
                    commitFailure,
                    TransactionFailureState.OutcomeUnknown,
                )
            }
        } catch (e: Throwable) {
            // Setup failures (autoCommit=false) and rethrown
            // cancellation / JVM errors pass through here.
            if (attachTo == null) attachTo = e
            throw e
        } finally {
            releaseConnection(conn, attachTo, resolved)
        }
    }

    /**
     * Release [conn], routing any failure into [propagating] as a
     * suppressed exception instead of throwing. See [withTransaction] for
     * why cleanup must never be the thing the caller observes, and
     * [restoreAutoCommit] for why the restore is conditional.
     */
    private fun releaseConnection(
        conn: java.sql.Connection,
        propagating: Throwable?,
        transactionResolved: Boolean,
    ) {
        restoreAutoCommit(conn, propagating, transactionResolved)
        // Deliberately a separate statement, not chained to the call
        // above: a failed autoCommit restore must not skip close() and
        // leak the connection.
        closeAttributingFailure(conn, propagating)
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

    // ---------- Driver exception classification (write certainty) ----------

    /**
     * Classify a [PSQLException] thrown by one of this driver's
     * mutation statement executions into a state-bearing typed
     * exception. SQLSTATE classes covered:
     *
     *  - `23xxx` (integrity constraint violation): UNIQUE (`23505`),
     *    FOREIGN KEY (`23503`), CHECK (`23514`), NOT NULL (`23502`),
     *    EXCLUSION (`23P01`) — a typed
     *    [entkt.runtime.result.EntConstraintViolationException]
     *    (which hardcodes `NotPersisted`; the failed statement's
     *    effect did not persist) with the SQLSTATE preserved as
     *    `driverCode` and the original exception as the cause. When
     *    the server attached `ServerErrorMessage` metadata (typical
     *    for constraint errors), `constraint` and `field` are
     *    populated from it; otherwise they're `null`.
     *  - `40001` (serialization failure) / `40P01` (deadlock
     *    detected): a typed
     *    [entkt.runtime.result.EntConflictException] (also
     *    `NotPersisted`). Statement-level only: these classifications
     *    describe a failed *statement*, whose effect did not persist.
     *    Commit-path failures never reach classification — the
     *    transaction boundary reports those as `OutcomeUnknown`
     *    itself.
     *
     * Anything unrecognized returns `null`; the generated terminal
     * then stores the original exception with its phase-derived
     * state. Read execution never calls this method.
     */
    override fun classifyMutationException(
        exception: Exception,
        entity: String,
        operation: entkt.runtime.result.EntOperation,
    ): entkt.runtime.result.EntMutationException? {
        if (exception !is org.postgresql.util.PSQLException) return null
        val state = exception.sqlState ?: return null
        if (state.startsWith("23")) {
            val server = exception.serverErrorMessage
            return entkt.runtime.result.EntConstraintViolationException(
                entityType = entity,
                operation = operation,
                constraint = server?.constraint,
                field = server?.column,
                driverCode = state,
                message = exception.message ?: "constraint violation",
                cause = exception,
            )
        }
        if (state == "40001" || state == "40P01") {
            return entkt.runtime.result.EntConflictException(
                entityType = entity,
                operation = operation,
                code = state,
                message = exception.message ?: "serialization conflict",
                cause = exception,
            )
        }
        return null
    }

    private companion object {
        /** SQLSTATE for `relation "..." does not exist`. */
        const val UNDEFINED_TABLE = "42P01"
    }
}
