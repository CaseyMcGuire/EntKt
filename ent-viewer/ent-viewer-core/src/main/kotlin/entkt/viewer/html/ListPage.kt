package entkt.viewer.html

import entkt.viewer.EntViewerColumn
import entkt.viewer.EntViewerEntity
import entkt.viewer.EntViewerResponse
import entkt.viewer.EntViewerRow
import kotlinx.html.h1
import kotlinx.html.span

internal fun listPage(
    entity: EntViewerEntity<*>,
    columns: List<EntViewerColumn>,
    rows: List<EntViewerRow>,
    ctx: ListContext,
    hasNext: Boolean?,
    privacyFiltered: Boolean,
    urls: ViewerUrls,
): EntViewerResponse = pageShell(200, entity.displayName, urls) {
    h1 { +"${entity.displayName} " ; span("muted") { +"(${entity.schema.table})" } }
    if (privacyFiltered) privacyBanner(ctx.size)
    filterChips(ctx, urls)
    filterForm(ctx, columns.filter { it.filterable }, urls)
    dataTable(entity, columns, rows, ctx, urls)
    pager(ctx, hasNext, urls)
}
