package entkt.viewer.html

import entkt.viewer.EntViewerColumn
import entkt.viewer.EntViewerRow
import kotlinx.html.FlowContent
import kotlinx.html.table
import kotlinx.html.tbody
import kotlinx.html.td
import kotlinx.html.th
import kotlinx.html.tr

/** Detail-page field listing: one row per column, full (untruncated) values. */
internal fun FlowContent.fieldsTable(columns: List<EntViewerColumn>, row: EntViewerRow) {
    table {
        tbody {
            for (col in columns) {
                val cell = row.values.firstOrNull { it.column == col.name }
                tr {
                    th { +col.name }
                    td { valueCell(cell, truncateAt = null) }
                }
            }
        }
    }
}
