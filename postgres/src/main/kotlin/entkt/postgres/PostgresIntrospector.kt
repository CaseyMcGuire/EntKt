package entkt.postgres

import entkt.migrations.DatabaseIntrospector
import entkt.migrations.NormalizedColumn
import entkt.migrations.NormalizedForeignKey
import entkt.migrations.NormalizedIndex
import entkt.migrations.NormalizedSchema
import entkt.migrations.NormalizedTable
import entkt.migrations.normalizeWhere
import javax.sql.DataSource

/**
 * Introspects a live PostgreSQL database to build a [NormalizedSchema]
 * for the given managed table names.
 *
 * Queries `information_schema` plus the `pg_catalog` tables (`pg_attribute`,
 * `pg_am`, `pg_opclass`) to discover columns, primary keys, unique
 * constraints, indexes, and foreign keys.
 *
 * Serial columns (identified by a `nextval(...)` default) are mapped
 * to `serial`/`bigserial` types. Extension column types (e.g. pgvector's
 * `vector(n)`) are reconstructed via `format_type`, and non-btree indexes
 * (hnsw/ivfflat) carry their access method, operator classes, and storage
 * params — so a native-type schema round-trips without spurious drift.
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
            // Join pg_attribute for format_type so extension types (e.g. pgvector's
            // `vector(3)`) round-trip with their modifier — information_schema's
            // data_type reports only the bare "USER-DEFINED" for them.
            """
            SELECT c.column_name, c.data_type, c.udt_name, c.is_nullable, c.column_default,
                   format_type(a.atttypid, a.atttypmod) AS formatted_type
            FROM information_schema.columns c
            JOIN pg_class cl ON cl.relname = c.table_name
            JOIN pg_namespace ns ON ns.oid = cl.relnamespace AND ns.nspname = c.table_schema
            JOIN pg_attribute a ON a.attrelid = cl.oid AND a.attname = c.column_name AND NOT a.attisdropped
            WHERE c.table_schema = 'public' AND c.table_name = ?
            ORDER BY c.ordinal_position
            """.trimIndent(),
        ).use { stmt ->
            stmt.setString(1, tableName)
            stmt.executeQuery().use { rs ->
                while (rs.next()) {
                    val colName = rs.getString("column_name")
                    val rawType = rs.getString("data_type")
                    val udtName = rs.getString("udt_name")
                    val formatted = rs.getString("formatted_type")
                    val nullable = rs.getString("is_nullable") == "YES"
                    val default = rs.getString("column_default")

                    // Detect serial columns by nextval default
                    val isSerial = default != null && default.startsWith("nextval(")
                    // Extension/user-defined types (vector, etc.) carry their full
                    // declared form (vector(3)) only in format_type. canonicalize()
                    // handles the standard SQL types unchanged.
                    val isUserDefined = rawType.equals("USER-DEFINED", ignoreCase = true)
                    val sqlType = when {
                        isSerial -> when (typeMapper.canonicalize(rawType)) {
                            "integer" -> "serial"
                            "bigint" -> "bigserial"
                            else -> typeMapper.canonicalize(rawType)
                        }
                        isUserDefined -> formatted.lowercase()
                        else -> typeMapper.canonicalize(rawType)
                    }

                    columns.add(
                        NormalizedColumn(
                            name = colName,
                            sqlType = sqlType,
                            nullable = nullable,
                            primaryKey = false, // filled in later
                            // The nextval(...) default is an artifact of the
                            // serial type, not a user-declared DEFAULT — drop
                            // it so it doesn't read as schema drift.
                            default = if (isSerial) null else default,
                            // pgvector and other extension types need their
                            // extension present; surface it so a round-tripped
                            // schema records the dependency.
                            requiredExtension = if (udtName.equals("vector", ignoreCase = true)) "vector" else null,
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
        // Raw rows first; opclasses for non-btree indexes are fetched in a
        // second pass so the main ResultSet is closed before re-querying.
        data class RawIndex(
            val name: String,
            val unique: Boolean,
            val accessMethod: String,
            val columns: List<String>,
            val predicate: String?,
            val reloptions: Map<String, String>?,
        )

        val raw = mutableListOf<RawIndex>()
        conn.prepareStatement(
            """
            SELECT i.relname AS index_name,
                   ix.indisunique AS is_unique,
                   am.amname AS access_method,
                   array_agg(a.attname ORDER BY array_position(ix.indkey, a.attnum)) AS columns,
                   pg_get_expr(ix.indpred, ix.indrelid) AS predicate,
                   i.reloptions AS reloptions
            FROM pg_index ix
            JOIN pg_class t ON t.oid = ix.indrelid
            JOIN pg_class i ON i.oid = ix.indexrelid
            JOIN pg_am am ON am.oid = i.relam
            JOIN pg_namespace n ON n.oid = t.relnamespace
            JOIN pg_attribute a ON a.attrelid = t.oid AND a.attnum = ANY(ix.indkey)
            WHERE n.nspname = 'public'
              AND t.relname = ?
              AND NOT ix.indisprimary
            GROUP BY i.relname, ix.indisunique, am.amname, ix.indpred, ix.indrelid, i.reloptions
            """.trimIndent(),
        ).use { stmt ->
            stmt.setString(1, tableName)
            stmt.executeQuery().use { rs ->
                while (rs.next()) {
                    val indexName = rs.getString("index_name")
                    val isUnique = rs.getBoolean("is_unique")
                    val accessMethod = rs.getString("access_method")
                    val columns = (rs.getArray("columns").array as Array<*>).map { it.toString() }
                    val predicate = rs.getString("predicate")

                    // Skip PK indexes
                    if (columns.size == 1 && columns[0] in primaryKeys && !isUnique) continue

                    raw.add(
                        RawIndex(
                            name = indexName,
                            unique = isUnique,
                            accessMethod = accessMethod,
                            columns = columns,
                            predicate = predicate,
                            reloptions = parseReloptions(rs.getArray("reloptions")),
                        ),
                    )
                }
            }
        }

        return raw.map { r ->
            // btree is the default access method — leave the native fields null
            // so a plain index round-trips equal to its entity-derived form
            // (which carries no using/opclasses/with). Only non-btree indexes
            // (hnsw/ivfflat) reconstruct the native metadata.
            val isBtree = r.accessMethod == "btree"
            NormalizedIndex(
                columns = r.columns,
                unique = r.unique,
                name = r.name,
                where = normalizeWhere(r.predicate),
                using = if (isBtree) null else r.accessMethod,
                opclasses = if (isBtree) null else fetchOpclasses(conn, r.name),
                with = if (isBtree) null else r.reloptions,
            )
        }
    }

    /** Operator-class names for [indexName]'s columns, in column order. */
    private fun fetchOpclasses(conn: java.sql.Connection, indexName: String): List<String> {
        val opclasses = mutableListOf<String>()
        conn.prepareStatement(
            """
            SELECT opc.opcname
            FROM pg_index ix
            JOIN pg_class i ON i.oid = ix.indexrelid
            CROSS JOIN LATERAL unnest(string_to_array(ix.indclass::text, ' ')::oid[])
                WITH ORDINALITY AS u(oid, ord)
            JOIN pg_opclass opc ON opc.oid = u.oid
            WHERE i.relname = ?
            ORDER BY u.ord
            """.trimIndent(),
        ).use { stmt ->
            stmt.setString(1, indexName)
            stmt.executeQuery().use { rs ->
                while (rs.next()) opclasses.add(rs.getString("opcname"))
            }
        }
        return opclasses
    }

    /**
     * Parse Postgres `reloptions` (`{lists=100}`) into the WITH map the differ
     * compares against the entity-derived form. Null/empty → null so an index
     * with no storage params matches a desired index that sets none.
     */
    private fun parseReloptions(arr: java.sql.Array?): Map<String, String>? {
        val elements = (arr?.array as? Array<*>) ?: return null
        if (elements.isEmpty()) return null
        val map = LinkedHashMap<String, String>(elements.size)
        for (e in elements) {
            val s = e?.toString() ?: continue
            val eq = s.indexOf('=')
            if (eq > 0) map[s.substring(0, eq)] = s.substring(eq + 1)
        }
        return map.ifEmpty { null }
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
                   ccu.column_name AS target_column,
                   rc.delete_rule
            FROM information_schema.table_constraints tc
            JOIN information_schema.key_column_usage kcu
              ON tc.constraint_name = kcu.constraint_name
              AND tc.table_schema = kcu.table_schema
            JOIN information_schema.constraint_column_usage ccu
              ON tc.constraint_name = ccu.constraint_name
              AND tc.table_schema = ccu.table_schema
            JOIN information_schema.referential_constraints rc
              ON tc.constraint_name = rc.constraint_name
              AND tc.table_schema = rc.constraint_schema
            WHERE tc.table_schema = 'public'
              AND tc.table_name = ?
              AND tc.constraint_type = 'FOREIGN KEY'
            """.trimIndent(),
        ).use { stmt ->
            stmt.setString(1, tableName)
            stmt.executeQuery().use { rs ->
                while (rs.next()) {
                    val colName = rs.getString("column_name")
                    val deleteRule = rs.getString("delete_rule")
                    val onDelete = when (deleteRule) {
                        "CASCADE" -> entkt.schema.OnDelete.CASCADE
                        "SET NULL" -> entkt.schema.OnDelete.SET_NULL
                        "RESTRICT", "NO ACTION" -> entkt.schema.OnDelete.RESTRICT
                        else -> null
                    }
                    fks.add(
                        NormalizedForeignKey(
                            column = colName,
                            targetTable = rs.getString("target_table"),
                            targetColumn = rs.getString("target_column"),
                            columnNullable = nullabilityByName[colName] ?: false,
                            constraintName = rs.getString("constraint_name"),
                            onDelete = onDelete,
                        ),
                    )
                }
            }
        }
        return fks
    }
}
