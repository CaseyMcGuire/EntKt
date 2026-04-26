package entkt.migrations

import entkt.runtime.EntitySchema
import entkt.runtime.IdStrategy
import entkt.schema.OnDelete
import java.io.Reader
import java.io.Writer
import java.nio.file.Path

/**
 * Canonical, driver-agnostic representation of a database schema.
 * Two [NormalizedSchema] values can be diffed regardless of whether they
 * came from entity schemas, live DB introspection, or a committed JSON
 * snapshot.
 */
data class NormalizedSchema(
    val tables: Map<String, NormalizedTable>,
) {
    companion object {
        /**
         * Build a [NormalizedSchema] from the runtime [EntitySchema] list
         * using a [TypeMapper] for driver-specific SQL type mapping.
         *
         * Single-column `ColumnMetadata.unique = true` is normalized into
         * [NormalizedIndex] so all uniqueness constraints live in one place.
         */
        fun fromEntitySchemas(
            schemas: List<EntitySchema>,
            typeMapper: TypeMapper,
        ): NormalizedSchema {
            val tables = schemas.associate { schema ->
                val columns = schema.columns.map { col ->
                    NormalizedColumn(
                        name = col.name,
                        sqlType = typeMapper.sqlTypeFor(col.type, col.primaryKey, schema.idStrategy),
                        nullable = col.nullable,
                        primaryKey = col.primaryKey,
                    )
                }

                // Merge single-column unique constraints into the index list
                val columnUniqueIndexes = schema.columns
                    .filter { it.unique && !it.primaryKey }
                    .map { col ->
                        NormalizedIndex(
                            columns = listOf(col.name),
                            unique = true,
                            name = null,
                        )
                    }

                val compositeIndexes = schema.indexes.map { idx ->
                    NormalizedIndex(
                        columns = idx.columns,
                        unique = idx.unique,
                        name = typeMapper.normalizeIdentifier(idx.name),
                        where = idx.where,
                    )
                }

                val foreignKeys = schema.columns
                    .filter { it.references != null }
                    .map { col ->
                        NormalizedForeignKey(
                            column = col.name,
                            targetTable = col.references!!.table,
                            targetColumn = col.references!!.column,
                            columnNullable = col.nullable,
                            onDelete = col.references!!.onDelete,
                        )
                    }

                schema.table to NormalizedTable(
                    name = schema.table,
                    columns = columns,
                    indexes = columnUniqueIndexes + compositeIndexes,
                    foreignKeys = foreignKeys,
                )
            }
            return NormalizedSchema(tables)
        }

        /**
         * Deserialize from a JSON snapshot. The format is a simple
         * hand-rolled JSON to avoid pulling in a JSON library dependency.
         */
        fun fromJson(path: Path): NormalizedSchema =
            fromJson(path.toFile().reader())

        fun fromJson(reader: Reader): NormalizedSchema {
            val text = reader.use { it.readText() }
            return JsonCodec.decode(text)
        }
    }

    /**
     * Serialize to a JSON snapshot with deterministic ordering:
     * tables sorted by name, columns in declaration order, indexes
     * sorted by (columns, unique), FKs sorted by column name.
     *
     * @param parentChecksum SHA-256 of the previous snapshot's content,
     *   or null for the first snapshot in the chain.
     */
    fun toJson(path: Path, parentChecksum: String? = null) {
        path.toFile().writer().use { toJson(it, parentChecksum) }
    }

    fun toJson(writer: Writer, parentChecksum: String? = null) {
        writer.write(JsonCodec.encode(this, parentChecksum))
    }
}

data class NormalizedTable(
    val name: String,
    val columns: List<NormalizedColumn>,
    val indexes: List<NormalizedIndex>,
    val foreignKeys: List<NormalizedForeignKey>,
)

data class NormalizedColumn(
    val name: String,
    /** Canonical SQL type (e.g. "text", "integer", "serial"). */
    val sqlType: String,
    val nullable: Boolean,
    val primaryKey: Boolean,
)

data class NormalizedIndex(
    /** Column names — part of semantic identity. */
    val columns: List<String>,
    /** Whether this is a UNIQUE index — part of semantic identity. */
    val unique: Boolean,
    /** Explicit name override, or null (name is a rendering detail). */
    val name: String?,
    /** SQL WHERE clause for partial indexes — part of semantic identity. */
    val where: String? = null,
)

/**
 * Normalize a SQL WHERE predicate for comparison. PostgreSQL's
 * `pg_get_expr` deparses predicates with differences from the
 * user-written form:
 *
 * - Outer parentheses: `active = true` → `(active = true)`
 * - Type casts on columns: `active` → `(active)::boolean`
 * - Type casts on literals: `'foo'` → `'foo'::text`
 * - Whitespace differences
 *
 * This function strips balanced outer parens, removes simple
 * PostgreSQL type casts, and collapses whitespace so that the
 * deparsed and user-written forms compare equal for typical
 * predicates. For exotic expressions where this isn't enough,
 * use an explicit index name to pin the index and avoid spurious
 * diffs.
 */
fun normalizeWhere(predicate: String?): String? {
    if (predicate == null) return null
    var s = predicate.trim()

    // Strip PostgreSQL type casts:
    //   (col)::type  → col
    //   'literal'::type  → 'literal'
    //   identifier::type → identifier
    s = s.replace(Regex("\\(([^()]+)\\)::[a-z_]+"), "$1")
    s = s.replace(Regex("('[^']*')::[a-z_]+"), "$1")
    s = s.replace(Regex("([a-zA-Z_][a-zA-Z0-9_]*)::[a-z_]+"), "$1")

    // Strip balanced outer parentheses.
    while (s.startsWith("(") && s.endsWith(")")) {
        // Verify the opening paren matches the closing one (not part
        // of a compound expression like "(a = 1) OR (b = 2)").
        var depth = 0
        var wraps = false
        for ((i, c) in s.withIndex()) {
            when (c) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) {
                        wraps = i == s.length - 1
                        break
                    }
                }
            }
        }
        if (!wraps) break
        s = s.substring(1, s.length - 1).trim()
    }

    // Collapse runs of whitespace.
    return s.replace(Regex("\\s+"), " ")
}

data class NormalizedForeignKey(
    val column: String,
    val targetTable: String,
    val targetColumn: String,
    /** Whether the FK column is nullable — drives ON DELETE behavior when [onDelete] is null. */
    val columnNullable: Boolean,
    /** Actual constraint name from introspection, or null for entity-derived FKs. */
    val constraintName: String? = null,
    /** Explicit ON DELETE action, or null to infer from [columnNullable]. */
    val onDelete: OnDelete? = null,
)
