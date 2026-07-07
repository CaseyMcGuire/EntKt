package entkt.viewer.html

import kotlinx.html.FlowContent
import kotlinx.html.a
import kotlinx.html.div
import kotlinx.html.span

/**
 * Prev/next navigation. `hasNext == null` (privacy-windowed) offers next
 * unconditionally rather than turning its presence into an oracle over
 * denied rows.
 */
internal fun FlowContent.pager(ctx: ListContext, hasNext: Boolean?, urls: ViewerUrls) {
    div("pager") {
        if (ctx.page > 1) a(href = urls.entityList(ctx.route, ctx.filters, ctx.order, ctx.page - 1, ctx.size)) { +"< prev" }
        span("muted") { +"page ${ctx.page}" }
        if (hasNext != false) a(href = urls.entityList(ctx.route, ctx.filters, ctx.order, ctx.page + 1, ctx.size)) { +"next >" }
    }
}
