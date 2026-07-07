package entkt.viewer.html

import entkt.viewer.EntViewerColumn
import entkt.viewer.EntViewerFilterOp
import kotlinx.html.FlowContent
import kotlinx.html.button
import kotlinx.html.form
import kotlinx.html.hiddenInput
import kotlinx.html.option
import kotlinx.html.select
import kotlinx.html.textInput

/** Add-filter form (GET; existing state carried in hidden inputs). */
internal fun FlowContent.filterForm(ctx: ListContext, filterable: List<EntViewerColumn>, urls: ViewerUrls) {
    if (filterable.isEmpty()) return
    form(action = "${urls.base}/entities/${ctx.route}", classes = "filter") {
        for (filter in ctx.filters) hiddenInput(name = "f") { value = urls.filterToken(filter) }
        if (ctx.order != null) {
            hiddenInput(name = "order") { value = ctx.order.column }
            if (ctx.order.descending) hiddenInput(name = "dir") { value = "desc" }
        }
        hiddenInput(name = "size") { value = ctx.size.toString() }
        select {
            attributes["name"] = "fc"
            for (col in filterable) option { value = col.name; +col.name }
        }
        select {
            attributes["name"] = "fo"
            for (op in EntViewerFilterOp.entries) option { value = op.token; +op.token }
        }
        textInput(name = "fv") {}
        button { +"Filter" }
    }
}
