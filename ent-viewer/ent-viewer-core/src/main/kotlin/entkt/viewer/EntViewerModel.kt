package entkt.viewer

import entkt.runtime.driver.EntitySchema
import entkt.runtime.privacy.PrivacyContext
import entkt.schema.FieldType

/**
 * The generated bridge between the framework-neutral viewer and one
 * application's generated ent API. Codegen emits an object implementing this
 * (`GeneratedEntViewerRegistry`) when `entkt { viewer.set(true) }` is enabled.
 *
 * [C] is the application's generated `EntClient` type — the viewer core never
 * references generated types directly; everything client-specific flows
 * through this interface and the per-entity [EntViewerEntity] adapters.
 */
interface EntViewerRegistry<C : Any> {
    val entities: List<EntViewerEntity<C>>

    /**
     * Run [block] against a client scoped to [context]. The generated
     * implementation delegates to the generated client's
     * `withPrivacyContext`, so every viewer read runs under the per-request
     * privacy context — the viewer is a presentation surface over the normal
     * read path, never a bypass.
     */
    fun <T> withPrivacyContext(client: C, context: PrivacyContext, block: (C) -> T): T
}

/**
 * One entity's typed bridge from dynamic viewer requests to the generated
 * API. Generated implementations call the normal typed repos
 * (`client.users.query { ... }.visibleAll()`, `visibleByIdOrNull`), which
 * preserves read privacy, read interceptors, soft-delete filters, result
 * decoding, and generated entity construction.
 */
interface EntViewerEntity<C : Any> {
    /** Runtime schema metadata (columns, edges, indexes) for schema pages. */
    val schema: EntitySchema

    /** Human-facing name, e.g. `"User"`. */
    val displayName: String

    /**
     * Stable route segment: the generated entity name in lower camel case
     * (`User` -> `user`, `StudyAsset` -> `studyAsset`).
     */
    val routeName: String

    val columns: List<EntViewerColumn>

    val edges: List<EntViewerEdge>

    /**
     * List visible rows for one page window. The adapter owns the
     * privacy-coherent pagination semantics:
     *
     *  - entities without load privacy fetch a `pageSize + 1` probe, return
     *    at most [EntViewerListRequest.pageSize] rows, and report an exact
     *    [EntViewerListResult.hasNext];
     *  - entities WITH load privacy fetch exactly the raw window
     *    `[offset, offset + pageSize)` through the privacy-filtering
     *    terminal, return the visible rows inside it (possibly fewer than
     *    the page size), and report `hasNext = null` — next-page
     *    availability is unknowable without disclosing denied-row counts,
     *    so the viewer renders further navigation unconditionally with an
     *    explicit sparse-pages notice;
     *  - a page window that exceeds the client's visible-scan cap throws
     *    [EntViewerBadRequestException] with the cap, never silently
     *    truncates.
     *
     * Throws [EntViewerBadRequestException] for filters/orders the entity
     * cannot translate — before any query executes.
     */
    fun list(client: C, request: EntViewerListRequest): EntViewerListResult

    /**
     * One row by its route id string, or null when the id does not parse,
     * the row does not exist, or privacy denies it — identical not-found
     * behavior in all three cases, so the viewer discloses nothing about
     * existence.
     */
    fun get(client: C, id: String): EntViewerRow?
}

/** Display/filter metadata for one column, derived from the schema at codegen time. */
data class EntViewerColumn(
    /** snake_case column name (stable filter/order key). */
    val name: String,
    val type: FieldType,
    val nullable: Boolean,
    val unique: Boolean,
    /** Redacted-by-default display contract from `.sensitive()`. */
    val sensitive: Boolean,
    /** Whether the viewer accepts filters on this column. */
    val filterable: Boolean,
    /** Whether the viewer accepts ordering by this column. */
    val orderable: Boolean,
    /**
     * Kotlin-facing type as codegen resolved it — `Instant`, `Priority`,
     * `List<HighlightRect>` — for schema pages. Empty means "derive a
     * display from [type]" (hand-built adapters).
     */
    val entType: String = "",
)

/**
 * One edge as the viewer can present it. Link construction:
 *
 *  - [localFkColumn] set (belongsTo): the row's FK value links straight to
 *    the target detail route;
 *  - [targetFilterColumn] set (hasOne/hasMany): links to the target list
 *    filtered by that column equal to this row's id;
 *  - neither set (M2M in V1): rendered as plain text — the viewer never
 *    falls back to a raw driver join to make a link work.
 *
 * Privacy still applies after navigation; a link never implies the target
 * row will be readable.
 */
data class EntViewerEdge(
    val name: String,
    /** Route name of the target entity, or null when the target is not viewable. */
    val targetRouteName: String?,
    /** "to-one", "to-many", "many-to-many" — display only. */
    val cardinality: String,
    val localFkColumn: String? = null,
    val targetFilterColumn: String? = null,
)

/** Parsed, validated list-page inputs handed to a generated adapter. */
data class EntViewerListRequest(
    val filters: List<EntViewerFilter>,
    val order: EntViewerOrder?,
    /** Maximum rows to render on this page. */
    val pageSize: Int,
    /** Raw-window offset: `(page - 1) * pageSize`. */
    val offset: Int,
)

data class EntViewerFilter(
    val column: String,
    val op: EntViewerFilterOp,
    val value: String?,
)

enum class EntViewerFilterOp(val token: String, val needsValue: Boolean) {
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
        fun fromToken(token: String): EntViewerFilterOp? =
            entries.firstOrNull { it.token == token }
    }
}

data class EntViewerOrder(val column: String, val descending: Boolean)

/**
 * One page of results. [hasNext] is `true`/`false` when the adapter knows
 * exactly (no load privacy: probe-based), and `null` when the entity has
 * row-level load privacy — the viewer then offers navigation
 * unconditionally and labels pages as privacy-windowed, rather than turning
 * next-link presence into an oracle over denied rows.
 */
data class EntViewerListResult(
    val rows: List<EntViewerRow>,
    val hasNext: Boolean? = null,
    /** True when rows were privacy-filtered inside the raw window. */
    val privacyFiltered: Boolean = false,
)

/**
 * One rendered row. [id] is the route id string; [values] follow the
 * adapter's column order.
 */
data class EntViewerRow(
    val id: String,
    val values: List<EntViewerValue>,
)

/**
 * One rendered cell. Sensitive columns arrive with [redacted] = true and no
 * [display] — the generated adapter never materializes the value into the
 * row, and it deliberately does not disclose null-ness either ([isNull] is
 * false for redacted cells).
 */
data class EntViewerValue(
    val column: String,
    val display: String?,
    val isNull: Boolean,
    val redacted: Boolean,
) {
    companion object {
        fun of(column: String, display: String?): EntViewerValue =
            EntViewerValue(column, display, isNull = display == null, redacted = false)

        fun redacted(column: String): EntViewerValue =
            EntViewerValue(column, display = null, isNull = false, redacted = true)
    }
}

/**
 * Thrown by generated adapters for filters/orders they cannot translate
 * (unknown column, unsupported op for the type, unparseable value). The
 * viewer renders it as a 400 with the message — before any query executes.
 */
class EntViewerBadRequestException(message: String) : RuntimeException(message)
