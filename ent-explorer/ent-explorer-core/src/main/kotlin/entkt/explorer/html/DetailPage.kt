package entkt.explorer.html

import entkt.explorer.EntExplorerColumn
import entkt.explorer.EntExplorerEdge
import entkt.explorer.EntExplorerEntity
import entkt.explorer.EntExplorerResponse
import entkt.explorer.EntExplorerRow
import kotlinx.html.h1
import kotlinx.html.h2

internal fun detailPage(
    entity: EntExplorerEntity<*, *>,
    columns: List<EntExplorerColumn>,
    row: EntExplorerRow,
    visibleRoutes: Set<String>,
    edges: List<EntExplorerEdge>,
    urls: ExplorerUrls,
): EntExplorerResponse = pageShell(200, "${entity.displayName} #${row.id}", urls) {
    h1 { +"${entity.displayName} #${row.id}" }
    h2 { +"Fields" }
    fieldsTable(columns, row)
    if (edges.isNotEmpty()) {
        h2 { +"Edges" }
        edgesTable(edges, row, visibleRoutes, urls)
    }
}
