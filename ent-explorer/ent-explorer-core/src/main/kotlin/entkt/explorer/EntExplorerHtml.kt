package entkt.explorer

import entkt.explorer.html.ListContext
import entkt.explorer.html.ExplorerUrls
import entkt.explorer.html.detailPage
import entkt.explorer.html.entityIndexPage
import entkt.explorer.html.errorPage
import entkt.explorer.html.homePage
import entkt.explorer.html.listPage
import entkt.explorer.html.SchemaMatch
import entkt.explorer.html.schemaDetailPage
import entkt.explorer.html.schemaIndexPage

/**
 * The router's rendering facade. Real markup lives in the component
 * library (`entkt.explorer.html`): pages compose a shared shell over typed
 * `FlowContent` components ([entkt.explorer.html.valueCell],
 * [entkt.explorer.html.pager], [entkt.explorer.html.edgeLinkCell], ...) with
 * links built by [ExplorerUrls]. All dynamic text flows through kotlinx.html
 * text nodes (escaped by the DSL); the only `unsafe` block is the
 * Kotlin-generated stylesheet. Sensitive values never reach rendering —
 * adapters hand over pre-redacted cells — so they cannot end up in HTML
 * attributes or text.
 */
internal class EntExplorerHtml(basePath: String) {

    private val urls = ExplorerUrls(basePath)

    fun error(status: Int, message: String): EntExplorerResponse =
        errorPage(status, message, urls)

    fun home(entities: List<EntExplorerEntity<*>>): EntExplorerResponse =
        homePage(entities, urls)

    fun entityIndex(entities: List<EntExplorerEntity<*>>): EntExplorerResponse =
        entityIndexPage(entities, urls)

    fun schemaIndex(matches: List<SchemaMatch>, q: String): EntExplorerResponse =
        schemaIndexPage(matches, q, urls)

    fun schemaDetail(
        entity: EntExplorerEntity<*>,
        columns: List<EntExplorerColumn>,
        visibleRoutes: Set<String>,
    ): EntExplorerResponse = schemaDetailPage(entity, columns, visibleRoutes, urls)

    fun listPage(
        entity: EntExplorerEntity<*>,
        columns: List<EntExplorerColumn>,
        rows: List<EntExplorerRow>,
        filters: List<EntExplorerFilter>,
        order: EntExplorerOrder?,
        page: Int,
        size: Int,
        hasNext: Boolean?,
        privacyFiltered: Boolean,
    ): EntExplorerResponse = listPage(
        entity = entity,
        columns = columns,
        rows = rows,
        ctx = ListContext(entity.routeName, filters, order, page, size),
        hasNext = hasNext,
        privacyFiltered = privacyFiltered,
        urls = urls,
    )

    fun detailPage(
        entity: EntExplorerEntity<*>,
        columns: List<EntExplorerColumn>,
        row: EntExplorerRow,
        visibleRoutes: Set<String>,
        edges: List<EntExplorerEdge> = entity.edges,
    ): EntExplorerResponse = detailPage(entity, columns, row, visibleRoutes, edges, urls)
}
