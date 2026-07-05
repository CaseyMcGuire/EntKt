package entkt.postgres

import entkt.runtime.driver.ColumnMetadata
import entkt.runtime.driver.EntitySchema
import entkt.runtime.driver.IdStrategy

/**
 * Renders `register(autoDdl = true)`'s DDL from a registered
 * [EntitySchema]: the `CREATE TABLE IF NOT EXISTS` statement and the
 * `CREATE [UNIQUE] INDEX IF NOT EXISTS` statements. Distinct from
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
            val ref = col.references
            if (ref != null) {
                val onDelete = ref.onDelete.toSql(col.nullable)
                add("REFERENCES ${quote(ref.table)}(${quote(ref.column)}) ON DELETE $onDelete")
            }
        }.joinToString(" ")
        val tail = if (constraints.isEmpty()) "" else " $constraints"
        return "${quote(col.name)} $sqlType$tail"
    }

    /**
     * Build `CREATE [UNIQUE] INDEX IF NOT EXISTS` statements for both
     * composite indexes declared via [EntitySchema.indexes] and
     * single-column unique constraints from [ColumnMetadata.unique].
     * Using standalone index DDL (rather than inline `UNIQUE` in
     * `CREATE TABLE`) ensures the constraint is applied even when the
     * table already exists.
     */
    fun createIndexesSql(schema: EntitySchema): List<String> {
        val columnUniques = schema.columns
            .filter { it.unique && !it.primaryKey }
            .map { col ->
                val name = typeMapper.normalizeIdentifier("idx_${schema.table}_${col.name}_unique")
                "CREATE UNIQUE INDEX IF NOT EXISTS ${quote(name)} ON ${quote(schema.table)} (${quote(col.name)})"
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
            "$keyword IF NOT EXISTS ${quote(name)} ON ${quote(schema.table)}$usingClause ($cols)$withClause$whereSuffix"
        }

        return columnUniques + compositeIndexes
    }

    private fun isAutoSerial(schema: EntitySchema, col: ColumnMetadata): Boolean {
        if (!col.primaryKey) return false
        return schema.idStrategy == IdStrategy.AUTO_INT ||
            schema.idStrategy == IdStrategy.AUTO_LONG
    }

    private fun sqlTypeFor(schema: EntitySchema, col: ColumnMetadata): String =
        typeMapper.sqlTypeFor(col.type, col.primaryKey, schema.idStrategy, col.storage)
}
