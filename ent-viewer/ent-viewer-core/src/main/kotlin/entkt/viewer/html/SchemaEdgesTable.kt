package entkt.viewer.html

import entkt.viewer.EntViewerEdge
import kotlinx.html.FlowContent
import kotlinx.html.table
import kotlinx.html.tbody
import kotlinx.html.td
import kotlinx.html.th
import kotlinx.html.thead
import kotlinx.html.tr

/** Schema-page edge listing (name, cardinality, target route). */
internal fun FlowContent.schemaEdgesTable(edges: List<EntViewerEdge>) {
    card {
        table {
            thead { tr { th { +"Edge" }; th { +"Cardinality" }; th { +"Target" } } }
            tbody {
                for (edge in edges) {
                    tr {
                        td { +edge.name }
                        td { +edge.cardinality }
                        td { +(edge.targetRouteName ?: "-") }
                    }
                }
            }
        }
    }
}
