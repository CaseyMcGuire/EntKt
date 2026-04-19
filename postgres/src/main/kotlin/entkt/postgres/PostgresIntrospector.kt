package entkt.postgres

import entkt.migrations.DatabaseIntrospector
import entkt.migrations.NormalizedColumn
import entkt.migrations.NormalizedForeignKey
import entkt.migrations.NormalizedIndex
import entkt.migrations.NormalizedSchema
import entkt.migrations.NormalizedTable
import javax.sql.DataSource

/**
 * Introspects a live PostgreSQL database to build a [NormalizedSchema]
 * for the given managed table names.
 *
 * Queries `information_schema.columns`, `information_schema.table_constraints`,
 * `information_schema.key_column_usage`, and `pg_indexes` to discover
 * columns, primary keys, unique constraints, indexes, and foreign keys.
 *
 * Serial columns (identified by a `nextval(...)` default) are mapped
 * to `serial`/`bigserial` types.
 */
class PostgresIntrospector(
    private val dataSource: DataSource,
    private val typeMapper: PostgresTypeMapper = PostgresTypeMapper(),
) : DatabaseIntrospector {

    override fun introspect(managedTableNames: Set<String>): NormalizedSchema {
        if (managedTableNames.isEmpty()) return NormalizedSchema(emptyMap())

        dataSource.connection.use { conn ->
            val tables = mutableMapOf<String, NormalizedTable>()

            // Find which managed tables actually exist
            val existingTables = mutableSetOf<String>()
            val placeholders = managedTableNames.joinToString(", ") { "?" }
            conn.prepareStatement(
                "SELECT table_name FROM information_schema.tables " +
                    "WHERE table_schema = 'public' AND table_name IN ($placeholders)",
            ).use { stmt ->
                var i = 1
                for (name in managedTableNames) stmt.setString(i++, name)
                stmt.executeQuery().use { rs ->
                    while (rs.next()) existingTables.add(rs.getString("table_name"))
                }
            }

            for (tableName in existingTables) {
                val columns = introspectColumns(conn, tableName)
                val primaryKeys = introspectPrimaryKeys(conn, tableName)

                val normalizedColumns = columns.map { col ->
                    col.copy(primaryKey = col.name in primaryKeys)
                }

                val indexes = introspectIndexes(conn, tableName, primaryKeys)
                val foreignKeys = introspectForeignKeys(conn, tableName, normalizedColumns)

                tables[tableName] = NormalizedTable(
                    name = tableName,
                    columns = normalizedColumns,
                    indexes = indexes,
                    foreignKeys = foreignKeys,
                )
            }

            return NormalizedSchema(tables)
        }
    }

    private fun introspectColumns(
        conn: java.sql.Connection,
        tableName: String,
    ): List<NormalizedColumn> {
        val columns = mutableListOf<NormalizedColumn>()
        conn.prepareStatement(
            """
            SELECT column_name, data_type, is_nullable, column_default
            FROM information_schema.columns
            WHERE table_schema = 'public' AND table_name = ?
            ORDER BY ordinal_position
            """.trimIndent(),
        ).use { stmt ->
            stmt.setString(1, tableName)
            stmt.executeQuery().use { rs ->
                while (rs.next()) {
                    val colName = rs.getString("column_name")
                    val rawType = rs.getString("data_type")
                    val nullable = rs.getString("is_nullable") == "YES"
                    val default = rs.getString("column_default")

                    // Detect serial columns by nextval default
                    val sqlType = if (default != null && default.startsWith("nextval(")) {
                        when (typeMapper.canonicalize(rawType)) {
                            "integer" -> "serial"
                            "bigint" -> "bigserial"
                            else -> typeMapper.canonicalize(rawType)
                        }
                    } else {
                        typeMapper.canonicalize(rawType)
                    }

                    columns.add(
                        NormalizedColumn(
                            name = colName,
                            sqlType = sqlType,
                            nullable = nullable,
                            primaryKey = false, // filled in later
                        ),
                    )
                }
            }
        }
        return columns
    }

    private fun introspectPrimaryKeys(
        conn: java.sql.Connection,
        tableName: String,
    ): Set<String> {
        val pks = mutableSetOf<String>()
        conn.prepareStatement(
            """
            SELECT kcu.column_name
            FROM information_schema.table_constraints tc
            JOIN information_schema.key_column_usage kcu
              ON tc.constraint_name = kcu.constraint_name
              AND tc.table_schema = kcu.table_schema
            WHERE tc.table_schema = 'public'
              AND tc.table_name = ?
              AND tc.constraint_type = 'PRIMARY KEY'
            """.trimIndent(),
        ).use { stmt ->
            stmt.setString(1, tableName)
            stmt.executeQuery().use { rs ->
                while (rs.next()) pks.add(rs.getString("column_name"))
            }
        }
        return pks
    }

    private fun introspectIndexes(
        conn: java.sql.Connection,
        tableName: String,
        primaryKeys: Set<String>,
    ): List<NormalizedIndex> {
        // Use pg_indexes + pg_index to get index details
        val indexes = mutableListOf<NormalizedIndex>()
        conn.prepareStatement(
            """
            SELECT i.relname AS index_name,
                   ix.indisunique AS is_unique,
                   array_agg(a.attname ORDER BY array_position(ix.indkey, a.attnum)) AS columns,
                   pg_get_expr(ix.indpred, ix.indrelid) AS predicate
            FROM pg_index ix
            JOIN pg_class t ON t.oid = ix.indrelid
            JOIN pg_class i ON i.oid = ix.indexrelid
            JOIN pg_namespace n ON n.oid = t.relnamespace
            JOIN pg_attribute a ON a.attrelid = t.oid AND a.attnum = ANY(ix.indkey)
            WHERE n.nspname = 'public'
              AND t.relname = ?
              AND NOT ix.indisprimary
            GROUP BY i.relname, ix.indisunique, ix.indpred, ix.indrelid
            """.trimIndent(),
        ).use { stmt ->
            stmt.setString(1, tableName)
            stmt.executeQuery().use { rs ->
                while (rs.next()) {
                    val indexName = rs.getString("index_name")
                    val isUnique = rs.getBoolean("is_unique")
                    val colArray = rs.getArray("columns")
                    val columns = (colArray.array as Array<*>).map { it.toString() }
                    val predicate = rs.getString("predicate")

                    // Skip PK indexes
                    if (columns.size == 1 && columns[0] in primaryKeys && !isUnique) continue

                    indexes.add(
                        NormalizedIndex(
                            columns = columns,
                            unique = isUnique,
                            storageKey = indexName,
                            where = predicate,
                        ),
                    )
                }
            }
        }
        return indexes
    }

    private fun introspectForeignKeys(
        conn: java.sql.Connection,
        tableName: String,
        columns: List<NormalizedColumn>,
    ): List<NormalizedForeignKey> {
        val nullabilityByName = columns.associate { it.name to it.nullable }
        val fks = mutableListOf<NormalizedForeignKey>()
        conn.prepareStatement(
            """
            SELECT tc.constraint_name,
                   kcu.column_name,
                   ccu.table_name AS target_table,
                   ccu.column_name AS target_column
            FROM information_schema.table_constraints tc
            JOIN information_schema.key_column_usage kcu
              ON tc.constraint_name = kcu.constraint_name
              AND tc.table_schema = kcu.table_schema
            JOIN information_schema.constraint_column_usage ccu
              ON tc.constraint_name = ccu.constraint_name
              AND tc.table_schema = ccu.table_schema
            WHERE tc.table_schema = 'public'
              AND tc.table_name = ?
              AND tc.constraint_type = 'FOREIGN KEY'
            """.trimIndent(),
        ).use { stmt ->
            stmt.setString(1, tableName)
            stmt.executeQuery().use { rs ->
                while (rs.next()) {
                    val colName = rs.getString("column_name")
                    fks.add(
                        NormalizedForeignKey(
                            column = colName,
                            targetTable = rs.getString("target_table"),
                            targetColumn = rs.getString("target_column"),
                            columnNullable = nullabilityByName[colName] ?: false,
                            constraintName = rs.getString("constraint_name"),
                        ),
                    )
                }
            }
        }
        return fks
    }
}
