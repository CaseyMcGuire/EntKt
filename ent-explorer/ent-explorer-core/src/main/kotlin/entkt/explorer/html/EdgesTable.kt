package entkt.explorer.html

import entkt.explorer.EntExplorerEdge
import entkt.explorer.EntExplorerRow
import kotlinx.html.FlowContent
import kotlinx.html.table
import kotlinx.html.tbody
import kotlinx.html.td
import kotlinx.html.th
import kotlinx.html.tr

/** Detail-page edge listing: one [edgeLinkCell] per edge. */
internal fun FlowContent.edgesTable(
    edges: List<EntExplorerEdge>,
    row: EntExplorerRow,
    visibleRoutes: Set<String>,
    urls: ExplorerUrls,
) {
    card {
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
}
