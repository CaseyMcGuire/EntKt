package entkt.explorer.html

import entkt.explorer.EntExplorerEdge
import entkt.explorer.EntExplorerFilter
import entkt.explorer.EntExplorerFilterOp
import entkt.explorer.EntExplorerRow
import kotlinx.html.FlowContent
import kotlinx.html.a
import kotlinx.html.span

/**
 * One edge's cell, per the link rules: local FK -> target detail; target
 * filter column -> filtered target list; neither (M2M in V1) or an
 * unviewable target -> plain text.
 */
internal fun FlowContent.edgeLinkCell(
    edge: EntExplorerEdge,
    row: EntExplorerRow,
    visibleRoutes: Set<String>,
    urls: ExplorerUrls,
) {
    val target = edge.targetRouteName
    if (target == null || target !in visibleRoutes) {
        span("muted") { +"${edge.cardinality} (not viewable)" }
        return
    }
    when {
        edge.localFkColumn != null -> {
            val fk = row.values.firstOrNull { it.column == edge.localFkColumn }
            when {
                fk == null || fk.redacted -> span("muted") { +"${edge.cardinality} (redacted)" }
                fk.isNull -> span("null") { +"null" }
                else -> a(href = urls.entityDetail(target, fk.display.orEmpty())) {
                    +"$target #${fk.display}"
                }
            }
        }
        edge.targetFilterColumn != null -> {
            val filter = EntExplorerFilter(edge.targetFilterColumn, EntExplorerFilterOp.EQ, row.id)
            a(href = urls.entityList(target, listOf(filter))) {
                +"${edge.cardinality} -> $target"
            }
        }
        else -> span("muted") { +"${edge.cardinality} (no generated traversal link in V1)" }
    }
}
