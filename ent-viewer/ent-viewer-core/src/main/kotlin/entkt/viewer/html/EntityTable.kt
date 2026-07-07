package entkt.viewer.html

import entkt.viewer.EntViewerEntity
import kotlinx.html.FlowContent
import kotlinx.html.a
import kotlinx.html.table
import kotlinx.html.tbody
import kotlinx.html.td
import kotlinx.html.th
import kotlinx.html.thead
import kotlinx.html.tr

/** Entity overview rows for the home and entity-index pages. */
internal fun FlowContent.entityTable(entities: List<EntViewerEntity<*>>, urls: ViewerUrls) {
    table {
        thead { tr { th { +"entity" }; th { +"table" }; th { +"columns" }; th { +"edges" } } }
        tbody {
            for (entity in entities) {
                tr {
                    td { a(href = urls.entityList(entity.routeName)) { +entity.displayName } }
                    td { +entity.schema.table }
                    td { +entity.columns.size.toString() }
                    td { +entity.edges.size.toString() }
                }
            }
        }
    }
}
