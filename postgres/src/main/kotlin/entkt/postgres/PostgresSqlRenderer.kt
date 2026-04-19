package entkt.postgres

import entkt.migrations.MigrationOp
import entkt.migrations.MigrationSqlRenderer
import entkt.migrations.NormalizedColumn
import entkt.migrations.NormalizedTable
import entkt.migrations.RenderMode

/**
 * Renders [MigrationOp] values to PostgreSQL DDL statements.
 *
 * In [RenderMode.DEV], emits `IF NOT EXISTS` for `CREATE TABLE` and
 * `CREATE INDEX` where Postgres supports it. FK/constraint idempotency
 * in dev mode is handled upstream by the differ (if the FK already
 * exists in the introspected schema, no AddForeignKey op is emitted).
 *
 * In [RenderMode.MIGRATION_FILE], no `IF NOT EXISTS` — fail loudly on
 * drift.
 */
class PostgresSqlRenderer(
    private val typeMapper: PostgresTypeMapper = PostgresTypeMapper(),
) : MigrationSqlRenderer {

    override fun render(op: MigrationOp, mode: RenderMode): List<String> = when (op) {
        is MigrationOp.CreateTable -> renderCreateTable(op.table, mode)
        is MigrationOp.AddColumn -> renderAddColumn(op.table, op.column)
        is MigrationOp.AddIndex -> renderAddIndex(op.table, op.index, mode)
        is MigrationOp.AddForeignKey -> renderAddForeignKey(op.table, op.fk)
        // Manual ops — render as comments describing what needs to be done
        is MigrationOp.DropTable -> listOf("-- TODO: DROP TABLE ${quote(op.tableName)}")
        is MigrationOp.DropColumn -> listOf("-- TODO: ALTER TABLE ${quote(op.table)} DROP COLUMN ${quote(op.columnName)}")
        is MigrationOp.AlterColumnType -> listOf(
            "-- TODO: ALTER TABLE ${quote(op.table)} ALTER COLUMN ${quote(op.columnName)} TYPE ${op.newType}",
        )
        is MigrationOp.SetColumnNotNull -> listOf(
            "-- TODO: ALTER TABLE ${quote(op.table)} ALTER COLUMN ${quote(op.columnName)} SET NOT NULL",
        )
        is MigrationOp.DropColumnNotNull -> listOf(
            "-- TODO: ALTER TABLE ${quote(op.table)} ALTER COLUMN ${quote(op.columnName)} DROP NOT NULL",
        )
        is MigrationOp.AlterPrimaryKey -> if (op.added) {
            listOf("-- TODO: ALTER TABLE ${quote(op.table)} ADD ${quote(op.columnName)} to PRIMARY KEY (requires DROP + re-CREATE)")
        } else {
            listOf("-- TODO: ALTER TABLE ${quote(op.table)} DROP ${quote(op.columnName)} from PRIMARY KEY (requires DROP + re-CREATE)")
        }
        is MigrationOp.DropIndex -> listOf(
            "-- TODO: DROP INDEX ${quote(truncateIdentifier(op.storageKey ?: deriveIndexName(op.table, op.columns, op.unique, op.where)))}",
        )
        is MigrationOp.DropForeignKey -> listOf(
            "-- TODO: ALTER TABLE ${quote(op.table)} DROP CONSTRAINT ${quote(truncateIdentifier(op.constraintName ?: "fk_${op.table}_${op.column}"))}",
        )
    }

    /** Emits CREATE TABLE with columns + PK only. No indexes or FKs. */
    private fun renderCreateTable(table: NormalizedTable, mode: RenderMode): List<String> {
        val ifNotExists = if (mode == RenderMode.DEV) " IF NOT EXISTS" else ""
        val cols = table.columns.joinToString(",\n  ") { col ->
            renderColumnDdl(col)
        }
        return listOf("CREATE TABLE$ifNotExists ${quote(table.name)} (\n  $cols\n)")
    }

    private fun renderColumnDdl(col: NormalizedColumn): String {
        val constraints = buildList {
            if (col.primaryKey) add("PRIMARY KEY")
            if (!col.nullable && !col.primaryKey && col.sqlType !in setOf("serial", "bigserial")) {
                add("NOT NULL")
            }
        }.joinToString(" ")
        val tail = if (constraints.isEmpty()) "" else " $constraints"
        return "${quote(col.name)} ${col.sqlType}$tail"
    }

    private fun renderAddColumn(table: String, column: NormalizedColumn): List<String> {
        val nullable = if (column.nullable) "" else " NOT NULL"
        return listOf("ALTER TABLE ${quote(table)} ADD COLUMN ${quote(column.name)} ${column.sqlType}$nullable")
    }

    private fun renderAddIndex(
        table: String,
        index: entkt.migrations.NormalizedIndex,
        mode: RenderMode,
    ): List<String> {
        val ifNotExists = if (mode == RenderMode.DEV) " IF NOT EXISTS" else ""
        val cols = index.columns.joinToString(", ") { quote(it) }
        val name = truncateIdentifier(index.storageKey ?: deriveIndexName(table, index.columns, index.unique, index.where))
        val keyword = if (index.unique) "CREATE UNIQUE INDEX" else "CREATE INDEX"
        val whereSuffix = if (index.where != null) " WHERE ${index.where}" else ""
        return listOf("$keyword$ifNotExists ${quote(name)} ON ${quote(table)} ($cols)$whereSuffix")
    }

    private fun renderAddForeignKey(
        table: String,
        fk: entkt.migrations.NormalizedForeignKey,
    ): List<String> {
        val constraintName = truncateIdentifier("fk_${table}_${fk.column}")
        val onDelete = if (fk.columnNullable) "SET NULL" else "RESTRICT"
        return listOf(
            "ALTER TABLE ${quote(table)} ADD CONSTRAINT ${quote(constraintName)} " +
                "FOREIGN KEY (${quote(fk.column)}) REFERENCES ${quote(fk.targetTable)} (${quote(fk.targetColumn)}) " +
                "ON DELETE $onDelete",
        )
    }

    /**
     * Derive an index name and truncate to 63 bytes (Postgres
     * NAMEDATALEN - 1). Postgres silently truncates identifiers at this
     * limit, so the derived name must match what the DB actually stores.
     *
     * When [where] is non-null, a short hash suffix is appended so that
     * indexes on the same columns with different predicates (or a full
     * index plus a partial one) get distinct names.
     */
    private fun deriveIndexName(table: String, columns: List<String>, unique: Boolean, where: String? = null): String {
        val full = buildString {
            append("idx_$table")
            for (col in columns) append("_$col")
            if (unique) append("_unique")
            if (where != null) {
                append("_w")
                append(where.hashCode().toUInt().toString(16).take(8))
            }
        }
        return truncateIdentifier(full)
    }

    private fun truncateIdentifier(name: String): String =
        typeMapper.normalizeIdentifier(name)

    private fun quote(identifier: String): String =
        "\"${identifier.replace("\"", "\"\"")}\""
}
