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
 * Security model: read-only (non-GET → 405); authorization defaults to
 * deny-all; every read runs under the per-request privacy context through
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

    private val defaultPageSize = 50
    private val maxPageSize = 200

    fun handle(request: EntViewerRequest): EntViewerResponse {
        if (!request.method.equals("GET", ignoreCase = true)) {
            return html.error(405, "The ent viewer is read-only; only GET is supported.")
        }
        if (!config.authorize(request)) {
            return html.error(
                403,
                "Not authorized. The viewer denies all requests until the application " +
                    "supplies an authorize { ... } callback that accepts this request.",
            )
        }
        val segments = relativeSegments(request.path)
            ?: return html.error(404, "Not found.")
        return try {
            route(request, segments)
        } catch (e: EntViewerBadRequestException) {
            html.error(400, e.message ?: "Bad request.")
        }
    }

    private fun relativeSegments(path: String): List<String>? {
        val prefix = config.path.trimEnd('/')
        val rest = when {
            path == prefix -> ""
            path.startsWith("$prefix/") -> path.removePrefix(prefix)
            else -> return null
        }
        return rest.split('/').filter { it.isNotEmpty() }
    }

    private fun visibleEntities(): List<EntViewerEntity<C>> =
        registry.entities.filterNot { it.routeName in config.visibility.excludedRoutes }

    private fun route(request: EntViewerRequest, segments: List<String>): EntViewerResponse {
        val entities = visibleEntities()
        return when {
            segments.isEmpty() -> html.home(entities)
            segments == listOf("schema") -> html.schemaPage(entities.map { it to effectiveColumns(it) })
            segments == listOf("entities") -> html.entityIndex(entities)
            segments.size == 2 && segments[0] == "entities" ->
                entityFor(segments[1])?.let { listPage(request, it) } ?: html.error(404, "Not found.")
            segments.size == 3 && segments[0] == "entities" ->
                entityFor(segments[1])?.let { detailPage(request, it, segments[2]) } ?: html.error(404, "Not found.")
            else -> html.error(404, "Not found.")
        }
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

        // Fetch one extra row so pagination can offer "next" without a count
        // query; pagination is always applied — the viewer never issues an
        // unbounded scan.
        val listRequest = EntViewerListRequest(
            filters = filters,
            order = order,
            limit = size + 1,
            offset = (page - 1) * size,
        )
        val context = config.privacyContext(request)
        val result = registry.withPrivacyContext(client, context) { scoped ->
            entity.list(scoped, listRequest)
        }
        val hasNext = result.rows.size > size
        val rows = result.rows.take(size).map { applyExtraRedaction(entity, it) }

        return html.listPage(
            entity = entity,
            columns = columns,
            rows = rows,
            filters = filters,
            order = order,
            page = page,
            size = size,
            hasNext = hasNext,
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

        val visibleRoutes = visibleEntities().mapTo(mutableSetOf()) { it.routeName }
        return html.detailPage(
            entity = entity,
            columns = effectiveColumns(entity),
            row = applyExtraRedaction(entity, row),
            visibleRoutes = visibleRoutes,
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
        return EntViewerOrder(column.name, descending = request.queryFirst("dir") == "desc")
    }
}
