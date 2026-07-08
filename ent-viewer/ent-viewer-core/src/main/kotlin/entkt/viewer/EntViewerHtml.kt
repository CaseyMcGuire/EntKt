package entkt.viewer

import entkt.viewer.html.ListContext
import entkt.viewer.html.ViewerUrls
import entkt.viewer.html.detailPage
import entkt.viewer.html.entityIndexPage
import entkt.viewer.html.errorPage
import entkt.viewer.html.homePage
import entkt.viewer.html.listPage
import entkt.viewer.html.SchemaMatch
import entkt.viewer.html.schemaDetailPage
import entkt.viewer.html.schemaIndexPage

/**
 * The router's rendering facade. Real markup lives in the component
 * library (`entkt.viewer.html`): pages compose a shared shell over typed
 * `FlowContent` components ([entkt.viewer.html.valueCell],
 * [entkt.viewer.html.pager], [entkt.viewer.html.edgeLinkCell], ...) with
 * links built by [ViewerUrls]. All dynamic text flows through kotlinx.html
 * text nodes (escaped by the DSL); the only `unsafe` block is the
 * Kotlin-generated stylesheet. Sensitive values never reach rendering —
 * adapters hand over pre-redacted cells — so they cannot end up in HTML
 * attributes or text.
 */
internal class EntViewerHtml(basePath: String) {

    private val urls = ViewerUrls(basePath)

    fun error(status: Int, message: String): EntViewerResponse =
        errorPage(status, message, urls)

    fun home(entities: List<EntViewerEntity<*>>): EntViewerResponse =
        homePage(entities, urls)

    fun entityIndex(entities: List<EntViewerEntity<*>>): EntViewerResponse =
        entityIndexPage(entities, urls)

    fun schemaIndex(matches: List<SchemaMatch>, q: String): EntViewerResponse =
        schemaIndexPage(matches, q, urls)

    fun schemaDetail(
        entity: EntViewerEntity<*>,
        columns: List<EntViewerColumn>,
        visibleRoutes: Set<String>,
    ): EntViewerResponse = schemaDetailPage(entity, columns, visibleRoutes, urls)

    fun listPage(
        entity: EntViewerEntity<*>,
        columns: List<EntViewerColumn>,
        rows: List<EntViewerRow>,
        filters: List<EntViewerFilter>,
        order: EntViewerOrder?,
        page: Int,
        size: Int,
        hasNext: Boolean?,
        privacyFiltered: Boolean,
    ): EntViewerResponse = listPage(
        entity = entity,
        columns = columns,
        rows = rows,
        ctx = ListContext(entity.routeName, filters, order, page, size),
        hasNext = hasNext,
        privacyFiltered = privacyFiltered,
        urls = urls,
    )

    fun detailPage(
        entity: EntViewerEntity<*>,
        columns: List<EntViewerColumn>,
        row: EntViewerRow,
        visibleRoutes: Set<String>,
        edges: List<EntViewerEdge> = entity.edges,
    ): EntViewerResponse = detailPage(entity, columns, row, visibleRoutes, edges, urls)
}
