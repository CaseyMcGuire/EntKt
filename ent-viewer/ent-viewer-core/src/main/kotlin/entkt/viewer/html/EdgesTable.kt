package entkt.viewer.html

import entkt.viewer.EntViewerEdge
import entkt.viewer.EntViewerRow
import kotlinx.html.FlowContent
import kotlinx.html.table
import kotlinx.html.tbody
import kotlinx.html.td
import kotlinx.html.th
import kotlinx.html.tr

/** Detail-page edge listing: one [edgeLinkCell] per edge. */
internal fun FlowContent.edgesTable(
    edges: List<EntViewerEdge>,
    row: EntViewerRow,
    visibleRoutes: Set<String>,
    urls: ViewerUrls,
) {
    table {
        tbody {
            for (edge in edges) {
                tr {
                    th { +edge.name }
                    td { edgeLinkCell(edge, row, visibleRoutes, urls) }
                }
            }
        }
    }
}
