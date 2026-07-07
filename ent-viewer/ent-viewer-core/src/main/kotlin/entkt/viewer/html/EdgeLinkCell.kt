package entkt.viewer.html

import entkt.viewer.EntViewerEdge
import entkt.viewer.EntViewerFilter
import entkt.viewer.EntViewerFilterOp
import entkt.viewer.EntViewerRow
import kotlinx.html.FlowContent
import kotlinx.html.a
import kotlinx.html.span

/**
 * One edge's cell, per the link rules: local FK -> target detail; target
 * filter column -> filtered target list; neither (M2M in V1) or an
 * unviewable target -> plain text.
 */
internal fun FlowContent.edgeLinkCell(
    edge: EntViewerEdge,
    row: EntViewerRow,
    visibleRoutes: Set<String>,
    urls: ViewerUrls,
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
            val filter = EntViewerFilter(edge.targetFilterColumn, EntViewerFilterOp.EQ, row.id)
            a(href = urls.entityList(target, listOf(filter))) {
                +"${edge.cardinality} -> $target"
            }
        }
        else -> span("muted") { +"${edge.cardinality} (no generated traversal link in V1)" }
    }
}
