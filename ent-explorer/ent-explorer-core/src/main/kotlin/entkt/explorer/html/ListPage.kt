package entkt.explorer.html

import entkt.explorer.EntExplorerColumn
import entkt.explorer.EntExplorerEntity
import entkt.explorer.EntExplorerResponse
import entkt.explorer.EntExplorerRow
import kotlinx.html.h1
import kotlinx.html.span

internal fun listPage(
    entity: EntExplorerEntity<*, *>,
    columns: List<EntExplorerColumn>,
    rows: List<EntExplorerRow>,
    ctx: ListContext,
    hasNext: Boolean?,
    privacyFiltered: Boolean,
    urls: ExplorerUrls,
): EntExplorerResponse = pageShell(200, entity.displayName, urls) {
    h1 { +"${entity.displayName} " ; span("muted") { +"(${entity.schema.table})" } }
    if (privacyFiltered) privacyBanner(ctx.size)
    filterChips(ctx, urls)
    filterForm(ctx, columns.filter { it.filterable }, urls)
    dataTable(entity, columns, rows, ctx, urls)
    pager(ctx, hasNext, urls)
}
