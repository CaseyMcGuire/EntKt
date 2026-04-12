package entkt.migrations

import entkt.runtime.EntitySchema
import entkt.runtime.IdStrategy
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
                            storageKey = null,
                        )
                    }

                val compositeIndexes = schema.indexes.map { idx ->
                    NormalizedIndex(
                        columns = idx.columns,
                        unique = idx.unique,
                        storageKey = idx.storageKey?.let { typeMapper.normalizeIdentifier(it) },
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
     */
    fun toJson(path: Path) {
        path.toFile().writer().use { toJson(it) }
    }

    fun toJson(writer: Writer) {
        writer.write(JsonCodec.encode(this))
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
    val storageKey: String?,
)

data class NormalizedForeignKey(
    val column: String,
    val targetTable: String,
    val targetColumn: String,
    /** Whether the FK column is nullable — drives ON DELETE behavior. */
    val columnNullable: Boolean,
    /** Actual constraint name from introspection, or null for entity-derived FKs. */
    val constraintName: String? = null,
)
