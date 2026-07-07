package entkt.viewer.html

import entkt.runtime.driver.IndexMetadata
import kotlinx.html.FlowContent
import kotlinx.html.table
import kotlinx.html.tbody
import kotlinx.html.td
import kotlinx.html.th
import kotlinx.html.thead
import kotlinx.html.tr

/** Schema-page composite-index listing. */
internal fun FlowContent.indexesTable(indexes: List<IndexMetadata>) {
    table {
        thead { tr { th { +"index" }; th { +"columns" }; th { +"unique" } } }
        tbody {
            for (idx in indexes) {
                tr {
                    td { +idx.name }
                    td { +idx.columns.joinToString(", ") }
                    td { +if (idx.unique) "yes" else "no" }
                }
            }
        }
    }
}
