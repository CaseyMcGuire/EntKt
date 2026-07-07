package entkt.viewer.html

import entkt.viewer.EntViewerColumn
import entkt.viewer.EntViewerEdge
import entkt.viewer.EntViewerEntity
import entkt.viewer.EntViewerResponse
import entkt.viewer.EntViewerRow
import kotlinx.html.h1
import kotlinx.html.h2

internal fun detailPage(
    entity: EntViewerEntity<*>,
    columns: List<EntViewerColumn>,
    row: EntViewerRow,
    visibleRoutes: Set<String>,
    edges: List<EntViewerEdge>,
    urls: ViewerUrls,
): EntViewerResponse = pageShell(200, "${entity.displayName} #${row.id}", urls) {
    h1 { +"${entity.displayName} #${row.id}" }
    h2 { +"Fields" }
    fieldsTable(columns, row)
    if (edges.isNotEmpty()) {
        h2 { +"Edges" }
        edgesTable(edges, row, visibleRoutes, urls)
    }
}
