package entkt.postgres

import entkt.migrations.MigrationOp
import entkt.migrations.MigrationSqlRenderer
import entkt.migrations.NormalizedColumn
import entkt.migrations.NormalizedTable

/**
 * Renders [MigrationOp] values to PostgreSQL DDL statements.
 */
class PostgresSqlRenderer(
    private val typeMapper: PostgresTypeMapper = PostgresTypeMapper(),
) : MigrationSqlRenderer {

    override fun render(op: MigrationOp): List<String> = when (op) {
        is MigrationOp.CreateExtension -> listOf("CREATE EXTENSION IF NOT EXISTS ${quote(op.name)}")
        is MigrationOp.CreateTable -> renderCreateTable(op.table)
        is MigrationOp.AddColumn -> renderAddColumn(op.table, op.column)
        is MigrationOp.AddIndex -> renderAddIndex(op.table, op.index)
        is MigrationOp.AddForeignKey -> renderAddForeignKey(op.table, op.fk)
        is MigrationOp.SetColumnDefault -> listOf(
            "ALTER TABLE ${quote(op.table)} ALTER COLUMN ${quote(op.columnName)} SET DEFAULT ${op.default}",
        )
        is MigrationOp.DropColumnDefault -> listOf(
            "ALTER TABLE ${quote(op.table)} ALTER COLUMN ${quote(op.columnName)} DROP DEFAULT",
        )
        // Destructive / manual ops — rendered as the raw DDL the operation
        // would run. They are NOT meant to be applied as-is: the migration
        // writer emits these commented out for human review (see
        // FlywayMigrationWorkflow.buildMigrationContent). Keeping render()
        // a pure op→SQL function — commenting is the caller's concern.
        is MigrationOp.DropTable -> listOf("DROP TABLE ${quote(op.tableName)}")
        is MigrationOp.DropColumn -> listOf("ALTER TABLE ${quote(op.table)} DROP COLUMN ${quote(op.columnName)}")
        is MigrationOp.AlterColumnType -> listOf(
            "ALTER TABLE ${quote(op.table)} ALTER COLUMN ${quote(op.columnName)} TYPE ${op.newType}",
        )
        is MigrationOp.SetColumnNotNull -> listOf(
            "ALTER TABLE ${quote(op.table)} ALTER COLUMN ${quote(op.columnName)} SET NOT NULL",
        )
        is MigrationOp.DropColumnNotNull -> listOf(
            "ALTER TABLE ${quote(op.table)} ALTER COLUMN ${quote(op.columnName)} DROP NOT NULL",
        )
        is MigrationOp.DropColumnExpression -> listOf(
            "ALTER TABLE ${quote(op.table)} ALTER COLUMN ${quote(op.columnName)} DROP EXPRESSION",
        )
        // A PK change is not a single statement (it needs DROP CONSTRAINT +
        // ADD PRIMARY KEY), so there is no candidate DDL to emit. Return
        // nothing rather than a prose "hint" that would be commented as if it
        // were uncommentable SQL — the describeOp() checklist line carries the
        // guidance instead.
        is MigrationOp.AlterPrimaryKey -> emptyList()
        is MigrationOp.DropIndex -> listOf(
            "DROP INDEX ${quote(truncateIdentifier(op.name ?: deriveIndexName(op.table, op.columns, op.unique, op.where)))}",
        )
        is MigrationOp.DropForeignKey -> listOf(
            "ALTER TABLE ${quote(op.table)} DROP CONSTRAINT " +
                quote(truncateIdentifier(op.constraintName ?: "fk_${op.table}_${op.columns.joinToString("_")}")),
        )
    }

    /** Emits CREATE TABLE with columns + PK only. No indexes or FKs. */
    private fun renderCreateTable(table: NormalizedTable): List<String> {
        val cols = table.columns.joinToString(",\n  ") { col ->
            renderColumnDdl(col)
        }
        return listOf("CREATE TABLE ${quote(table.name)} (\n  $cols\n)")
    }

    private fun renderColumnDdl(col: NormalizedColumn): String {
        val parts = buildList {
            add(quote(col.name))
            add(col.sqlType)
            if (col.default != null) add("DEFAULT ${col.default}")
            if (col.primaryKey) add("PRIMARY KEY")
            if (!col.nullable && !col.primaryKey && col.sqlType !in setOf("serial", "bigserial")) {
                add("NOT NULL")
            }
        }
        return parts.joinToString(" ")
    }

    private fun renderAddColumn(table: String, column: NormalizedColumn): List<String> {
        val default = if (column.default != null) " DEFAULT ${column.default}" else ""
        val nullable = if (column.nullable) "" else " NOT NULL"
        return listOf("ALTER TABLE ${quote(table)} ADD COLUMN ${quote(column.name)} ${column.sqlType}$default$nullable")
    }

    private fun renderAddIndex(
        table: String,
        index: entkt.migrations.NormalizedIndex,
    ): List<String> {
        val name = truncateIdentifier(index.name ?: deriveIndexName(table, index.columns, index.unique, index.where))
        val keyword = if (index.unique) "CREATE UNIQUE INDEX" else "CREATE INDEX"
        // Native (pgvector) index: USING <method> (col opclass[, ...]) WITH (...).
        val cols = if (index.using != null) {
            index.columns.mapIndexed { i, c -> "${quote(c)} ${index.opclasses?.getOrNull(i).orEmpty()}".trim() }
                .joinToString(", ")
        } else {
            index.columns.joinToString(", ") { quote(it) }
        }
        val usingClause = if (index.using != null) " USING ${index.using}" else ""
        val withClause = index.with?.takeIf { it.isNotEmpty() }
            ?.entries?.joinToString(", ") { "${it.key} = ${it.value}" }
            ?.let { " WITH ($it)" } ?: ""
        val whereSuffix = if (index.where != null) " WHERE ${index.where}" else ""
        return listOf("$keyword ${quote(name)} ON ${quote(table)}$usingClause ($cols)$withClause$whereSuffix")
    }

    private fun renderAddForeignKey(
        table: String,
        fk: entkt.migrations.NormalizedForeignKey,
    ): List<String> {
        val constraintName = truncateIdentifier("fk_${table}_${fk.columns.joinToString("_")}")
        val onDelete = fk.onDelete.toSql()
        val cols = fk.columns.joinToString(", ") { quote(it) }
        val targetCols = fk.targetColumns.joinToString(", ") { quote(it) }
        return listOf(
            "ALTER TABLE ${quote(table)} ADD CONSTRAINT ${quote(constraintName)} " +
                "FOREIGN KEY ($cols) REFERENCES ${quote(fk.targetTable)} ($targetCols) " +
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
}
