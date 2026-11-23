package entkt.explorer.html

import entkt.explorer.EntExplorerEdge
import kotlinx.html.FlowContent
import kotlinx.html.a
import kotlinx.html.table
import kotlinx.html.tbody
import kotlinx.html.td
import kotlinx.html.th
import kotlinx.html.thead
import kotlinx.html.tr

/**
 * Schema-page edge listing; targets cross-link to their own schema pages
 * when viewable.
 */
internal fun FlowContent.schemaEdgesTable(
    edges: List<EntExplorerEdge>,
    visibleRoutes: Set<String>,
    urls: ExplorerUrls,
) {
    card {
        table {
            thead { tr { th { +"Edge" }; th { +"Cardinality" }; th { +"Target" } } }
            tbody {
                for (edge in edges) {
                    tr {
                        td { +edge.name }
                        td { +edge.cardinality }
                        td {
                            val target = edge.targetRouteName
                            if (target != null && target in visibleRoutes) {
                                a(href = urls.schemaDetail(target)) { +target }
                            } else {
                                +(target ?: "-")
                            }
                        }
                    }
                }
            }
        }
    }
}
