package entkt.viewer.html

import entkt.viewer.EntViewerColumn
import kotlinx.html.FlowContent
import kotlinx.html.span
import kotlinx.html.table
import kotlinx.html.tbody
import kotlinx.html.td
import kotlinx.html.th
import kotlinx.html.thead
import kotlinx.html.tr

/** Schema-page column listing with nullable/unique/sensitive flags. */
internal fun FlowContent.columnsTable(columns: List<EntViewerColumn>) {
    table {
        thead {
            tr { th { +"column" }; th { +"type" }; th { +"flags" } }
        }
        tbody {
            for (col in columns) {
                tr {
                    td { +col.name }
                    td { +col.type.name.lowercase() }
                    td {
                        if (col.nullable) span("flag") { +"nullable" }
                        if (col.unique) span("flag") { +"unique" }
                        if (col.sensitive) span("flag sensitive") { +"sensitive" }
                    }
                }
            }
        }
    }
}
