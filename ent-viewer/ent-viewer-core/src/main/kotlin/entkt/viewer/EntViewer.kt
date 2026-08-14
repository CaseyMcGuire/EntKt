package entkt.viewer

/**
 * The framework-neutral ent viewer: a read-only, server-rendered inspection
 * surface over an application's generated ent API.
 *
 * ```kotlin
 * val viewer = EntViewer(client, GeneratedEntViewerRegistry) {
 *     path = "/_ent"
 *     authorize { request -> request.principal.isAdminOfSomeKind() }
 *     privacyContext { request -> PrivacyContext(Viewer.User(...)) }
 *     entities { exclude("session") }
 * }
 *
 * // host framework:
 * val response = viewer.handle(EntViewerRequest(path = req.path, query = req.params, principal = req.user))
 * ```
 *
 * Routes (under [EntViewerConfig.path]):
 * ```
 * /            viewer home
 * /schema      schema metadata
 * /entities    entity type index
 * /entities/{type}        paginated, filterable list
 * /entities/{type}/{id}   row detail + followable edges
 * ```
 *
 * Security model: authorization defaults to deny-all and an unauthorized
 * request is a cloaked 404, indistinguishable from an unmapped route (the
 * viewer's existence is never disclosed to unauthorized callers);
 * read-only (non-GET → 405, checked after authorization); every read runs
 * under the per-request privacy context through
 * the generated client's normal read path; `.sensitive()` values are
 * redacted; excluded and unknown entities are the same 404; unknown,
 * unparseable, and privacy-denied ids are the same 404.
 */
class EntViewer<C : Any>(
    private val client: C,
    private val registry: EntViewerRegistry<C>,
    configure: EntViewerConfig.() -> Unit = {},
) {
    private val config = EntViewerConfig().apply(configure)
    private val html = EntViewerHtml(config.path)

    /** The configured mount path (`/_ent` by default), for host adapters registering routes. */
    val path: String get() = config.path

    private val defaultPageSize = 50
    private val maxPageSize = 200

    /** Cap on (page-1)*size so a crafted page can't overflow or force deep scans. */
    private val maxOffset = 1_000_000L

    init {
        // Fail fast on redaction typos: a mistyped table/column would
        // otherwise be silently fail-open.
        val known = registry.entities.flatMapTo(mutableSetOf()) { entity ->
            entity.columns.map { entity.schema.table to it.name }
        }
        for (extra in config.redaction.extra) {
            require(extra in known) {
                "redaction.extra(${extra.first}, ${extra.second}) does not match any registered " +
                    "entity column — known tables: ${registry.entities.map { it.schema.table }.sorted()}"
            }
        }
    }

    fun handle(request: EntViewerRequest): EntViewerResponse {
        // Authorization runs before anything else — including the method
        // check — so an unauthorized caller learns nothing: not the 405
        // that would reveal a mapped route, not a branded error page. The
        // cloaked 404 is unbranded, and hosts that can (the Spring adapter
        // does) replace it with their framework's native not-found so the
        // mount path is indistinguishable from an unmapped one.
        if (!config.authorize(request)) {
            return EntViewerResponse(
                status = 404,
                contentType = "text/plain; charset=utf-8",
                body = "Not Found",
                unmapped = true,
            )
        }
        if (!request.method.equals("GET", ignoreCase = true)) {
            return html.error(405, "The ent viewer is read-only; only GET is supported.")
        }
        val segments = relativeSegments(request.path)
            ?: return html.error(404, "Not found.")
        return try {
            route(request, segments)
        } catch (e: EntViewerBadRequestException) {
            html.error(400, e.message ?: "Bad request.")
        } catch (e: entkt.runtime.result.EntQueryRejectedException) {
            // A read interceptor (tenant guard, limit rule, ...) rejected the
            // query. That's a policy outcome, not a viewer bug — render it as
            // a controlled error instead of leaking a 500 to the host.
            html.error(400, "Query rejected by read interceptor '${e.interceptor}': ${e.reason}")
        }
    }

    private fun relativeSegments(path: String): List<String>? {
        val prefix = config.path.trimEnd('/')
        val rest = when {
            path == prefix -> ""
            path.startsWith("$prefix/") -> path.removePrefix(prefix)
            else -> return null
        }
        // Split the RAW path first (an encoded %2F inside an id must not
        // create a segment), then percent-decode each segment with path
        // semantics: '+' is a literal plus in paths, not a space.
        return rest.split('/').filter { it.isNotEmpty() }.map { segment ->
            try {
                java.net.URLDecoder.decode(segment.replace("+", "%2B"), Charsets.UTF_8)
            } catch (_: IllegalArgumentException) {
                return null // malformed percent-encoding -> 404
            }
        }
    }

    private fun visibleEntities(): List<EntViewerEntity<C>> =
        registry.entities.filterNot { it.routeName in config.visibility.excludedRoutes }

    private fun route(request: EntViewerRequest, segments: List<String>): EntViewerResponse {
        val entities = visibleEntities()
        return when {
            segments.isEmpty() -> html.home(entities)
            segments == listOf("schema") -> schemaIndex(request)
            segments.size == 2 && segments[0] == "schema" ->
                entityFor(segments[1])
                    ?.let { html.schemaDetail(it, effectiveColumns(it), entities.mapTo(mutableSetOf()) { e -> e.routeName }) }
                    ?: html.error(404, "Not found.")
            segments == listOf("entities") -> html.entityIndex(entities)
            segments.size == 2 && segments[0] == "entities" ->
                entityFor(segments[1])?.let { listPage(request, it) } ?: html.error(404, "Not found.")
            segments.size == 3 && segments[0] == "entities" ->
                entityFor(segments[1])?.let { detailPage(request, it, segments[2]) } ?: html.error(404, "Not found.")
            else -> html.error(404, "Not found.")
        }
    }

    /**
     * The schema index, searchable: `?q=` matches entity/route/table names
     * plus column and edge names (case-insensitive); member hits are
     * reported so "where is api_token" lands on the right entity.
     */
    private fun schemaIndex(request: EntViewerRequest): EntViewerResponse {
        val q = request.queryFirst("q")?.trim().orEmpty()
        val needle = q.lowercase()
        val matches = visibleEntities().mapNotNull { entity ->
            if (needle.isEmpty()) return@mapNotNull entkt.viewer.html.SchemaMatch(entity, emptyList())
            val nameHit = entity.displayName.lowercase().contains(needle) ||
                entity.routeName.lowercase().contains(needle) ||
                entity.schema.table.lowercase().contains(needle)
            val members = entity.columns.filter { needle in it.name.lowercase() }.map { it.name } +
                entity.edges.filter { needle in it.name.lowercase() }.map { "${it.name} (edge)" }
            if (nameHit || members.isNotEmpty()) entkt.viewer.html.SchemaMatch(entity, members) else null
        }
        return html.schemaIndex(matches, q)
    }

    private fun entityFor(route: String): EntViewerEntity<C>? =
        visibleEntities().firstOrNull { it.routeName == route }

    /**
     * Columns with application-level extra redaction folded in: an
     * extra-redacted column behaves exactly like a `.sensitive()` one —
     * value redacted, not filterable, not orderable.
     */
    private fun effectiveColumns(entity: EntViewerEntity<C>): List<EntViewerColumn> =
        entity.columns.map { col ->
            if ((entity.schema.table to col.name) in config.redaction.extra) {
                col.copy(sensitive = true, filterable = false, orderable = false)
            } else {
                col
            }
        }

    /** Re-redact rows for extra-redacted columns the generated adapter doesn't know about. */
    private fun applyExtraRedaction(entity: EntViewerEntity<C>, row: EntViewerRow): EntViewerRow {
        if (config.redaction.extra.none { it.first == entity.schema.table }) return row
        return row.copy(
            values = row.values.map { v ->
                if ((entity.schema.table to v.column) in config.redaction.extra) {
                    EntViewerValue.redacted(v.column)
                } else {
                    v
                }
            },
        )
    }

    private fun listPage(request: EntViewerRequest, entity: EntViewerEntity<C>): EntViewerResponse {
        val columns = effectiveColumns(entity)
        val byName = columns.associateBy { it.name }

        val filters = parseFilters(request, byName)
        val order = parseOrder(request, byName)
        val page = (request.queryFirst("page")?.toIntOrNull() ?: 1).coerceAtLeast(1)
        val size = (request.queryFirst("size")?.toIntOrNull() ?: defaultPageSize)
            .coerceIn(1, maxPageSize)

        // Overflow-safe raw offset with a hard depth cap: pagination is
        // always applied and bounded — the viewer never issues an unbounded
        // or absurdly deep scan.
        val offset = (page - 1L) * size
        if (offset > maxOffset) {
            throw EntViewerBadRequestException("Page $page is beyond the viewer's depth limit.")
        }
        val listRequest = EntViewerListRequest(
            filters = filters,
            order = order,
            pageSize = size,
            offset = offset.toInt(),
        )
        val context = config.privacyContext(request)
        val result = registry.withPrivacyContext(client, context) { scoped ->
            entity.list(scoped, listRequest)
        }
        val rows = result.rows.take(size).map { applyExtraRedaction(entity, it) }

        return html.listPage(
            entity = entity,
            columns = columns,
            rows = rows,
            filters = filters,
            order = order,
            page = page,
            size = size,
            hasNext = result.hasNext,
            privacyFiltered = result.privacyFiltered,
        )
    }

    private fun detailPage(
        request: EntViewerRequest,
        entity: EntViewerEntity<C>,
        id: String,
    ): EntViewerResponse {
        val context = config.privacyContext(request)
        val row = registry.withPrivacyContext(client, context) { scoped ->
            entity.get(scoped, id)
        } ?: return html.error(404, "Not found.")

        val visible = visibleEntities()
        val visibleRoutes = visible.mapTo(mutableSetOf()) { it.routeName }
        // Strip filter-links whose target FK column can't actually be
        // filtered (sensitive, or extra-redacted at runtime) — a rendered
        // link must never be a guaranteed 400. The edge still renders, as
        // plain text.
        val edges = entity.edges.map { edge ->
            val filterColumn = edge.targetFilterColumn ?: return@map edge
            val target = visible.firstOrNull { it.routeName == edge.targetRouteName } ?: return@map edge
            val targetColumn = effectiveColumns(target).firstOrNull { it.name == filterColumn }
            if (targetColumn?.filterable == true) edge else edge.copy(targetFilterColumn = null)
        }
        return html.detailPage(
            entity = entity,
            columns = effectiveColumns(entity),
            row = applyExtraRedaction(entity, row),
            visibleRoutes = visibleRoutes,
            edges = edges,
        )
    }

    // ---------- query-parameter parsing ----------

    /**
     * Filters arrive as repeated `f=column:op[:value]` tokens, plus an
     * optional `fc`/`fo`/`fv` triple from the list page's add-filter form.
     * Everything is validated against the entity's effective columns before
     * any query runs; violations render as 400 with a message.
     */
    private fun parseFilters(
        request: EntViewerRequest,
        columns: Map<String, EntViewerColumn>,
    ): List<EntViewerFilter> {
        val tokens = (request.query["f"] ?: emptyList()).toMutableList()
        val formColumn = request.queryFirst("fc")?.takeIf { it.isNotBlank() }
        if (formColumn != null) {
            val formOp = request.queryFirst("fo") ?: "eq"
            val formValue = request.queryFirst("fv") ?: ""
            tokens.add("$formColumn:$formOp:$formValue")
        }
        return tokens.map { token -> parseFilterToken(token, columns) }
    }

    private fun parseFilterToken(
        token: String,
        columns: Map<String, EntViewerColumn>,
    ): EntViewerFilter {
        val parts = token.split(':', limit = 3)
        if (parts.size < 2) {
            throw EntViewerBadRequestException("Malformed filter '$token' — expected column:op[:value].")
        }
        val column = columns[parts[0]]
            ?: throw EntViewerBadRequestException("Unknown filter column '${parts[0]}'.")
        if (!column.filterable) {
            throw EntViewerBadRequestException("Column '${column.name}' is not filterable.")
        }
        val op = EntViewerFilterOp.fromToken(parts[1])
            ?: throw EntViewerBadRequestException("Unknown filter op '${parts[1]}'.")
        if ((op == EntViewerFilterOp.IS_NULL || op == EntViewerFilterOp.NOT_NULL) && !column.nullable) {
            throw EntViewerBadRequestException("Column '${column.name}' is not nullable.")
        }
        val value = parts.getOrNull(2)
        if (op.needsValue && value == null) {
            throw EntViewerBadRequestException("Filter op '${op.token}' needs a value.")
        }
        return EntViewerFilter(column.name, op, if (op.needsValue) value else null)
    }

    private fun parseOrder(
        request: EntViewerRequest,
        columns: Map<String, EntViewerColumn>,
    ): EntViewerOrder? {
        val orderColumn = request.queryFirst("order")?.takeIf { it.isNotBlank() } ?: return null
        val column = columns[orderColumn]
            ?: throw EntViewerBadRequestException("Unknown order column '$orderColumn'.")
        if (!column.orderable) {
            throw EntViewerBadRequestException("Column '${column.name}' is not orderable.")
        }
        val descending = when (val dir = request.queryFirst("dir")) {
            null, "asc" -> false
            "desc" -> true
            else -> throw EntViewerBadRequestException("Unknown order direction '$dir' — use asc or desc.")
        }
        return EntViewerOrder(column.name, descending)
    }
}
