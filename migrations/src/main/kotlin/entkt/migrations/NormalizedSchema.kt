package entkt.migrations

import entkt.runtime.driver.EntitySchema
import entkt.runtime.driver.IdStrategy
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
            // Backstop for callers that reach normalization without
            // going through SchemaInspector.validate (the CLI path's
            // structured check) or the workflow entry preflight.
            requireIdentifiersWithinPostgresLimit(schemas)
            val tables = schemas.associate { schema ->
                val columns = schema.columns.map { col ->
                    NormalizedColumn(
                        name = col.name,
                        sqlType = typeMapper.sqlTypeFor(col.type, col.primaryKey, schema.idStrategy, col.storage),
                        nullable = col.nullable,
                        primaryKey = col.primaryKey,
                        default = typeMapper.formatDefault(col.type, col.default),
                        requiredExtension = (col.storage as? entkt.schema.ColumnStorage.Native)?.requiredExtension,
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
                        using = idx.using,
                        opclasses = idx.opclasses,
                        with = idx.with,
                    )
                }

                val foreignKeys = schema.columns
                    .filter { it.references != null }
                    .map { col ->
                        NormalizedForeignKey(
                            columns = listOf(col.name),
                            targetTable = col.references!!.table,
                            targetColumns = listOf(col.references!!.column),
                            onDelete = resolveDslOnDelete(col.references!!.onDelete, col.nullable),
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
    /** Extension this column's type needs (e.g. `"vector"`), or null. */
    val requiredExtension: String? = null,
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
    /** Access method (`"hnsw"` / `"ivfflat"`) — part of semantic identity. Null = btree. */
    val using: String? = null,
    /** Per-column operator class (e.g. `["vector_cosine_ops"]`) — part of identity. */
    val opclasses: List<String>? = null,
    /** Index storage params rendered `WITH (k = v, …)` — part of identity. */
    val with: Map<String, String>? = null,
)

/**
 * Normalize a SQL WHERE predicate for comparison. PostgreSQL's
 * `pg_get_expr` deparses predicates with differences from the
 * user-written form:
 *
 * - Outer parentheses: `active = true` → `(active = true)`
 * - Casts on string literals: `'foo'` → `'foo'::text`
 * - Numeric constants respelled as quoted literals with casts
 *   (`x <> -5` → `x <> '-5'::integer`) or parenthesized with casts
 *   (`f = 1` → `f = (1)::double precision`)
 * - Promotion casts on columns: a varchar column `v` compared to a
 *   string becomes `(v)::text`; an integer column `x` compared to a
 *   decimal literal becomes `(x)::numeric`
 * - Whitespace differences
 *
 * The cast rewrites run to a fixed point (stripping an inner cast can
 * expose an enclosing one, e.g. `((v)::text)::integer`) and remove
 * exactly those deparser decorations, so the deparsed and user-written
 * forms of the SAME predicate compare equal. Casts that change which
 * rows a predicate selects are NOT stripped: a column cast survives
 * unless [columnTypes] (lowercased column name → declared SQL type)
 * confirms a value-preserving promotion — `::text` from a
 * case-preserving textual column (citext flips case-sensitivity, and
 * `::character` means `character(1)` and truncates, so neither ever
 * strips), or `::numeric` from an integer-family column (float →
 * numeric can round). Kept casts are canonicalized to `col::type` with
 * common aliases resolved, so `f::int8` and the reported `(f)::bigint`
 * compare equal. Without [columnTypes] the two promotion targets strip
 * unconditionally — the legacy behavior unit callers rely on.
 * String-literal content is preserved verbatim — two predicates that
 * differ only inside quotes never compare equal.
 *
 * Shapes this rewrite cannot reconcile surface as drop+recreate drift
 * rather than silent equality: casts on function results
 * (`(upper(v))::text`), deparser-added base-type casts on DOMAIN
 * columns, deparser grouping parens inside arithmetic
 * (`(x / 100.0) > 9.99`), and literals Postgres rewrites wholesale
 * (a date collapsing into `'2020-01-01 00:00:00+00'::timestamp with
 * time zone`). Reconcile those by declaring the predicate the way
 * `pg_get_expr` reports it.
 */
fun normalizeWhere(predicate: String?, columnTypes: Map<String, String> = emptyMap()): String? {
    if (predicate == null) return null

    // Mask string literals before any rewrite: literal content is
    // semantic. `pg_get_expr` round-trips it verbatim, so two
    // predicates that differ only inside quotes select genuinely
    // different row sets — running the cast and whitespace rewrites
    // over literal bytes made `'foo::text'` compare equal to `'foo'`
    // and `'in  progress'` equal to `'in progress'`, which let
    // migration validation and auto-DDL index reconciliation accept a
    // partial index whose predicate covers the wrong rows. Same rule
    // [normalizeDefault] applies to DEFAULT expressions. Doubled ''
    // escapes are part of one literal, and masking also keeps quoted
    // parens out of the outer-paren scan below.
    val literals = mutableListOf<String>()
    var s = WHERE_STRING_LITERAL.replace(predicate.trim()) { m ->
        literals.add(m.value)
        "\u0001${literals.size - 1}\u0002"
    }

    // Run the cast rewrites to a fixed point: each pass is a single
    // left-to-right scan, and stripping an inner cast can expose an
    // enclosing `(x)::type` the same scan already passed. The bound is
    // a defensive backstop — every rewrite either shortens the string
    // or moves a cast spelling to its canonical form, so real inputs
    // converge in a handful of iterations.
    var iterations = 0
    while (iterations++ < 100) {
        val before = s
        s = rewriteWhereCasts(s, literals, columnTypes)
        if (s == before) break
    }

    s = stripBalancedOuterParens(s)

    // Collapse runs of whitespace — masked literal content can't be touched.
    s = s.replace(Regex("\\s+"), " ")

    // Restore the literals verbatim.
    return WHERE_LITERAL_MASK.replace(s) { m -> literals[m.groupValues[1].toInt()] }
}

/** One fixed-point iteration of [normalizeWhere]'s cast rewrites. */
private fun rewriteWhereCasts(
    input: String,
    literals: List<String>,
    columnTypes: Map<String, String>,
): String {
    var s = input

    // Casts on masked string literals — deparser decoration recording
    // the type a constant was parsed as ('a'::text, '2020-01-01'::date).
    // Only modifier-less targets: 'foobar'::varchar(3) truncates and
    // stays part of the predicate's identity. A quoted numeric under a
    // numeric-family cast is the deparser's spelling of a plain number
    // ('-5'::integer, '5000000000'::bigint): resolve it to the bare
    // number so it reconciles with the DSL's `-5`.
    s = MASKED_LITERAL_CAST.replace(s) { m ->
        val index = m.groupValues[1].toInt()
        val target = canonicalCastType(m.groupValues[2])
        val content = literals[index].removeSurrounding("'")
        if (target in NUMERIC_CAST_TYPES && NUMERIC_LITERAL.matches(content)) content
        else "\u0001$index\u0002"
    }

    // Casts on parenthesized numeric literals ((1)::double precision,
    // (5)::numeric — modifier-less only, (1.55)::numeric(2,1) rounds),
    // then redundant parens around a bare number ((-5)). Both guarded
    // so a function call's trailing argument — `abs (5)::integer` —
    // is left alone.
    run {
        val current = s
        s = PAREN_NUMERIC_CAST.replace(current) { m ->
            if (isFunctionCallContext(current, m.range.first)) m.value else m.groupValues[1]
        }
    }
    run {
        val current = s
        s = PAREN_NUMERIC.replace(current) { m ->
            if (isFunctionCallContext(current, m.range.first)) m.value else m.groupValues[1]
        }
    }

    // Casts on bare or parenthesized column references: strip provable
    // promotions, keep and canonicalize everything else. The guard
    // keeps `upper (v)::text` — a cast on a function result — intact.
    run {
        val current = s
        s = WHERE_IDENT_CAST.replace(current) { m ->
            val parenIdent = m.groupValues[1]
            if (parenIdent.isNotEmpty() && isFunctionCallContext(current, m.range.first)) {
                m.value
            } else {
                val ident = parenIdent.ifEmpty { m.groupValues[2] }
                val type = m.groupValues[3]
                if (isStrippableColumnCast(ident, type, columnTypes)) ident
                else "$ident::${canonicalCastType(type)}"
            }
        }
    }

    // Unwrap parens around a kept cast chain so both spellings of the
    // same chain converge: ((c)::text)::integer canonicalizes through
    // (c::text)::integer to c::text::integer, matching the user's
    // c::text::integer.
    run {
        val current = s
        s = CHAIN_PAREN_UNWRAP.replace(current) { m ->
            if (isFunctionCallContext(current, m.range.first)) m.value else m.groupValues[1]
        }
    }

    return s
}

/** A single-quoted string literal, quotes doubled for escapes. */
private val WHERE_STRING_LITERAL = Regex("'(?:[^']|'')*'")

/** Placeholder emitted by [normalizeWhere]'s literal masking. */
private val WHERE_LITERAL_MASK = Regex("\u0001(\\d+)\u0002")

/** A SQL numeric literal: `5`, `-5`, `1.5`, `.5`, `1e3`, `-1.5e-3`. */
private const val NUMERIC_LITERAL_SRC =
    "-?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?"

/**
 * A cast target type in predicate position: one of PostgreSQL's
 * multi-word type names (case-insensitive — users write `::DOUBLE
 * PRECISION`, `pg_get_expr` deparses lowercase; a typmod may sit
 * after the first word, as in `timestamp(0) with time zone`), or a
 * single (optionally quoted) identifier, with an optional trailing
 * length/precision modifier. The multi-word names are enumerated so
 * the match can't swallow following SQL (`::integer AND b` must stop
 * at `integer`).
 */
private const val WHERE_CAST_TYPE =
    "(?i:double\\s+precision" +
        "|timestamp(?:\\s*\\([0-9, ]*\\))?\\s+with(?:out)?\\s+time\\s+zone" +
        "|time(?:\\s*\\([0-9, ]*\\))?\\s+with(?:out)?\\s+time\\s+zone" +
        "|character\\s+varying|bit\\s+varying" +
        "|\"?[a-zA-Z_][a-zA-Z0-9_]*\"?)(?:\\s*\\([0-9, ]*\\))?"

/**
 * [WHERE_CAST_TYPE] restricted to modifier-less spellings. The
 * lookahead rejects a following `(` entirely rather than matching the
 * bare base name — half-consuming `::varchar(3)` would orphan the
 * `(3)`.
 */
private const val WHERE_CAST_TYPE_NOMOD =
    "(?i:double\\s+precision|timestamp\\s+with(?:out)?\\s+time\\s+zone" +
        "|time\\s+with(?:out)?\\s+time\\s+zone|character\\s+varying|bit\\s+varying" +
        "|\"?[a-zA-Z_][a-zA-Z0-9_]*\"?)(?!\\s*\\()"

/** A modifier-less cast on a masked string literal. */
private val MASKED_LITERAL_CAST = Regex("\u0001(\\d+)\u0002::($WHERE_CAST_TYPE_NOMOD)")

/** A modifier-less cast on a parenthesized numeric literal. */
private val PAREN_NUMERIC_CAST = Regex("\\(\\s*($NUMERIC_LITERAL_SRC)\\s*\\)::($WHERE_CAST_TYPE_NOMOD)")

/** Redundant parens around a bare numeric literal. */
private val PAREN_NUMERIC = Regex("\\(\\s*($NUMERIC_LITERAL_SRC)\\s*\\)")

/** A cast on a parenthesized or bare column reference: `(col)::type` / `col::type`. */
private val WHERE_IDENT_CAST = Regex(
    "(?:\\(\\s*([a-zA-Z_][a-zA-Z0-9_]*)\\s*\\)|([a-zA-Z_][a-zA-Z0-9_]*))::($WHERE_CAST_TYPE)",
)

/** Parens wrapping a kept cast chain that is itself being cast again. */
private val CHAIN_PAREN_UNWRAP = Regex(
    "\\(\\s*([a-zA-Z_][a-zA-Z0-9_]*(?:::$WHERE_CAST_TYPE)+)\\s*\\)(?=::)",
)

/**
 * Whether the parenthesized group opening at [openParen] is a function
 * call's argument list rather than a grouped column reference: the
 * preceding word (whitespace allowed between a function name and its
 * parens) is an identifier that isn't a SQL keyword. Getting this
 * wrong corrupts the expression — `upper (v)::text` must not have
 * `(v)::text` rewritten out from under the call.
 */
private fun isFunctionCallContext(input: String, openParen: Int): Boolean {
    var i = openParen - 1
    while (i >= 0 && input[i].isWhitespace()) i--
    if (i < 0) return false
    if (!(input[i].isLetterOrDigit() || input[i] == '_')) return false
    val end = i + 1
    while (i >= 0 && (input[i].isLetterOrDigit() || input[i] == '_')) i--
    return input.substring(i + 1, end).lowercase() !in PREDICATE_KEYWORDS
}

/** SQL keywords that can directly precede a parenthesized expression. */
private val PREDICATE_KEYWORDS = setOf(
    "and", "or", "not", "in", "is", "like", "ilike", "similar", "to",
    "between", "symmetric", "escape", "when", "then", "else", "end",
    "case", "all", "any", "some", "exists", "distinct", "from", "by",
    "on", "where", "as", "null", "true", "false", "isnull", "notnull",
    "over", "collate",
)

/**
 * Whether stripping `ident::type` is a provably value-preserving
 * promotion. Only two targets qualify, and each needs the source
 * column's declared type to vouch for it:
 *
 * - `::text` from a case-preserving textual column. citext sources are
 *   excluded (its equality is case-insensitive, so `c::text` changes
 *   which rows match), and so are all other textual targets:
 *   `::character` means `character(1)` and truncates, `::name`
 *   truncates at 63 bytes, `::citext` flips comparisons the other way,
 *   and any length modifier truncates.
 * - `::numeric` from an integer-family column — the promotion
 *   `pg_get_expr` adds when an integer column meets a decimal literal.
 *   Float sources are excluded: binary → decimal conversion can round.
 *
 * A column the map doesn't know gets no benefit of the doubt. An empty
 * map (unit callers) keeps the unconditional legacy strip for these
 * two targets.
 */
private fun isStrippableColumnCast(
    ident: String,
    target: String,
    columnTypes: Map<String, String>,
): Boolean {
    val source = columnTypes[ident.lowercase()]
        ?.let { canonicalCastType(it).substringBefore("(").trim() }
    return when (canonicalCastType(target)) {
        "text" -> source?.let { it in CASE_PRESERVING_TEXTUAL_TYPES } ?: columnTypes.isEmpty()
        "numeric" -> source?.let { it in INTEGER_FAMILY_TYPES } ?: columnTypes.isEmpty()
        else -> false
    }
}

/**
 * Source types whose `::text` casts preserve both bytes and comparison
 * semantics. `citext` is deliberately absent (case-insensitive
 * equality), as is `character` (trailing-blank padding compares
 * differently through text).
 */
private val CASE_PRESERVING_TEXTUAL_TYPES = setOf("text", "character varying", "name")

/** Integer-family source types whose `::numeric` promotion is exact. */
private val INTEGER_FAMILY_TYPES =
    setOf("smallint", "integer", "bigint", "smallserial", "serial", "bigserial")

/** Cast targets under which a quoted numeric constant is a plain number. */
private val NUMERIC_CAST_TYPES =
    setOf("smallint", "integer", "bigint", "numeric", "real", "double precision", "oid")

/**
 * Canonical spelling for a cast type: whitespace collapsed, unquoted
 * names lowercased, and common aliases resolved to the name
 * `pg_get_expr` deparses, so `f::int8` and the reported `(f)::bigint`
 * compare equal. A length/precision modifier is preserved (spaces
 * dropped) and re-anchored after the canonical base name, wherever it
 * appeared — `timestamp(0) with time zone` and `timestamptz(0)` both
 * canonicalize to `timestamp with time zone(0)`.
 *
 * Quoted identifiers are case-sensitive in PostgreSQL, so a quoted
 * name that NEEDS its quotes — mixed case, like `"Money"` — keeps
 * them and its exact case: `"Money"` and `money` can be distinct
 * types with different behavior. A quoted-lowercase name is identical
 * to its unquoted spelling and folds into it.
 */
private fun canonicalCastType(type: String): String {
    var t = type.trim().replace(Regex("\\s+"), " ")
    var modifier: String? = null
    Regex("\\s*\\(([0-9, ]*)\\)").find(t)?.let { m ->
        modifier = m.groupValues[1].replace(" ", "")
        t = t.removeRange(m.range).trim().replace(Regex("\\s+"), " ")
    }
    val canonical = if (t.length >= 2 && t.startsWith("\"") && t.endsWith("\"")) {
        val inner = t.removeSurrounding("\"")
        // Quoted-lowercase normally IS the unquoted type — except
        // `"char"`, PostgreSQL's 1-byte type, which is distinct from
        // the keyword `char` (= character/bpchar).
        if (inner.matches(UNQUOTED_SAFE_NAME) && inner != "char") CAST_TYPE_ALIASES[inner] ?: inner
        else "\"$inner\""
    } else {
        val lower = t.lowercase()
        CAST_TYPE_ALIASES[lower] ?: lower
    }
    return if (modifier == null) canonical else "$canonical($modifier)"
}

/** A name whose quoted and unquoted spellings PostgreSQL treats identically. */
private val UNQUOTED_SAFE_NAME = Regex("[a-z_][a-z0-9_]*")

private val CAST_TYPE_ALIASES = mapOf(
    "int" to "integer",
    "int2" to "smallint",
    "int4" to "integer",
    "int8" to "bigint",
    "float4" to "real",
    "float8" to "double precision",
    "bool" to "boolean",
    "decimal" to "numeric",
    "timestamptz" to "timestamp with time zone",
    "timetz" to "time with time zone",
    "varchar" to "character varying",
    "char" to "character",
    "bpchar" to "character",
)

/**
 * Strip balanced outer parentheses from [input], repeatedly, as long as the
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
 * A quoted string literal (optionally cast) canonicalizes to its quoted
 * form `'inner'` with the cast dropped, so a `::` *inside* the value
 * (e.g. a default of `'a::b'`) is never mistaken for a type cast — and
 * the quotes are KEPT, because quoting is what separates the constant
 * string `'now()'` from a call to `now()`: a text column defaulted to
 * the literal `'now()'` stores that string on every insert, while
 * `now()::text` stores the current timestamp, and Postgres reports the
 * two as `'now()'::text` vs `(now())::text`. The one exception is
 * numeric content: Postgres respells many numeric default constants as
 * quoted literals (`'5'::bigint`, `'-5'::integer` — any signed or
 * non-int4 value) where the DSL renders them bare, so
 * a quoted numeric unquotes to reconcile with the bare `5` form — safe
 * because a bare numeric token can never be an expression.
 *
 * Bare numerics keep their constant decoration stripped
 * (`1.5::double precision` ≡ `1.5`, `(-5)` ≡ `-5`), but a cast on an
 * EXPRESSION changes what the default produces — `(now())::date`
 * stores midnight where `now()` stores the current timestamp — so
 * expression casts are kept, canonicalized to `expr::type` (parens
 * dropped, [canonicalCastType] spelling). Postgres never decorates a
 * bare expression default on its own — every reported decoration sits
 * on constants — so keeping them costs no reconciliation. Because BOTH
 * sides pass through this function, the entity-derived and
 * database-reported forms converge whenever they mean the same thing.
 */
fun normalizeDefault(expr: String?): String? {
    if (expr == null) return null
    val s = expr.trim()

    // A single-quoted string literal, optionally followed by a cast
    // (e.g. 'active'::text, '5'::bigint, 'a::b'). Never run the cast
    // regex over a value's bytes; keep the quotes unless the content is
    // a numeric literal (see KDoc).
    QUOTED_DEFAULT_LITERAL.matchEntire(s)?.let {
        val inner = it.groupValues[1]
        return if (NUMERIC_LITERAL.matches(inner)) inner else "'$inner'"
    }

    // Bare (unquoted) forms: peel trailing casts and the balanced
    // parens Postgres wraps around them, innermost-out — (now())::date
    // yields base now() and cast [date], (-5) yields -5 and no cast.
    var t = stripBalancedOuterParens(s)
    val casts = mutableListOf<String>()
    while (true) {
        val m = TRAILING_DEFAULT_CAST.find(t) ?: break
        casts.add(canonicalCastType(m.groupValues[1]))
        t = stripBalancedOuterParens(t.removeRange(m.range).trim())
    }
    t = t.replace(Regex("\\s+"), " ")

    // A numeric constant's modifier-less casts are decoration —
    // `1.5::double precision` means 1.5, and `(5)::text` produces the
    // same '5' the DSL's quoted `'5'` does — while a typmod cast
    // ((1.55)::numeric(2,1) is 1.6) changes the value and stays. An
    // expression's casts are always semantic and are re-attached in
    // cast order, canonicalized.
    if (casts.isEmpty()) return t
    if (NUMERIC_LITERAL.matches(t) && casts.none { it.contains("(") }) return t
    val reattached = t + casts.reversed().joinToString("") { "::$it" }

    // A base that reduced to a quoted literal under one cast —
    // `('x'::text)` — is the QUOTED_DEFAULT_LITERAL shape spelled with
    // parens; route it through the same rule so normalization is a
    // fixed point and both spellings share one key.
    QUOTED_DEFAULT_LITERAL.matchEntire(reattached)?.let {
        val inner = it.groupValues[1]
        return if (NUMERIC_LITERAL.matches(inner)) inner else "'$inner'"
    }
    return reattached
}

/** [NUMERIC_LITERAL_SRC] compiled, for whole-string literal checks. */
private val NUMERIC_LITERAL = Regex(NUMERIC_LITERAL_SRC)

/** PostgreSQL type-cast suffix, including multi-word types and length modifiers. */
private val CAST_SUFFIX =
    Regex("::\\s*\"?[a-zA-Z_][a-zA-Z0-9_]*(\\s+[a-zA-Z_][a-zA-Z0-9_]*)*\"?(\\s*\\([0-9, ]*\\))?")

/** A [CAST_SUFFIX]-shaped cast at the very end of a default expression, type captured. */
private val TRAILING_DEFAULT_CAST =
    Regex("::\\s*(\"?[a-zA-Z_][a-zA-Z0-9_]*(?:\\s+[a-zA-Z_][a-zA-Z0-9_]*)*\"?(?:\\s*\\([0-9, ]*\\))?)\\s*$")

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
        // JSON columns expose no default (rejected at the builder), so this is
        // only reached defensively (value != null can't happen).
        FieldType.JSON -> error("json columns have no default")
    }
}

/** Single-quote a SQL string literal, doubling embedded single quotes. */
private fun sqlStringLiteral(value: String): String = "'" + value.replace("'", "''") + "'"

/**
 * SQL referential action as PostgreSQL's catalog reports it. Wider
 * than the DSL's [OnDelete]: hand-written migrations can use actions
 * (`NO ACTION`, `SET DEFAULT`) the DSL never declares, and the differ
 * must see them exactly rather than collapse them into an inferred
 * near-equivalent.
 */
enum class FkAction { NO_ACTION, RESTRICT, CASCADE, SET_NULL, SET_DEFAULT }

/** SQL `MATCH` type of a foreign key; entkt only ever creates SIMPLE. */
enum class FkMatchType { SIMPLE, FULL, PARTIAL }

data class NormalizedForeignKey(
    /**
     * Referencing columns in constraint order. Every FK entkt itself
     * declares is single-column; introspection reports composite FKs
     * faithfully so the differ flags them instead of exploding them
     * into invented single-column pairings.
     */
    val columns: List<String>,
    val targetTable: String,
    /** Referenced columns, ordinally paired with [columns]. */
    val targetColumns: List<String>,
    /**
     * The resolved ON DELETE action. Always exact: entity-derived FKs
     * resolve the DSL default (SET_NULL for nullable columns, RESTRICT
     * for required ones) at construction in [NormalizedSchema.fromEntitySchemas];
     * introspected FKs carry the catalog code verbatim. One
     * representation means no contradictory declared-vs-exact state
     * and no inference at comparison time.
     */
    val onDelete: FkAction,
    /** Actual constraint name from introspection, or null for entity-derived FKs. */
    val constraintName: String? = null,
    /** ON UPDATE action; entkt only ever creates the default. */
    val onUpdate: FkAction = FkAction.NO_ACTION,
    val matchType: FkMatchType = FkMatchType.SIMPLE,
    val deferrable: Boolean = false,
    val initiallyDeferred: Boolean = false,
    /** False for `NOT VALID` constraints — existing rows were never checked. */
    val validated: Boolean = true,
) {
    init {
        require(columns.isNotEmpty()) { "foreign key must reference at least one column" }
        require(columns.size == targetColumns.size) {
            "foreign key columns ($columns) and target columns ($targetColumns) must pair ordinally"
        }
    }
}

/** PostgreSQL NAMEDATALEN - 1: identifiers beyond this are silently truncated. */
private const val MAX_IDENTIFIER_BYTES = 63

/**
 * Reject table or column names PostgreSQL would silently truncate.
 * A truncated name never introspects back under its declared form,
 * which wedges the migration differ permanently — so migration entry
 * points call this *before* any database work (the CLI path already
 * rejects through `SchemaInspector.validate`; this covers programmatic
 * `FlywayMigrationWorkflow` callers), and [NormalizedSchema.fromEntitySchemas]
 * applies it again as a backstop. Index and FK-constraint names are
 * exempt: the renderers hash-truncate those to fit.
 */
fun requireIdentifiersWithinPostgresLimit(schemas: List<EntitySchema>) {
    for (schema in schemas) {
        requireIdentifierFits("table", schema.table)
        for (col in schema.columns) {
            requireIdentifierFits("column", "${schema.table}.${col.name}", col.name)
        }
    }
}

private fun requireIdentifierFits(kind: String, label: String, identifier: String = label) {
    val bytes = identifier.toByteArray(Charsets.UTF_8).size
    require(bytes <= MAX_IDENTIFIER_BYTES) {
        "$kind name '$label' is $bytes bytes, but PostgreSQL silently truncates identifiers " +
            "longer than $MAX_IDENTIFIER_BYTES bytes (quoted or not). The truncated name would " +
            "permanently desynchronize the migration differ from the database — shorten the name."
    }
}

/**
 * Resolve a DSL [OnDelete] (or its absence) into the exact [FkAction]
 * the rendered constraint will carry: an undeclared action defaults to
 * SET NULL on nullable columns and RESTRICT on required ones, matching
 * what the DDL renderers emit. SET NULL on a NOT NULL column is
 * rejected here — normalization is where column nullability is last in
 * scope, so the renderer never needs to re-derive it.
 */
internal fun resolveDslOnDelete(onDelete: OnDelete?, columnNullable: Boolean): FkAction = when (onDelete) {
    OnDelete.CASCADE -> FkAction.CASCADE
    OnDelete.SET_NULL -> {
        require(columnNullable) { "ON DELETE SET NULL is invalid on a NOT NULL column" }
        FkAction.SET_NULL
    }
    OnDelete.RESTRICT -> FkAction.RESTRICT
    null -> if (columnNullable) FkAction.SET_NULL else FkAction.RESTRICT
}
