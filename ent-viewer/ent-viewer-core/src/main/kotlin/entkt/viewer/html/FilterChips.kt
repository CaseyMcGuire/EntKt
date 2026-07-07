package entkt.viewer.html

import kotlinx.html.FlowContent
import kotlinx.html.a
import kotlinx.html.div
import kotlinx.html.span

/** Active filters as removable chips; removing one links to the same page without it. */
internal fun FlowContent.filterChips(ctx: ListContext, urls: ViewerUrls) {
    if (ctx.filters.isEmpty()) return
    div("chips") {
        ctx.filters.forEachIndexed { index, filter ->
            span("chip") {
                +urls.filterLabel(filter)
                a(href = urls.entityList(ctx.route, ctx.filters.filterIndexed { i, _ -> i != index }, ctx.order, 1, ctx.size)) {
                    +"x"
                }
            }
        }
    }
}
