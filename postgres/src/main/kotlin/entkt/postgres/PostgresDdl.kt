package entkt.postgres

import entkt.runtime.driver.ColumnMetadata
import entkt.runtime.driver.EntitySchema
import entkt.runtime.driver.IdStrategy

/**
 * The DDL for one foreign key constraint, produced by
 * [PostgresDdl.foreignKeysFor].
 *
 * Carries [table] / [column] / [targetTable] alongside the statement so
 * a failure can name what it was trying to link. See
 * [PostgresDdl.foreignKeysFor] for why FKs are split out of
 * `CREATE TABLE` at all.
 */
internal data class ForeignKeyDdl(
    /** Table the constraint is added to. */
    val table: String,
    /** FK column on [table]. */
    val column: String,
    /** Table the FK points at — must exist before [sql] can run. */
    val targetTable: String,
    /** Referenced column on [targetTable]. */
    val targetColumn: String,
    /** `ON DELETE` action as SQL, e.g. `CASCADE`. */
    val onDelete: String,
    /**
     * `pg_constraint.confdeltype` code matching [onDelete], for
     * comparing against a constraint already in the catalog.
     */
    val onDeleteCode: Char,
    /** Derived constraint name, `fk_<table>_<column>`. */
    val constraintName: String,
    /**
     * Plain `ALTER TABLE ... ADD CONSTRAINT`. Applying this idempotently
     * is the driver's job — see `PostgresDriver.ensureForeignKey`, which
     * reconciles against the catalog first because `ADD CONSTRAINT` has
     * no `IF NOT EXISTS` form.
     */
    val sql: String,
)

/**
 * One index the schema declares, in both structured and executable
 * form. The structured fields exist for reconciliation:
 * `CREATE INDEX IF NOT EXISTS` skips on a name match without comparing
 * definitions, so `PostgresDriver.ensureIndex` compares these against
 * `pg_index` before trusting the name — the index-side analogue of
 * [ForeignKeyDdl]'s `pg_constraint` fields.
 */
internal data class IndexDdl(
    /** Table the index is created on. */
    val table: String,
    /** Index name: derived (`idx_<table>_<col>_unique`) or declared. */
    val name: String,
    val unique: Boolean,
    /** Column names in index order. */
    val columns: List<String>,
    /** Declared partial-index predicate, or null. */
    val where: String?,
    /** Access method (`hnsw` / `ivfflat`), or null for btree. */
    val using: String?,
    /** Declared per-column operator classes, or null. */
    val opclasses: List<String>?,
    /** Declared storage parameters, or null when none. */
    val with: Map<String, String>?,
    /** The `CREATE [UNIQUE] INDEX IF NOT EXISTS` statement. */
    val sql: String,
    /**
     * Lowercased column name → declared SQL type for the whole table,
     * so predicate reconciliation can tell a deparser-added textual
     * promotion from a semantic cast (citext) — see
     * [entkt.migrations.normalizeWhere].
     */
    val columnTypes: Map<String, String> = emptyMap(),
)

/**
 * Renders `register(autoDdl = true)`'s DDL from a registered
 * [EntitySchema]: the `CREATE TABLE IF NOT EXISTS` statement, the
 * `CREATE [UNIQUE] INDEX IF NOT EXISTS` statements, and the
 * [ForeignKeyDdl]s carrying its foreign key constraints. Distinct from
 * [PostgresSqlRenderer], which renders migration-path [entkt.migrations.MigrationOp]s
 * from a normalized schema diff — this class renders directly from runtime
 * entity metadata for the auto-DDL convenience path.
 */
internal class PostgresDdl {

    private val typeMapper = PostgresTypeMapper()

    fun createTableSql(schema: EntitySchema): String {
        val cols = schema.columns.joinToString(",\n  ") { col ->
            renderColumnDdl(schema, col)
        }
        return "CREATE TABLE IF NOT EXISTS ${quote(schema.table)} (\n  $cols\n)"
    }

    private fun renderColumnDdl(schema: EntitySchema, col: ColumnMetadata): String {
        val sqlType = sqlTypeFor(schema, col)
        val constraints = buildList {
            if (col.primaryKey) add("PRIMARY KEY")
            if (!col.nullable && !col.primaryKey && !isAutoSerial(schema, col)) add("NOT NULL")
            // Foreign keys are deliberately NOT rendered inline — see
            // [foreignKeysFor].
        }.joinToString(" ")
        val tail = if (constraints.isEmpty()) "" else " $constraints"
        return "${quote(col.name)} $sqlType$tail"
    }

    /**
     * Build one [ForeignKeyDdl] per FK column on [schema], as a
     * standalone `ALTER TABLE ... ADD CONSTRAINT` rather than an inline
     * `REFERENCES` in [createTableSql].
     *
     * **Why not inline.** An inline `REFERENCES` requires the target
     * table to already exist. For mutually-referencing entities
     * (`user.pinned_post_id → posts`, `post.author_id → users`) no
     * creation order satisfies both: codegen's topological sort breaks
     * the cycle arbitrarily, and whichever table lands first fails with
     * `42P01 undefined_table`. Separating these from [createTableSql]
     * lets `PostgresDriver.registerAll` create every table in a batch
     * before adding any constraint, at which point ordering is
     * irrelevant. This also matches [PostgresSqlRenderer], which has
     * always emitted FKs as separate `ALTER TABLE` statements on the
     * migration path.
     *
     * The rendered statement is a plain `ADD CONSTRAINT`, which is not
     * idempotent — registration is routinely replayed across driver
     * instances and test runs, so `PostgresDriver.ensureForeignKey`
     * reconciles against `pg_constraint` before running it. The
     * structured fields here are what that comparison needs.
     */
    fun foreignKeysFor(schema: EntitySchema): List<ForeignKeyDdl> =
        schema.columns.mapNotNull { col ->
            val ref = col.references ?: return@mapNotNull null
            val name = constraintNameFor(schema.table, col.name)
            val onDelete = ref.onDelete.toSql(col.nullable)
            ForeignKeyDdl(
                table = schema.table,
                column = col.name,
                targetTable = ref.table,
                targetColumn = ref.column,
                onDelete = onDelete,
                onDeleteCode = confDeleteTypeFor(onDelete),
                constraintName = name,
                sql = "ALTER TABLE ${quote(schema.table)} ADD CONSTRAINT ${quote(name)} " +
                    "FOREIGN KEY (${quote(col.name)}) REFERENCES ${quote(ref.table)}(${quote(ref.column)}) " +
                    "ON DELETE $onDelete",
            )
        }

    /**
     * Map an `ON DELETE` clause to the `pg_constraint.confdeltype` code
     * Postgres stores for it, so a rendered constraint can be compared
     * against one already in the catalog.
     */
    private fun confDeleteTypeFor(onDelete: String): Char = when (onDelete) {
        "CASCADE" -> 'c'
        "SET NULL" -> 'n'
        "RESTRICT" -> 'r'
        "SET DEFAULT" -> 'd'
        "NO ACTION" -> 'a'
        else -> error("Unmapped ON DELETE action '$onDelete' — cannot verify against pg_constraint")
    }

    /**
     * Constraint name for an FK column, matching
     * [PostgresSqlRenderer]'s `fk_<table>_<column>` convention so the
     * auto-DDL and migration paths converge on the same schema.
     */
    private fun constraintNameFor(table: String, column: String): String =
        typeMapper.normalizeIdentifier("fk_${table}_$column")

    /**
     * Build structured `CREATE [UNIQUE] INDEX IF NOT EXISTS` DDL for
     * both composite indexes declared via [EntitySchema.indexes] and
     * single-column unique constraints from [ColumnMetadata.unique].
     * Using standalone index DDL (rather than inline `UNIQUE` in
     * `CREATE TABLE`) ensures the constraint is applied even when the
     * table already exists.
     *
     * Each entry carries the declared attributes alongside the
     * executable [IndexDdl.sql], because `IF NOT EXISTS` alone is not a
     * safe idempotent apply: Postgres skips on a *name* match without
     * comparing the definition. `PostgresDriver.ensureIndex` uses the
     * structured fields to reconcile against `pg_index` — the same
     * arrangement [foreignKeysFor] has with `pg_constraint`.
     */
    fun indexesFor(schema: EntitySchema): List<IndexDdl> {
        val columnTypes = schema.columns.associate { it.name.lowercase() to sqlTypeFor(schema, it) }
        val columnUniques = schema.columns
            .filter { it.unique && !it.primaryKey }
            .map { col ->
                val name = typeMapper.normalizeIdentifier("idx_${schema.table}_${col.name}_unique")
                IndexDdl(
                    table = schema.table,
                    name = name,
                    unique = true,
                    columns = listOf(col.name),
                    where = null,
                    using = null,
                    opclasses = null,
                    with = null,
                    sql = "CREATE UNIQUE INDEX IF NOT EXISTS ${quote(name)} ON ${quote(schema.table)} (${quote(col.name)})",
                    columnTypes = columnTypes,
                )
            }

        val compositeIndexes = schema.indexes.map { idx ->
            val name = typeMapper.normalizeIdentifier(idx.name)
            val keyword = if (idx.unique) "CREATE UNIQUE INDEX" else "CREATE INDEX"
            // Native (pgvector) index: USING <method> (col opclass[, ...]) WITH (...).
            // Btree: (col[, ...]) with an optional partial WHERE.
            val cols = if (idx.using != null) {
                idx.columns.mapIndexed { i, c -> "${quote(c)} ${idx.opclasses?.getOrNull(i).orEmpty()}".trim() }
                    .joinToString(", ")
            } else {
                idx.columns.joinToString(", ") { quote(it) }
            }
            val usingClause = if (idx.using != null) " USING ${idx.using}" else ""
            val withClause = idx.with?.takeIf { it.isNotEmpty() }
                ?.entries?.joinToString(", ") { "${it.key} = ${it.value}" }
                ?.let { " WITH ($it)" } ?: ""
            val whereSuffix = if (idx.where != null) " WHERE ${idx.where}" else ""
            IndexDdl(
                table = schema.table,
                name = name,
                unique = idx.unique,
                columns = idx.columns,
                where = idx.where,
                using = idx.using,
                opclasses = idx.opclasses,
                with = idx.with?.takeIf { it.isNotEmpty() },
                sql = "$keyword IF NOT EXISTS ${quote(name)} ON ${quote(schema.table)}$usingClause ($cols)$withClause$whereSuffix",
                columnTypes = columnTypes,
            )
        }

        return columnUniques + compositeIndexes
    }

    /** The executable statements from [indexesFor], for callers that only render. */
    fun createIndexesSql(schema: EntitySchema): List<String> = indexesFor(schema).map { it.sql }

    private fun isAutoSerial(schema: EntitySchema, col: ColumnMetadata): Boolean {
        if (!col.primaryKey) return false
        return schema.idStrategy == IdStrategy.AUTO_INT ||
            schema.idStrategy == IdStrategy.AUTO_LONG
    }

    private fun sqlTypeFor(schema: EntitySchema, col: ColumnMetadata): String =
        typeMapper.sqlTypeFor(col.type, col.primaryKey, schema.idStrategy, col.storage)
}
