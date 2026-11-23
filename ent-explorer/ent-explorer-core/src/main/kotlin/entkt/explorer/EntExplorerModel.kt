package entkt.explorer

import entkt.runtime.driver.EntitySchema
import entkt.runtime.privacy.ViewerContext
import entkt.schema.FieldType

/**
 * One entity's typed bridge from dynamic explorer requests to the generated
 * API. Generated implementations call the normal typed repos
 * (`client.users.query { ... }.all()`,
 * `findById(...).visibleOrNull().getOrThrow()`), which preserves read privacy,
 * read interceptors, soft-delete filters, result decoding, and generated
 * entity construction.
 */
interface EntExplorerEntity<C : Any> {
    /** Runtime schema metadata (columns, edges, indexes) for schema pages. */
    val schema: EntitySchema

    /** Human-facing name, e.g. `"User"`. */
    val displayName: String

    /**
     * Stable route segment: the generated entity name in lower camel case
     * (`User` -> `user`, `StudyAsset` -> `studyAsset`).
     */
    val routeName: String

    val columns: List<EntExplorerColumn>

    val edges: List<EntExplorerEdge>

    /**
     * List rows for one bounded page window through the canonical strict read
     * path. The adapter owns the privacy-coherent pagination semantics:
     *
     *  - every request fetches a `pageSize + 1` probe;
     *  - when the strict read succeeds, at most
     *    [EntExplorerListRequest.pageSize] rows are returned and
     *    [EntExplorerListResult.hasNext] is exact;
     *  - when LOAD privacy denies any root row in that probe, no partial page
     *    is returned: the result is an explicitly privacy-filtered empty page
     *    with `hasNext = null`, so the explorer can offer further navigation
     *    without deriving it from denied-row counts.
     *
     * Throws [EntExplorerBadRequestException] for filters/orders the entity
     * cannot translate — before any query executes.
     */
    fun list(
        client: C,
        viewerContext: ViewerContext,
        request: EntExplorerListRequest,
    ): EntExplorerListResult

    /**
     * One row by its route id string, or null when the id does not parse,
     * the row does not exist, or privacy denies it — identical not-found
     * behavior in all three cases, so the explorer discloses nothing about
     * existence.
     */
    fun get(client: C, viewerContext: ViewerContext, id: String): EntExplorerRow?
}

/** Display/filter metadata for one column, derived from the schema at codegen time. */
data class EntExplorerColumn(
    /** snake_case column name (stable filter/order key). */
    val name: String,
    val type: FieldType,
    val nullable: Boolean,
    val unique: Boolean,
    /** Redacted-by-default display contract from `.sensitive()`. */
    val sensitive: Boolean,
    /** Whether the explorer accepts filters on this column. */
    val filterable: Boolean,
    /** Whether the explorer accepts ordering by this column. */
    val orderable: Boolean,
    /**
     * Kotlin-facing type as codegen resolved it — `Instant`, `Priority`,
     * `List<HighlightRect>` — for schema pages. Empty means "derive a
     * display from [type]" (hand-built adapters).
     */
    val entType: String = "",
)

/**
 * One edge as the explorer can present it. Link construction:
 *
 *  - [localFkColumn] set (belongsTo): the row's FK value links straight to
 *    the target detail route;
 *  - [targetFilterColumn] set (hasOne/hasMany): links to the target list
 *    filtered by that column equal to this row's id;
 *  - neither set (M2M in V1): rendered as plain text — the explorer never
 *    falls back to a raw driver join to make a link work.
 *
 * Privacy still applies after navigation; a link never implies the target
 * row will be readable.
 */
data class EntExplorerEdge(
    val name: String,
    /** Route name of the target entity, or null when the target is not viewable. */
    val targetRouteName: String?,
    /** "to-one", "to-many", "many-to-many" — display only. */
    val cardinality: String,
    val localFkColumn: String? = null,
    val targetFilterColumn: String? = null,
)

/** Parsed, validated list-page inputs handed to a generated adapter. */
data class EntExplorerListRequest(
    val filters: List<EntExplorerFilter>,
    val order: EntExplorerOrder?,
    /** Maximum rows to render on this page. */
    val pageSize: Int,
    /** Raw-window offset: `(page - 1) * pageSize`. */
    val offset: Int,
)

data class EntExplorerFilter(
    val column: String,
    val op: EntExplorerFilterOp,
    val value: String?,
)

enum class EntExplorerFilterOp(val token: String, val needsValue: Boolean) {
    EQ("eq", true),
    NEQ("neq", true),
    GT("gt", true),
    GTE("gte", true),
    LT("lt", true),
    LTE("lte", true),
    CONTAINS("contains", true),
    PREFIX("prefix", true),
    SUFFIX("suffix", true),
    IS_NULL("isnull", false),
    NOT_NULL("notnull", false),
    ;

    companion object {
        fun fromToken(token: String): EntExplorerFilterOp? =
            entries.firstOrNull { it.token == token }
    }
}

data class EntExplorerOrder(val column: String, val descending: Boolean)

/**
 * One page of results. [hasNext] is `true`/`false` when the adapter knows
 * exactly (no load privacy: probe-based), and `null` when the entity has
 * row-level load privacy — the explorer then offers navigation
 * unconditionally and labels pages as privacy-windowed, rather than turning
 * next-link presence into an oracle over denied rows.
 */
data class EntExplorerListResult(
    val rows: List<EntExplorerRow>,
    val hasNext: Boolean? = null,
    /** True when root LOAD denial replaced the strict page with an empty result. */
    val privacyFiltered: Boolean = false,
)

/**
 * One rendered row. [id] is the route id string; [values] follow the
 * adapter's column order.
 */
data class EntExplorerRow(
    val id: String,
    val values: List<EntExplorerValue>,
)

/**
 * One rendered cell. Sensitive columns arrive with [redacted] = true and no
 * [display] — the generated adapter never materializes the value into the
 * row, and it deliberately does not disclose null-ness either ([isNull] is
 * false for redacted cells).
 */
data class EntExplorerValue(
    val column: String,
    val display: String?,
    val isNull: Boolean,
    val redacted: Boolean,
) {
    companion object {
        fun of(column: String, display: String?): EntExplorerValue =
            EntExplorerValue(column, display, isNull = display == null, redacted = false)

        fun redacted(column: String): EntExplorerValue =
            EntExplorerValue(column, display = null, isNull = false, redacted = true)
    }
}

/**
 * Thrown by generated adapters for filters/orders they cannot translate
 * (unknown column, unsupported op for the type, unparseable value). The
 * explorer renders it as a 400 with the message — before any query executes.
 */
class EntExplorerBadRequestException(message: String) : RuntimeException(message)
