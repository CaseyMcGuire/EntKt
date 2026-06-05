package entkt.migrations

import entkt.runtime.EntitySchema
import entkt.runtime.IdStrategy
import entkt.schema.FieldType
import entkt.schema.OnDelete

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
                        sqlType = typeMapper.sqlTypeFor(col.type, col.primaryKey, schema.idStrategy, col.storage),
                        nullable = col.nullable,
                        primaryKey = col.primaryKey,
                        default = typeMapper.formatDefault(col.type, col.default),
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
    /**
     * SQL `DEFAULT` expression for the column, or null when none.
     *
     * Entity-derived columns hold the dialect form produced by
     * [TypeMapper.formatDefault] (e.g. `'ACTIVE'`, `true`, `42`,
     * `now()`); introspected columns hold the raw `column_default` the
     * database reports. The two forms are reconciled through
     * [normalizeDefault] before comparison, so the differ doesn't emit
     * spurious changes for cosmetic differences (type casts, parens).
     */
    val default: String? = null,
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

    s = stripBalancedOuterParens(s)

    // Collapse runs of whitespace.
    return s.replace(Regex("\\s+"), " ")
}

/**
 * Strip balanced outer parentheses from [s], repeatedly, as long as the
 * leading `(` matches the trailing `)` (i.e. they actually wrap the whole
 * expression, not a compound like `(a = 1) OR (b = 2)`).
 */
private fun stripBalancedOuterParens(input: String): String {
    var s = input
    while (s.startsWith("(") && s.endsWith(")")) {
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
    return s
}

/**
 * Canonicalize a SQL column `DEFAULT` expression so the entity-derived
 * form (from [formatSqlDefault]) and the database-reported form (from
 * `information_schema.columns.column_default`) compare equal when they
 * mean the same thing. PostgreSQL decorates stored defaults in ways the
 * DSL form omits:
 *
 * - Type casts: `'ACTIVE'::text`, `'5'::bigint`, `1.5::double precision`
 * - Parens around negative numerics: `(-5)`
 * - Quoting of numeric literals on wider types: `'5'::bigint` vs `5`
 *
 * A quoted string literal (optionally cast) is reduced to its inner
 * content verbatim, so a `::` *inside* the value (e.g. a default of
 * `'a::b'`) is never mistaken for a type cast. Everything else (bare
 * numerics, `now()`, `true`) has casts and balanced outer parens
 * stripped and whitespace collapsed. A quoted numeric like `'5'::bigint`
 * therefore reduces to `5`, reconciling with the bare `5` form. Because
 * BOTH sides pass through this function, the entity-derived and
 * database-reported forms converge whenever they mean the same thing.
 */
fun normalizeDefault(expr: String?): String? {
    if (expr == null) return null
    val s = expr.trim()

    // A single-quoted string literal, optionally followed by a cast
    // (e.g. 'active'::text, '5'::bigint, 'a::b'). Reduce to the inner
    // content verbatim — never run the cast regex over a value's bytes.
    QUOTED_DEFAULT_LITERAL.matchEntire(s)?.let { return it.groupValues[1] }

    // Bare (unquoted) forms: strip type casts — ::text, ::bigint,
    // ::double precision, ::character varying(255) — then balanced outer
    // parens (e.g. Postgres wraps negative numerics as (-5)), then
    // collapse whitespace.
    var t = s.replace(CAST_SUFFIX, "").trim()
    t = stripBalancedOuterParens(t)
    return t.replace(Regex("\\s+"), " ")
}

/** PostgreSQL type-cast suffix, including multi-word types and length modifiers. */
private val CAST_SUFFIX =
    Regex("::\\s*\"?[a-zA-Z_][a-zA-Z0-9_]*(\\s+[a-zA-Z_][a-zA-Z0-9_]*)*\"?(\\s*\\([0-9, ]*\\))?")

/** A whole-string single-quoted literal (quotes doubled for escapes), optionally cast. */
private val QUOTED_DEFAULT_LITERAL =
    Regex("^'((?:[^']|'')*)'(?:$CAST_SUFFIX)?$")

/**
 * Render a raw schema-DSL default [value] into a PostgreSQL `DEFAULT`
 * expression, or null when [value] is null. This is the form baked into
 * `CREATE TABLE` / `ADD COLUMN` DDL and compared (via [normalizeDefault])
 * against the database's reported default.
 *
 * entkt targets PostgreSQL, so the dialect choices here (`now()`,
 * `true`/`false`, single-quoted string/enum literals) are Postgres'.
 * It is exposed as the default body of [TypeMapper.formatDefault].
 */
fun formatSqlDefault(fieldType: FieldType, value: Any?): String? {
    if (value == null) return null
    return when (fieldType) {
        // defaultNow() is the only TIME default the DSL exposes, stored
        // as the sentinel string "now".
        FieldType.TIME -> if (value == "now") "now()" else sqlStringLiteral(value.toString())
        FieldType.ENUM -> sqlStringLiteral((value as? Enum<*>)?.name ?: value.toString())
        FieldType.STRING, FieldType.TEXT -> sqlStringLiteral(value.toString())
        FieldType.BOOL -> value.toString()
        // Numeric literals render bare. Non-finite floats (NaN / ±Infinity)
        // are rejected upstream at FieldBuilder.build(), so value.toString()
        // is always a valid SQL numeric here.
        FieldType.INT, FieldType.LONG, FieldType.FLOAT, FieldType.DOUBLE -> value.toString()
        // UUID / BYTES have no DSL default surface today; treat any value
        // defensively as a quoted literal.
        FieldType.UUID, FieldType.BYTES -> sqlStringLiteral(value.toString())
        // pgvector columns expose no default (the builder has no .default()),
        // so this is only reached defensively (value != null can't happen).
        FieldType.PGVECTOR -> error("pgvector columns have no default")
    }
}

/** Single-quote a SQL string literal, doubling embedded single quotes. */
private fun sqlStringLiteral(value: String): String = "'" + value.replace("'", "''") + "'"

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
