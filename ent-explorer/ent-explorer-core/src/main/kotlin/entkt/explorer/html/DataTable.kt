package entkt.explorer.html

import entkt.explorer.EntExplorerColumn
import entkt.explorer.EntExplorerEntity
import entkt.explorer.EntExplorerOrder
import entkt.explorer.EntExplorerRow
import kotlinx.html.FlowContent
import kotlinx.html.a
import kotlinx.html.p
import kotlinx.html.table
import kotlinx.html.tbody
import kotlinx.html.td
import kotlinx.html.th
import kotlinx.html.thead
import kotlinx.html.tr

/**
 * The list-page table: orderable column headers (click to toggle direction),
 * the id column linking to detail, and [valueCell]s truncated for list view.
 */
internal fun FlowContent.dataTable(
    entity: EntExplorerEntity<*, *>,
    columns: List<EntExplorerColumn>,
    rows: List<EntExplorerRow>,
    ctx: ListContext,
    urls: ExplorerUrls,
) {
    card {
        table {
            thead {
                tr {
                    for (col in columns) {
                        th {
                            if (col.orderable) {
                                val descending = ctx.order?.column == col.name && !ctx.order.descending
                                a(href = urls.entityList(ctx.route, ctx.filters, EntExplorerOrder(col.name, descending), 1, ctx.size)) {
                                    +col.name
                                    if (ctx.order?.column == col.name) +(if (ctx.order.descending) " v" else " ^")
                                }
                            } else {
                                +col.name
                            }
                        }
                    }
                }
            }
            tbody {
                for (row in rows) {
                    tr {
                        for (col in columns) {
                            val cell = row.values.firstOrNull { it.column == col.name }
                            td {
                                if (col.name == entity.schema.idColumn) {
                                    a(href = urls.entityDetail(ctx.route, row.id)) { +row.id }
                                } else {
                                    valueCell(cell, truncateAt = LIST_CELL_MAX)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    if (rows.isEmpty()) p("muted") { +"No visible rows." }
}
